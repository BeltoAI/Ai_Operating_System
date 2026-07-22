package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE CONVERGENCE FLYWHEEL.
 *
 * Every time the user edits an AI-drafted reply before sending, the difference between the draft and what
 * they actually sent is a labeled, in-their-own-voice correction — "you wrote X, I'd have said Y". It's the
 * single highest-signal piece of data in the product, and it used to be thrown away (only the final text was
 * kept). Here it's captured as a preference pair.
 *
 * Two payoffs:
 *  - NOW: [recentCorrections] feeds the voice layer live exemplars of how the user fixes the AI, so drafts
 *    stop repeating the same off-notes and the voice converges on the real person instead of plateauing.
 *  - LATER: [exportJsonl] emits (rejected=draft, chosen=sent) preference pairs — exactly the DPO/RLHF
 *    alignment dataset for a personal model, generated for free by normal use.
 *
 * SharedPreferences-backed (so it's in every brain backup); capped and newest-last.
 */
object EditPairStore {
    private const val PREFS = "slyos_editpairs"
    private const val KEY = "pairs"
    private const val CAP = 500
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Pair(val channel: String, val recipient: String, val draft: String, val sent: String, val ts: Long)

    fun add(ctx: Context, channel: String, recipient: String, draft: String, sent: String) {
        val arr = load(ctx)
        arr.put(JSONObject()
            .put("channel", channel).put("recipient", recipient)
            .put("draft", draft.take(2000)).put("sent", sent.take(2000))
            .put("ts", System.currentTimeMillis()))
        // Trim to the cap, newest-last.
        while (arr.length() > CAP) arr.remove(0)
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }

    fun count(ctx: Context): Int = load(ctx).length()

    fun all(ctx: Context): List<Pair> {
        val arr = load(ctx); val out = ArrayList<Pair>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Pair(o.optString("channel"), o.optString("recipient"),
                o.optString("draft"), o.optString("sent"), o.optLong("ts")))
        }
        return out
    }

    /**
     * The most recent corrections (optionally scoped to a channel) as compact "you changed X → Y" lines, for
     * feeding back into the voice layer so it learns how this user fixes the AI. Newest first.
     */
    fun recentCorrections(ctx: Context, channel: String? = null, limit: Int = 4): List<String> =
        all(ctx).asReversed()
            .filter { channel == null || it.channel.equals(channel, true) }
            .take(limit)
            .map { "you changed \"${it.draft.replace("\n", " ").take(90)}\" → \"${it.sent.replace("\n", " ").take(90)}\"" }

    /** Preference pairs as JSONL (one {rejected, chosen, context} per line) — the alignment dataset export. */
    fun exportJsonl(ctx: Context): String = all(ctx).joinToString("\n") {
        JSONObject()
            .put("channel", it.channel).put("recipient", it.recipient)
            .put("rejected", it.draft).put("chosen", it.sent).put("ts", it.ts)
            .toString()
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
