package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A simple persistent checklist. The agent can also add items via the checklist_add action. */
object ChecklistStore {
    data class Item(val id: Long, val text: String, val done: Boolean)

    private const val PREF = "slyos_checklist"
    private const val KEY = "items"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Item> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.getLong("id"), o.getString("text"), o.getBoolean("done"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        // Skip near-duplicates (case-insensitive) so repeated mission "add to checklist" taps and the
        // daily worker don't pile up the same task.
        if (load(ctx).any { it.text.trim().equals(t, ignoreCase = true) }) return
        save(ctx, load(ctx) + Item(System.currentTimeMillis(), t, false))
    }

    fun toggle(ctx: Context, id: Long) =
        save(ctx, load(ctx).map { if (it.id == id) it.copy(done = !it.done) else it })

    fun remove(ctx: Context, id: Long) =
        save(ctx, load(ctx).filterNot { it.id == id })

    fun clearDone(ctx: Context) =
        save(ctx, load(ctx).filterNot { it.done })
}
