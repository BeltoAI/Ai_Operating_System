package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The real memory database: every message (imported or live) in a local SQLite full-text index.
 * Scales to hundreds of thousands of rows and supports fast keyword retrieval — the backbone of
 * the "massive brain" and the RAG-style Ask. (Semantic/embedding RAG can layer on top later.)
 */
object MessageStore {
    data class Hit(val contact: String, val role: String, val body: String)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_msgs.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE messages USING fts4(contact, platform, sender, role, body, ts)")
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

    data class Row(val contact: String, val platform: String, val sender: String, val role: String, val body: String, val ts: Long)

    /** Bulk insert in one transaction — fast even for tens of thousands of rows. */
    fun insertBatch(ctx: Context, rows: List<Row>) {
        if (rows.isEmpty()) return
        val d = db(ctx)
        d.beginTransaction()
        try {
            val stmt = d.compileStatement("INSERT INTO messages(contact,platform,sender,role,body,ts) VALUES(?,?,?,?,?,?)")
            for (r in rows) {
                if (r.body.isBlank()) continue
                stmt.clearBindings()
                stmt.bindString(1, r.contact); stmt.bindString(2, r.platform); stmt.bindString(3, r.sender)
                stmt.bindString(4, r.role); stmt.bindString(5, r.body); stmt.bindString(6, r.ts.toString())
                stmt.executeInsert()
            }
            d.setTransactionSuccessful()
        } catch (e: Exception) { /* ignore */ } finally { d.endTransaction() }
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
        "a","an","of","to","in","on","at","about","from","conversation","conversations","chat","chats",
        "message","messages","talk","talked","talking","know","knew","tell","find","there","any","some",
        "did","can","could","would","should","i","we","our","us","he","she","they","them","it"
    )

    /**
     * Smart keyword search. Drops filler words so a name like "Papa" isn't drowned out, matches the
     * CONTACT name directly (so asking about a person pulls their thread), then adds body matches.
     */
    fun search(ctx: Context, query: String, limit: Int = 60): List<Hit> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        val d = db(ctx)
        val out = LinkedHashMap<String, Hit>()
        fun add(c: android.database.Cursor) { while (c.moveToNext()) {
            val h = Hit(c.getString(0), c.getString(1), c.getString(2)); out["${h.contact}|${h.body}"] = h } }
        try {
            // 1) Contacts whose NAME matches a term → pull their recent thread.
            val contactMatch = terms.joinToString(" OR ") { "contact:$it*" }
            d.rawQuery("SELECT DISTINCT contact FROM messages WHERE messages MATCH ? LIMIT 10", arrayOf(contactMatch)).use { c ->
                val names = ArrayList<String>(); while (c.moveToNext()) names.add(c.getString(0))
                names.forEach { name ->
                    d.rawQuery("SELECT contact, role, body FROM messages WHERE contact = ? ORDER BY rowid DESC LIMIT 12",
                        arrayOf(name)).use(::add)
                }
            }
        } catch (e: Exception) {}
        try {
            // 2) Body matches, newest first.
            val bodyMatch = terms.joinToString(" OR ") { "$it*" }
            d.rawQuery("SELECT contact, role, body FROM messages WHERE messages MATCH ? ORDER BY rowid DESC LIMIT ?",
                arrayOf(bodyMatch, limit.toString())).use(::add)
        } catch (e: Exception) {}
        return out.values.take(limit)
    }

    /** Recent messages with one contact (exact-ish), newest first — for grounding replies. */
    fun threadFor(ctx: Context, contact: String, limit: Int = 40): List<Hit> = try {
        db(ctx).rawQuery(
            "SELECT contact, role, body FROM messages WHERE contact = ? ORDER BY rowid DESC LIMIT ?",
            arrayOf(contact, limit.toString())
        ).use { c ->
            val out = ArrayList<Hit>()
            while (c.moveToNext()) out.add(Hit(c.getString(0), c.getString(1), c.getString(2)))
            out.reversed()
        }
    } catch (e: Exception) { emptyList() }

    /** Top contacts by message volume — for the graph + "who do I talk to most". */
    fun topContacts(ctx: Context, limit: Int = 45): List<Pair<String, Int>> = try {
        db(ctx).rawQuery(
            "SELECT contact, count(*) c FROM messages GROUP BY contact ORDER BY c DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur ->
            val out = ArrayList<Pair<String, Int>>()
            while (cur.moveToNext()) out.add(cur.getString(0) to cur.getInt(1))
            out
        }
    } catch (e: Exception) { emptyList() }
}
