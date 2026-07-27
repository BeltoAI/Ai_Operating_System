import Foundation

/// Mail into the brain.
///
/// On iOS this is the *only* route to someone's mail — apps have no access to the Mail app, its
/// accounts, or its database. So Gmail's API is not a convenience here, it is the whole feature,
/// and an account that isn't Gmail simply has no mail in its brain. Better to say that than to
/// imply otherwise.
///
/// Uses the `gmail.readonly` scope already granted at sign-in.
enum GmailImport {

    private static let base = "https://gmail.googleapis.com/gmail/v1/users/me"

    /// Import recent mail.
    ///
    /// Both sent and received: what the owner *wrote* is the more valuable half, because it is the
    /// record of their own voice and their own commitments. A brain built only from inbound mail
    /// knows what people asked and never what was promised.
    @discardableResult
    static func run(maxMessages: Int = 400,
                    progress: @escaping (Int, Int) -> Void = { _, _ in }) async throws -> Int {
        let token = try await GoogleAuth.shared.accessToken()

        // Skip the noise. Promotions and social are the bulk of most inboxes and the least useful
        // thing a brain could be full of.
        let query = "-category:promotions -category:social -in:spam -in:trash"
        let ids = try await messageIDs(query: query, limit: maxMessages, token: token)
        guard !ids.isEmpty else { return 0 }

        var batch: [Memory] = []
        for (index, id) in ids.enumerated() {
            if let memory = try? await message(id: id, token: token) { batch.append(memory) }
            progress(index + 1, ids.count)

            // Commit as we go: a 400-message import that fails at message 380 should not lose the
            // 379 that worked.
            if batch.count >= 50 {
                SlyStore.shared.insertMany(batch)
                batch.removeAll()
            }
        }
        if !batch.isEmpty { SlyStore.shared.insertMany(batch) }
        return ids.count
    }

    private static func messageIDs(query: String, limit: Int, token: String) async throws -> [String] {
        var out: [String] = []
        var pageToken: String?

        repeat {
            var c = URLComponents(string: "\(base)/messages")!
            c.queryItems = [
                .init(name: "q", value: query),
                .init(name: "maxResults", value: String(min(500, limit - out.count)))
            ]
            if let pageToken { c.queryItems?.append(.init(name: "pageToken", value: pageToken)) }

            let json = try await get(c.url!, token: token)
            let messages = json["messages"] as? [[String: Any]] ?? []
            out += messages.compactMap { $0["id"] as? String }
            pageToken = json["nextPageToken"] as? String
        } while pageToken != nil && out.count < limit

        return Array(out.prefix(limit))
    }

    /// One message, as a memory.
    ///
    /// `format=full` rather than `metadata`, because the body is the point — a brain that knows only
    /// who mailed whom and when cannot answer anything about what was said.
    private static func message(id: String, token: String) async throws -> Memory? {
        let json = try await get(URL(string: "\(base)/messages/\(id)?format=full")!, token: token)

        let payload = json["payload"] as? [String: Any] ?? [:]
        let headers = payload["headers"] as? [[String: Any]] ?? []
        func header(_ name: String) -> String {
            headers.first { ($0["name"] as? String)?.lowercased() == name.lowercased() }?["value"]
                as? String ?? ""
        }

        let subject = header("Subject")
        let from = header("From")
        let to = header("To")
        let labels = json["labelIds"] as? [String] ?? []
        let sentByOwner = labels.contains("SENT")

        // The correspondent is whoever isn't the owner.
        let person = displayName(from: sentByOwner ? to : from)

        var body = text(from: payload)
        if body.isEmpty { body = json["snippet"] as? String ?? "" }
        guard !subject.isEmpty || !body.isEmpty else { return nil }

        // Internal date is epoch millis as a *string* — parsing it as a number directly returns nil.
        let millis = Double(json["internalDate"] as? String ?? "") ?? 0

        return Memory(
            kind: "mail",
            person: person,
            title: subject,
            body: String(body.prefix(4_000)),
            source: sentByOwner ? "Gmail (sent)" : "Gmail",
            date: Date(timeIntervalSince1970: millis / 1000))
    }

    /// Walk the MIME tree for readable text.
    ///
    /// Gmail nests parts arbitrarily — `multipart/alternative` inside `multipart/mixed` and so on —
    /// so this recurses rather than assuming a shape. Plain text is preferred over HTML; HTML is
    /// only used when there is nothing else, and then stripped.
    private static func text(from part: [String: Any]) -> String {
        let mime = part["mimeType"] as? String ?? ""

        if mime == "text/plain",
           let data = (part["body"] as? [String: Any])?["data"] as? String {
            return decode(data)
        }

        if let parts = part["parts"] as? [[String: Any]] {
            for child in parts {
                let found = text(from: child)
                if !found.isEmpty { return found }
            }
        }

        if mime == "text/html",
           let data = (part["body"] as? [String: Any])?["data"] as? String {
            return stripHTML(decode(data))
        }
        return ""
    }

    /// Gmail encodes bodies as base64**url**, and omits the padding.
    private static func decode(_ s: String) -> String {
        var b64 = s.replacingOccurrences(of: "-", with: "+")
                   .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private static func stripHTML(_ html: String) -> String {
        html
            .replacingOccurrences(of: "<style[^>]*>[\\s\\S]*?</style>", with: " ",
                                  options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "<script[^>]*>[\\s\\S]*?</script>", with: " ",
                                  options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "\\s{2,}", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `"Carlos Ruiz <carlos@x.com>"` → `"Carlos Ruiz"`; a bare address keeps its local part.
    private static func displayName(from header: String) -> String {
        let first = header.split(separator: ",").first.map(String.init) ?? header
        if let open = first.firstIndex(of: "<") {
            let name = first[first.startIndex..<open]
                .trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
            if !name.isEmpty { return name }
            let address = first[first.index(after: open)...].prefix { $0 != ">" }
            return String(address.prefix { $0 != "@" })
        }
        return String(first.trimmingCharacters(in: .whitespaces).prefix { $0 != "@" })
    }

    private static func get(_ url: URL, token: String) async throws -> [String: Any] {
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 30

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw GoogleCalendar.CalendarError.api(status, String(data: data, encoding: .utf8) ?? "")
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
