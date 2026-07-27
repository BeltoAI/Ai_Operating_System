import Foundation
import Observation

/// How much SlyOS is allowed to do on its own.
///
/// The three settings are not three shades of the same thing — they are three genuinely different
/// architectures, and only one of them can ever send without you:
///
/// * **Off** — SlyOS answers when asked and nothing else.
/// * **Draft only** — it prepares replies; you send them. Everything on the phone lives here,
///   because iOS gives no app a way to put text into another app and press send. That is not a
///   permission we lack, it is an API that does not exist.
/// * **Autonomous** — replies actually go out. Only possible through an OpenClaw gateway, because
///   the gateway is connected to WhatsApp and Telegram directly and is not bound by the phone's
///   sandbox.
///
/// The distinction is kept in the UI rather than smoothed over. Someone who turns on "autonomous"
/// and finds their phone still only drafting would rightly feel lied to.
@Observable
final class Autonomy {

    static let shared = Autonomy()

    enum Level: String, CaseIterable, Identifiable, Codable {
        case off, draft, autonomous
        var id: String { rawValue }

        var title: String {
            switch self {
            case .off: "Off"
            case .draft: "Draft only"
            case .autonomous: "Autonomous"
            }
        }

        var detail: String {
            switch self {
            case .off:
                "SlyOS answers you and nothing else."
            case .draft:
                "It writes replies in your voice — from the keyboard, the share sheet, or Now. You "
                + "still press send."
            case .autonomous:
                "It replies for you, without asking. Needs an OpenClaw gateway: nothing on the "
                + "phone itself can send inside WhatsApp."
            }
        }

        /// Autonomous is the only level with a hard external requirement.
        var needsGateway: Bool { self == .autonomous }
    }

    var level: Level {
        get {
            let raw = SharedContainer.defaults.string(forKey: "autonomy.level") ?? Level.draft.rawValue
            return Level(rawValue: raw) ?? .draft
        }
        set { SharedContainer.defaults.set(newValue.rawValue, forKey: "autonomy.level") }
    }

    /// Never send to someone SlyOS has not spoken with before without asking, whatever the level.
    ///
    /// The worst outcome of autonomy is not a clumsy reply to a friend — it is a confident reply to
    /// a stranger, a recruiter, or a bank. Familiarity is a cheap, effective brake.
    var onlyKnownContacts: Bool {
        get { SharedContainer.defaults.object(forKey: "autonomy.knownOnly") as? Bool ?? true }
        set { SharedContainer.defaults.set(newValue, forKey: "autonomy.knownOnly") }
    }

    /// A ceiling on how many messages can go out unattended per day.
    ///
    /// An agent in a loop is the failure mode that costs someone their reputation, not their money.
    /// The cap is small on purpose and the count resets daily.
    var dailyLimit: Int {
        get { SharedContainer.defaults.object(forKey: "autonomy.dailyLimit") as? Int ?? 10 }
        set { SharedContainer.defaults.set(newValue, forKey: "autonomy.dailyLimit") }
    }

    private(set) var sentToday: Int {
        get {
            let day = SharedContainer.defaults.string(forKey: "autonomy.day") ?? ""
            guard day == Self.today else { return 0 }
            return SharedContainer.defaults.integer(forKey: "autonomy.count")
        }
        set {
            SharedContainer.defaults.set(Self.today, forKey: "autonomy.day")
            SharedContainer.defaults.set(newValue, forKey: "autonomy.count")
        }
    }

    private static var today: String {
        Date.now.formatted(.iso8601.year().month().day())
    }

    enum Refusal: LocalizedError {
        case notAutonomous
        case noGateway
        case unknownContact(String)
        case dailyLimit(Int)

        var errorDescription: String? {
            switch self {
            case .notAutonomous:
                "SlyOS is set to draft only, so this is waiting for you to send it."
            case .noGateway:
                "Autonomous replies need an OpenClaw gateway — nothing on the phone can send inside "
                + "WhatsApp. Set one up in Settings, or leave this on Draft only."
            case .unknownContact(let who):
                "\(who) isn't someone you've spoken with before, so SlyOS won't reply on its own."
            case .dailyLimit(let n):
                "That's \(n) automatic replies today, which is the limit you set. The rest are "
                + "waiting as drafts."
            }
        }
    }

    /// Whether a reply may go out unattended, right now, to this person.
    func maySendUnattended(to person: String) -> Result<Void, Refusal> {
        guard level == .autonomous else { return .failure(.notAutonomous) }
        guard OpenClaw.shared.isConfigured, OpenClaw.shared.allowActions else {
            return .failure(.noGateway)
        }
        if onlyKnownContacts, !person.isEmpty, !hasHistory(with: person) {
            return .failure(.unknownContact(person))
        }
        guard sentToday < dailyLimit else { return .failure(.dailyLimit(dailyLimit)) }
        return .success(())
    }

    /// Count a send that actually went out.
    func recordSend() { sentToday += 1 }

    /// Someone the owner has genuinely corresponded with, not merely been messaged by once.
    private func hasHistory(with person: String) -> Bool {
        SlyStore.shared.search(person, limit: 6).count >= 2
    }
}
