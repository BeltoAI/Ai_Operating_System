package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The brain's MISSION: a standing goal the user sets ("You are now me. Find me 10 new customers for
 * X"). SlyOS keeps it front-of-mind and periodically assesses how close it is — with an honest
 * argument and concrete next steps. Stored locally.
 */
object MissionStore {
    data class Check(val ts: Long, val percent: Int, val note: String, val next: String)

    private const val PREF = "slyos_mission"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun mission(ctx: Context): String = prefs(ctx).getString("text", "") ?: ""
    fun setMission(ctx: Context, text: String) {
        val cur = mission(ctx)
        val e = prefs(ctx).edit().putString("text", text.trim())
        // Changing the mission resets the progress history so the tracker measures the NEW goal.
        if (text.trim() != cur) e.putString("checks", "[]").putLong("since", System.currentTimeMillis())
        e.apply()
    }
    fun since(ctx: Context): Long = prefs(ctx).getLong("since", 0L)

    fun checks(ctx: Context): List<Check> = try {
        val arr = JSONArray(prefs(ctx).getString("checks", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Check(o.getLong("ts"), o.getInt("pct"), o.optString("note"), o.optString("next"))
        }
    } catch (e: Exception) { emptyList() }

    fun latest(ctx: Context): Check? = checks(ctx).maxByOrNull { it.ts }

    fun addCheck(ctx: Context, percent: Int, note: String, next: String) {
        val list = checks(ctx).toMutableList()
        list.add(Check(System.currentTimeMillis(), percent.coerceIn(0, 100), note, next))
        val capped = list.takeLast(40)
        val arr = JSONArray()
        capped.forEach { arr.put(JSONObject().put("ts", it.ts).put("pct", it.percent).put("note", it.note).put("next", it.next)) }
        prefs(ctx).edit().putString("checks", arr.toString()).apply()
    }

    // ── Milestone plan: concrete sub-goals the AI breaks the mission into, checkable by the user ──
    data class Milestone(val id: Long, val text: String, val done: Boolean)

    fun milestones(ctx: Context): List<Milestone> = try {
        val arr = JSONArray(prefs(ctx).getString("plan", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Milestone(o.getLong("id"), o.getString("text"), o.getBoolean("done")) }
    } catch (e: Exception) { emptyList() }

    private fun saveMilestones(ctx: Context, list: List<Milestone>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        prefs(ctx).edit().putString("plan", arr.toString()).apply()
    }

    /** Replace the plan with a fresh set of milestones (from the AI planner). */
    fun setPlan(ctx: Context, steps: List<String>) {
        var id = System.currentTimeMillis()
        saveMilestones(ctx, steps.filter { it.isNotBlank() }.map { Milestone(id++, it.trim().take(140), false) })
    }

    fun toggleMilestone(ctx: Context, id: Long) =
        saveMilestones(ctx, milestones(ctx).map { if (it.id == id) it.copy(done = !it.done) else it })

    /** Fraction of milestones completed (0..1), or null if there's no plan yet. */
    fun planProgress(ctx: Context): Float? {
        val m = milestones(ctx); if (m.isEmpty()) return null
        return m.count { it.done }.toFloat() / m.size
    }
}
