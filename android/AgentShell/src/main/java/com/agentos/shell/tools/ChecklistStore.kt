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

    // Significant words of a task, for fuzzy duplicate detection (reworded-but-same tasks).
    private fun sig(t: String): Set<String> =
        t.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 2 }.toSet()
    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size.toFloat(); return inter / a.union(b).size >= 0.6f
    }

    fun add(ctx: Context, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        val items = load(ctx)
        val s = sig(t)
        // Skip exact AND near-duplicates (e.g. the mission worker rewording "run the 5 LinkedIn searches").
        if (items.any { it.text.trim().equals(t, ignoreCase = true) || (!it.done && similar(sig(it.text), s)) }) return
        save(ctx, (items + Item(System.currentTimeMillis(), t, false)).takeLast(300))   // cap so it can't grow forever
    }

    /** MANUAL add (from the user typing) — only blocks EXACT duplicates, so the user is never stopped from
     *  adding a task they want by the fuzzy de-dupe that's meant only for the auto-agents. */
    fun addManual(ctx: Context, text: String) {
        val t = text.trim(); if (t.isBlank()) return
        val items = load(ctx)
        if (items.any { it.text.trim().equals(t, ignoreCase = true) }) return
        save(ctx, (items + Item(System.currentTimeMillis(), t, false)).takeLast(300))
    }

    /** Edit an item's text in place. */
    fun edit(ctx: Context, id: Long, text: String) {
        val t = text.trim(); if (t.isBlank()) return
        save(ctx, load(ctx).map { if (it.id == id) it.copy(text = t) else it })
    }

    /** Collapse existing near-duplicate items (keep the newest of each cluster). Safe to call anytime. */
    fun prune(ctx: Context) {
        val items = load(ctx).sortedByDescending { it.id }
        val kept = ArrayList<Item>()
        for (it in items) {
            val s = sig(it.text)
            if (kept.any { k -> k.done == it.done && (k.text.equals(it.text, true) || similar(sig(k.text), s)) }) continue
            kept.add(it)
        }
        if (kept.size != items.size) save(ctx, kept.sortedBy { it.id })
    }

    fun toggle(ctx: Context, id: Long) =
        save(ctx, load(ctx).map { if (it.id == id) it.copy(done = !it.done) else it })

    fun remove(ctx: Context, id: Long) =
        save(ctx, load(ctx).filterNot { it.id == id })

    fun clearDone(ctx: Context) =
        save(ctx, load(ctx).filterNot { it.done })

    /** Remove EVERY item. Returns how many were cleared (for honest feedback + brain logging). */
    fun clearAll(ctx: Context): Int {
        val n = load(ctx).size
        save(ctx, emptyList())
        return n
    }
}
