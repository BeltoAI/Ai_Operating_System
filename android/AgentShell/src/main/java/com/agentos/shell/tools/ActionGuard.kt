package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * Stops the same consequential action happening twice.
 *
 * Nothing guarded this before. Say "send it" twice, tap retry after a slow reply, or let a flaky
 * connection make one request look like two, and two emails went out, two events were created, two
 * invitations arrived. The second is never what anyone wanted and cannot be taken back — an email
 * is not undoable, and a duplicate invitation makes the sender look careless to the recipient.
 *
 * Deliberately narrow. It fingerprints *what the action does to the outside world* — who it reaches
 * and about what — not the exact JSON, because the model rewords the same request between attempts
 * and a byte-comparison would never match. And it only covers actions that leave the phone; making
 * two notes or opening an app twice is harmless and blocking it would be irritating.
 */
object ActionGuard {

    private const val PREFS = "slyos_actguard"

    /** How long a repeat is treated as an accident rather than an intention. */
    private const val WINDOW_MS = 5 * 60_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * What this action does, reduced to the parts that matter.
     *
     * Recipient and subject, not the body: the model rewords a body freely between two attempts at
     * the same instruction, so including it would make every repeat look new — which is exactly the
     * failure this is meant to catch.
     */
    private fun signature(type: String, arg: String): String {
        val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            o.optString(k).takeIf { it.isNotBlank() }
        }.orEmpty().lowercase().trim()

        val who = s("to", "name", "email", "recipient", "target")
        val what = s("subject", "title", "text").take(60)
        // Attendees belong to the signature: the same slot with different people is a different act.
        val guests = o.optJSONArray("attendees")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it).lowercase() }.sorted().joinToString(",")
        }.orEmpty()
        val whenAt = s("start", "at")
        return listOf(type, who, what, guests, whenAt).joinToString("|")
    }

    /** Whether this exact act was carried out moments ago. */
    fun isRepeat(ctx: Context, type: String, arg: String): Boolean {
        val key = signature(type, arg)
        val last = p(ctx).getLong(key, 0L)
        return last > 0 && System.currentTimeMillis() - last < WINDOW_MS
    }

    /** Record that it happened, so an immediate repeat is caught. */
    fun remember(ctx: Context, type: String, arg: String) {
        val e = p(ctx).edit().putLong(signature(type, arg), System.currentTimeMillis())
        // Keep the file from growing without bound: drop anything older than the window whenever
        // we write. Cheap, and there are only ever a handful of live entries.
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        p(ctx).all.forEach { (k, v) -> if (v is Long && v < cutoff) e.remove(k) }
        e.apply()
    }

    /** What to tell the owner, naming the thing so they can decide. */
    fun repeatNotice(type: String): String = when (type) {
        "send_email" -> "**Not sent again** — that same email went out less than five minutes ago. " +
            "Say \"send it anyway\" if you really want a second copy."
        "add_event" -> "**Not created again** — an identical event was added less than five minutes " +
            "ago. Say \"add it anyway\" if you want a second one."
        "send_sms", "message" -> "**Not sent again** — that message just went out. Say \"send it " +
            "anyway\" if you meant to repeat it."
        "outreach" -> "**Not queued again** — that campaign was queued moments ago."
        else -> "**Skipped** — the same thing was done less than five minutes ago."
    }

    /** The owner overriding the guard, in the words people actually use. */
    fun overridden(prompt: String): Boolean =
        Regex("(?i)\\b(anyway|again|do it again|send it again|yes really|i mean it|second one)\\b")
            .containsMatchIn(prompt)

    /** Forget everything — used when the owner explicitly overrides. */
    fun clear(ctx: Context) = p(ctx).edit().clear().apply()
}
