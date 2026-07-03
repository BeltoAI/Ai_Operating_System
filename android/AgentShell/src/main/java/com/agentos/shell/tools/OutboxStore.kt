package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5.1 / P5.5 — the "Sent for you" outbox + agent activity log. Every autonomous or agent-executed action
 * (a reply, a message, an email, an event, a reminder) is recorded here with WHAT it did, to WHOM, WHY
 * (the trigger), and the memory/persona it drew on. Aggressive auto-send is only safe if it's visible and
 * reversible — this is that record. On-device, capped, newest-first.
 */
object OutboxStore {
    private const val PREF = "slyos_outbox"
    private const val KEY = "items"
    private const val CAP = 200

    data class Entry(
        val id: Long, val time: Long, val channel: String, val contact: String,
        val kind: String, val body: String, val reason: String, val status: String  // sent | held | undone
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun load(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(ctx: Context, arr: JSONArray) = prefs(ctx).edit().putString(KEY, arr.toString()).apply()

    /** Record an action. [reason] = why it happened (trigger + a short note on the memory/persona used). */
    fun record(ctx: Context, channel: String, contact: String, kind: String, body: String, reason: String, status: String = "sent"): Long {
        val id = System.currentTimeMillis()
        val o = JSONObject().put("id", id).put("time", id).put("channel", channel).put("contact", contact)
            .put("kind", kind).put("body", body.take(600)).put("reason", reason.take(400)).put("status", status)
        val arr = load(ctx)
        val out = JSONArray().put(o)                              // newest first
        for (i in 0 until minOf(arr.length(), CAP - 1)) out.put(arr.get(i))
        save(ctx, out)
        return id
    }

    fun recent(ctx: Context, n: Int = 50): List<Entry> {
        val arr = load(ctx); val out = ArrayList<Entry>()
        for (i in 0 until minOf(arr.length(), n)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optLong("id"), o.optLong("time"), o.optString("channel"), o.optString("contact"),
                o.optString("kind"), o.optString("body"), o.optString("reason"), o.optString("status", "sent")))
        }
        return out
    }

    fun setStatus(ctx: Context, id: Long, status: String) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") == id) { o.put("status", status); break }
        }
        save(ctx, arr)
    }

    fun unreadCount(ctx: Context): Int = recent(ctx, CAP).count { it.status == "sent" }
}
