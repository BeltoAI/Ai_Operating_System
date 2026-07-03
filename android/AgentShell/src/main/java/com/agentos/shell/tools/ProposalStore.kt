package com.agentos.shell.tools

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5.3 — proactive proposals. When a rule spots something worth acting on (a booking/flight confirmation,
 * a bill, someone you've left waiting), it drops a one-tap proposal here. The Now feed renders them at the
 * top: Confirm runs the pre-filled actions (through the same gated ToolRouter), Dismiss drops it. This is
 * how SlyOS goes from reactive to proactive without doing anything behind your back.
 */
object ProposalStore {
    data class Proposal(val id: Long, val title: String, val subtitle: String, val actions: List<AgentAction>)

    val items = mutableStateListOf<Proposal>()
    private const val PREF = "slyos_proposals"
    private const val KEY = "items"
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val acts = ArrayList<AgentAction>()
                o.optJSONArray("actions")?.let { a -> for (j in 0 until a.length()) { val ao = a.getJSONObject(j); acts.add(AgentAction(ao.optString("type"), ao.optString("arg"))) } }
                items.add(Proposal(o.optLong("id"), o.optString("title"), o.optString("subtitle"), acts))
            }
        } catch (e: Exception) {}
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach { p ->
            val a = JSONArray()
            p.actions.forEach { a.put(JSONObject().put("type", it.type).put("arg", it.arg)) }
            arr.put(JSONObject().put("id", p.id).put("title", p.title).put("subtitle", p.subtitle).put("actions", a))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Add a proposal (deduped by title). Safe to call from a background thread. */
    fun add(ctx: Context, title: String, subtitle: String, actions: List<AgentAction>) {
        ensureLoaded(ctx)
        if (items.any { it.title.equals(title, true) }) return
        items.add(0, Proposal(System.currentTimeMillis(), title, subtitle, actions))
        while (items.size > 20) items.removeAt(items.size - 1)
        persist(ctx)
    }

    fun remove(ctx: Context, id: Long) {
        items.removeAll { it.id == id }
        persist(ctx)
    }
}
