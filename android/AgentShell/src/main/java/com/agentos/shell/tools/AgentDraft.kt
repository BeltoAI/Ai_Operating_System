package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * The single clean, ready-to-paste output an agent produced this shift — a Reddit comment, a post, a
 * message. Kept apart from the agent's meta chatter so "Copy & post" copies EXACTLY what should go in the
 * box, nothing else (no "Subreddit:", no "Exact comment:", no markdown fences). One draft per employee.
 */
object AgentDraft {
    private const val PREFS = "slyos_agent_draft"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Draft(val kind: String, val target: String, val text: String, val ts: Long)

    fun set(ctx: Context, empId: String, kind: String, target: String, text: String) {
        if (text.isBlank()) return
        val o = JSONObject().put("kind", kind).put("target", target).put("text", text).put("ts", System.currentTimeMillis())
        p(ctx).edit().putString(empId, o.toString()).apply()
    }

    fun get(ctx: Context, empId: String): Draft? = try {
        val s = p(ctx).getString(empId, null) ?: return null
        val o = JSONObject(s)
        Draft(o.optString("kind"), o.optString("target"), o.optString("text"), o.optLong("ts"))
    } catch (e: Exception) { null }

    fun clear(ctx: Context, empId: String) = p(ctx).edit().remove(empId).apply()
}
