import Foundation
import AuthenticationServices
import CryptoKit

/// Google sign-in, ported from the Android `GoogleAuth`.
///
/// Same flow: authorization code with PKCE (S256) and **no client secret**, which is what makes it
/// safe to ship in an app binary at all. Each user signs into their own account and the tokens
/// never leave the device.
///
/// Two things differ from Android and both are deliberate:
///
/// * `ASWebAuthenticationSession` replaces the Custom Tab. It is the only API that shares Safari's
///   cookie jar, so someone already signed into Google is one tap from done — and it is the one
///   Apple accepts in review for third-party sign-in.
/// * Tokens live in the **Keychain**, not `UserDefaults`. A refresh token is a long-lived key to
///   the user's mail and calendar; `UserDefaults` is a plist that comes out in an unencrypted
///   backup. Android's `SharedPreferences` had the same weakness and this is the chance to fix it.
@Observable
final class GoogleAuth: NSObject {

    static let shared = GoogleAuth()

    private static let authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    private static let tokenEndpoint = "https://oauth2.googleapis.com/token"

    /// Exactly the scopes the Android build asks for, so one brain behaves the same on both phones.
    private static let scopes = [
        "openid", "email",
        "https://www.googleapis.com/auth/calendar.events",
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/documents",
        "https://www.googleapis.com/auth/presentations",
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive.file"
    ].joined(separator: " ")

    /// Whether the account is connected. Observable so the UI can react the moment it changes.
    private(set) var connectedEmail: String?
    var isConnected: Bool { connectedEmail != nil }

    /// The iOS OAuth client id, read from Info.plist (`GoogleOAuthClientID`).
    ///
    /// This must be an **iOS** client in Google Cloud Console — the Android client id will not work,
    /// because Google ties the redirect scheme to the client type.
    var clientID: String {
        Bundle.main.object(forInfoDictionaryKey: "GoogleOAuthClientID") as? String ?? ""
    }

    /// Google's iOS convention: the reversed client id is the URL scheme.
    private var redirectURI: String {
        let reversed = clientID.split(separator: ".").reversed().joined(separator: ".")
        return "\(reversed):/oauth2redirect"
    }

    var isConfigured: Bool { !clientID.isEmpty }

    private var session: ASWebAuthenticationSession?
    private let keychain = Keychain(service: "com.belto.slyos.google")

    private override init() {
        super.init()
        connectedEmail = keychain.string(for: "email")
    }

    // MARK: - Sign in

    enum AuthError: LocalizedError {
        case notConfigured, cancelled, denied(String), badResponse(Int, String), malformed

        var errorDescription: String? {
            switch self {
            case .notConfigured:
                "Google sign-in isn't set up in this build (no OAuth client id)."
            case .cancelled:
                "Sign-in was cancelled."
            case .denied(let why):
                "Google refused the sign-in: \(why)"
            case .badResponse(let code, _):
                "Couldn't finish sign-in (\(code))."
            case .malformed:
                "Google's sign-in response was malformed."
            }
        }
    }

    @MainActor
    func signIn() async throws {
        guard isConfigured else { throw AuthError.notConfigured }

        // PKCE: a fresh random verifier per attempt, and only its SHA-256 goes over the wire.
        let verifier = Self.randomVerifier()
        let challenge = Self.challenge(for: verifier)

        var components = URLComponents(string: Self.authEndpoint)!
        components.queryItems = [
            .init(name: "client_id", value: clientID),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: Self.scopes),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            // Without both of these Google returns no refresh token on repeat sign-ins, and the
            // connection silently dies an hour later when the access token expires.
            .init(name: "access_type", value: "offline"),
            .init(name: "prompt", value: "consent")
        ]

