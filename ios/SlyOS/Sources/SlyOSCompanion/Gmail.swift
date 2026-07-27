import Foundation

/// Sending mail as the owner, through their own Gmail account.
///
/// Uses the `gmail.send` scope already granted at sign-in. Every send is recorded in the outbox by
/// the caller, because a message that left the phone must be checkable afterwards.
enum Gmail {

    /// Send a plain-text message.
    ///
    /// Gmail's API takes a whole RFC 2822 message base64url-encoded, not a set of fields — so the
    /// headers are built by hand. Subject is RFC 2047 encoded, or a non-ASCII subject arrives as
    /// mojibake in the recipient's inbox.
    static func send(to: String, subject: String, body: String) async throws {
        let token = try await GoogleAuth.shared.accessToken()

        let encodedSubject = "=?UTF-8?B?" + Data(subject.utf8).base64EncodedString() + "?="
        let raw = """
            To: \(to)\r
            Subject: \(encodedSubject)\r
            MIME-Version: 1.0\r
            Content-Type: text/plain; charset=UTF-8\r
            \r
            \(body)
            """

        // base64url, and Gmail rejects the standard alphabet.
        let encoded = Data(raw.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        var req = URLRequest(url: URL(string: "https://gmail.googleapis.com/gmail/v1/users/me/messages/send")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 30
        req.httpBody = try JSONSerialization.data(withJSONObject: ["raw": encoded])

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw GoogleCalendar.CalendarError.api(code, String(data: data, encoding: .utf8) ?? "")
        }
    }
}
