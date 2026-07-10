package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the classical chatbot thread (Research → Chat). A single ongoing conversation, stored
 * locally as JSON so it survives navigation and app restarts. "New chat" clears it. The chat ALSO feeds
 * the brain (via MessageStore + MemoryLog) at the call site, so anything discussed is recallable later.
 */
object ChatStore {
    private const val PREF = "slyos"
    private const val KEY = "chatbot_thread"
    private const val MAX = 400   // keep the last N turns; older ones live in the brain, not this view

    data class Msg(val role: String, val text: String, val ts: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Msg> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Msg(o.optString("role"), o.optString("text"), o.optLong("ts"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Msg>) {
        val arr = JSONArray()
        list.takeLast(MAX).forEach { m -> arr.put(JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Append a message and return the full updated thread. */
    fun append(ctx: Context, role: String, text: String): List<Msg> {
        val list = load(ctx).toMutableList()
        list.add(Msg(role, text, System.currentTimeMillis()))
        save(ctx, list)
        return list
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}
