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

    /** Replace an app's html (and optionally name), keeping its id + stored data. */
    fun update(ctx: Context, id: Long, html: String, name: String? = null) =
        save(ctx, load(ctx).map { if (it.id == id) it.copy(html = html, name = name ?: it.name) else it })

    // Per-app key-value storage so generated apps can persist real data across sessions.
    private fun dataPrefs(ctx: Context) = ctx.getSharedPreferences("slyos_appdata", Context.MODE_PRIVATE)
    fun saveData(ctx: Context, appId: Long, key: String, value: String) =
        dataPrefs(ctx).edit().putString("$appId|$key", value).apply()
    fun loadData(ctx: Context, appId: Long, key: String): String =
        dataPrefs(ctx).getString("$appId|$key", "") ?: ""
}
