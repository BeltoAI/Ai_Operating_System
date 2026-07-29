package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation with one agent that survives being answered.
 *
 * Asking a teammate something worked exactly once. The reply landed in a `teamReply` string, the
 * sheet closed, and the next question started from nothing — so getting what you wanted meant
 * re-explaining the whole brief every time. That is the complaint in its original words: *"if I
 * wanted to adjust, I would have to re-explain how I wanted the script to sound to get a new
 * draft."*
 *
 * There was a half-measure already: [EmployeeStore.logFor] was fed in as context, so the agent knew
 * what **it** had said. Your own side of the exchange was never written down at all, which is why a
 * follow-up could still miss the thing it was following up on.
 *
 * So both sides are stored, per agent, and the draft the agent is working on is the thread's
 * subject — "make it warmer" edits that draft instead of producing a new one. The routing rule is
 * borrowed wholesale from [WorkingDraft.isEdit], because it is the same judgement made in the same
 * way, and two different answers to "is this an edit?" would be worse than either.
 */
object AgentThread {

    private const val PREFS = "slyos_agent_thread"
    /** Enough to hold a working session; old turns are dropped rather than growing without bound. */
    private const val KEEP = 40

    data class Msg(val role: String, val text: String, val ts: Long) {
        val fromOwner: Boolean get() = role == "you"
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun messages(ctx: Context, empId: String): List<Msg> = try {
        val arr = JSONArray(p(ctx).getString(empId, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Msg(it.optString("r"), it.optString("t"), it.optLong("ts")) }
        }
    } catch (e: Exception) { emptyList() }

    fun add(ctx: Context, empId: String, role: String, text: String) {
        if (text.isBlank()) return
        val kept = (messages(ctx, empId) + Msg(role, text.trim(), System.currentTimeMillis())).takeLast(KEEP)
        val arr = JSONArray()
        kept.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text).put("ts", it.ts)) }
        p(ctx).edit().putString(empId, arr.toString()).apply()
    }

    fun clear(ctx: Context, empId: String) = p(ctx).edit().remove(empId).apply()

    /** The one-line summary shown on the agent's card, so a live thread is visible from outside it. */
    fun lastLine(ctx: Context, empId: String): String =
        messages(ctx, empId).lastOrNull()?.let {
            (if (it.fromOwner) "you: " else "") + it.text.replace("\n", " ").take(60)
        }.orEmpty()

    /**
     * The exchange so far, as the agent should see it.
     *
     * Both sides, oldest first. Bounded by turns rather than characters because dropping the middle
     * of a conversation is how an agent ends up answering the first question again.
     */
    fun transcript(ctx: Context, empId: String, ownerName: String, agentName: String, turns: Int = 10): String =
        messages(ctx, empId).takeLast(turns).joinToString("\n") {
            (if (it.fromOwner) ownerName else agentName) + ": " + it.text
        }

    /**
     * Whether this message edits the agent's current draft rather than asking something new.
     *
     * Same conservative test as the Home pin: treating a real question as an edit — the owner asks
     * something unrelated and gets their draft rewritten — is the failure that would make this feel
     * broken, so anything that reads as its own request wins.
     */
    fun isEdit(ctx: Context, empId: String, prompt: String): Boolean =
        AgentDraft.get(ctx, empId) != null && WorkingDraft.isEdit(prompt)

    /** The instruction for revising the agent's draft, given what the owner just said. */
    fun revisionPrompt(draft: AgentDraft.Draft, instruction: String): String =
        "Here is the current ${draft.kind.lowercase().ifBlank { "draft" }}" +
            (if (draft.target.isBlank()) "" else " for ${draft.target}") + ":\n\n${draft.text}\n\n" +
        "Revise it exactly per this instruction: \"$instruction\".\n" +
        "Keep everything that was not asked to change — this is an edit, not a rewrite from the " +
        "brief. Return ONLY the revised text, with no preamble and no explanation of what you changed."

    /** The quick edits offered as pills, matching the ones on Home so the gesture is learned once. */
    val QUICK = WorkingDraft.QUICK
}
