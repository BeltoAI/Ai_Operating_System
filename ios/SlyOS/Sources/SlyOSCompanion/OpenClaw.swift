import Foundation
import Observation

/// Talks to the owner's own OpenClaw gateway.
///
/// OpenClaw is a self-hosted agent that connects to WhatsApp, Telegram, Slack and the rest through
/// its own gateway rather than through the phone. That is precisely the wall iOS puts in front of
/// SlyOS: Apple will never let an app read another app's notifications, but nothing stops SlyOS
/// asking a machine the owner controls what it has seen. It is the one route to a WhatsApp-aware
/// brain on iPhone.
///
/// **Guardrails, and why each exists.** OpenClaw can read files, run scripts and drive browsers. A
/// client that pointed at one casually would be handing that reach to whatever came back over the
/// wire, so:
///
/// * **A token is mandatory.** Over 17,000 gateways are reachable on the public internet with no
///   token set; anyone can read their session history or send them arbitrary instructions. This
///   client refuses to connect without one rather than quietly joining that set.
/// * **Plaintext off-device is refused.** `http://` to anything but localhost sends the token in
///   the clear over whatever network the phone is on.
/// * **Read-only unless the owner says otherwise**, per-session, off by default.
/// * **Nothing executes silently.** Every action is proposed, confirmed by the owner, and recorded
///   in the outbox — the same gate every other side-effect in SlyOS goes through.
@Observable
final class OpenClaw {

    static let shared = OpenClaw()

    /// The gateway's default port.
    static let defaultPort = 18789

    private let keychain = Keychain(service: "com.belto.slyos.openclaw")

    // MARK: - Configuration

