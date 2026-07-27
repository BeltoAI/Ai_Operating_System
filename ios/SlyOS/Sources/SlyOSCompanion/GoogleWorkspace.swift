import Foundation

/// Documents, spreadsheets and decks in the owner's own Google account — ported from Android's
/// `GoogleWorkspace`.
///
/// This is the third pillar and the one iOS was missing entirely. It matters because the output is
/// a **real link the owner can send someone**, not a wall of text in a chat window. The difference
/// between "here's a draft proposal" and a Google Doc URL is the difference between a demo and a
/// tool.
///
/// Uses the `documents`, `spreadsheets`, `presentations` and `drive.file` scopes already granted at
/// sign-in — `drive.file` meaning SlyOS can only ever touch files it created itself.
enum GoogleWorkspace {

    struct Made {
        let id: String
        let url: String
        let title: String
    }

    enum WorkspaceError: LocalizedError {
        case notConnected
        case api(Int, String)

        var errorDescription: String? {
            switch self {
            case .notConnected: "Connect Google first — documents are made in your own account."
            case .api(let code, let body):
                code == 403
                    ? "Google refused that. The Docs, Sheets and Slides APIs need enabling on your "
                      + "Cloud project."
                    : "Google returned \(code): \(body.prefix(160))"
            }
        }
    }

    /// SlyOS orange, as Google's APIs want it — 0…1 components rather than hex.
    private static let accent: [String: Any] = [
        "rgbColor": ["red": 232.0 / 255, "green": 100.0 / 255, "blue": 44.0 / 255]
    ]

    // MARK: - Docs

