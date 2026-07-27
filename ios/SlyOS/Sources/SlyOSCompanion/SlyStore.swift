import Foundation
import SQLite3

/// The brain's storage layer — the iOS counterpart of Android's `MessageStore`.
///
/// Real SQLite with a real FTS5 index, not an in-memory array. Everything the app can recall lives
/// here: imported mail, calendar events, contacts, documents, and anything the owner tells it.
///
/// Two decisions carried over from Android, both of which were bugs there first:
///
/// * **Bind integers as integers.** SQLite's type affinity sorts every integer below every string,
///   so a numeric comparison against a value bound as TEXT is silently always false. On Android
///   that returned zero rows against a 24,000-message table and looked like an empty database.
/// * **Rank by who, not by when.** Ordering by recency answers "who is Carlos?" with whatever was
///   said last. Matches on the correspondent's name are worth far more than matches in body text,
///   hence `contactWeight`.
final class SlyStore {

    static let shared = SlyStore()

    // Not private: the backup extension in this file's module reads both.
    fileprivate(set) var db: OpaquePointer?
    fileprivate let queue = DispatchQueue(label: "com.belto.slyos.store")

    /// SQLite needs to know whether it may keep a borrowed pointer. Swift's `String` buffers are
    /// not guaranteed to outlive the call, so every text binding must be TRANSIENT (copy now).
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private init() {
        open()
    }

    // MARK: - Schema

