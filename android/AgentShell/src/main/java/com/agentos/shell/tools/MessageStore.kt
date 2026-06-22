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

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_msgs.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages(contact TEXT, platform TEXT, sender TEXT, role TEXT, body TEXT, ts INTEGER)")
            db.execSQL("CREATE INDEX idx_contact ON messages(contact)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS messages"); onCreate(db)
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
    }

    fun insertOne(ctx: Context, contact: String, platform: String, sender: String, role: String, body: String) =
        insertBatch(ctx, listOf(Row(contact, platform, sender, role, body, System.currentTimeMillis())))

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM messages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun clear(ctx: Context) { try { db(ctx).execSQL("DELETE FROM messages") } catch (e: Exception) {} }

    private val STOP = setOf(
        "the","and","you","your","with","have","has","had","for","that","this","what","who","whom",
        "when","where","why","how","does","did","do","done","my","me","mine","is","are","was","were",
        "about","from","conversation","conversations","chat","chats","message","messages","talk","talked",
        "talking","know","knew","tell","find","there","any","some","can","could","would","should","our","us"
    )

    /** Keyword search: match contact name first (so asking about a person pulls their thread), then body. */
    fun search(ctx: Context, query: String, limit: Int = 60): List<Hit> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        val d = db(ctx)
        val out = LinkedHashMap<String, Hit>()
        fun run(sql: String, args: Array<String>) = try {
            d.rawQuery(sql, args).use { c -> while (c.moveToNext()) {
                val h = Hit(c.getString(0), c.getString(1), c.getString(2)); out["${h.contact}|${h.body}"] = h } }
        } catch (e: Exception) {}
        // 1) contacts whose name matches a term → recent thread for each
        try {
            val w = terms.joinToString(" OR ") { "lower(contact) LIKE ?" }
            d.rawQuery("SELECT DISTINCT contact FROM messages WHERE $w LIMIT 10",
                terms.map { "%$it%" }.toTypedArray()).use { c ->
                val names = ArrayList<String>(); while (c.moveToNext()) names.add(c.getString(0))
                names.forEach { run("SELECT contact,role,body FROM messages WHERE contact=? ORDER BY ts DESC LIMIT 12", arrayOf(it)) }
            }
        } catch (e: Exception) {}
        // 2) body matches
        val w = terms.joinToString(" OR ") { "lower(body) LIKE ?" }
        run("SELECT contact,role,body FROM messages WHERE $w ORDER BY ts DESC LIMIT $limit",
            terms.map { "%$it%" }.toTypedArray())
        return out.values.take(limit)
    }

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
