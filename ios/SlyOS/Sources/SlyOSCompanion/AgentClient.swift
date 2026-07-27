import Foundation

/// Talks to whichever model the router picked, grounded in the brain.
///
/// The hard-won lessons from the Android build are encoded here rather than rediscovered:
///
/// * **Budget the context.** An unbounded profile block once consumed the entire window and left no
///   room for the actual memories, so the app "knew nothing about you" despite holding everything.
/// * **Truncate, don't drop. Skip, don't stop.** Walking the candidate list and `break`ing at the
///   first oversized item threw away everything after it. Long items get cut; oversized ones get
///   skipped; the walk continues.
/// * **Never on the main thread.** The Memory tab returned "-1 couldn't search" because a network
///   call was made from a main-actor context.
enum AgentClient {

    /// How much of the window recalled memories may occupy.
    ///
    /// 6,000 characters, not the 40,000 that seemed generous: oversized context made answers take
    /// 105 seconds and some providers reject the request outright.
    private static let recallBudget = 6_000
    /// No single memory may dominate the budget.
    private static let perItemCap = 1_200

    enum ClientError: LocalizedError {
        case noProvider
        case noVisionProvider
        case malformedImage
        case allFailed([String])
        case badResponse(Int, String)

        var errorDescription: String? {
            switch self {
            case .noProvider:
                "No AI provider is set up yet. Add a key in Settings — Groq, Gemini and Cerebras have free tiers."
            case .malformedImage:
                "That photo didn't come through properly."
            case .noVisionProvider:
                "Identifying needs a model that can see. Add an Anthropic, OpenAI or Gemini key — "
                + "reading text off the page works without one."
            case .allFailed(let reasons):
                // One line per provider, already summarised — the raw JSON from three failing APIs
                // is thousands of characters of nothing anyone can act on.
                "No provider could answer.\n" + reasons.map { "· " + $0 }.joined(separator: "\n")
            case .badResponse(let code, let body):
                AgentClient.humanise(code: code, body: body)
            }
        }
    }

    /// Turn a provider's error into something the owner can act on.
    ///
    /// Every one of these has a different remedy, and the raw body says so in a paragraph of JSON
    /// that buries it. The status code is usually enough to know which.
    static func humanise(code: Int, body: String) -> String {
        switch code {
        case 401, 403:
            return "that key was rejected — check it in Settings"
        case 429:
            return body.lowercased().contains("quota")
                ? "you're out of credit on that account"
                : "too many requests just now — try again in a moment"
        case 400:
            return body.lowercased().contains("model")
                ? "that model name isn't valid for this account"
                : "the request was refused"
        case 500...599:
            return "the provider is having problems"
        default:
            return "failed (\(code))"
        }
    }

    // MARK: - Public

    /// Answer a question using the brain as ground truth.
    static func ask(_ question: String, tier: ModelRouter.Tier = .standard) async throws -> String {
        let context = corpus(for: question)
        let system = groundedSystemPrompt(hasContext: !context.isEmpty)
            + profileBlock()
            + (await placeBlock(for: question))
            + (await agendaBlock(for: question))
        let user = context.isEmpty
            ? question
            : "WHAT YOU KNOW:\n\(context)\n\nQUESTION: \(question)"
        return try await complete(system: system, user: user, tier: tier)
    }

    /// Send the prompt through the fallback chain, returning the first success.
    static func complete(system: String, user: String,
                         tier: ModelRouter.Tier = .standard) async throws -> String {
        let router = ModelRouter.shared
        let providers = router.chain(tier: tier)
        guard !providers.isEmpty else { throw ClientError.noProvider }

        var failures: [String] = []
        for provider in providers {
            do {
                return try await send(provider: provider,
                                      model: provider.model(for: tier),
                                      key: router.key(for: provider),
                                      system: system, user: user)
            } catch {
                // Record and move on. One dead provider must never mean no answer.
                failures.append("\(provider.label): \(short(error))")
                continue
            }
        }
        throw ClientError.allFailed(failures)
    }

    /// A one-line version of whatever went wrong.
    private static func short(_ error: Error) -> String {
        if case let ClientError.badResponse(code, body) = error { return humanise(code: code, body: body) }
        if (error as NSError).domain == NSURLErrorDomain { return "couldn't reach it" }
        return error.localizedDescription
    }

    // MARK: - Grounding

    /// What the model is told about how to use the brain.
    ///
    /// The explicit "say you don't know" clause matters more than it looks: without it the model
    /// fills gaps with plausible invention, and on Android that produced a confident yes to "did you
    /// send Joslyn the invite?" for an invite that was never sent.
    private static func groundedSystemPrompt(hasContext: Bool) -> String {
        var s = """
        You are SlyOS, the owner's own assistant. You speak plainly and briefly, like a sharp \
        colleague — no preamble, no restating the question, no bullet lists unless asked.

        The current time is \(Date.now.formatted(date: .complete, time: .shortened)).
        """

        if hasContext {
            s += """


            WHAT YOU KNOW is drawn from the owner's own messages, mail, calendar and notes. Treat it \
            as fact and use it. Refer to people by name.

            Never state as done something you have only been asked to do. If the brain does not \
            contain the answer, say so in one sentence and say what would be needed — do not \
            invent, and do not infer an action happened because it was discussed.
            """
            // Appended where the context is described, so "the material above" has an antecedent.
            if lastCorpusWasUntrusted { s += Untrusted.clause }
        } else {
            s += """


            You have no stored context for this question. Answer from general knowledge, and say \
            plainly that you don't have anything about it in memory.
            """
        }
        return s
    }