    private func open() {
        // Shared container, so the share extension reads the same brain the app does.
        SharedContainer.migrateIfNeeded()
        let dir = SharedContainer.directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = SharedContainer.databaseURL.path

        guard sqlite3_open(path, &db) == SQLITE_OK else {
            assertionFailure("brain: could not open \(path)")
            return
        }
        // WAL keeps reads from blocking on an import that is still writing.
        exec("PRAGMA journal_mode=WAL;")
        exec("PRAGMA synchronous=NORMAL;")

        exec("""
            CREATE TABLE IF NOT EXISTS memories (
              id      INTEGER PRIMARY KEY AUTOINCREMENT,
              kind    TEXT NOT NULL,
              person  TEXT NOT NULL DEFAULT '',
              title   TEXT NOT NULL DEFAULT '',
              body    TEXT NOT NULL DEFAULT '',
              source  TEXT NOT NULL DEFAULT '',
              ts      INTEGER NOT NULL
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_memories_ts ON memories(ts DESC);")
        exec("CREATE INDEX IF NOT EXISTS idx_memories_person ON memories(person);")

        // External-content FTS5: the index stores no copy of the text, it points back at `memories`.
        exec("""
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
              person, title, body,
              content='memories', content_rowid='id', tokenize='unicode61'
            );
            """)
        // Triggers keep the index honest. Without them a row can be edited or deleted and the
        // index still answers for the old text — the search equivalent of the app lying.
        exec("""
            CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
              INSERT INTO memories_fts(rowid, person, title, body)
              VALUES (new.id, new.person, new.title, new.body);
            END;
            """)
        exec("""
            CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
              INSERT INTO memories_fts(memories_fts, rowid, person, title, body)
              VALUES ('delete', old.id, old.person, old.title, old.body);
            END;
            """)
        exec("""
            CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
              INSERT INTO memories_fts(memories_fts, rowid, person, title, body)
              VALUES ('delete', old.id, old.person, old.title, old.body);
              INSERT INTO memories_fts(rowid, person, title, body)
              VALUES (new.id, new.person, new.title, new.body);
            END;
            """)
    }

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        var err: UnsafeMutablePointer<CChar>?
        let ok = sqlite3_exec(db, sql, nil, nil, &err) == SQLITE_OK
        if let err { sqlite3_free(err) }
        return ok
    }

    // MARK: - Writing

    /// Insert one memory. Returns the new row id, or nil if the write failed.
    @discardableResult
    func insert(kind: String, person: String = "", title: String = "",
                body: String, source: String = "", date: Date = .now) -> Int64? {
        queue.sync {
            let sql = "INSERT INTO memories (kind, person, title, body, source, ts) VALUES (?,?,?,?,?,?);"
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_text(st, 1, kind, -1, Self.transient)
            sqlite3_bind_text(st, 2, person, -1, Self.transient)
            sqlite3_bind_text(st, 3, title, -1, Self.transient)
            sqlite3_bind_text(st, 4, body, -1, Self.transient)
            sqlite3_bind_text(st, 5, source, -1, Self.transient)
            // Bound as an integer on purpose — see the note on type affinity above.
            sqlite3_bind_int64(st, 6, Int64(date.timeIntervalSince1970))
            guard sqlite3_step(st) == SQLITE_DONE else { return nil }
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// Bulk insert in one transaction. An import of thousands of rows is otherwise thousands of
    /// separate fsyncs and takes minutes instead of seconds.
    func insertMany(_ items: [Memory]) {
        queue.sync {
            exec("BEGIN IMMEDIATE;")
            let sql = "INSERT INTO memories (kind, person, title, body, source, ts) VALUES (?,?,?,?,?,?);"
            var st: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK {
                for m in items {
                    sqlite3_bind_text(st, 1, m.kind, -1, Self.transient)
                    sqlite3_bind_text(st, 2, m.person, -1, Self.transient)
                    sqlite3_bind_text(st, 3, m.title, -1, Self.transient)
                    sqlite3_bind_text(st, 4, m.body, -1, Self.transient)
                    sqlite3_bind_text(st, 5, m.source, -1, Self.transient)
                    sqlite3_bind_int64(st, 6, Int64(m.date.timeIntervalSince1970))
                    sqlite3_step(st)
                    sqlite3_reset(st)
                }
                sqlite3_finalize(st)
            }
            exec("COMMIT;")
        }
    }

    // MARK: - Reading

    /// How much a name match outweighs a body match. Android landed on 8 after "who is Carlos?"
    /// kept returning the most recent thing anyone had said rather than anything about Carlos.
    private static let contactWeight = 8.0

    /// Search the brain.
    ///
    /// Ranked by bm25 across the three indexed columns, with the correspondent's name weighted far
    /// above body text, then by recency only as a tie-break.
    func search(_ query: String, limit: Int = 40) -> [Memory] {
        let cleaned = Self.ftsQuery(query)
        guard !cleaned.isEmpty else { return [] }

        return queue.sync {
            // bm25() returns a NEGATIVE score where more negative is a better match, so the column
            // weights are ordered person, title, body and the result is sorted ascending.
            let sql = """
                SELECT m.id, m.kind, m.person, m.title, m.body, m.source, m.ts
                FROM memories_fts f
                JOIN memories m ON m.id = f.rowid
                WHERE memories_fts MATCH ?
                ORDER BY bm25(memories_fts, ?, 2.0, 1.0), m.ts DESC
                LIMIT ?;
                """
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_text(st, 1, cleaned, -1, Self.transient)
            sqlite3_bind_double(st, 2, Self.contactWeight)
            sqlite3_bind_int(st, 3, Int32(limit))
            return rows(from: st)
        }
    }

    /// The most recent memories, for the brain's resting state.
    func recent(limit: Int = 40) -> [Memory] {
        queue.sync {
            var st: OpaquePointer?
            let sql = "SELECT id, kind, person, title, body, source, ts FROM memories ORDER BY ts DESC LIMIT ?;"
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_int(st, 1, Int32(limit))
            return rows(from: st)
        }
    }

    /// Total rows — what the brain screen reports, and the honest answer to "is it empty?".
    func count() -> Int {
        queue.sync {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM memories;", -1, &st, nil) == SQLITE_OK
            else { return 0 }
            defer { sqlite3_finalize(st) }
            return sqlite3_step(st) == SQLITE_ROW ? Int(sqlite3_column_int64(st, 0)) : 0
        }
    }

    /// Rows grouped by where they came from, so the brain can show what it actually holds.
    func countsBySource() -> [(String, Int)] {
        queue.sync {
            var st: OpaquePointer?
            let sql = """
                SELECT CASE WHEN source = '' THEN kind ELSE source END AS s, COUNT(*)
                FROM memories GROUP BY s ORDER BY COUNT(*) DESC;
                """
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(st) }
            var out: [(String, Int)] = []
            while sqlite3_step(st) == SQLITE_ROW {
                out.append((Self.text(st, 0), Int(sqlite3_column_int64(st, 1))))
            }
            return out
        }
    }

    private func rows(from st: OpaquePointer?) -> [Memory] {
        var out: [Memory] = []
        while sqlite3_step(st) == SQLITE_ROW {
            out.append(Memory(
                id: sqlite3_column_int64(st, 0),
                kind: Self.text(st, 1),
                person: Self.text(st, 2),
                title: Self.text(st, 3),
                body: Self.text(st, 4),
                source: Self.text(st, 5),
                date: Date(timeIntervalSince1970: TimeInterval(sqlite3_column_int64(st, 6)))
            ))
        }
        return out
    }

    private static func text(_ st: OpaquePointer?, _ col: Int32) -> String {
        guard let c = sqlite3_column_text(st, col) else { return "" }
        return String(cString: c)
    }

    /// Turn what someone typed into something FTS5 will accept.
    ///
    /// Raw input goes straight into MATCH, where a stray quote or a bare `-` is a syntax error and
    /// the whole search returns nothing. Each word is quoted and given a prefix `*` so partial
    /// names still hit, which is how people actually search for someone.
    static func ftsQuery(_ raw: String) -> String {
        let words = raw
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count > 1 }
        guard !words.isEmpty else { return "" }
        return words.map { "\"\($0)\"*" }.joined(separator: " OR ")
    }
}

/// One thing the brain knows.
struct Memory: Identifiable, Equatable {
    var id: Int64 = 0
    var kind: String            // message | mail | event | contact | doc | fact | note
    var person: String = ""     // who it is with or about
    var title: String = ""
    var body: String
    var source: String = ""     // Gmail | Calendar | Contacts | typed …
    var date: Date = .now
}

// MARK: - Backup support

extension SlyStore {

    /// Fold the write-ahead log back into the main database file.
    ///
    /// In WAL mode recent commits live in a `-wal` sidecar, so copying the `.sqlite` on its own
    /// backs up a database missing everything written since the last automatic checkpoint — which
    /// is precisely the newest memories. Anything that copies the file must call this first.
    func checkpoint() {
        queue.sync {
            sqlite3_exec(db, "PRAGMA wal_checkpoint(TRUNCATE);", nil, nil, nil)
        }
    }

    /// Merge another SlyOS database into this one, returning how many rows were added.
    ///
    /// Rows already present are skipped on `(kind, body, ts)` rather than replaced, so restoring a
    /// backup onto a phone that has kept using itself adds what was missing instead of flattening
    /// it back to the snapshot.
    @discardableResult
    func merge(from url: URL) -> Int {
        queue.sync {
            guard sqlite3_exec(db, "ATTACH DATABASE '\(url.path)' AS backup;", nil, nil, nil) == SQLITE_OK
            else { return 0 }
            defer { sqlite3_exec(db, "DETACH DATABASE backup;", nil, nil, nil) }

            let before = countUnsafe()
            sqlite3_exec(db, """
                INSERT INTO memories (kind, person, title, body, source, ts)
                SELECT b.kind, b.person, b.title, b.body, b.source, b.ts
                FROM backup.memories b
                WHERE NOT EXISTS (
                  SELECT 1 FROM main.memories m
                  WHERE m.kind = b.kind AND m.body = b.body AND m.ts = b.ts
                );
                """, nil, nil, nil)
            return countUnsafe() - before
        }
    }

    /// Row count without re-entering the serial queue — callers here already hold it.
    private func countUnsafe() -> Int {
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM memories;", -1, &st, nil) == SQLITE_OK
        else { return 0 }
        defer { sqlite3_finalize(st) }
        return sqlite3_step(st) == SQLITE_ROW ? Int(sqlite3_column_int64(st, 0)) : 0
    }
}

extension SlyStore {
    /// Remove one memory. Used where the owner explicitly discards something — a paper they don't
    /// want kept — never as a side effect of anything else.
    func delete(id: Int64) {
        queue.sync {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "DELETE FROM memories WHERE id = ?;", -1, &st, nil) == SQLITE_OK
            else { return }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_int64(st, 1, id)
            sqlite3_step(st)
        }
    }
}
