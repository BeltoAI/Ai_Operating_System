package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the classical chatbot. Supports MULTIPLE saved conversations (like Cowork and Research),
 * each with its own title and message list, stored locally as JSON so they survive restarts. Every turn
 * also feeds the brain (MessageStore + MemoryLog) at the call site, so anything discussed is recallable.
 */
object ChatStore {
    private const val PREF = "slyos"
    private const val KEY = "chatbot_threads"
    private const val MAXMSG = 400   // per-thread cap; older turns still live in the brain

    data class Msg(val role: String, val text: String, val ts: Long)
    data class Thread(val id: Long, val title: String, val updated: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }

    private fun writeAll(ctx: Context, arr: JSONArray) = prefs(ctx).edit().putString(KEY, arr.toString()).apply()

    private fun indexOf(arr: JSONArray, id: Long): Int {
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optLong("id") == id) return i
        return -1
    }

    /** Newest-first list of conversations for the picker. */
    fun threads(ctx: Context): List<Thread> {
        val arr = readAll(ctx)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Thread(o.optLong("id"), o.optString("title").ifBlank { "New chat" }, o.optLong("updated"))
        }.sortedByDescending { it.updated }
    }

    fun messages(ctx: Context, id: Long): List<Msg> {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx < 0) return emptyList()
        val ms = arr.optJSONObject(idx)?.optJSONArray("msgs") ?: return emptyList()
        return (0 until ms.length()).mapNotNull { i ->
            val o = ms.optJSONObject(i) ?: return@mapNotNull null
            Msg(o.optString("role"), o.optString("text"), o.optLong("ts"))
        }
    }

    fun create(ctx: Context): Long {
        val arr = readAll(ctx)
        val id = System.currentTimeMillis()
        arr.put(JSONObject().put("id", id).put("title", "").put("updated", id).put("msgs", JSONArray()))
        writeAll(ctx, arr)
        return id
    }

    /** Append a message; auto-titles the thread from its first user line. Returns the updated message list. */
    fun append(ctx: Context, id: Long, role: String, text: String): List<Msg> {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx < 0) return emptyList()
        val o = arr.optJSONObject(idx) ?: return emptyList()
        val ms = o.optJSONArray("msgs") ?: JSONArray()
        ms.put(JSONObject().put("role", role).put("text", text).put("ts", System.currentTimeMillis()))
        while (ms.length() > MAXMSG) ms.remove(0)
        o.put("msgs", ms).put("updated", System.currentTimeMillis())
        if (o.optString("title").isBlank() && role == "you")
            o.put("title", text.trim().take(42).ifBlank { "New chat" })
        writeAll(ctx, arr)
        return messages(ctx, id)
    }

    fun rename(ctx: Context, id: Long, title: String) {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx < 0) return
        arr.optJSONObject(idx)?.put("title", title.trim().ifBlank { "New chat" })
        writeAll(ctx, arr)
    }

    fun delete(ctx: Context, id: Long) {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx < 0) return
        val out = JSONArray()
        for (i in 0 until arr.length()) if (i != idx) out.put(arr.get(i))
        writeAll(ctx, out)
    }

    /** Insert or update a whole thread from a synced source (last-write-wins on [updated]). Used by
     *  BrainSync to pull chats down onto another device. */
    fun importThread(ctx: Context, id: Long, title: String, updated: Long, msgs: List<Msg>) {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx >= 0) {
            val existing = arr.optJSONObject(idx)
            if ((existing?.optLong("updated") ?: 0L) >= updated) return   // local is newer or equal — keep it
        }
        val ms = JSONArray()
        msgs.forEach { m -> ms.put(JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts)) }
        val obj = JSONObject().put("id", id).put("title", title.ifBlank { "New chat" }).put("updated", updated).put("msgs", ms)
        if (idx >= 0) arr.put(idx, obj) else arr.put(obj)
        writeAll(ctx, arr)
    }

    /** Delete a single message from a thread by its timestamp. Returns the updated message list. */
    fun deleteMessage(ctx: Context, id: Long, ts: Long): List<Msg> {
        val arr = readAll(ctx); val idx = indexOf(arr, id)
        if (idx < 0) return emptyList()
        val o = arr.optJSONObject(idx) ?: return emptyList()
        val ms = o.optJSONArray("msgs") ?: return emptyList()
        val keep = JSONArray()
        for (i in 0 until ms.length()) { val m = ms.optJSONObject(i) ?: continue; if (m.optLong("ts") != ts) keep.put(m) }
        o.put("msgs", keep)
        writeAll(ctx, arr)
        return messages(ctx, id)
    }
}
