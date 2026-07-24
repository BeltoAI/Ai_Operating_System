package com.agentos.shell.tools

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * OUTBOUND APPROVAL GATE for the team. A teammate can DRAFT an email or a calendar change, but it can never
 * send mail or touch the owner's calendar on its own — those land here as a pending approval the owner sees in
 * the Now feed. Swipe LEFT to decline, swipe RIGHT to open the full details and approve. On approve the same
 * gated [ToolRouter.executeActions] path runs the action for real.
 *
 * Everything reversible/internal (notes, leads, docs, drafts) still just happens — this gate is only for the
 * two things that leave the phone in the owner's name: sending email, and writing/moving calendar events.
 */
object ApprovalStore {
    /**
     * @param agent   which teammate asked (e.g. "Riri")
     * @param kind    "email" or "event" (drives the icon/verb in the card)
     * @param title   one-line headline for the compact card ("Email Jane at Acme")
     * @param detail  the full thing the agent wrote — the email subject+body, or the event time/attendees —
     *                shown when the owner swipes right to review before approving
     * @param actions what actually runs on approve (a real ToolRouter action)
     */
    data class Approval(val id: Long, val agent: String, val kind: String, val title: String,
                        val detail: String, val actions: List<AgentAction>)

    val items = mutableStateListOf<Approval>()
    private const val PREF = "slyos_approvals"
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
                items.add(Approval(o.optLong("id"), o.optString("agent"), o.optString("kind"), o.optString("title"), o.optString("detail"), acts))
            }
        } catch (e: Exception) {}
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach { p ->
            val a = JSONArray()
            p.actions.forEach { a.put(JSONObject().put("type", it.type).put("arg", it.arg)) }
            arr.put(JSONObject().put("id", p.id).put("agent", p.agent).put("kind", p.kind)
                .put("title", p.title).put("detail", p.detail).put("actions", a))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Queue an outbound action for the owner's approval. Deduped (an agent re-running its shift shouldn't stack
     * the same email five times). Returns the new id, or 0 if it was a duplicate. Safe from a background thread.
     */
    fun request(ctx: Context, agent: String, kind: String, title: String, detail: String, actions: List<AgentAction>): Long {
        ensureLoaded(ctx)
        if (actions.isEmpty()) return 0L
        if (items.any { it.agent.equals(agent, true) && it.title.equals(title, true) && it.detail == detail }) return 0L
        val id = System.currentTimeMillis()
        items.add(0, Approval(id, agent, kind, title, detail, actions))
        while (items.size > 30) items.removeAt(items.size - 1)
        persist(ctx)
        return id
    }

    fun remove(ctx: Context, id: Long) {
        items.removeAll { it.id == id }
        persist(ctx)
    }

    /** How many approvals are waiting (for a badge). Loads lazily. */
    fun pending(ctx: Context): Int { ensureLoaded(ctx); return items.size }
}
