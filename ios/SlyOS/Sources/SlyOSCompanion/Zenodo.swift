import Foundation
import Observation
import UIKit
import CoreText

/// Publishing a paper to Zenodo, ported from Android's `ZenodoClient`.
///
/// Zenodo is CERN's open repository: publishing there mints a real DOI, which is what turns
/// something SlyOS wrote from a document into a citable record with a permanent address. It is the
/// end of the Research pillar — write it, then publish it.
///
/// The token is the owner's own personal access token, needing `deposit:write` and
/// `deposit:actions`. It lives in the Keychain and is never sent anywhere but zenodo.org.
@Observable
final class Zenodo {

    static let shared = Zenodo()

    private static let base = "https://zenodo.org/api"
    private let keychain = Keychain(service: "com.belto.slyos.zenodo")

    var token: String {
        get { keychain.string(for: "token") ?? "" }
        set {
            let t = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            t.isEmpty ? keychain.remove("token") : keychain.set(t, for: "token")
        }
    }

    var isConfigured: Bool { !token.isEmpty }

    struct Published {
        let doi: String
        let url: String
        let depositID: Int
        /// True when it was left as a draft rather than actually published.
        let isDraft: Bool
    }

    enum ZenodoError: LocalizedError {
        case noToken
        case step(String, Int, String)

        var errorDescription: String? {
            switch self {
            case .noToken:
                "Add a Zenodo token in Settings (zenodo.org → Applications → Personal access "
                + "tokens, with deposit:write and deposit:actions)."
            case .step(let what, let code, let body):
                "Zenodo refused the \(what) (\(code)): \(body.prefix(160))"
            }
        }
    }

    /// Publish a paper.
    ///
    /// Four steps, in this order, because Zenodo requires it: create the deposition, upload the
    /// file, set the metadata, then publish. Metadata cannot be set before a file exists, and
    /// publishing is irreversible — which is why `publish` defaults to false and the caller has to
    /// ask for it.
    func publish(title: String, body: String, author: String, affiliation: String = "",
                 keywords: [String] = [], publish: Bool = false) async throws -> Published {
        guard isConfigured else { throw ZenodoError.noToken }

        // 1) A new deposition.
        let created = try await json("POST", "\(Self.base)/deposit/depositions", body: [:], step: "create")
        guard let id = created["id"] as? Int else {
            throw ZenodoError.step("create", 0, "no deposition id")
        }
        let bucket = (created["links"] as? [String: Any])?["bucket"] as? String ?? ""

        // 2) The file itself. Zenodo will not accept metadata for an empty deposition.
        let name = Self.sanitise(title).isEmpty ? "paper" : String(Self.sanitise(title).prefix(80))
        let pdf = try makePDF(title: title, body: body, author: author)
        try await upload(pdf, as: "\(name).pdf", bucket: bucket, depositID: id)

        // 3) Metadata. Open access and CC-BY, matching Android — a paper published behind a licence
        // nobody can use is not published in any sense that matters.
        var creator: [String: Any] = ["name": author.isEmpty ? "Anonymous" : author]
        if !affiliation.isEmpty { creator["affiliation"] = affiliation }

        var metadata: [String: Any] = [
            "title": title.isEmpty ? "Untitled" : title,
            "upload_type": "publication",
            "publication_type": "workingpaper",
            // Zenodo renders the description as HTML, so paragraphs need wrapping or the whole
            // paper arrives as one unbroken block.
            "description": Self.html(body.isEmpty ? title : body),
            "creators": [creator],
            "access_right": "open",
            "license": "cc-by-4.0",
            "language": "eng",
            "publication_date": Self.today()
        ]
        let cleaned = keywords.map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        if !cleaned.isEmpty { metadata["keywords"] = Array(Set(cleaned)).prefix(12).map { $0 } }

        _ = try await json("PUT", "\(Self.base)/deposit/depositions/\(id)",
                           body: ["metadata": metadata], step: "metadata")

        guard publish else {
            return Published(doi: "draft", url: "https://zenodo.org/uploads/\(id)",
                             depositID: id, isDraft: true)
        }

        // 4) Publish — this mints the DOI and cannot be undone.
        let done = try await json("POST", "\(Self.base)/deposit/depositions/\(id)/actions/publish",
                                  body: [:], step: "publish")
        let doi = (done["doi"] as? String)
            ?? ((done["metadata"] as? [String: Any])?["doi"] as? String) ?? ""
        return Published(doi: doi, url: "https://zenodo.org/records/\(id)",
                         depositID: id, isDraft: false)
    }

