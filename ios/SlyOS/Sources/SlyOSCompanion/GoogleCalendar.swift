import Foundation

/// Google Calendar, ported from the Android `GoogleCalendarClient`.
///
/// This creates *real* events with a *real* Meet link and *really* emails the invitations — the one
/// thing a local calendar write cannot do. On Android the local-calendar path claimed it had
/// invited people while emailing nobody, that claim was written into the brain, and the app later
/// repeated it as fact. There is no local path here at all, for exactly that reason.
enum GoogleCalendar {

    private static let base = "https://www.googleapis.com/calendar/v3/calendars/primary/events"

    struct Attendee: Identifiable, Equatable {
        var id: String { email }
        let email: String
        /// Google's own word: needsAction | accepted | declined | tentative.
        let responseStatus: String
        var organizer: Bool = false

        var hasAccepted: Bool { responseStatus == "accepted" }
        var hasDeclined: Bool { responseStatus == "declined" }
    }

    /// An event as Google holds it — the only trustworthy answer to "did that actually happen?".
    struct Event: Identifiable, Equatable {
        let id: String
        var title: String = ""
        var start: Date?
        var end: Date?
        var meetLink: String = ""
        var htmlLink: String = ""
        var attendees: [Attendee] = []

        /// Who has not replied yet — the people worth chasing.
        var awaitingReply: [Attendee] { attendees.filter { !$0.hasAccepted && !$0.organizer } }
    }

    // MARK: - Create

    /// Insert an event on the primary calendar.
    ///
    /// `sendUpdates=all` is what actually emails the invitations, and `conferenceDataVersion=1` is
    /// what makes Google honour the Meet request. Drop either and the call still returns 200 while
    /// doing nothing the user asked for.
    static func create(title: String, start: Date, end: Date,
                       attendees: [String] = [], withMeet: Bool = true,
                       description: String? = nil) async throws -> Event {
        var body: [String: Any] = [
            "summary": title,
            "start": ["dateTime": iso(start), "timeZone": TimeZone.current.identifier],
            "end": ["dateTime": iso(end), "timeZone": TimeZone.current.identifier]
        ]
        if let description { body["description"] = description }

        let emails = attendees.filter(isEmail)
        if !emails.isEmpty { body["attendees"] = emails.map { ["email": $0] } }

        if withMeet {
            body["conferenceData"] = ["createRequest": [
                "requestId": "slyos-\(Int(Date().timeIntervalSince1970 * 1000))",
                "conferenceSolutionKey": ["type": "hangoutsMeet"]
            ]]
        }

        let json = try await request("POST",
            "\(base)?conferenceDataVersion=1&sendUpdates=all", body: body)
        return parse(json)
    }

    // MARK: - Read

    /// Find events by title within a window.
    ///
    /// Searching by title is deliberate: people refer to "the date night invite", never to an
    /// opaque event id, so this is the lookup every follow-up question actually needs.
    static func find(titleContains: String = "",
                     from: Date = .now,
                     to: Date = Date().addingTimeInterval(30 * 24 * 3600),
                     max: Int = 20) async throws -> [Event] {
        var c = URLComponents(string: base)!
        c.queryItems = [
            .init(name: "timeMin", value: iso(from)),
            .init(name: "timeMax", value: iso(to)),
            .init(name: "singleEvents", value: "true"),
            .init(name: "orderBy", value: "startTime"),
            .init(name: "maxResults", value: String(max))
        ]
        if !titleContains.isEmpty { c.queryItems?.append(.init(name: "q", value: titleContains)) }

        let json = try await request("GET", c.url!.absoluteString)
        let items = json["items"] as? [[String: Any]] ?? []
        return items.map(parse)
    }

    static func get(_ id: String) async throws -> Event {
        parse(try await request("GET", "\(base)/\(id)"))
    }

    // MARK: - Change

