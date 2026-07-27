import AppIntents
import Foundation
import Observation

/// Siri and Shortcuts — the one surface where iOS genuinely beats Android.
///
/// What these replace only *opened* the app. That is a shortcut, not an assistant: if you have to
/// look at the screen anyway, asking out loud saved you nothing. These answer **in place** —
/// `openAppWhenRun = false` plus a spoken dialog — so "Hey Siri, ask SlyOS who Carlos is" works
/// with the phone in a pocket, and every answer is grounded in the brain rather than in Siri's own
/// general knowledge.
@Observable
final class AppIntentRouter {
    enum Route: Equatable {
        case openCommand(prompt: String?)
        case openMemory
        case remember(text: String)
    }

    struct HandledIntent: Equatable {
        let id = UUID()
        let route: Route
    }

    static let shared = AppIntentRouter()
    var handledIntent: HandledIntent?

    private init() {}
}

/// "Hey Siri, ask SlyOS who Carlos is."
struct AskSlyOSIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask SlyOS"
    static let description = IntentDescription(
        "Ask a question and get an answer from everything SlyOS knows about you.")
    /// Answered without opening anything — that is the whole point.
    static let openAppWhenRun = false

    @Parameter(title: "Question", requestValueDialog: "What do you want to know?")
    var question: String

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        // The cheap tier on purpose: a spoken answer that arrives in two seconds beats a better one
        // that arrives in ten, and Siri cuts long replies off anyway.
        let answer = try await AgentClient.ask(question, tier: .cheap)
        let spoken = String(answer.prefix(700))
        return .result(value: answer, dialog: IntentDialog(stringLiteral: spoken))
    }
}

/// "Hey Siri, remember in SlyOS that the lease renews in March."
struct RememberInSlyOSIntent: AppIntent {
    static let title: LocalizedStringResource = "Remember in SlyOS"
    static let description = IntentDescription("Save something into the SlyOS brain.")
    static let openAppWhenRun = false

    @Parameter(title: "Memory", requestValueDialog: "What should I remember?")
    var text: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        // Written straight to the store rather than handed to the app, so it is saved whether or
        // not the app is ever opened afterwards.
        SlyStore.shared.insert(kind: "note", title: "", body: text, source: "Siri")
        return .result(dialog: "Got it.")
    }
}

/// "Hey Siri, what's on in SlyOS."
struct AgendaIntent: AppIntent {
    static let title: LocalizedStringResource = "What's on"
    static let description = IntentDescription("Read out what's on your calendar and reminders.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let feed = NowFeed()
        await feed.load()
        guard !feed.items.isEmpty else {
            return .result(dialog: "Nothing on for the next two days.")
        }
        let spoken = feed.items.prefix(5)
            .map { "\($0.title), \($0.detail)" }
            .joined(separator: ". ")
        return .result(dialog: IntentDialog(stringLiteral: spoken))
    }
}

/// Draft a reply — returns the text as a value too, so a Shortcut can pipe it into another app.
struct DraftReplyIntent: AppIntent {
    static let title: LocalizedStringResource = "Draft a reply"
    static let description = IntentDescription(
        "Write a reply in your voice to a message, using what SlyOS knows.")
    static let openAppWhenRun = false

    @Parameter(title: "Message", requestValueDialog: "What are you replying to?")
    var message: String

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        let context = AgentClient.corpus(for: message)
        var system = """
            You are drafting a reply as the owner, in their voice. Output only the reply — no \
            preamble, no quotes, no explanation.
            """
        system += AgentClient.voiceBlock()
        // The message was written by someone else: data, never instruction.
        system += Untrusted.clause

        let user = context.isEmpty
            ? "MESSAGE:\n\(message)"
            : "WHAT YOU KNOW:\n\(context)\n\nMESSAGE:\n\(message)"

        let draft = try await AgentClient.complete(system: system, user: user, tier: .cheap)
        DraftLog.shared.record(source: message, draft: draft, tone: "Siri")
        return .result(value: draft, dialog: IntentDialog(stringLiteral: draft))
    }
}

/// Open the brain.
struct OpenSlyOSMemoryIntent: AppIntent {
    static let title: LocalizedStringResource = "Open SlyOS memory"
    static let description = IntentDescription("Open the SlyOS brain.")
    static let openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        await MainActor.run {
            AppIntentRouter.shared.handledIntent = .init(route: .openMemory)
        }
        return .result()
    }
}

/// Open SlyOS, optionally with something already typed.
struct OpenSlyOSIntent: AppIntent {
    static let title: LocalizedStringResource = "Open SlyOS"
    static let description = IntentDescription("Open SlyOS.")
    static let openAppWhenRun = true

    @Parameter(title: "Prompt", inputConnectionBehavior: .connectToPreviousIntentResult)
    var prompt: String?

    func perform() async throws -> some IntentResult {
        await MainActor.run {
            AppIntentRouter.shared.handledIntent = .init(route: .openCommand(prompt: prompt))
        }
        return .result()
    }
}

/// The phrases Siri listens for.
///
/// Every utterance must contain `\(.applicationName)` — the build is rejected otherwise — and it is
/// also what lets someone rename the app and still be understood.
struct SlyOSShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskSlyOSIntent(),
            phrases: [
                "Ask \(.applicationName)",
                "\(.applicationName), what do you know"
            ],
            shortTitle: "Ask",
            systemImageName: "bubble.left.fill"
        )

        AppShortcut(
            intent: AgendaIntent(),
            phrases: [
                "What's on in \(.applicationName)",
                "\(.applicationName), what's on"
            ],
            shortTitle: "What's on",
            systemImageName: "bolt.fill"
        )

        AppShortcut(
            intent: RememberInSlyOSIntent(),
            phrases: [
                "Remember this in \(.applicationName)",
                "Save to \(.applicationName)"
            ],
            shortTitle: "Remember",
            systemImageName: "cpu"
        )

        AppShortcut(
            intent: DraftReplyIntent(),
            phrases: [
                "Draft a reply with \(.applicationName)",
                "\(.applicationName) write a reply"
            ],
            shortTitle: "Draft a reply",
            systemImageName: "square.and.pencil"
        )

        AppShortcut(
            intent: OpenSlyOSMemoryIntent(),
            phrases: [
                "Open \(.applicationName) memory",
                "Show my \(.applicationName) brain"
            ],
            shortTitle: "Memory",
            systemImageName: "memorychip"
        )
    }
}