    /// Real examples of how the owner writes, when there are any.
    ///
    /// A description of someone's tone is a guess; six things they actually sent are evidence.
    static func voiceBlock() -> String { DraftLog.shared.styleExamples() }

    /// Who the owner is, appended to the system prompt.
    ///
    /// Capped inside `fullProfile` rather than here — an unbounded profile is what once ate the
    /// entire context window and left no room for the memories the question was about.
    private static func profileBlock() -> String {
        let profile = SlyProfile.shared.fullProfile()
        guard !profile.isEmpty else { return "" }
        return "\n\nWHO YOU ARE SPEAKING FOR:\n\(profile)"
    }

    /// Where the owner is, added only when the question implies it matters.
    ///
    /// Gated rather than always-on: taking a location fix for "summarise this email" would waste
    /// power and ask for a permission the question never needed. If location is refused this
    /// silently contributes nothing.
    /// Reads today's agenda, injected by the app at launch. Same reason as location: the calendar
    /// lives behind EventKit, which an app extension cannot reach.
    nonisolated(unsafe) static var agendaResolver: (() async -> String?)?

    /// What is actually on, when the question is about that.
    ///
    /// Without this the model answers "I don't have access to your calendar" while sitting on a
    /// brain full of imported events — because a question like "what's on today" matches nothing by
    /// keyword, and the events it should find are dated today rather than named it.
    private static func agendaBlock(for question: String) async -> String {
        let q = question.lowercased()
        let asks = ["what's on", "whats on", "my day", "today", "tomorrow", "this week",
                    "schedule", "calendar", "agenda", "meeting", "free time", "busy"]
            .contains { q.contains($0) }
        guard asks, let resolver = agendaResolver, let agenda = await resolver(),
              !agenda.isEmpty else { return "" }
        return "\n\nWHAT IS ACTUALLY ON — read from the calendar just now:\n\(agenda)"
    }

    /// Resolves the owner's location, injected by the app at launch.
    ///
    /// A closure rather than a direct call to `LocationProvider`, because that reaches `Permissions`
    /// and then `UIApplication.shared`, which is unavailable in an app extension. The share
    /// extension leaves this nil and simply contributes no location.
    static var placeResolver: (() async -> String?)?

    private static func placeBlock(for question: String) async -> String {
        let q = question.lowercased()
        let wantsPlace = ["where am i", "near me", "nearby", "around here", "my location",
                          "closest", "local", "weather", "directions", "how far"]
            .contains { q.contains($0) }
        guard wantsPlace, let resolver = placeResolver else { return "" }
        guard let place = await resolver() else { return "" }
        return "\n\nWHERE THE OWNER IS RIGHT NOW: \(place)"
    }

    /// Whether the last assembled corpus contained third-party content.
    ///
    /// Set by `corpus`, read when building the system prompt. Messages and mail are written by other
    /// people, and a model that treats them as instructions is one crafted message away from doing
    /// what a stranger asked instead of what its owner asked.
    nonisolated(unsafe) private(set) static var lastCorpusWasUntrusted = false

    /// Gather the memories most relevant to a question, inside the budget.
    static func corpus(for question: String) -> String {
        let store = SlyStore.shared
        var hits = store.search(question, limit: 40)
        // With nothing matched, recent memory is better than none — it is at least about the owner.
        if hits.isEmpty { hits = store.recent(limit: 12) }
        lastCorpusWasUntrusted = Untrusted.present(in: hits)

        var out: [String] = []
        var used = 0
        for m in hits {
            if used >= recallBudget { break }

            var line = ""
            if !m.person.isEmpty { line += "\(m.person) " }
            if !m.source.isEmpty { line += "(\(m.source)) " }
            line += "\(m.date.formatted(date: .abbreviated, time: .omitted)): "
            line += m.title.isEmpty ? m.body : "\(m.title) — \(m.body)"

            // Truncate, don't drop.
            if line.count > perItemCap { line = String(line.prefix(perItemCap)) + "…" }
            // Skip, don't stop: an item that won't fit must not end the walk.
            if used + line.count > recallBudget { continue }

            out.append(line)
            used += line.count
        }
        return out.joined(separator: "\n")
    }

    // MARK: - Vision

