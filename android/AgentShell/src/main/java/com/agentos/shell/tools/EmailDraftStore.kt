package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Editable outreach email drafts (one per recipient), each sent by the user after review. */
object EmailDraftStore {
    data class Draft(val id: Long, val to: String, val subject: String, val body: String)

    private const val PREF = "slyos_outreach"
    private const val KEY = "drafts"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Draft> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Draft(o.getLong("id"), o.getString("to"), o.getString("subject"), o.getString("body"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Draft>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("to", it.to).put("subject", it.subject).put("body", it.body)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, to: String, subject: String, body: String): Long {
        val id = System.currentTimeMillis() + (0..999).random()
        save(ctx, load(ctx) + Draft(id, to, subject, body)); return id
    }
    fun update(ctx: Context, id: Long, subject: String, body: String) =
        save(ctx, load(ctx).map { if (it.id == id) it.copy(subject = subject, body = body) else it })
    fun remove(ctx: Context, id: Long) = save(ctx, load(ctx).filterNot { it.id == id })
}
