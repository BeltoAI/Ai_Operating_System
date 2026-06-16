package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Captures prompts, responses and other moments as memories for the graph. */
object MemoryLog {
    data class Entry(val id: Long, val type: String, val label: String, val content: String, val source: String, val parent: String?)

    private const val PREF = "slyos_memlog"
    private const val KEY = "log"
    private const val CAP = 80
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Entry> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getLong("id"), o.getString("type"), o.getString("label"),
                o.getString("content"), o.getString("source"),
                if (o.isNull("parent")) null else o.getString("parent"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.takeLast(CAP).forEach {
            arr.put(JSONObject().put("id", it.id).put("type", it.type).put("label", it.label)
                .put("content", it.content).put("source", it.source).put("parent", it.parent))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Add an entry; returns its stable key (for linking a child to it). */
    fun add(ctx: Context, type: String, label: String, content: String, source: String, parent: String? = null): String {
        val id = System.currentTimeMillis() + (0..999).random()
        save(ctx, load(ctx) + Entry(id, type, label.take(40), content, source, parent))
        return "log:$id"
    }
}