    var host: String {
        get { SharedContainer.defaults.string(forKey: "openclaw.host") ?? "" }
        set { SharedContainer.defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "openclaw.host") }
    }

    var token: String {
        get { keychain.string(for: "token") ?? "" }
        set {
            let t = newValue.trimmingCharacters(in: .whitespaces)
            t.isEmpty ? keychain.remove("token") : keychain.set(t, for: "token")
        }
    }

    /// Whether SlyOS may ask the gateway to *do* things rather than only read. Off by default and
    /// never implied by connecting.
    var allowActions: Bool {
        get { SharedContainer.defaults.bool(forKey: "openclaw.allowActions") }
        set { SharedContainer.defaults.set(newValue, forKey: "openclaw.allowActions") }
    }

    private(set) var status: String?
    private(set) var checking = false
    private(set) var connected = false

    var isConfigured: Bool { !baseURL.isEmpty && !token.isEmpty }

    /// Normalised base URL. A bare host gets the default port and http, which is the common case
    /// because the gateway usually sits on the owner's own machine.
    var baseURL: String {
        var h = host
        guard !h.isEmpty else { return "" }
        if !h.contains("://") { h = "http://" + h }
        if URLComponents(string: h)?.port == nil, !h.hasSuffix("/") {
            h += ":\(Self.defaultPort)"
        }
        return h.hasSuffix("/") ? String(h.dropLast()) : h
    }

    enum ClawError: LocalizedError {
        case notConfigured
        case insecure(String)
        case actionsDisabled
        case refused(String)
        case needsConfirmation(String)
        case api(Int, String)
        case gateway(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:
                "Set your OpenClaw address and token first."
            case .insecure(let host):
                "Refusing to send your token in the clear to \(host). Use https, or a tunnel, or "
                + "reach it over localhost."
            case .actionsDisabled:
                "SlyOS is connected to OpenClaw read-only. Turn on actions in Settings if you want "
                + "it to be able to do things there."
            case .refused(let why):
                why
            case .needsConfirmation(let tool):
                "\(tool) needs your say-so first."
            case .api(let code, let body):
                code == 401 ? "OpenClaw rejected the token." : "OpenClaw returned \(code): \(body.prefix(140))"
            case .gateway(let message):
                message
            }
        }
    }

    /// A token travelling in plaintext to anything but the loopback interface is a credential
    /// handed to the network.
    private func assertSafe() throws {
        guard isConfigured else { throw ClawError.notConfigured }
        guard let url = URL(string: baseURL), let scheme = url.scheme, let h = url.host else {
            throw ClawError.notConfigured
        }
        let local = ["localhost", "127.0.0.1", "::1"].contains(h) || h.hasSuffix(".local")
        if scheme == "http" && !local { throw ClawError.insecure(h) }
    }

    // MARK: - Health

    @MainActor
    func check() async {
        checking = true
        defer { checking = false }
        do {
            try assertSafe()
            // A read-only tool listing is the cheapest thing that proves both reachability and that
            // the token is accepted.
            _ = try await invoke(tool: "session.status", args: [:])
            connected = true
            status = "Connected to \(URL(string: baseURL)?.host ?? baseURL)."
        } catch {
            connected = false
            status = error.localizedDescription
        }
    }

    // MARK: - Tools

    /// Invoke a gateway tool.
    ///
    /// Every call is classified by `OpenClawPolicy` first. The allowlist is the guardrail: a tool
    /// nobody has explicitly permitted is refused, so a gateway that gains new capabilities
    /// tomorrow does not silently gain them here.
    ///
    /// `confirmed` must be true for anything that changes the world. Callers cannot opt out of that
    /// by passing `readOnly` — the policy decides which is which, not the caller.
    @discardableResult
    func invoke(tool: String, action: String? = nil, args: [String: Any] = [:],
                sessionKey: String = "main", confirmed: Bool = false) async throws -> [String: Any] {
        try assertSafe()

        switch OpenClawPolicy.decide(tool: tool, actionsEnabled: allowActions) {
        case .allowedRead:
            break
        case .needsConfirmation:
            guard confirmed else { throw ClawError.needsConfirmation(tool) }
        case .refused(let why):
            throw ClawError.refused(why)
        }

        var body: [String: Any] = ["tool": tool, "args": args, "sessionKey": sessionKey]
        if let action { body["action"] = action }
        // A stable key so a retry cannot fire the same side effect twice.
        body["idempotencyKey"] = "slyos-\(tool)-\(Int(Date().timeIntervalSince1970))"

        var req = URLRequest(url: URL(string: baseURL + "/tools/invoke")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 30
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw ClawError.api(code, String(data: data, encoding: .utf8) ?? "")
        }

        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        // The gateway reports its own failures inside a 200, so `ok` has to be checked separately.
        if let ok = json["ok"] as? Bool, ok == false {
            let error = json["error"] as? [String: Any]
            throw ClawError.gateway((error?["message"] as? String) ?? "OpenClaw refused that.")
        }
        return json["result"] as? [String: Any] ?? json
    }

    // MARK: - Embeddings

    /// Vectors from the owner's own gateway.
    ///
    /// The brain currently searches by keyword. Embeddings are what turn "who handles logistics"
    /// into the right person without that phrase ever appearing — and getting them here means the
    /// text never leaves hardware the owner controls.
    func embed(_ texts: [String], model: String = "default") async throws -> [[Double]] {
        try assertSafe()

        var req = URLRequest(url: URL(string: baseURL + "/v1/embeddings")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 60
        req.httpBody = try JSONSerialization.data(withJSONObject: ["model": model, "input": texts])

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw ClawError.api(code, String(data: data, encoding: .utf8) ?? "")
        }
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let rows = json["data"] as? [[String: Any]] ?? []
        return rows.compactMap { $0["embedding"] as? [Double] }
    }

    // MARK: - Brain

    /// Pull recent messages the gateway has seen and put them in the brain.
    ///
    /// This is the payoff: WhatsApp, Telegram and Slack conversations that iOS itself will never
    /// hand over, arriving through a machine the owner runs. Read-only by construction.
    @discardableResult
    func importMessages(limit: Int = 300) async throws -> Int {
        let result = try await invoke(tool: "messages.search", args: ["limit": limit])

        // Be liberal about the envelope: gateways differ by version, and a rigid parser here would
        // break on an upgrade the owner didn't ask for.
        let rows = (result["messages"] as? [[String: Any]])
            ?? (result["items"] as? [[String: Any]])
            ?? (result["results"] as? [[String: Any]])
            ?? []
        guard !rows.isEmpty else { return 0 }

        let memories: [Memory] = rows.compactMap { row in
            let text = (row["text"] as? String) ?? (row["body"] as? String) ?? ""
            guard !text.isEmpty else { return nil }
            let who = (row["from"] as? String) ?? (row["sender"] as? String)
                ?? (row["contact"] as? String) ?? ""
            let channel = (row["channel"] as? String) ?? (row["platform"] as? String) ?? "OpenClaw"
            let seconds = (row["timestamp"] as? Double)
                ?? (row["ts"] as? Double).map { $0 > 1e11 ? $0 / 1000 : $0 }
                ?? Date().timeIntervalSince1970
            return Memory(kind: "message", person: who, title: "",
                          body: String(text.prefix(4_000)),
                          source: channel.capitalized,
                          date: Date(timeIntervalSince1970: seconds))
        }

        SlyStore.shared.insertMany(memories)
        return memories.count
    }

    /// Run an action the owner has just confirmed.
    ///
    /// `confirm` is not a formality: it is called with a plain-language description of what is about
    /// to happen and must return true. Nothing reaches a gateway tool that changes the world without
    /// a human answering that question first, and the result lands in the outbox either way — the
    /// same gate every other side effect in SlyOS goes through.
    func perform(tool: String, args: [String: Any], describedAs description: String,
                 confirm: (String) async -> Bool) async throws -> String {
        guard allowActions else { throw ClawError.actionsDisabled }
        guard case .needsConfirmation = OpenClawPolicy.decide(tool: tool, actionsEnabled: true) else {
            throw ClawError.refused("\(tool) is not something SlyOS will ask OpenClaw to do.")
        }
        guard await confirm(description) else {
            Outbox.shared.record(what: description, detail: "via OpenClaw · \(tool)", outcome: "cancelled")
            throw ClawError.refused("Cancelled.")
        }
        do {
            let result = try await invoke(tool: tool, args: args, confirmed: true)
            let summary = (result["message"] as? String) ?? "done"
            await MainActor.run {
                Outbox.shared.record(what: description, detail: "via OpenClaw · \(tool)", outcome: "sent")
            }
            return summary
        } catch {
            await MainActor.run {
                Outbox.shared.record(what: description,
                                     detail: "via OpenClaw · \(tool) — \(error.localizedDescription)",
                                     outcome: "failed")
            }
            throw error
        }
    }
}
