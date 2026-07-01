package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists Cowork conversations (like ChatGPT threads): an index of chats plus each chat's visible
 * log and model transcript. Also mirrors messages into the brain so past Cowork work feeds context.
 */
object CoworkChatStore {
    data class Convo(val id: Long, val title: String, val updated: Long)

    private const val PREF = "slyos_cowork"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Convo> = try {
        val arr = JSONArray(prefs(ctx).getString("index", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Convo(o.getLong("id"), o.optString("title"), o.optLong("updated")) }
            .sortedByDescending { it.updated }
    } catch (e: Exception) { emptyList() }

    private fun writeIndex(ctx: Context, convos: List<Convo>) {
        val arr = JSONArray()
        convos.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)) }
        prefs(ctx).edit().putString("index", arr.toString()).apply()
    }

    fun create(ctx: Context, title: String = "New chat"): Long {
        val id = System.currentTimeMillis()
        writeIndex(ctx, listOf(Convo(id, title, id)) + list(ctx))
        return id
    }

    /** Update title + bump to top. */
    fun touch(ctx: Context, id: Long, title: String) {
        val cur = list(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == id }
        val c = Convo(id, title.take(48).ifBlank { "Chat" }, System.currentTimeMillis())
        if (i >= 0) cur[i] = c else cur.add(c)
        writeIndex(ctx, cur)
    }

    fun delete(ctx: Context, id: Long) {
        writeIndex(ctx, list(ctx).filterNot { it.id == id })
        prefs(ctx).edit().remove("chat_$id").remove("turns_$id").apply()
    }

    // role/text pairs (display) and role/content pairs (transcript) stored as JSON arrays.
    fun saveChat(ctx: Context, id: Long, chat: List<Pair<String, String>>) =
        prefs(ctx).edit().putString("chat_$id", pairsToJson(chat)).apply()
    fun saveTurns(ctx: Context, id: Long, turns: List<Pair<String, String>>) =
        prefs(ctx).edit().putString("turns_$id", pairsToJson(turns)).apply()
    fun loadChat(ctx: Context, id: Long): List<Pair<String, String>> = jsonToPairs(prefs(ctx).getString("chat_$id", "[]"))
    fun loadTurns(ctx: Context, id: Long): List<Pair<String, String>> = jsonToPairs(prefs(ctx).getString("turns_$id", "[]"))

    private fun pairsToJson(list: List<Pair<String, String>>): String {
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("a", it.first).put("b", it.second)) }; return arr.toString()
    }
    private fun jsonToPairs(s: String?): List<Pair<String, String>> = try {
        val arr = JSONArray(s ?: "[]"); (0 until arr.length()).map { val o = arr.getJSONObject(it); o.optString("a") to o.optString("b") }
    } catch (e: Exception) { emptyList() }
}
