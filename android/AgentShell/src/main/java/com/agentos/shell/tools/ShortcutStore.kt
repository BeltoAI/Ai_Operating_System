package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Home-screen shortcuts the user pins: real apps or generated mini-apps, freely positioned. */
object ShortcutStore {
    // x,y are dp offsets within the home canvas.
    data class Shortcut(val id: Long, val kind: String, val label: String, val ref: String, val x: Float = 0f, val y: Float = 0f)

    private const val PREF = "slyos_shortcuts"
    private const val KEY = "items"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Shortcut> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Shortcut(o.getLong("id"), o.getString("kind"), o.getString("label"), o.getString("ref"),
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat())
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Shortcut>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("kind", it.kind).put("label", it.label).put("ref", it.ref).put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, kind: String, label: String, ref: String) {
        val cur = list(ctx)
        if (cur.any { it.kind == kind && it.ref == ref }) return
        val n = cur.size
        val x = (n % 4) * 78f          // tile into a 4-wide grid by default
        val y = (n / 4) * 84f
        save(ctx, cur + Shortcut(System.currentTimeMillis(), kind, label, ref, x, y))
    }

    fun remove(ctx: Context, id: Long) = save(ctx, list(ctx).filterNot { it.id == id })

    /** Persist current positions/order. */
    fun savePositions(ctx: Context, items: List<Shortcut>) = save(ctx, items)
}
