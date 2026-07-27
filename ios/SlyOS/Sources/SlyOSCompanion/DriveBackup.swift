import Foundation
import SQLite3
import Observation

/// Full-brain backup into the owner's own Google Drive, mirroring Android's `DriveBackup`.
///
/// The `drive.file` scope — already requested at sign-in — lets an app see only the files it
/// created itself. So SlyOS can keep its own rolling backup up to date and find it again on a new
/// device, while never being able to read anything else in the user's Drive. That is a much easier
/// permission to justify than full Drive access, and it is all this needs.
///
/// Supabase syncs individual memories between devices; this is the whole database as one file, for
/// the case where the phone is lost rather than merely joined by a second one.
@Observable
final class DriveBackup {

    static let shared = DriveBackup()

    /// One rolling file, updated in place, so backups never accumulate in someone's Drive.
    private static let fileName = "slyos-ios-brain.sqlite"
    private static let uploadEndpoint = "https://www.googleapis.com/upload/drive/v3/files"
    private static let filesEndpoint = "https://www.googleapis.com/drive/v3/files"

    private(set) var working = false
    private(set) var lastBackup: Date?
    private(set) var lastError: String?
    private(set) var backupSize: Int?

    private init() {
        if let raw = SharedContainer.defaults.object(forKey: "drive.lastBackup") as? Double {
            lastBackup = Date(timeIntervalSince1970: raw)
        }
    }

    enum BackupError: LocalizedError {
        case notConnected, api(Int, String), noBackup

        var errorDescription: String? {
            switch self {
            case .notConnected: "Connect Google first — backups go to your own Drive."
            case .api(let code, let body): "Drive refused that (\(code)): \(body.prefix(140))"
            case .noBackup: "No backup found in your Drive yet."
            }
        }
    }

    // MARK: - Back up

    @MainActor
    func backUp() async {
        guard GoogleAuth.shared.isConnected else {
            lastError = BackupError.notConnected.localizedDescription
            return
        }
        working = true
        lastError = nil

        do {
            let data = try snapshot()
            let token = try await GoogleAuth.shared.accessToken()

            if let existing = try await findExisting(token: token) {
                try await upload(data, token: token, fileID: existing)
            } else {
                try await upload(data, token: token, fileID: nil)
            }

            backupSize = data.count
            lastBackup = Date()
            SharedContainer.defaults.set(lastBackup!.timeIntervalSince1970, forKey: "drive.lastBackup")
        } catch {
            lastError = error.localizedDescription
        }
        working = false
    }

    /// A consistent copy of the database.
    ///
    /// The WAL is checkpointed first. SQLite in WAL mode keeps recent commits in a sidecar file, so
    /// copying the .sqlite alone would silently back up a database missing everything written since
    /// the last automatic checkpoint — the newest memories, exactly the ones worth keeping.
    private func snapshot() throws -> Data {
        SlyStore.shared.checkpoint()
        return try Data(contentsOf: SharedContainer.databaseURL)
    }

    // MARK: - Restore

    /// Pull the backup down and merge it into the current brain.
    ///
    /// Merge rather than replace: restoring onto a phone that has already been used should not throw
    /// away whatever it has learned since. Duplicates are avoided by the store's own identity rules.
    @MainActor
    @discardableResult
    func restore() async -> Int {
        guard GoogleAuth.shared.isConnected else {
            lastError = BackupError.notConnected.localizedDescription
            return 0
        }
        working = true
        lastError = nil
        var merged = 0

        do {
            let token = try await GoogleAuth.shared.accessToken()
            guard let fileID = try await findExisting(token: token) else { throw BackupError.noBackup }

            let data = try await download(fileID: fileID, token: token)
            // Write it beside the live database and read it as a separate connection, rather than
            // overwriting the file the app currently has open.
            let temp = FileManager.default.temporaryDirectory
                .appendingPathComponent("slyos-restore.sqlite")
            try data.write(to: temp)
            defer { try? FileManager.default.removeItem(at: temp) }

            merged = SlyStore.shared.merge(from: temp)
        } catch {
            lastError = error.localizedDescription
        }
        working = false
        return merged
    }

    // MARK: - Drive plumbing

    private func findExisting(token: String) async throws -> String? {
        var c = URLComponents(string: Self.filesEndpoint)!
        c.queryItems = [
            .init(name: "q", value: "name = '\(Self.fileName)' and trashed = false"),
            .init(name: "spaces", value: "drive"),
            .init(name: "fields", value: "files(id,name,size)")
        ]
        var req = URLRequest(url: c.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw BackupError.api(status, String(data: data, encoding: .utf8) ?? "")
        }
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        let files = json?["files"] as? [[String: Any]] ?? []
        return files.first?["id"] as? String
    }

    /// Multipart upload: metadata part, then the bytes. `fileID` nil creates, non-nil updates.
    private func upload(_ data: Data, token: String, fileID: String?) async throws {
        let boundary = "slyos-\(UUID().uuidString)"
        let url = fileID == nil
            ? "\(Self.uploadEndpoint)?uploadType=multipart"
            : "\(Self.uploadEndpoint)/\(fileID!)?uploadType=multipart"

        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = fileID == nil ? "POST" : "PATCH"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 120

        // The name may only be sent on create; Drive rejects it as a no-op field on some updates.
        let metadata: [String: Any] = fileID == nil
            ? ["name": Self.fileName, "description": "SlyOS brain backup — created by SlyOS for iPhone"]
            : ["description": "SlyOS brain backup — updated \(Date.now.formatted())"]

        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/json; charset=UTF-8\r\n\r\n".data(using: .utf8)!)
        body.append(try JSONSerialization.data(withJSONObject: metadata))
        body.append("\r\n--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        req.httpBody = body

        let (out, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw BackupError.api(status, String(data: out, encoding: .utf8) ?? "")
        }
    }

    private func download(fileID: String, token: String) async throws -> Data {
        var req = URLRequest(url: URL(string: "\(Self.filesEndpoint)/\(fileID)?alt=media")!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 120

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw BackupError.api(status, String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }
}
