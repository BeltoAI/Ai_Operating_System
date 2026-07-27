import Foundation

/// What SlyOS can actually *do*, and — just as importantly — what it must never claim to do.
///
/// Android has a ToolRouter that executes; iOS had nothing, so "send a message to Joslyn on
/// WhatsApp" produced the sentence "I'll send a message to Joslyn on WhatsApp" and then no message.
/// That is worse than a missing feature. It is the app lying, and it is the specific failure that
/// cost the Android build its owner's trust when it claimed to have invited someone it never had.
///
/// The rule here is absolute: **a channel SlyOS cannot reach is never described as done or
/// promised.** It offers the draft and says plainly who has to send it.
enum ActionRouter {

    enum Channel {
        case email(to: String)
        case whatsapp(to: String)
        case telegram(to: String)
        case sms(to: String)
        case calendar
        case unknown

        /// Whether this phone can complete it without a human.
        var reachable: Bool {
            switch self {
            case .email: GoogleAuth.shared.isConnected
            case .calendar: GoogleAuth.shared.isConnected
            // Nothing on iOS can put text into WhatsApp and press send. A gateway can.
            case .whatsapp, .telegram:
                OpenClaw.shared.isConfigured && OpenClaw.shared.allowActions
            // iOS can open Messages pre-filled, but the human still taps send.
            case .sms, .unknown: false
            }
        }

        var name: String {
            switch self {
            case .email: "email"
            case .whatsapp: "WhatsApp"
            case .telegram: "Telegram"
            case .sms: "Messages"
            case .calendar: "your calendar"
            case .unknown: "that"
            }
        }
    }

    struct Intent {
        let channel: Channel
        let recipient: String
        let instruction: String
    }

    /// Read a request. Deliberately conservative: an unrecognised request is a question, not an
    /// action, because treating a question as an action is how an assistant sends something nobody
    /// asked for.
    static func detect(_ prompt: String) -> Intent? {
        let p = prompt.lowercased()
        let verbs = ["send", "message", "write to", "text", "email", "reply to", "tell "]
        guard verbs.contains(where: p.contains) else { return nil }

        let recipient = person(in: prompt)

        if p.contains("whatsapp") { return Intent(channel: .whatsapp(to: recipient), recipient: recipient, instruction: prompt) }
        if p.contains("telegram") { return Intent(channel: .telegram(to: recipient), recipient: recipient, instruction: prompt) }
        if p.contains("email") || p.contains("mail ") { return Intent(channel: .email(to: recipient), recipient: recipient, instruction: prompt) }
        if p.contains("text ") || p.contains("sms") || p.contains("imessage") {
            return Intent(channel: .sms(to: recipient), recipient: recipient, instruction: prompt)
        }
        return nil
    }

    /// Best guess at who is being addressed — the word after "to", or a capitalised name.
    private static func person(in prompt: String) -> String {
        if let m = prompt.firstMatch(#"(?i)\b(?:to|message|text|tell)\s+([A-Z][\w'-]+)"#) { return m[1] }
        if let m = prompt.firstMatch(#"\b([A-Z][a-z]{2,})\b"#) { return m[1] }
        return ""
    }

    struct Outcome {
        let text: String
        /// True only when something actually left the phone.
        let didSomething: Bool
    }

    /// Carry out what can be carried out, and be honest about the rest.
    static func perform(_ intent: Intent, draft: String) async -> Outcome {
        switch intent.channel {
        case .email(let to):
            guard GoogleAuth.shared.isConnected else {
                return Outcome(text: notConnected("Google", draft: draft), didSomething: false)
            }
            let address = to.contains("@") ? to : (await lookUpEmail(for: intent.recipient) ?? "")
            guard !address.isEmpty else {
                return Outcome(text: "I don't have an email address for \(intent.recipient). Here's "
                               + "the draft:\n\n\(draft)", didSomething: false)
            }
            do {
                try await Gmail.send(to: address, subject: subject(of: draft), body: draft)
                Outbox.shared.record(what: "Email to \(address)", detail: subject(of: draft), outcome: "sent")
                return Outcome(text: "Sent to \(address).\n\n\(draft)", didSomething: true)
            } catch {
                Outbox.shared.record(what: "Email to \(address)", detail: error.localizedDescription,
                                     outcome: "failed")
                return Outcome(text: "Couldn't send it — \(error.localizedDescription)\n\n\(draft)",
                               didSomething: false)
            }

        case .whatsapp, .telegram:
            // The honest case, and the one that was lying before.
            guard intent.channel.reachable else {
                return Outcome(text: cannotSend(intent.channel, to: intent.recipient, draft: draft),
                               didSomething: false)
            }
            do {
                _ = try await OpenClaw.shared.invoke(
                    tool: "messages.send",
                    args: ["to": intent.recipient, "text": draft],
                    confirmed: true)
                Outbox.shared.record(what: "\(intent.channel.name) to \(intent.recipient)",
                                     detail: draft, outcome: "sent")
                return Outcome(text: "Sent to \(intent.recipient) on \(intent.channel.name).\n\n\(draft)",
                               didSomething: true)
            } catch {
                return Outcome(text: "Your gateway refused it — \(error.localizedDescription)\n\n\(draft)",
                               didSomething: false)
            }

        case .sms, .unknown, .calendar:
            return Outcome(text: cannotSend(intent.channel, to: intent.recipient, draft: draft),
                           didSomething: false)
        }
    }

    // MARK: - Honest copy

    /// The wording that matters most in the whole app.
    ///
    /// It states what did *not* happen first, before the draft, so nobody skims it and believes a
    /// message went out.
    private static func cannotSend(_ channel: Channel, to who: String, draft: String) -> String {
        """
        **Not sent — I can't send on \(channel.name) from an iPhone.**

        Apple gives no app a way to put text into another app and send it. Copy this and paste it, \
        or use the SlyOS keyboard inside \(channel.name) to insert it in one tap. Connecting an \
        OpenClaw gateway is the only way to make this automatic.

        \(draft)
        """
    }

    private static func notConnected(_ what: String, draft: String) -> String {
        "**Not sent** — connect \(what) in Settings first.\n\n\(draft)"
    }

    private static func subject(of draft: String) -> String {
        if let m = draft.firstMatch(#"(?im)^subject:\s*(.+)$"#) { return m[1] }
        return String(draft.split(separator: "\n").first ?? "").prefix(80).description
    }

    /// An address from the brain — contacts were imported precisely so this works.
    private static func lookUpEmail(for name: String) async -> String? {
        guard !name.isEmpty else { return nil }
        for memory in SlyStore.shared.search(name, limit: 10) {
            if let m = memory.body.firstMatch(#"[\w.+-]+@[\w-]+\.[\w.]+"#) { return m[0] }
        }
        return nil
    }
}
