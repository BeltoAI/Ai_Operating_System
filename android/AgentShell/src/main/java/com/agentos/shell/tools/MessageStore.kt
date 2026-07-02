package com.agentos.shell.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The real memory database: every message (imported or live) in a local SQLite table with indexes.
 * Reliable aggregation (top contacts) + keyword search at scale — the backbone of the brain.
 */
object MessageStore {
    data class Hit(val contact: String, val role: String, val body: String)
    data class Row(val contact: String, val platform: String, val sender: String, val role: String, val body: String, val ts: Long)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_msgs.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS messages(contact TEXT, platform TEXT, sender TEXT, role TEXT, body TEXT, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_contact ON messages(contact)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON messages(ts)")
            ensureFts(db)
        }
        // FAULT FIX: the brain is the user's irreplaceable memory — NEVER drop it on a schema bump.
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = onCreate(db)
        override fun onOpen(db: SQLiteDatabase) {
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON messages(ts)") } catch (e: Exception) {}
            ensureFts(db)
        }
        // Full-text search index so keyword search stays instant at MILLIONS of messages (an OS that
        // lives with you for years can't do a full table scan on every search). External-content FTS4
        // mirrors the messages table via triggers; rebuilt once for existing data.
        private fun ensureFts(db: SQLiteDatabase) {
            try {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(content=\"messages\", contact, body)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS msg_ai AFTER INSERT ON messages BEGIN " +
                    "INSERT INTO messages_fts(docid, contact, body) VALUES(new.rowid, new.contact, new.body); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS msg_ad AFTER DELETE ON messages BEGIN " +
                    "INSERT INTO messages_fts(messages_fts, docid, contact, body) VALUES('delete', old.rowid, old.contact, old.body); END")
                val ftsN = db.rawQuery("SELECT count(*) FROM messages_fts", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                val msgN = db.rawQuery("SELECT count(*) FROM messages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (ftsN == 0 && msgN > 0) db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
            } catch (e: Exception) {}
        }
    }

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    fun insertBatch(ctx: Context, rows: List<Row>) {
        if (rows.isEmpty()) return
        val d = db(ctx); d.beginTransaction()
        try {
            val stmt = d.compileStatement("INSERT INTO messages(contact,platform,sender,role,body,ts) VALUES(?,?,?,?,?,?)")
            for (r in rows) {
                if (r.body.isBlank()) continue
                stmt.clearBindings()
                stmt.bindString(1, r.contact); stmt.bindString(2, r.platform); stmt.bindString(3, r.sender)
                stmt.bindString(4, r.role); stmt.bindString(5, r.body); stmt.bindLong(6, r.ts)
                stmt.executeInsert()
            }
            d.setTransactionSuccessful()
        } catch (e: Exception) {} finally { d.endTransaction() }
        // Mirror into the semantic index (instant queue; embedded later in the background). Best-effort.
        try { for (r in rows) if (r.body.isNotBlank()) VectorStore.enqueue(ctx, r.contact, r.role, r.body) } catch (e: Exception) {}
    }

    fun insertOne(ctx: Context, contact: String, platform: String, sender: String, role: String, body: String) =
        insertBatch(ctx, listOf(Row(contact, platform, sender, role, body, System.currentTimeMillis())))

    /**
     * Import-safe insert: skips rows that already exist (same contact + body), so re-importing the
     * same export doesn't double-count the brain. Returns how many NEW rows were actually written.
     */
    fun insertBatchDedupe(ctx: Context, rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        val d = db(ctx)
        // Snapshot existing (contact|body) keys once — one scan beats thousands of per-row queries.
        val existing = HashSet<String>()
        try {
            d.rawQuery("SELECT contact, body FROM messages", null).use { c ->
                while (c.moveToNext()) existing.add(c.getString(0) + "" + c.getString(1))
            }
        } catch (e: Exception) {}
        val fresh = ArrayList<Row>(rows.size)
        val seenThisBatch = HashSet<String>()
        for (r in rows) {
            if (r.body.isBlank()) continue
            val key = r.contact + "" + r.body
            if (key in existing || !seenThisBatch.add(key)) continue
            fresh.add(r)
        }
        if (fresh.isEmpty()) return 0
        insertBatch(ctx, fresh)
        return fresh.size
    }

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM messages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun clear(ctx: Context) {
        try {
            val d = db(ctx)
            d.execSQL("DROP TRIGGER IF EXISTS msg_ai"); d.execSQL("DROP TRIGGER IF EXISTS msg_ad")
            d.execSQL("DELETE FROM messages")
            try { d.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')") } catch (e: Exception) {}
            d.execSQL("CREATE TRIGGER IF NOT EXISTS msg_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(docid, contact, body) VALUES(new.rowid, new.contact, new.body); END")
            d.execSQL("CREATE TRIGGER IF NOT EXISTS msg_ad AFTER DELETE ON messages BEGIN INSERT INTO messages_fts(messages_fts, docid, contact, body) VALUES('delete', old.rowid, old.contact, old.body); END")
        } catch (e: Exception) {}
    }

    /** Stream every row through [action] WITHOUT loading them all into memory — for exporting a huge
     *  brain (hundreds of thousands of messages) without OOM. Returns how many rows were seen. */
    fun forEachRow(ctx: Context, action: (contact: String, role: String, body: String) -> Unit): Int {
        var n = 0
        try {
            db(ctx).rawQuery("SELECT contact, role, body FROM messages ORDER BY ts DESC", null).use { c ->
                while (c.moveToNext()) { action(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: ""); n++ }
            }
        } catch (e: Exception) {}
        return n
    }

    private val STOP = setOf(
        "the","and","you","your","with","have","has","had","for","that","this","what","who","whom",
        "when","where","why","how","does","did","do","done","my","me","mine","is","are","was","were",
        "about","from","conversation","conversations","chat","chats","message","messages","talk","talked",
        "talking","know","knew","tell","find","there","any","some","can","could","would","should","our","us"
    )

    /** Keyword search. Uses the FTS index (instant even at millions of rows); falls back to LIKE only
     *  if FTS is unavailable or empty — so it never regresses. */
    fun search(ctx: Context, query: String, limit: Int = 60): List<Hit> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        val d = db(ctx)
        // FTS path — index lookup, not a table scan.
        try {
            val match = terms.joinToString(" OR ") { it.replace("\"", "") + "*" }
            val out = LinkedHashMap<String, Hit>()
            d.rawQuery("SELECT m.contact, m.role, m.body FROM messages_fts f, messages m " +
                "WHERE f.docid = m.rowid AND messages_fts MATCH ? LIMIT ?", arrayOf(match, (limit * 2).toString())).use { c ->
                while (c.moveToNext()) { val h = Hit(c.getString(0), c.getString(1), c.getString(2)); out["${h.contact}|${h.body}"] = h }
            }
            if (out.isNotEmpty()) return out.values.take(limit)
        } catch (e: Exception) {}
        return searchLike(ctx, query, limit)
    }

    /** Fallback keyword search (full scan via LIKE) — used only when FTS isn't available. */
    private fun searchLike(ctx: Context, query: String, limit: Int): List<Hit> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        val d = db(ctx)
        val out = LinkedHashMap<String, Hit>()
        fun run(sql: String, args: Array<String>) = try {
            d.rawQuery(sql, args).use { c -> while (c.moveToNext()) {
                val h = Hit(c.getString(0), c.getString(1), c.getString(2)); out["${h.contact}|${h.body}"] = h } }
        } catch (e: Exception) {}
        try {
            val w = terms.joinToString(" OR ") { "lower(contact) LIKE ?" }
            d.rawQuery("SELECT DISTINCT contact FROM messages WHERE $w LIMIT 10",
                terms.map { "%$it%" }.toTypedArray()).use { c ->
                val names = ArrayList<String>(); while (c.moveToNext()) names.add(c.getString(0))
                names.forEach { run("SELECT contact,role,body FROM messages WHERE contact=? ORDER BY ts DESC LIMIT 12", arrayOf(it)) }
            }
        } catch (e: Exception) {}
        val w = terms.joinToString(" OR ") { "lower(body) LIKE ?" }
        run("SELECT contact,role,body FROM messages WHERE $w ORDER BY ts DESC LIMIT $limit",
            terms.map { "%$it%" }.toTypedArray())
        return out.values.take(limit)
    }