    /// Patch an event and tell the people on it.
    ///
    /// `notify` defaults to true because a time change nobody is told about is worse than no change
    /// at all — everyone still believes the old time.
    ///
    /// Adding attendees **merges**: Google replaces the whole array on a patch, so sending only the
    /// new person silently uninvites everyone already on the event. That was a real Android bug.
    static func patch(id: String, title: String? = nil,
                      start: Date? = nil, end: Date? = nil,
                      addAttendees: [String] = [], addMeet: Bool = false,
                      description: String? = nil, notify: Bool = true) async throws -> Event {
        var body: [String: Any] = [:]
        if let title { body["summary"] = title }
        if let description { body["description"] = description }
        if let start { body["start"] = ["dateTime": iso(start), "timeZone": TimeZone.current.identifier] }
        if let end { body["end"] = ["dateTime": iso(end), "timeZone": TimeZone.current.identifier] }

        let incoming = addAttendees.filter(isEmail)
        if !incoming.isEmpty {
            let existing = try await get(id).attendees.map(\.email)
            var merged = existing
            for e in incoming where !merged.contains(e) { merged.append(e) }
            body["attendees"] = merged.map { ["email": $0] }
        }

        if addMeet {
            body["conferenceData"] = ["createRequest": [
                "requestId": "slyos-\(Int(Date().timeIntervalSince1970 * 1000))",
                "conferenceSolutionKey": ["type": "hangoutsMeet"]
            ]]
        }

        let json = try await request("PATCH",
            "\(base)/\(id)?conferenceDataVersion=1&sendUpdates=\(notify ? "all" : "none")",
            body: body)
        return parse(json)
    }

    /// Cancel an event. Attendees are told by default — a silent cancellation strands everyone.
    static func delete(id: String, notify: Bool = true) async throws {
        _ = try await request("DELETE", "\(base)/\(id)?sendUpdates=\(notify ? "all" : "none")")
    }

    // MARK: - Plumbing

    enum CalendarError: LocalizedError {
        case notConnected
        case api(Int, String)

        var errorDescription: String? {
            switch self {
            case .notConnected: "Google isn't connected."
            case .api(let code, let body): "Calendar refused that (\(code)): \(body.prefix(160))"
            }
        }
    }

    @discardableResult
    private static func request(_ method: String, _ url: String,
                                body: [String: Any]? = nil) async throws -> [String: Any] {
        let token = try await GoogleAuth.shared.accessToken()

        var req = URLRequest(url: URL(string: url)!)
        // URLSession speaks PATCH natively, so none of the Android override-header workaround is
        // needed here — HttpURLConnection was the thing that couldn't.
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 25
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw CalendarError.api(status, String(data: data, encoding: .utf8) ?? "")
        }
        guard !data.isEmpty else { return [:] }   // DELETE returns no body
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private static func parse(_ o: [String: Any]) -> Event {
        let people = (o["attendees"] as? [[String: Any]] ?? []).map {
            Attendee(email: $0["email"] as? String ?? "",
                     responseStatus: $0["responseStatus"] as? String ?? "needsAction",
                     organizer: $0["organizer"] as? Bool ?? false)
        }

        // The Meet link is usually `hangoutLink`, but on a freshly created event it only appears
        // inside conferenceData's video entry point.
        var meet = o["hangoutLink"] as? String ?? ""
        if meet.isEmpty,
           let entries = (o["conferenceData"] as? [String: Any])?["entryPoints"] as? [[String: Any]] {
            meet = entries.first { $0["entryPointType"] as? String == "video" }?["uri"] as? String ?? ""
        }

        return Event(
            id: o["id"] as? String ?? "",
            title: o["summary"] as? String ?? "",
            start: date(from: o["start"]),
            end: date(from: o["end"]),
            meetLink: meet,
            htmlLink: o["htmlLink"] as? String ?? "",
            attendees: people
        )
    }

    private static func date(from any: Any?) -> Date? {
        guard let d = (any as? [String: Any])?["dateTime"] as? String else { return nil }
        return isoParser.date(from: d)
    }

    private static let isoParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static func iso(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone.current
        return f.string(from: d)
    }

    private static func isEmail(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces)
        return t.contains("@") && t.contains(".")
    }
}