    /// Ask about an image.
    ///
    /// Routed only to providers that can actually see — a photo sent to a text model becomes a
    /// prompt with no picture in it, and the model answers confidently about nothing.
    static func look(at jpeg: Data, question: String,
                     tier: ModelRouter.Tier = .standard) async throws -> String {
        let router = ModelRouter.shared
        let providers = router.chain(tier: tier, needsVision: true)
        guard !providers.isEmpty else { throw ClientError.noVisionProvider }

        let base64 = jpeg.base64EncodedString()
        var failures: [String] = []
        for provider in providers {
            do {
                return try await sendImage(provider: provider, model: provider.model(for: tier),
                                           key: router.key(for: provider),
                                           base64: base64, question: question)
            } catch {
                failures.append("\(provider.label): \(short(error))")
                continue
            }
        }
        throw ClientError.allFailed(failures)
    }

    /// Each provider wraps images differently; the text half is identical to a normal call.
    private static func sendImage(provider: ModelRouter.Provider, model: String, key: String,
                                  base64: String, question: String) async throws -> String {
        var req: URLRequest
        var body: [String: Any]

        switch provider {
        case .anthropic:
            req = URLRequest(url: URL(string: provider.endpoint)!)
            req.setValue(key, forHTTPHeaderField: "x-api-key")
            req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            body = [
                "model": model, "max_tokens": 1024,
                "messages": [["role": "user", "content": [
                    ["type": "image",
                     "source": ["type": "base64", "media_type": "image/jpeg", "data": base64]],
                    ["type": "text", "text": question]
                ]]]
            ]

        case .gemini:
            req = URLRequest(url: URL(string: "\(provider.endpoint)/\(model):generateContent?key=\(key)")!)
            body = ["contents": [["parts": [
                ["inline_data": ["mime_type": "image/jpeg", "data": base64]],
                ["text": question]
            ]]]]

        default:
            req = URLRequest(url: URL(string: provider.endpoint)!)
            req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
            body = [
                "model": model, "max_tokens": 1024,
                "messages": [["role": "user", "content": [
                    ["type": "image_url",
                     "image_url": ["url": "data:image/jpeg;base64,\(base64)"]],
                    ["type": "text", "text": question]
                ]]]
            ]
        }

        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 90
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let json = try await fire(req)
        switch provider {
        case .anthropic:
            let blocks = json["content"] as? [[String: Any]] ?? []
            return blocks.compactMap { $0["text"] as? String }.joined()
                .trimmingCharacters(in: .whitespacesAndNewlines)
        case .gemini:
            let candidates = json["candidates"] as? [[String: Any]] ?? []
            let parts = (candidates.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
            return parts.compactMap { $0["text"] as? String }.joined()
                .trimmingCharacters(in: .whitespacesAndNewlines)
        default:
            let choices = json["choices"] as? [[String: Any]] ?? []
            let message = choices.first?["message"] as? [String: Any]
            return ((message?["content"] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    // MARK: - Providers

    private static func send(provider: ModelRouter.Provider, model: String, key: String,
                             system: String, user: String) async throws -> String {
        switch provider {
        case .anthropic: try await anthropic(model: model, key: key, system: system, user: user)
        case .gemini:    try await gemini(model: model, key: key, system: system, user: user)
        default:         try await openAICompatible(provider: provider, model: model, key: key,
                                                    system: system, user: user)
        }
    }

    private static func anthropic(model: String, key: String,
                                  system: String, user: String) async throws -> String {
        var req = URLRequest(url: URL(string: ModelRouter.Provider.anthropic.endpoint)!)
        req.httpMethod = "POST"
        req.setValue(key, forHTTPHeaderField: "x-api-key")
        req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 90
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "model": model,
            "max_tokens": 2048,
            "system": system,
            "messages": [["role": "user", "content": user]]
        ])

        let json = try await fire(req)
        // Content is a list of blocks; the text lives in the first text block.
        let blocks = json["content"] as? [[String: Any]] ?? []
        let text = blocks.compactMap { $0["text"] as? String }.joined()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// OpenAI, Groq, Cerebras and Mistral all speak the same chat-completions shape.
    private static func openAICompatible(provider: ModelRouter.Provider, model: String, key: String,
                                         system: String, user: String) async throws -> String {
        var req = URLRequest(url: URL(string: provider.endpoint)!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 90
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "model": model,
            "max_tokens": 2048,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": user]
            ]
        ])

        let json = try await fire(req)
        let choices = json["choices"] as? [[String: Any]] ?? []
        let message = choices.first?["message"] as? [String: Any]
        return ((message?["content"] as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func gemini(model: String, key: String,
                               system: String, user: String) async throws -> String {
        let url = "\(ModelRouter.Provider.gemini.endpoint)/\(model):generateContent?key=\(key)"
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 90
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "system_instruction": ["parts": [["text": system]]],
            "contents": [["parts": [["text": user]]]]
        ])

        let json = try await fire(req)
        let candidates = json["candidates"] as? [[String: Any]] ?? []
        let parts = (candidates.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        return parts.compactMap { $0["text"] as? String }.joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// One place where every provider's HTTP result is checked, so a non-200 can never be parsed as
    /// if it were an answer.
    private static func fire(_ req: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw ClientError.badResponse(status, String(data: data, encoding: .utf8) ?? "")
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
