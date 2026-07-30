package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only part of you the network can see.
 *
 * Two lines — what you are looking for, and what you are open to — plus a handful of coarse tags
 * for routing. That is the entire public surface. Everything that makes an actual decision (the
 * messages, the CRM, the meetings, the health data) stays on the phone and is never uploaded.
 *
 * The split matters more than any feature here. The server narrows ten thousand people to two
 * hundred plausible ones using tags that could be printed on a business card. Each of those two
 * hundred phones then decides privately, against a brain the server has never seen, whether its
 * owner actually cares. So a request reaches the right person without anybody's life leaving their
 * device — and if the database were breached tomorrow, what leaks is a sentence you wrote on
 * purpose.
 *
 * Tags are derived rather than typed. A field asking somebody to categorise themselves gets
 * abandoned, and the brain already knows what they work on far better than a dropdown would.
 */
object NetworkProfile {

    private const val PREFS = "slyos_netprofile"

    data class Profile(
        val lookingFor: String = "",
        val openTo: String = "",
        val tags: List<String> = emptyList(),
        /** open · vouched · closed — nobody is forced to be reachable. */
        val reachability: String = "vouched",
        val updatedAt: Long = 0L
    ) {
        val isEmpty: Boolean get() = lookingFor.isBlank() && openTo.isBlank()
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): Profile = try {
        val s = p(ctx)
        Profile(
            s.getString("looking", "").orEmpty(),
            s.getString("open", "").orEmpty(),
            (s.getStringSet("tags", emptySet()) ?: emptySet()).toList().sorted(),
            s.getString("reach", "vouched") ?: "vouched",
            s.getLong("at", 0L))
    } catch (e: Exception) { Profile() }

    fun save(ctx: Context, prof: Profile) {
        try {
            p(ctx).edit()
                .putString("looking", prof.lookingFor.trim())
                .putString("open", prof.openTo.trim())
                .putStringSet("tags", prof.tags.toSet())
                .putString("reach", prof.reachability)
                .putLong("at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    val REACHABILITY = listOf(
        "open" to "Anyone can reach me",
        "vouched" to "Only through someone I know",
        "closed" to "Nobody, for now")

    // MARK: - Written by the brain, edited by you

    /**
     * A first draft of both fields, from what the owner has actually been doing.
     *
     * An empty profile is the thing that kills this: nobody fills in two thoughtful sentences about
     * themselves on a screen they opened out of curiosity. But the brain has months of evidence
     * about what they are working on and who they keep talking to, so it can propose something
     * accurate enough to edit — and editing a wrong draft is far easier than writing a right one
     * from nothing.
     */
    fun draft(ctx: Context): Profile {
        val about = try { MemoryStore.fullProfile(ctx).take(1200) } catch (e: Exception) { "" }
        val recall = try { BrainContext.build(ctx, "what am I working on and what do I need").take(1800) }
            catch (e: Exception) { "" }
        val raw = try {
            AgentClient.complete(
                "You write a person's two-line networking profile. Output ONLY compact JSON: " +
                "{\"looking_for\":\"…\",\"open_to\":\"…\",\"tags\":[\"…\"]}. " +
                "looking_for: what they genuinely need from other people right now — customers, " +
                "hires, introductions, advice — in one line, specific, no adjectives. " +
                "open_to: what they would welcome being contacted about, and what they would not, " +
                "in one line. tags: 4-8 lowercase routing words — industry, role, place, topic. " +
                "Never invent a need the material does not support.",
                "About them:\n$about\n\nWhat they have been working on:\n$recall\n\nJSON only.", 500)
        } catch (e: Exception) { "" }
        return try {
            val o = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val t = o.optJSONArray("tags")
            val tags = if (t == null) emptyList() else
                (0 until t.length()).map { t.optString(it).lowercase().trim() }
                    .filter { it.length in 2..24 }.distinct().take(8)
            Profile(o.optString("looking_for").trim(), o.optString("open_to").trim(), tags,
                get(ctx).reachability)
        } catch (e: Exception) { get(ctx) }
    }

    // MARK: - The only thing that leaves the phone

    /**
     * Publish the public two lines. Nothing else is ever sent.
     *
     * Written as one explicit row so it is readable at a glance what the network can see about
     * somebody — a privacy claim you cannot check is worth nothing, and this one is six fields long.
     */
    fun publish(ctx: Context): Pair<Boolean, String> {
        if (!AccountStore.signedIn(ctx)) return false to "Sign in first — your profile lives with your account."
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return false to "Session expired — sign in again."
        val prof = get(ctx)
        if (prof.isEmpty) return false to "Nothing to publish yet."
        return try {
            val row = JSONObject()
                .put("user_id", uid)
                .put("name", try { MemoryStore.ownerName(ctx) } catch (e: Exception) { "" })
                .put("looking_for", prof.lookingFor)
                .put("open_to", prof.openTo)
                .put("tags", JSONArray(prof.tags))
                .put("reachability", prof.reachability)
            val ok = SupabaseClient.upsert(
                "profiles", token, JSONArray().put(row), onConflict = "user_id")
            if (ok) true to "Published ✓"
            else false to "Couldn't publish — check your connection and try again."
        } catch (e: Exception) { false to (e.message ?: "couldn't publish") }
    }
}