    // MARK: - File

    /// Render the paper as a PDF.
    ///
    /// Zenodo needs a file, not a string. `UIGraphicsPDFRenderer` with attributed text paginates
    /// properly, so a long paper does not silently become one page with everything after the first
    /// screenful missing.
    private func makePDF(title: String, body: String, author: String) throws -> Data {
        let pageSize = CGRect(x: 0, y: 0, width: 595, height: 842)   // A4 at 72dpi
        let margin: CGFloat = 56
        let textRect = pageSize.insetBy(dx: margin, dy: margin)

        let text = NSMutableAttributedString()
        text.append(NSAttributedString(string: title + "\n", attributes: [
            .font: UIFont.systemFont(ofSize: 22, weight: .semibold)
        ]))
        if !author.isEmpty {
            text.append(NSAttributedString(string: author + "\n\n", attributes: [
                .font: UIFont.systemFont(ofSize: 12),
                .foregroundColor: UIColor.darkGray
            ]))
        }
        text.append(NSAttributedString(string: "\n" + body, attributes: [
            .font: UIFont.systemFont(ofSize: 11)
        ]))

        let framesetter = CTFramesetterCreateWithAttributedString(text)
        let renderer = UIGraphicsPDFRenderer(bounds: pageSize)

        return renderer.pdfData { ctx in
            var start = 0
            let total = text.length
            repeat {
                ctx.beginPage()
                guard let cg = UIGraphicsGetCurrentContext() else { break }
                // Core Text draws bottom-up; flipping keeps the page the right way round.
                cg.textMatrix = .identity
                cg.translateBy(x: 0, y: pageSize.height)
                cg.scaleBy(x: 1, y: -1)

                let path = CGPath(rect: CGRect(x: textRect.minX, y: margin,
                                               width: textRect.width, height: textRect.height),
                                  transform: nil)
                let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: start, length: 0),
                                                     path, nil)
                CTFrameDraw(frame, cg)
                let visible = CTFrameGetVisibleStringRange(frame)
                // No progress means nothing more will ever fit; stopping avoids an endless PDF.
                if visible.length == 0 { break }
                start += visible.length
            } while start < total
        }
    }

    private func upload(_ data: Data, as name: String, bucket: String, depositID: Int) async throws {
        // The bucket API is a plain PUT of the bytes. Older depositions have no bucket and need the
        // legacy endpoint instead, so both are supported rather than assuming the new one.
        let url = bucket.isEmpty
            ? "\(Self.base)/deposit/depositions/\(depositID)/files"
            : "\(bucket)/\(name)"

        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = bucket.isEmpty ? "POST" : "PUT"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 120

        if bucket.isEmpty {
            let boundary = "zenodo-\(UUID().uuidString)"
            req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            var form = Data()
            form.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n\(name)\r\n".data(using: .utf8)!)
            form.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(name)\"\r\n".data(using: .utf8)!)
            form.append("Content-Type: application/pdf\r\n\r\n".data(using: .utf8)!)
            form.append(data)
            form.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
            req.httpBody = form
        } else {
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.httpBody = data
        }

        let (out, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw ZenodoError.step("upload", code, String(data: out, encoding: .utf8) ?? "")
        }
    }

    // MARK: - Plumbing

    @discardableResult
    private func json(_ method: String, _ url: String,
                      body: [String: Any], step: String) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 60
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw ZenodoError.step(step, code, String(data: data, encoding: .utf8) ?? "")
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private static func sanitise(_ s: String) -> String {
        s.replacingOccurrences(of: "[^A-Za-z0-9 _-]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: "_", options: .regularExpression)
    }

    private static func html(_ text: String) -> String {
        String(text.prefix(8_000))
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .map { "<p>" + $0.replacingOccurrences(of: "\n", with: " ") + "</p>" }
            .joined()
    }

    private static func today() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }
}
