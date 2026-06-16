package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Home-screen shortcuts the user pins: real apps or generated mini-apps. */
object ShortcutStore {
    data class Shortcut(val id: Long, val kind: String, val label: String, val ref: String) // kind: app | miniapp

    private const val PREF = "slyos_shortcuts"
    private const val KEY = "items"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Shortcut> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Shortcut(o.getLong("id"), o.getString("kind"), o.getString("label"), o.getString("ref"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Shortcut>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("kind", it.kind).put("label", it.label).put("ref", it.ref)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, kind: String, label: String, ref: String) {
        if (list(ctx).any { it.kind == kind && it.ref == ref }) return
        save(ctx, list(ctx) + Shortcut(System.currentTimeMillis(), kind, label, ref))
    }

    fun remove(ctx: Context, id: Long) = save(ctx, list(ctx).filterNot { it.id == id })

    /** Persist a reordered list as-is. */
    fun saveOrder(ctx: Context, items: List<Shortcut>) = save(ctx, items)
}
