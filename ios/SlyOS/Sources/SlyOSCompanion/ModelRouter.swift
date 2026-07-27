import Foundation
import Observation

/// Which brain answers, and what happens when it can't.
///
/// Ported from Android's `ModelRouter`. Users bring their own API keys, so the router's real job is
/// picking the best provider the user has actually paid for and falling through to the next one when
/// a call fails — a single dead provider must never mean the app stops answering.
@Observable
final class ModelRouter {

    static let shared = ModelRouter()

    enum Tier: String, CaseIterable {
        case cheap, standard, heavy
    }

    enum Provider: String, CaseIterable, Identifiable {
        case anthropic, openai, gemini, groq, cerebras, mistral, openclaw

        var id: String { rawValue }

        var label: String {
            switch self {
            case .anthropic: "Anthropic"
            case .openai: "OpenAI"
            case .gemini: "Google Gemini"
            case .groq: "Groq"
            case .cerebras: "Cerebras"
            case .mistral: "Mistral"
            case .openclaw: "OpenClaw (your own)"
            }
        }

        /// Where to get a key, shown in Settings so nobody has to go hunting.
        var keyURL: String {
            switch self {
            case .anthropic: "https://console.anthropic.com/settings/keys"
            case .openai: "https://platform.openai.com/api-keys"
            case .gemini: "https://aistudio.google.com/apikey"
            case .groq: "https://console.groq.com/keys"
            case .cerebras: "https://cloud.cerebras.ai/"
            case .mistral: "https://console.mistral.ai/api-keys/"
            case .openclaw: "https://docs.openclaw.ai/gateway/security"
            }
        }

        /// Providers with a usable free tier — worth saying so, because "bring your own key" is a
        /// much smaller ask when one of the options costs nothing.
        var isFree: Bool {
            switch self {
            // Free in the sense that matters: it runs on hardware the owner already pays for.
            case .gemini, .groq, .cerebras, .mistral, .openclaw: true
            case .anthropic, .openai: false
            }
        }

        /// Only these can look at an image; a photo must never be routed to a text-only model.
        var canSeeImages: Bool {
            switch self {
            case .anthropic, .openai, .gemini: true
            // Depends on the model the owner has configured there, so assume not.
            case .groq, .cerebras, .mistral, .openclaw: false
            }
        }

        /// Default model per tier.
        ///
        /// These are fast-path defaults only. Providers retire model ids on their own schedule, and
        /// a stale id here means every call 404s forever — so each is editable in Settings.
        func model(for tier: Tier) -> String {
            switch self {
            case .anthropic:
                switch tier {
                case .cheap: "claude-haiku-4-5"
                case .standard: "claude-sonnet-5"
                case .heavy: "claude-opus-5"
                }
            case .openai:
                tier == .cheap ? "gpt-4o-mini" : "gpt-4o"
            case .gemini:
                tier == .heavy ? "gemini-2.5-pro" : "gemini-2.5-flash"
            case .groq:
                tier == .cheap ? "llama-3.1-8b-instant" : "llama-3.3-70b-versatile"
            case .cerebras:
                tier == .cheap ? "llama3.1-8b" : "llama-3.3-70b"
            case .mistral:
                tier == .heavy ? "mistral-large-latest" : "mistral-small-latest"
            // Whatever the gateway is pointed at; it routes by its own configuration.
            case .openclaw: "default"
            }
        }

        var endpoint: String {
            switch self {
            case .anthropic: "https://api.anthropic.com/v1/messages"
            case .openai: "https://api.openai.com/v1/chat/completions"
            case .gemini: "https://generativelanguage.googleapis.com/v1beta/models"
            case .groq: "https://api.groq.com/openai/v1/chat/completions"
            case .cerebras: "https://api.cerebras.ai/v1/chat/completions"
            case .mistral: "https://api.mistral.ai/v1/chat/completions"
            // The owner's own machine — resolved at call time, not baked in.
            case .openclaw: (ModelRouter.openClaw?.baseURL() ?? "") + "/v1/chat/completions"
            }
        }
    }

    /// How to reach the owner's OpenClaw gateway, supplied by the app at launch.
    ///
    /// A closure rather than a direct reference to `OpenClaw`: this file is compiled into the share
    /// extension too, and the extension deliberately does not carry the gateway client. Where it is
    /// nil, OpenClaw simply is not among the providers.
    nonisolated(unsafe) static var openClaw: (baseURL: () -> String, token: () -> String)?

    private let keychain = Keychain(service: "com.belto.slyos.models")

    /// Bumped whenever a key changes so observing views refresh.
    private(set) var revision = 0

    func key(for p: Provider) -> String {
        // OpenClaw authenticates with its gateway token, which lives with the rest of its config.
        if p == .openclaw { return Self.openClaw?.token() ?? "" }
        return keychain.string(for: p.rawValue) ?? ""
    }

    func setKey(_ value: String, for p: Provider) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { keychain.remove(p.rawValue) } else { keychain.set(trimmed, for: p.rawValue) }
        revision += 1
    }

    func hasKey(for p: Provider) -> Bool {
        if p == .openclaw {
            guard let claw = Self.openClaw else { return false }
            return !claw.baseURL().isEmpty && !claw.token().isEmpty
        }
        return !key(for: p).isEmpty
    }

    var configuredProviders: [Provider] { Provider.allCases.filter(hasKey) }
    var isConfigured: Bool { !configuredProviders.isEmpty }

    /// Preference order: the best answer first, then progressively cheaper or free fallbacks.
    ///
    /// A request is only ever sent to a provider the user has a key for, and `needsVision` removes
    /// text-only providers entirely rather than letting a photo silently become a text prompt.
    func chain(tier: Tier, needsVision: Bool = false) -> [Provider] {
        let order: [Provider] = [.openclaw, .anthropic, .openai, .gemini, .groq, .cerebras, .mistral]
        return order.filter { hasKey(for: $0) && (!needsVision || $0.canSeeImages) }
    }
}
