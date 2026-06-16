package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-person conversation memory: every incoming message and every reply we send, kept per
 * sender and persisted. This is what lets the agent hold a real, contextual conversation with
 * each individual contact.
 */
object ConversationStore {
    data class Msg(val role: String, val text: String, val time: Long)   // role: "them" | "me"

    private const val PREF = "slyos_convos"
    private const val KEY = "threads"
    private const val CAP = 30
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun root(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY, "{}")) } catch (e: Exception) { JSONObject() }
    fun sKey(app: String, title: String) = "$app|$title"

    fun thread(ctx: Context, app: String, title: String): List<Msg> {
        val arr = root(ctx).optJSONArray(sKey(app, title)) ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Msg(o.getString("r"), o.getString("t"), o.optLong("ts"))
        }
    }

    fun add(ctx: Context, app: String, title: String, role: String, text: String) {
        if (text.isBlank()) return
        val r = root(ctx); val k = sKey(app, title)
        val arr = r.optJSONArray(k) ?: JSONArray()
        if (arr.length() > 0) {
            val last = arr.getJSONObject(arr.length() - 1)
            if (last.getString("r") == role && last.getString("t") == text) return  // dedup repeats
        }
        arr.put(JSONObject().put("r", role).put("t", text).put("ts", System.currentTimeMillis()))
        val trimmed = if (arr.length() > CAP) JSONArray().also {
            for (i in arr.length() - CAP until arr.length()) it.put(arr.get(i))
        } else arr
        r.put(k, trimmed)
        prefs(ctx).edit().putString(KEY, r.toString()).apply()
    }

    fun all(ctx: Context): Map<String, List<Msg>> {
        val r = root(ctx); val out = LinkedHashMap<String, List<Msg>>()
        r.keys().forEach { k ->
            val arr = r.optJSONArray(k) ?: return@forEach
            out[k] = (0 until arr.length()).map {
                val o = arr.getJSONObject(it); Msg(o.getString("r"), o.getString("t"), o.optLong("ts"))
            }
        }
        return out
    }
}