    /// A styled document from markdown-ish text.
    ///
    /// Built in two calls because the API demands it: create the file, then batch the content in.
    /// Insertions run **back to front** — every insert shifts every index after it, so applying
    /// them in reading order makes each one land in the wrong place.
    static func createDoc(title: String, body: String) async throws -> Made {
        let created = try await request("POST", "https://docs.googleapis.com/v1/documents",
                                        body: ["title": title])
        guard let id = created["documentId"] as? String else { throw WorkspaceError.api(0, "no id") }

        var requests: [[String: Any]] = []
        var index = 1

        struct Line { let text: String; let heading: Bool; let bullet: Bool }
        let lines: [Line] = body.split(separator: "\n", omittingEmptySubsequences: false).map { raw in
            let t = raw.trimmingCharacters(in: .whitespaces)
            if let m = t.firstMatch(#"^#{1,6}\s+(.*)$"#) { return Line(text: m[1], heading: true, bullet: false) }
            if let m = t.firstMatch(#"^\*\*(.+)\*\*:?$"#) { return Line(text: m[1], heading: true, bullet: false) }
            if let m = t.firstMatch(#"^[-•*]\s+(.*)$"#) { return Line(text: m[1], heading: false, bullet: true) }
            return Line(text: t, heading: false, bullet: false)
        }

        for line in lines {
            let text = (line.bullet ? "• " : "") + line.text + "\n"
            requests.append(["insertText": ["location": ["index": index], "text": text]])
            let range: [String: Any] = ["startIndex": index, "endIndex": index + text.count]

            if line.heading {
                requests.append(["updateTextStyle": [
                    "range": range,
                    "textStyle": ["bold": true, "fontSize": ["magnitude": 15, "unit": "PT"],
                                  "foregroundColor": accent],
                    "fields": "bold,fontSize,foregroundColor"
                ]])
            }
            index += text.count
        }

        if !requests.isEmpty {
            _ = try await request("POST",
                "https://docs.googleapis.com/v1/documents/\(id):batchUpdate",
                body: ["requests": requests])
        }
        return Made(id: id, url: "https://docs.google.com/document/d/\(id)/edit", title: title)
    }

    // MARK: - Sheets

    /// A spreadsheet with a styled header row.
    static func createSheet(title: String, rows: [[String]]) async throws -> Made {
        let created = try await request("POST", "https://sheets.googleapis.com/v4/spreadsheets",
                                        body: ["properties": ["title": title]])
        guard let id = created["spreadsheetId"] as? String else { throw WorkspaceError.api(0, "no id") }

        if !rows.isEmpty {
            _ = try await request("PUT",
                "https://sheets.googleapis.com/v4/spreadsheets/\(id)/values/Sheet1!A1?valueInputOption=USER_ENTERED",
                body: ["values": rows])

            // Bold the header and freeze it, so a long sheet stays readable while scrolling.
            _ = try? await request("POST",
                "https://sheets.googleapis.com/v4/spreadsheets/\(id):batchUpdate",
                body: ["requests": [
                    ["repeatCell": [
                        "range": ["sheetId": 0, "startRowIndex": 0, "endRowIndex": 1],
                        "cell": ["userEnteredFormat": [
                            "textFormat": ["bold": true, "foregroundColor": accent["rgbColor"]!]
                        ]],
                        "fields": "userEnteredFormat.textFormat"
                    ]],
                    ["updateSheetProperties": [
                        "properties": ["sheetId": 0,
                                       "gridProperties": ["frozenRowCount": 1]],
                        "fields": "gridProperties.frozenRowCount"
                    ]]
                ]])
        }
        return Made(id: id, url: "https://docs.google.com/spreadsheets/d/\(id)/edit", title: title)
    }

    // MARK: - Slides

    /// A deck, one slide per title/body pair.
    ///
    /// Slides are created blank and then filled, and each shape needs an id we choose ourselves —
    /// the API will not tell us what it made until afterwards, which is too late to write into.
    static func createSlides(title: String, slides: [(title: String, body: String)]) async throws -> Made {
        let created = try await request("POST", "https://slides.googleapis.com/v1/presentations",
                                        body: ["title": title])
        guard let id = created["presentationId"] as? String else { throw WorkspaceError.api(0, "no id") }

        var requests: [[String: Any]] = []
        for (i, slide) in slides.enumerated() {
            let pageId = "slide_\(i)"
            let titleId = "t_\(i)", bodyId = "b_\(i)"

            requests.append(["createSlide": [
                "objectId": pageId,
                "slideLayoutReference": ["predefinedLayout": "BLANK"]
            ]])

            func box(_ objectId: String, y: Int, height: Int) -> [String: Any] {
                ["createShape": [
                    "objectId": objectId, "shapeType": "TEXT_BOX",
                    "elementProperties": [
                        "pageObjectId": pageId,
                        "size": ["width": ["magnitude": 8000000, "unit": "EMU"],
                                 "height": ["magnitude": height, "unit": "EMU"]],
                        "transform": ["scaleX": 1, "scaleY": 1,
                                      "translateX": 600000, "translateY": y, "unit": "EMU"]
                    ]
                ]]
            }

            requests.append(box(titleId, y: 700000, height: 1200000))
            requests.append(["insertText": ["objectId": titleId, "text": slide.title]])
            requests.append(["updateTextStyle": [
                "objectId": titleId,
                "style": ["bold": true, "fontSize": ["magnitude": 30, "unit": "PT"],
                          "foregroundColor": ["opaqueColor": accent]],
                "fields": "bold,fontSize,foregroundColor"
            ]])

            if !slide.body.isEmpty {
                requests.append(box(bodyId, y: 2100000, height: 2600000))
                requests.append(["insertText": ["objectId": bodyId, "text": slide.body]])
                requests.append(["updateTextStyle": [
                    "objectId": bodyId,
                    "style": ["fontSize": ["magnitude": 15, "unit": "PT"]],
                    "fields": "fontSize"
                ]])
            }
        }

        if !requests.isEmpty {
            _ = try await request("POST",
                "https://slides.googleapis.com/v1/presentations/\(id):batchUpdate",
                body: ["requests": requests])
        }
        return Made(id: id, url: "https://docs.google.com/presentation/d/\(id)/edit", title: title)
    }

    // MARK: - Plumbing

    @discardableResult
    private static func request(_ method: String, _ url: String,
                                body: [String: Any]) async throws -> [String: Any] {
        guard GoogleAuth.shared.isConnected else { throw WorkspaceError.notConnected }
        let token = try await GoogleAuth.shared.accessToken()

        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 60
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw WorkspaceError.api(code, String(data: data, encoding: .utf8) ?? "")
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