        let callbackScheme = String(redirectURI.split(separator: ":").first ?? "")
        let code = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
            let s = ASWebAuthenticationSession(
                url: components.url!, callbackURLScheme: callbackScheme
            ) { url, error in
                if let error {
                    let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                    cont.resume(throwing: cancelled ? AuthError.cancelled : error)
                    return
                }
                guard let url,
                      let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
                else { return cont.resume(throwing: AuthError.malformed) }

                if let deny = items.first(where: { $0.name == "error" })?.value {
                    return cont.resume(throwing: AuthError.denied(deny))
                }
                guard let code = items.first(where: { $0.name == "code" })?.value else {
                    return cont.resume(throwing: AuthError.malformed)
                }
                cont.resume(returning: code)
            }
            s.presentationContextProvider = self
            // Use the existing Safari session so an already-signed-in user just taps Continue.
            s.prefersEphemeralWebBrowserSession = false
            self.session = s
            s.start()
        }

        try await exchange(code: code, verifier: verifier)
    }

    private func exchange(code: String, verifier: String) async throws {
        let body = [
            "code": code,
            "client_id": clientID,
            "redirect_uri": redirectURI,
            "grant_type": "authorization_code",
            "code_verifier": verifier
        ]
        let json = try await post(Self.tokenEndpoint, form: body)

        guard let access = json["access_token"] as? String else { throw AuthError.malformed }
        keychain.set(access, for: "access_token")
        if let refresh = json["refresh_token"] as? String, !refresh.isEmpty {
            keychain.set(refresh, for: "refresh_token")
        }
        let expiresIn = (json["expires_in"] as? Double) ?? 3600
        keychain.set(String(Date().timeIntervalSince1970 + expiresIn - 60), for: "expires_at")

        let email = (json["id_token"] as? String).flatMap(Self.emailFromIDToken) ?? "connected"
        keychain.set(email, for: "email")
        await MainActor.run { connectedEmail = email }
    }

    func signOut() {
        for k in ["access_token", "refresh_token", "expires_at", "email"] { keychain.remove(k) }
        connectedEmail = nil
    }

    // MARK: - Tokens

    /// A usable access token, refreshed transparently when it has expired.
    ///
    /// Every Google call goes through here rather than reading the stored token directly, because a
    /// token that expired sixty seconds ago fails with a 401 that looks exactly like "not connected".
    func accessToken() async throws -> String {
        guard let refresh = keychain.string(for: "refresh_token") else { throw AuthError.notConfigured }

        let expiry = keychain.string(for: "expires_at").flatMap(Double.init) ?? 0
        if let current = keychain.string(for: "access_token"), Date().timeIntervalSince1970 < expiry {
            return current
        }

        let json = try await post(Self.tokenEndpoint, form: [
            "client_id": clientID,
            "refresh_token": refresh,
            "grant_type": "refresh_token"
        ])
        guard let access = json["access_token"] as? String else { throw AuthError.malformed }
        keychain.set(access, for: "access_token")
        let expiresIn = (json["expires_in"] as? Double) ?? 3600
        keychain.set(String(Date().timeIntervalSince1970 + expiresIn - 60), for: "expires_at")
        return access
    }

    // MARK: - Plumbing

    private func post(_ endpoint: String, form: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: endpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = form
            .map { "\($0.key)=\(Self.escape($0.value))" }
            .joined(separator: "&")
            .data(using: .utf8)
        req.timeoutInterval = 25

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw AuthError.badResponse(status, String(data: data, encoding: .utf8) ?? "")
        }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AuthError.malformed
        }
        return obj
    }

    private static func escape(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    private static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(Data(bytes))
    }

    private static func challenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    /// PKCE and JWTs both use base64**url** — the plain base64 alphabet is rejected by Google.
    private static func base64URL(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Pull the email out of the id_token payload. The signature is not checked because the token
    /// came straight from Google's token endpoint over TLS; this is a display name, not a decision.
    private static func emailFromIDToken(_ jwt: String) -> String? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj["email"] as? String
    }
}

extension GoogleAuth: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}