    /** High-VALUE rows for the semantic index: YOUR own messages first (that's what makes the brain
     *  YOU), then other substantial messages — skipping tiny/noise lines. Bounds the embed backlog so
     *  it's actually completable on a free tier instead of chasing hundreds of thousands of lines. */
    fun valueRows(ctx: Context, cap: Int = 15000): List<Hit> = try {
        db(ctx).rawQuery(
            "SELECT contact, role, body FROM messages WHERE role='me' OR length(body) > 40 " +
            "ORDER BY (role='me') DESC, ts DESC LIMIT ?", arrayOf(cap.toString())).use { c ->
            val out = ArrayList<Hit>(); while (c.moveToNext()) out.add(Hit(c.getString(0), c.getString(1), c.getString(2))); out
        }
    } catch (e: Exception) { emptyList() }

    /** Every stored message (newest first), for one-time seeding of the semantic index. */
    fun allRows(ctx: Context, cap: Int = 20000): List<Hit> = try {
        db(ctx).rawQuery("SELECT contact,role,body FROM messages ORDER BY ts DESC LIMIT ?", arrayOf(cap.toString())).use { c ->
            val out = ArrayList<Hit>(); while (c.moveToNext()) out.add(Hit(c.getString(0), c.getString(1), c.getString(2))); out
        }
    } catch (e: Exception) { emptyList() }

    fun threadFor(ctx: Context, contact: String, limit: Int = 40): List<Hit> = try {
        db(ctx).rawQuery("SELECT contact,role,body FROM messages WHERE contact=? ORDER BY ts DESC LIMIT ?",
            arrayOf(contact, limit.toString())).use { c ->
            val out = ArrayList<Hit>(); while (c.moveToNext()) out.add(Hit(c.getString(0), c.getString(1), c.getString(2)))
            out.reversed()
        }
    } catch (e: Exception) { emptyList() }

    /** Top contacts with (name, messageCount, platform) — platform drives the node color. */
    fun topContacts(ctx: Context, limit: Int = 45): List<Triple<String, Int, String>> = try {
        db(ctx).rawQuery("SELECT contact, count(*) c, platform FROM messages GROUP BY contact ORDER BY c DESC LIMIT ?",
            arrayOf(limit.toString())).use { cur ->
            val out = ArrayList<Triple<String, Int, String>>()
            while (cur.moveToNext()) out.add(Triple(cur.getString(0), cur.getInt(1), cur.getString(2) ?: ""))
            out
        }
    } catch (e: Exception) { emptyList() }
}
