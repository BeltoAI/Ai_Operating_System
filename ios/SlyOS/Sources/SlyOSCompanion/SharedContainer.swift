import Foundation

/// Where the brain lives on disk.
///
/// The share extension runs in its *own process* with its own sandbox, so it cannot see the app's
/// Documents directory at all. Both sides have to read and write the same SQLite file, and the only
/// way to do that on iOS is an App Group container.
///
/// Falling back to the app's own directory matters: if the entitlement is missing — an unsigned
/// build, a misconfigured team — a hard failure here would mean no brain rather than a brain the
/// extension happens not to share. Better to degrade to app-only than to lose everything.
enum SharedContainer {

    static let appGroup = "group.com.belto.slyos"

    /// The directory the brain database lives in.
    static var directory: URL {
        if let shared = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup) {
            return shared
        }
        return FileManager.default.urls(for: .applicationSupportDirectory,
                                        in: .userDomainMask)[0]
    }

    /// Whether the group container is actually available, so diagnostics can say so plainly rather
    /// than leaving someone to wonder why the extension sees an empty brain.
    static var isShared: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    static var databaseURL: URL {
        directory.appendingPathComponent("slyos-brain.sqlite")
    }

    /// Settings both processes read. `UserDefaults.standard` is per-process for an extension, so
    /// anything the extension needs — the owner's name, their character block — must live here.
    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    /// Move an existing brain into the shared container the first time the group becomes available.
    ///
    /// Without this, turning on the App Group would silently strand every memory the user had
    /// already imported: the app would start reading a new, empty database beside the full one.
    static func migrateIfNeeded() {
        guard isShared else { return }
        let fm = FileManager.default
        let target = databaseURL
        guard !fm.fileExists(atPath: target.path) else { return }

        let legacy = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("slyos-brain.sqlite")
        guard fm.fileExists(atPath: legacy.path) else { return }

        // Bring the write-ahead log and shared-memory files too — moving the .sqlite alone can
        // strand committed transactions that are still only in the WAL.
        for suffix in ["", "-wal", "-shm"] {
            let from = URL(fileURLWithPath: legacy.path + suffix)
            let to = URL(fileURLWithPath: target.path + suffix)
            if fm.fileExists(atPath: from.path) {
                try? fm.moveItem(at: from, to: to)
            }
        }
    }
}
