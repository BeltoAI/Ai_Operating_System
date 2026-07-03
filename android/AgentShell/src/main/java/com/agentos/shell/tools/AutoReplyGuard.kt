package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safety rail against runaway auto-replies. A fast back-and-forth (or an app that keeps re-posting)
 * could make SlyOS fire many autonomous sends and burn tokens. This caps auto-SENDS per contact and
 * globally within a rolling hour; over the cap we stage a draft instead of sending.
 *
 * P2.5: the windows are now PERSISTED to disk. SlyOS is the launcher, so the process restarts often —
 * an in-memory counter reset on every restart, letting a reply storm resume. On-disk state survives.
 */
object AutoReplyGuard {
    private const val WINDOW_MS = 60 * 60 * 1000L   // 1 hour
    private const val PER_CONTACT = 6
    private const val GLOBAL = 30
    private const val PREF = "slyos_arguard"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun parse(s: String?): ArrayList<Long> {
        val out = ArrayList<Long>()
        try { val a = JSONArray(s ?: "[]"); for (i in 0 until a.length()) out.add(a.getLong(i)) } catch (e: Exception) {}
        return out
    }
    private fun toJson(list: List<Long>) = JSONArray().also { list.forEach { v -> it.put(v) } }
    private fun prune(q: ArrayList<Long>, now: Long) { q.removeAll { now - it > WINDOW_MS } }

    /** True if an autonomous send to [contact] is allowed right now (and records it, on disk, if so). */
    @Synchronized
    fun allow(ctx: Context, contact: String): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val global = parse(p.getString("global", "[]")); prune(global, now)
        val obj = try { JSONObject(p.getString("perContact", "{}")) } catch (e: Exception) { JSONObject() }
        val key = contact.ifBlank { "?" }
        val cq = parse(obj.optString(key, "[]")); prune(cq, now)

        val allowed = global.size < GLOBAL && cq.size < PER_CONTACT
        if (allowed) { cq.add(now); global.add(now) }
        obj.put(key, toJson(cq))
        p.edit().putString("global", toJson(global).toString()).putString("perContact", obj.toString()).apply()
        return allowed
    }
}
