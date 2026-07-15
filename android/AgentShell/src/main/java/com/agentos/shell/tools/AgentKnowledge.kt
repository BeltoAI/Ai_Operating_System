package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A PRIVATE knowledge base per agent — the PDFs/documents YOU feed a specific agent (e.g. Bastardi the deep
 * expert). This is that agent's primary source of truth: it answers from these first, then the owner's brain,
 * then the live web. On-device, keyword-scored retrieval; scales to a lot of docs without any API cost to store.
 */
object AgentKnowledge {
    private const val TAG = "SlyOS-AgentKB"

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_agent_kb.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kb(id INTEGER PRIMARY KEY AUTOINCREMENT, emp_id TEXT, title TEXT, body TEXT, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_emp ON kb(emp_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }
    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    fun add(ctx: Context, empId: String, title: String, body: String) {
        val t = body.trim(); if (t.length < 20) return
        try {
            db(ctx).insert("kb", null, ContentValues().apply {
                put("emp_id", empId); put("title", title.take(140)); put("body", t.take(60000)); put("ts", System.currentTimeMillis())
            })
        } catch (e: Exception) {}
    }

    fun count(ctx: Context, empId: String): Int = try {
        db(ctx).rawQuery("SELECT COUNT(*) FROM kb WHERE emp_id=?", arrayOf(empId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun titles(ctx: Context, empId: String, limit: Int = 20): List<String> = try {
        val out = ArrayList<String>()
        db(ctx).rawQuery("SELECT title FROM kb WHERE emp_id=? ORDER BY ts DESC LIMIT $limit", arrayOf(empId)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: "document")
        }
        out
    } catch (e: Exception) { emptyList() }

    fun clear(ctx: Context, empId: String) = try { db(ctx).delete("kb", "emp_id=?", arrayOf(empId)); Unit } catch (e: Exception) {}

    /** Best passages from THIS agent's fed documents for [query]. Empty if the agent has no docs. */
    fun retrieve(ctx: Context, empId: String, query: String, maxChars: Int = 2400): String {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.distinct()
        data class Hit(val title: String, val snippet: String, val score: Int)
        val hits = ArrayList<Hit>()
        try {
            db(ctx).rawQuery("SELECT title, body FROM kb WHERE emp_id=? ORDER BY ts DESC", arrayOf(empId)).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: ""; val body = c.getString(1) ?: ""
                    val low = body.lowercase()
                    val score = if (terms.isEmpty()) 1 else terms.sumOf { low.split(it).size - 1 }
                    if (score > 0) {
                        val firstTerm = terms.firstOrNull { low.contains(it) }
                        val at = if (firstTerm != null) low.indexOf(firstTerm).coerceAtLeast(0) else 0
                        val start = (at - 150).coerceAtLeast(0)
                        hits.add(Hit(title, body.substring(start, (start + 700).coerceAtMost(body.length)).trim(), score))
                    }
                }
            }
        } catch (e: Exception) { return "" }
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        hits.sortedByDescending { it.score }.take(4).forEach { h ->
            if (sb.length < maxChars) sb.append("• From “${h.title}”: ").append(h.snippet.take(maxChars - sb.length)).append("\n")
        }
        return sb.toString().trim()
    }
}
