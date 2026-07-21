package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Full-text of documents that flowed through SlyOS — email bodies, forms, scanned/OCR'd text — so agents
 * and HomeAI can actually READ inside them, not just see a title + summary. Keyword-scored retrieval keeps
 * the relevant passage in the brain for the current question. (Native PDF binaries still need a parser to
 * extract body text; whatever text the pipeline already has is captured here.)
 */
object DocText {
    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "slyos_doctext.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS doctext(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, " +
                "source TEXT, body TEXT, ts INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }
    private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(ctx.applicationContext)
        return helper!!.writableDatabase
    }

    /** Store a document's full text (deduped by title — newer text replaces older). Trims runaway size. */
    fun add(ctx: Context, title: String, source: String, body: String) {
        val t = body.trim()
        if (t.length < 40) return
        try {
            db(ctx).delete("doctext", "title=?", arrayOf(title))
            db(ctx).insert("doctext", null, ContentValues().apply {
                put("title", title.take(120)); put("source", source); put("body", t.take(20000)); put("ts", System.currentTimeMillis())
            })
            // Cap the table so it never bloats storage.
            db(ctx).execSQL("DELETE FROM doctext WHERE id NOT IN (SELECT id FROM doctext ORDER BY ts DESC LIMIT 120)")
        } catch (e: Exception) {}
    }

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT COUNT(*) FROM doctext", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Pull the most relevant passages for [query] across all stored documents, up to [maxChars]. */
    /** Title+body of the most recent documents — so their CONTENT can be embedded and found
     *  semantically, not only by exact keyword. */
    fun recent(ctx: Context, limit: Int = 200): List<Pair<String, String>> = try {
        db(ctx).rawQuery("SELECT title, body FROM doctext ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            val out = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) out.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            out
        }
    } catch (e: Exception) { emptyList() }

    fun retrieve(ctx: Context, query: String, maxChars: Int = 2600): String {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.distinct()
        if (terms.isEmpty()) return ""
        val stored = count(ctx)
        data class Hit(val title: String, val snippet: String, val score: Int)
        val hits = ArrayList<Hit>()
        try {
            db(ctx).rawQuery("SELECT title, body FROM doctext ORDER BY ts DESC", null).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val low = body.lowercase()
                    val score = terms.sumOf { term -> low.split(term).size - 1 }
                    if (score > 0) {
                        val firstTerm = terms.firstOrNull { low.contains(it) }
                        val at = if (firstTerm != null) low.indexOf(firstTerm).coerceAtLeast(0) else 0
                        val start = (at - 160).coerceAtLeast(0)
                        val snippet = body.substring(start, (start + 1100).coerceAtMost(body.length)).trim()
                        hits.add(Hit(title, snippet, score))
                    }
                }
            }
        } catch (e: Exception) {
            Fail.log("Documents", "search documents for \"${query.take(50)}\"", "document index unreadable: ${e.message}")
            return ""
        }
        // Asking about a document and silently getting nothing is a failure the user FEELS as "the AI
        // doesn't know" — distinguish "we have no documents at all" from "we have some but none matched".
        if (hits.isEmpty()) {
            Fail.log("Documents", "search documents for \"${query.take(50)}\"",
                if (stored == 0) "no documents are indexed yet — nothing could match"
                else "none of the $stored indexed documents matched", "warn")
            return ""
        }
        val sb = StringBuilder()
        hits.sortedByDescending { it.score }.take(3).forEach { h ->
            if (sb.length < maxChars) sb.append("• “${h.title}”: ").append(h.snippet.take(maxChars - sb.length)).append("\n")
        }
        return sb.toString().trim()
    }
}
