import Foundation
import Observation

/// Accounts and brain sync, built against `agentos/ACCOUNT_AND_SYNC.md`.
///
/// That document is the cross-client contract the Android app already implements, so this speaks
/// the same endpoints and the same `brain_items` shape. A user who signs in on both phones gets one
/// brain, not two — which only works if neither client invents its own schema.
///
/// Plain HTTPS rather than the Supabase SDK: the surface used here is small, and a dependency that
/// pulls in its own networking stack is not worth it for six endpoints.
@Observable
final class SupabaseClient {

    static let shared = SupabaseClient()

    /// Both are read from Info.plist and both are safe to ship — the anon key is designed to be
    /// public, because Row-Level Security is what actually protects the data.
    /// Built from the bare host, because an xcconfig treats "//" as the start of a comment and a
    /// full https:// URL cannot survive being stored in one.
    var baseURL: String {
        let host = (Bundle.main.object(forInfoDictionaryKey: "SupabaseHost") as? String ?? "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        return host.isEmpty ? "" : "https://\(host)"
    }
    var anonKey: String {
        Bundle.main.object(forInfoDictionaryKey: "SupabaseAnonKey") as? String ?? ""
    }
    var isConfigured: Bool { !baseURL.isEmpty && !anonKey.isEmpty }

    // MARK: - Session

    private let keychain = Keychain(service: "com.belto.slyos.supabase")

    private(set) var email: String?
    private(set) var userID: String?
    var isSignedIn: Bool { userID != nil }

    /// When the last successful sync finished, so the UI can say something truthful about it.
    private(set) var lastSync: Date?
    private(set) var syncing = false
    private(set) var lastError: String?

    private init() {
        email = keychain.string(for: "email")
        userID = keychain.string(for: "user_id")
        if let raw = SharedContainer.defaults.object(forKey: "supabase.lastSync") as? Double {
            lastSync = Date(timeIntervalSince1970: raw)
        }
    }

    enum AuthError: LocalizedError {
        case notConfigured
        case api(Int, String)
        case malformed

        var errorDescription: String? {
            switch self {
            case .notConfigured:
                "Accounts aren't set up in this build (no Supabase host or key)."
            case .api(_, let message):
                message
            case .malformed:
                "The server's reply wasn't in the expected shape."
            }
        }
    }

    // MARK: - Auth

    func signUp(email: String, password: String) async throws {
        try await authenticate(path: "/auth/v1/signup",
                               body: ["email": email, "password": password])
    }

    func signIn(email: String, password: String) async throws {
        try await authenticate(path: "/auth/v1/token?grant_type=password",
                               body: ["email": email, "password": password])
    }

    @MainActor
    func signOut() {
        if let token = keychain.string(for: "access_token") {
            Task { _ = try? await send("POST", "/auth/v1/logout", body: [:], token: token) }
        }
        for k in ["access_token", "refresh_token", "expires_at", "email", "user_id"] {
            keychain.remove(k)
        }
        email = nil
        userID = nil
        lastSync = nil
    }

    private func authenticate(path: String, body: [String: Any]) async throws {
        guard isConfigured else { throw AuthError.notConfigured }
        let json = try await send("POST", path, body: body, token: nil)

        // Sign-up with email confirmation on returns a user but no session. That is a success, not
        // a failure, and saying "check your email" beats a misleading error.
        guard let access = json["access_token"] as? String else {
            if let user = json["user"] as? [String: Any], user["id"] != nil {
                throw AuthError.api(200, "Account created. Check your email to confirm it, then sign in.")
            }
            throw AuthError.malformed
        }

        keychain.set(access, for: "access_token")
        if let refresh = json["refresh_token"] as? String { keychain.set(refresh, for: "refresh_token") }
        let expiresIn = (json["expires_in"] as? Double) ?? 3600
        keychain.set(String(Date().timeIntervalSince1970 + expiresIn - 60), for: "expires_at")

        let user = json["user"] as? [String: Any]
        let id = user?["id"] as? String
        let mail = user?["email"] as? String
        if let id { keychain.set(id, for: "user_id") }
        if let mail { keychain.set(mail, for: "email") }

        await MainActor.run {
            self.userID = id
            self.email = mail
        }
    }

    /// A valid access token, refreshed when it has expired.
    private func accessToken() async throws -> String {
        guard let current = keychain.string(for: "access_token") else { throw AuthError.notConfigured }
        let expiry = keychain.string(for: "expires_at").flatMap(Double.init) ?? 0
        if Date().timeIntervalSince1970 < expiry { return current }

        guard let refresh = keychain.string(for: "refresh_token") else { return current }
        let json = try await send("POST", "/auth/v1/token?grant_type=refresh_token",
                                  body: ["refresh_token": refresh], token: nil)
        guard let access = json["access_token"] as? String else { return current }
        keychain.set(access, for: "access_token")
        if let r = json["refresh_token"] as? String { keychain.set(r, for: "refresh_token") }
        let expiresIn = (json["expires_in"] as? Double) ?? 3600
        keychain.set(String(Date().timeIntervalSince1970 + expiresIn - 60), for: "expires_at")
        return access
    }

    // MARK: - Sync

    /// Push local memories up, then pull anything newer down.
    ///
    /// Conflict strategy is the contract's: per-row last-write-wins on `updated_at` in UTC millis.
    /// `client_id` is stable per logical row so the same memory updates in place instead of
    /// multiplying every time a device syncs.
    @MainActor
    func sync() async {
        guard isConfigured, isSignedIn, !syncing else { return }
        syncing = true
        lastError = nil

        do {
            let token = try await accessToken()
            guard let user = userID else { throw AuthError.malformed }

            try await push(token: token, user: user)
            try await pull(token: token, user: user)

            lastSync = Date()
            SharedContainer.defaults.set(lastSync!.timeIntervalSince1970, forKey: "supabase.lastSync")
        } catch {
            lastError = error.localizedDescription
        }
        syncing = false
    }

    private func push(token: String, user: String) async throws {
        let local = SlyStore.shared.recent(limit: 2_000)
        guard !local.isEmpty else { return }

        let rows: [[String: Any]] = local.map { m in
            [
                "user_id": user,
                "kind": m.kind,
                // Stable identity for the row: the same memory must not duplicate on every sync.
                "client_id": "ios:\(m.id)",
                "title": m.title,
                "body": m.body,
                "data": ["person": m.person, "source": m.source,
                         "date": Int(m.date.timeIntervalSince1970 * 1000)],
                "updated_at": Int(m.date.timeIntervalSince1970 * 1000),
                "deleted": false
            ]
        }

        // Upsert on the contract's unique key, in batches — one 2,000-row request is a timeout.
        for chunk in stride(from: 0, to: rows.count, by: 200).map({
            Array(rows[$0..<min($0 + 200, rows.count)])
        }) {
            _ = try await send("POST", "/rest/v1/brain_items?on_conflict=user_id,kind,client_id",
                               bodyArray: chunk, token: token,
                               extraHeaders: ["Prefer": "resolution=merge-duplicates,return=minimal"])
        }
    }

    private func pull(token: String, user: String) async throws {
        let since = Int((lastSync?.timeIntervalSince1970 ?? 0) * 1000)
        let path = "/rest/v1/brain_items?user_id=eq.\(user)&updated_at=gt.\(since)"
            + "&deleted=is.false&order=updated_at.asc&limit=2000"
        // Note the watermark is `updated_at`, not the message date — rows arrive in the order they
        // were written, which is what makes resuming from `lastSync` correct.

        let rows = try await sendArray("GET", path, token: token)
        guard !rows.isEmpty else { return }

        // Anything this device pushed comes straight back; skipping it avoids duplicating our own
        // rows on every round trip.
        //
        // Android writes a different shape from iOS — it puts the correspondent in `title` and the
        // platform in `data.platform`, where iOS uses `data.person` and `data.source`. Both are read
        // here rather than forcing one to change, because the contract is the table, not the client.
        let incoming: [Memory] = rows.compactMap { row in
            guard let clientID = row["client_id"] as? String, !clientID.hasPrefix("ios:") else { return nil }
            let data = row["data"] as? [String: Any] ?? [:]
            let kind = row["kind"] as? String ?? "note"
            let title = row["title"] as? String ?? ""

            let person = (data["person"] as? String)
                ?? (kind == "message" ? title : "")
            let source = (data["source"] as? String)
                ?? (data["platform"] as? String)
                ?? "synced"

            // Android sends the message's own timestamp in `data.ts`; `updated_at` is when the row
            // was written, which for a five-year-old message is today and would sort it to the top.
            let millis = (data["ts"] as? Double)
                ?? (row["updated_at"] as? Double)
                ?? 0

            return Memory(
                kind: kind,
                person: person,
                // A message's title on Android is the contact, which is already the person — leaving
                // it in both places makes every search result read "Carlos Carlos".
                title: kind == "message" ? "" : title,
                body: row["body"] as? String ?? "",
                source: source,
                date: Date(timeIntervalSince1970: millis / 1000))
        }
        if !incoming.isEmpty { SlyStore.shared.insertMany(incoming) }
    }

    // MARK: - Deletion

    /// Delete the account and everything in it.
    ///
    /// **App Review requires this.** Guideline 5.1.1(v): any app that lets someone create an account
    /// must let them delete it from inside the app — not by emailing support, and not by only
    /// clearing local data.
    ///
    /// Two steps, in this order. The rows go first because Row-Level Security lets the user delete
    /// their own; once the auth user is gone their token is void and the rows would be orphaned.
    /// The auth record itself needs a server-side function, since no client key may delete a user —
    /// see `supabase/delete_account.sql`.
    @MainActor
    func deleteAccount() async throws {
        guard isConfigured, let user = userID else { throw AuthError.notConfigured }
        let token = try await accessToken()

        _ = try await send("DELETE", "/rest/v1/brain_items?user_id=eq.\(user)",
                           token: token, extraHeaders: ["Prefer": "return=minimal"])
        _ = try? await send("DELETE", "/rest/v1/vault_items?user_id=eq.\(user)",
                            token: token, extraHeaders: ["Prefer": "return=minimal"])
        _ = try? await send("DELETE", "/rest/v1/profiles?id=eq.\(user)",
                            token: token, extraHeaders: ["Prefer": "return=minimal"])

        // The auth user. Fails loudly rather than silently leaving a deleted-looking account behind.
        _ = try await send("POST", "/rest/v1/rpc/delete_account", body: [:], token: token)

        signOut()
    }

    // MARK: - Transport

    private func request(_ method: String, _ path: String, token: String?,
                         extraHeaders: [String: String] = [:]) -> URLRequest {
        var req = URLRequest(url: URL(string: baseURL + path)!)
        req.httpMethod = method
        req.setValue(anonKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(token ?? anonKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (k, v) in extraHeaders { req.setValue(v, forHTTPHeaderField: k) }
        req.timeoutInterval = 30
        return req
    }

    @discardableResult
    private func send(_ method: String, _ path: String, body: [String: Any]? = nil,
                      bodyArray: [[String: Any]]? = nil, token: String?,
                      extraHeaders: [String: String] = [:]) async throws -> [String: Any] {
        var req = request(method, path, token: token, extraHeaders: extraHeaders)
        if let bodyArray { req.httpBody = try JSONSerialization.data(withJSONObject: bodyArray) }
        else if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw AuthError.api(status, Self.message(from: data)) }
        guard !data.isEmpty else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func sendArray(_ method: String, _ path: String, token: String?) async throws -> [[String: Any]] {
        let req = request(method, path, token: token)
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw AuthError.api(status, Self.message(from: data)) }
        return (try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]) ?? []
    }

    /// Supabase reports failures in several shapes; show the human one rather than raw JSON.
    private static func message(from data: Data) -> String {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return String(data: data, encoding: .utf8) ?? "Unknown error"
        }
        return (o["msg"] as? String)
            ?? (o["message"] as? String)
            ?? (o["error_description"] as? String)
            ?? (o["error"] as? String)
            ?? "Unknown error"
    }
}
