package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * A spam-safe outreach drip. An agent enqueues one email per recipient (deck attached, personalized body); the
 * background worker sends AT MOST ONE every [spacingMin] minutes (default 60), so a 40-VC raise goes out as a
 * natural trickle instead of a blast that trips spam filters. A single global gate ([KEY_NEXT]) enforces the
 * cadence across the whole queue, and each recipient is only ever queued once (deduped by email).
 */
object OutreachQueue {
    private const val TAG = "SlyOS-Outreach"
    private const val PREFS = "slyos_outreach"
    private const val KEY_NEXT = "next_allowed_ts"

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "outreach.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE outreach(id INTEGER PRIMARY KEY AUTOINCREMENT, toName TEXT, toEmail TEXT, " +
                "subject TEXT, body TEXT, attach TEXT, spacingMin INTEGER, status TEXT, createdTs INTEGER, campaign TEXT, sentTs INTEGER DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) try { db.execSQL("ALTER TABLE outreach ADD COLUMN sentTs INTEGER DEFAULT 0") } catch (e: Exception) {}
        }
    }

    /** Morning report of an overnight run: what got sent, and a per-hour histogram for the graph. */
    data class Report(val sent: Int, val pending: Int, val failed: Int,
                      val hourly: IntArray, val recent: List<Pair<String, Long>>)

    fun sentToday(ctx: Context): Int = try {
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        db(ctx).rawQuery("SELECT COUNT(*) FROM outreach WHERE status='sent' AND sentTs>=?", arrayOf(c.timeInMillis.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun report(ctx: Context, sinceTs: Long): Report {
        val hourly = IntArray(24); var sent = 0; var failed = 0
        val recent = ArrayList<Pair<String, Long>>()
        try {
            db(ctx).rawQuery("SELECT toName,toEmail,status,sentTs FROM outreach WHERE createdTs>=? OR sentTs>=?",
                arrayOf(sinceTs.toString(), sinceTs.toString())).use { c ->
                while (c.moveToNext()) {
                    val st = c.getString(2) ?: ""; val ts = c.getLong(3)
                    when (st) {
                        "sent" -> { sent++; if (ts > 0) { hourly[java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.HOUR_OF_DAY)]++; recent.add((c.getString(0).ifBlankOr(c.getString(1))) to ts) } }
                        "failed" -> failed++
                    }
                }
            }
        } catch (e: Exception) {}
        val pending = pendingCount(ctx)
        return Report(sent, pending, failed, hourly, recent.sortedByDescending { it.second }.take(12))
    }
    private fun String?.ifBlankOr(alt: String?): String = this?.takeIf { it.isNotBlank() } ?: (alt ?: "someone")
    private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(ctx.applicationContext)
        return helper!!.writableDatabase
    }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Recipient(val name: String, val email: String)

    /** Queue one email per recipient (deduped against anything already pending). Returns how many were added. */
    fun enqueue(ctx: Context, recipients: List<Recipient>, subject: String, body: String,
                attach: String = "", spacingMin: Int = 60, campaign: String = ""): Int {
        var added = 0
        try {
            val d = db(ctx)
            val already = HashSet<String>()
            d.rawQuery("SELECT toEmail FROM outreach WHERE status='pending'", null).use { c ->
                while (c.moveToNext()) already.add((c.getString(0) ?: "").lowercase())
            }
            recipients.filter { it.email.contains("@") }.distinctBy { it.email.lowercase() }.forEach { r ->
                if (already.add(r.email.lowercase())) {
                    d.insert("outreach", null, ContentValues().apply {
                        put("toName", r.name); put("toEmail", r.email); put("subject", subject); put("body", body)
                        put("attach", attach); put("spacingMin", spacingMin.coerceIn(1, 1440))
                        put("status", "pending"); put("createdTs", System.currentTimeMillis()); put("campaign", campaign)
                    })
                    added++
                }
            }
        } catch (e: Exception) { Log.w(TAG, "enqueue: ${e.message}") }
        return added
    }

    fun pendingCount(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT COUNT(*) FROM outreach WHERE status='pending'", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /**
     * Send at most ONE due email, respecting the global cadence gate. Returns a short human line if something was
     * sent (for logging / a chat ping), or null if nothing was due / the gate hasn't opened yet. Call this every
     * worker cycle — the gate, not the call frequency, controls the real rate.
     */
    fun drainOne(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now < prefs(ctx).getLong(KEY_NEXT, 0L)) return null   // still inside the cooldown window
        var id = -1L; var toName = ""; var toEmail = ""; var subject = ""; var body = ""; var attach = ""; var spacing = 60
        try {
            db(ctx).rawQuery("SELECT id,toName,toEmail,subject,body,attach,spacingMin FROM outreach WHERE status='pending' ORDER BY id ASC LIMIT 1", null).use { c ->
                if (!c.moveToFirst()) return null
                id = c.getLong(0); toName = c.getString(1) ?: ""; toEmail = c.getString(2) ?: ""
                subject = c.getString(3) ?: ""; body = c.getString(4) ?: ""; attach = c.getString(5) ?: ""; spacing = c.getInt(6)
            }
        } catch (e: Exception) { return null }
        if (toEmail.isBlank()) { mark(ctx, id, "skipped"); return null }
        // Personalize: [Name]/[name]/[FirstName] → the recipient's first name (or a warm fallback).
        val first = toName.trim().split(" ").firstOrNull().orEmpty().ifBlank { "there" }
        val pBody = body.replace(Regex("(?i)\\[(first ?name|name)\\]"), first)
        val pSubj = subject.replace(Regex("(?i)\\[(first ?name|name)\\]"), first)
        val file = attach.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() }
        val (ok, msg) = try {
            if (file != null) GmailClient.sendWithAttachments(ctx, toEmail, pSubj.ifBlank { "Hello" }, pBody, listOf(file))
            else GmailClient.send(ctx, toEmail, pSubj.ifBlank { "Hello" }, pBody)
        } catch (e: Exception) { false to (e.message ?: "error") }
        if (ok) {
            try { db(ctx).execSQL("UPDATE outreach SET status='sent', sentTs=? WHERE id=?", arrayOf(now.toString(), id.toString())) }
            catch (e: Exception) { mark(ctx, id, "sent") }
            // Open the gate again only after the spacing window → strict ≤1 per window, whole-queue-wide.
            prefs(ctx).edit().putLong(KEY_NEXT, now + spacing * 60_000L).apply()
            val left = pendingCount(ctx)
            return "Sent outreach to ${toName.ifBlank { toEmail }}" + (if (left > 0) " ($left more queued, ~1 every ${spacing}m)" else " — that was the last one")
        } else {
            mark(ctx, id, "failed")
            Log.w(TAG, "send to $toEmail failed: $msg")
            return null
        }
    }

    /** Send one NOW, bypassing the pacing gate (for the very first send when a run starts, so it's observable).
     *  Subsequent sends still respect the spacing window that drainOne re-arms. */
    fun drainNow(ctx: Context): String? {
        prefs(ctx).edit().putLong(KEY_NEXT, 0L).apply()
        return drainOne(ctx)
    }

    private fun mark(ctx: Context, id: Long, status: String) {
        try { db(ctx).execSQL("UPDATE outreach SET status=? WHERE id=?", arrayOf(status, id.toString())) } catch (e: Exception) {}
    }

    /** Cancel everything still pending (e.g. owner says 'stop the outreach'). Returns how many were cancelled. */
    fun cancelPending(ctx: Context): Int = try {
        val n = pendingCount(ctx)
        db(ctx).execSQL("UPDATE outreach SET status='cancelled' WHERE status='pending'")
        n
    } catch (e: Exception) { 0 }
}
