package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXTRA CONNECTIONS the user sets up themselves — a CRM (HubSpot), a helpdesk, a webhook, anything with a key
 * or URL. Built-in things (Gmail, Calendar, Contacts) are handled elsewhere; this is the open slot so an
 * employee that needs, say, HubSpot has a real place to be connected. Employees are told what's here so they
 * use it instead of asking for it.
 */
object IntegrationStore {
    private const val PREFS = "slyos_integrations"
    private const val KEY = "items"

    data class Integration(val id: String, val name: String, val baseUrl: String, val secret: String, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Integration> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Integration(o.optString("id"), o.optString("name"), o.optString("baseUrl"), o.optString("secret"), o.optString("notes"))
        }
    } catch (e: Exception) { emptyList() }

    fun add(ctx: Context, name: String, baseUrl: String, secret: String, notes: String) {
        if (name.isBlank()) return
        val cur = list(ctx).filterNot { it.name.equals(name, true) }
        val arr = JSONArray()
        (cur + Integration(java.util.UUID.randomUUID().toString(), name.trim(), baseUrl.trim(), secret.trim(), notes.trim())).forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("secret", it.secret).put("notes", it.notes))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        val arr = JSONArray()
        list(ctx).filterNot { it.id == id }.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("secret", it.secret).put("notes", it.notes))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun names(ctx: Context): List<String> = list(ctx).map { it.name }
}
