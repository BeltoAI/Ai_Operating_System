package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the job hunt: your base résumé, the target role, the last posting, and a history of every
 *  application you've prepared/sent (so the brain can answer "what jobs did I apply to?"). */
object JobStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_job", Context.MODE_PRIVATE)

    fun resume(ctx: Context): String = prefs(ctx).getString("resume", "") ?: ""
    fun setResume(ctx: Context, v: String) = prefs(ctx).edit().putString("resume", v.trim()).apply()

    fun target(ctx: Context): String = prefs(ctx).getString("target", "") ?: ""
    fun setTarget(ctx: Context, v: String) = prefs(ctx).edit().putString("target", v.trim()).apply()

    fun posting(ctx: Context): String = prefs(ctx).getString("posting", "") ?: ""
    fun setPosting(ctx: Context, v: String) = prefs(ctx).edit().putString("posting", v.trim()).apply()

    // ── Applications history ──────────────────────────────────────────────────────────────────────
    data class Application(val ts: Long, val label: String, val link: String, val status: String)

    fun applications(ctx: Context): List<Application> = try {
        val arr = JSONArray(prefs(ctx).getString("apps", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Application(o.optLong("ts"), o.optString("label"), o.optString("link"), o.optString("status", "prepared")) }
    } catch (e: Exception) { emptyList() }

    /** Record (or update) an application. If the same label/link exists, updates its status. */
    fun addApplication(ctx: Context, label: String, link: String, status: String) {
        val cur = applications(ctx).toMutableList()
        val i = cur.indexOfFirst { (it.label == label && label.isNotBlank()) || (it.link == link && link.isNotBlank()) }
        if (i >= 0) cur[i] = cur[i].copy(status = status, ts = System.currentTimeMillis())
        else cur.add(Application(System.currentTimeMillis(), label, link, status))
        val capped = cur.takeLast(150)
        val arr = JSONArray()
        capped.forEach { arr.put(JSONObject().put("ts", it.ts).put("label", it.label).put("link", it.link).put("status", it.status)) }
        prefs(ctx).edit().putString("apps", arr.toString()).apply()
    }

    /** A short summary of recent applications for the agent's context (feeds OUT of the brain). */
    fun summary(ctx: Context): String {
        val a = applications(ctx); if (a.isEmpty()) return ""
        val recent = a.takeLast(15).reversed().joinToString("; ") { it.label.ifBlank { it.link }.take(60) + " (" + it.status + ")" }
        return "Jobs I've applied to / prepared (${a.size} total): $recent."
    }
}
