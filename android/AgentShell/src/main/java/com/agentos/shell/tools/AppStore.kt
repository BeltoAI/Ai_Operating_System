package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Mini-apps the Architect (Opus) generates, stored on the phone and run in a WebView. */
object AppStore {
    data class MiniApp(val id: Long, val name: String, val html: String)

    private const val PREF = "slyos_apps"
    private const val KEY = "apps"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<MiniApp> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MiniApp(o.getLong("id"), o.getString("name"), o.getString("html"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, apps: List<MiniApp>) {
        val arr = JSONArray()
        apps.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("html", it.html)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, name: String, html: String): Long {
        val id = System.currentTimeMillis()
        save(ctx, listOf(MiniApp(id, name, html)) + load(ctx))
        return id
    }

    fun get(ctx: Context, id: Long): MiniApp? = load(ctx).firstOrNull { it.id == id }
    fun remove(ctx: Context, id: Long) = save(ctx, load(ctx).filterNot { it.id == id })
}
