package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * HOMEAI MEMORY — every prompt and answer on the Home screen, timestamped and persisted, so the
 * conversation survives closing the app AND feeds back into the model as context next time. This is the
 * durable record behind "does HomeAI actually remember?". It lives in shared_prefs, so it's in every
 * brain backup too.
 */
object HomeChatStore {
    private const val PREFS = "slyos_homechat"
    private const val KEY = "turns"
    private const val MAX = 200

    data class Turn(val q: String, val a: String, val ts: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(ctx: Context, q: String, a: String) {
        if (q.isBlank() && a.isBlank()) return
        val arr = try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("q", q).put("a", a).put("ts", System.currentTimeMillis()))
        // Trim to the newest MAX.
        val trimmed = if (arr.length() <= MAX) arr else JSONArray().apply {
            for (i in (arr.length() - MAX) until arr.length()) put(arr.getJSONObject(i))
        }
        prefs(ctx).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun all(ctx: Context): List<Turn> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Turn(o.optString("q"), o.optString("a"), o.optLong("ts"))
        }
    } catch (e: Exception) { emptyList() }

    /** The recent turns as (prompt, reply) pairs for rehydrating the on-screen + model history. */
    fun recentPairs(ctx: Context, n: Int = 12): List<Pair<String, String>> =
        all(ctx).takeLast(n).map { it.q to it.a }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
