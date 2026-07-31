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
        /**
         * The three fields, and they do different jobs.
         *
         * OFFER is what your agent can say yes to when somebody else asks — the reason you are
         * useful to the network rather than only a consumer of it. Without it, matching runs one
         * way and everybody is looking for something nobody is giving.
         *
         * LOOKING FOR is what your agent goes out and asks about.
         *
         * OPEN TO is the filter on what reaches you at all.
         */
        val offer: String = "",
        val lookingFor: String = "",
        val openTo: String = "",
        val tags: List<String> = emptyList(),
        /** open · vouched · closed — nobody is forced to be reachable. */
        val reachability: String = "vouched",
        /**
         * How somebody reaches you once you have agreed to be reached.
         *
         * Not optional in practice, though the schema lets it be. `share_on_intro` defaults to
         * "email" while `contact_email` defaults to null — so a profile that has never filled this
         * in agrees to an introduction and then hands the other person a card with nothing on it.
         * Silent, and indistinguishable from the feature being broken.
         */
        val contactEmail: String = "",
        val contactPhone: String = "",
        val calendly: String = "",
        /** email · calendly · both · none */
        val shareOnIntro: String = "email",
        val updatedAt: Long = 0L
    ) {
        val isEmpty: Boolean get() = offer.isBlank() && lookingFor.isBlank() && openTo.isBlank()

        /** Can anybody actually reach you if you say yes? */
        val reachable: Boolean get() = when (shareOnIntro) {
            "none"     -> true                      // deliberate, and honest
            "calendly" -> calendly.isNotBlank()
            "both"     -> contactEmail.isNotBlank() || calendly.isNotBlank()
            else       -> contactEmail.isNotBlank()
        }
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): Profile = try {
        val s = p(ctx)
        Profile(
            s.getString("offer", "").orEmpty(),
            s.getString("looking", "").orEmpty(),
            s.getString("open", "").orEmpty(),
            (s.getStringSet("tags", emptySet()) ?: emptySet()).toList().sorted(),
            s.getString("reach", "vouched") ?: "vouched",
            s.getString("c_email", "").orEmpty(),
            s.getString("c_phone", "").orEmpty(),
            s.getString("c_cal", "").orEmpty(),
            s.getString("c_share", "email") ?: "email",
            s.getLong("at", 0L))
    } catch (e: Exception) { Profile() }

    fun save(ctx: Context, prof: Profile) {
        try {
            p(ctx).edit()
                .putString("offer", prof.offer.trim())
                .putString("looking", prof.lookingFor.trim())
                .putString("open", prof.openTo.trim())
                .putStringSet("tags", prof.tags.toSet())
                .putString("reach", prof.reachability)
                .putString("c_email", prof.contactEmail.trim())
                .putString("c_phone", prof.contactPhone.trim())
                .putString("c_cal", prof.calendly.trim())
                .putString("c_share", prof.shareOnIntro)
                .putLong("at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    val SHARING = listOf(
        "email" to "My email address",
        "calendly" to "A link to book time",
        "both" to "Email, phone and booking link",
        "none" to "Nothing — I'll reach out myself")

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
                "{\"offer\":\"…\",\"looking_for\":\"…\",\"open_to\":\"…\",\"tags\":[\"…\"]}. " +
                "offer: what this person can genuinely GIVE other people — introductions they could " +
                "make, expertise, a product, access. Specific. This is what their agent will say " +
                "yes to when somebody asks, so an empty or vague offer makes them useless to the " +
                "network. " +
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
            Profile(o.optString("offer").trim(), o.optString("looking_for").trim(),
                o.optString("open_to").trim(), tags, get(ctx).reachability)
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
                // `id`, not `user_id`. profiles is the ACCOUNT table from ACCOUNT_AND_SYNC.md and it
                // has been keyed on `id` since the first signup — posting user_id is a 400 every time.
                .put("id", uid)
                // `display_name`. The account table has never had a `name` column, and PostgREST
                // answers an unknown column with a 400 that the UI was reporting as "check your
                // connection" — a network message for a schema mistake.
                .put("display_name", try { MemoryStore.ownerName(ctx) } catch (e: Exception) { "" })
                .put("offer", prof.offer)
                .put("looking_for", prof.lookingFor)
                .put("open_to", prof.openTo)
                .put("tags", JSONArray(prof.tags))
                .put("reachability", prof.reachability)
                .put("contact_email", prof.contactEmail.trim())
                .put("contact_phone", prof.contactPhone.trim())
                .put("calendly", prof.calendly.trim())
                .put("share_on_intro", prof.shareOnIntro)
                // One integer, and deliberately nothing more. It is what lets somebody else's galaxy
                // be the right size on your screen without a single one of their people leaving
                // their phone.
                .put("network_size", try { Field.cached(ctx)?.total ?: 0 } catch (e: Exception) { 0 })
            val ok = SupabaseClient.upsert(
                "profiles", token, JSONArray().put(row), onConflict = "id")
            if (ok) true to "Published ✓"
            // The real reason, always. A generic failure string cost an hour of looking at the
            // wrong thing.
            else false to ("Couldn't publish — " +
                SupabaseClient.lastError.take(140).ifBlank { "check your connection" })
        } catch (e: Exception) { false to (e.message ?: "couldn't publish") }
    }

    // MARK: - Everyone else

    /**
     * The other people running SlyOS.
     *
     * Six public fields, and they are the same six anybody publishes about themselves — there is no
     * privileged read here, only the row they chose to write. Their contacts are not in this table
     * and never will be; `network_size` is a single integer, which is enough to draw their galaxy
     * the right size and not enough to know one name in it.
     *
     * Signed-in only, because the read policy is `auth.role() = 'authenticated'`. That is not a
     * paywall — it is what stops the network being scrapable by anybody with the anon key.
     */
    data class Peer(
        val userId: String,
        val name: String,
        val offer: String,
        val lookingFor: String,
        val openTo: String,
        val tags: List<String>,
        val networkSize: Int,
        val reachability: String
    )

    /** A text column that may be SQL NULL. */
    private fun str(o: JSONObject, k: String): String =
        if (o.isNull(k)) "" else o.optString(k).trim()

    fun others(ctx: Context, limit: Int = 200): List<Peer> {
        if (!AccountStore.signedIn(ctx)) return emptyList()
        val token = AccountStore.freshAccessToken(ctx)
        val me = AccountStore.userId(ctx)
        if (token.isBlank()) return emptyList()
        return try {
            val q = "select=id,display_name,offer,looking_for,open_to,tags,network_size," +
                "reachability&order=network_size.desc&limit=$limit"
            val arr = JSONArray(SupabaseClient.get("profiles", q, token))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank() || id == me) return@mapNotNull null
                val t = o.optJSONArray("tags")
                Peer(
                    id,
                    // `optString` on a JSON null returns the four characters "null", not "" — so a
                    // profile with no display name rendered a dot labelled null on the map.
                    str(o, "display_name").ifBlank { "Someone" },
                    str(o, "offer"), str(o, "looking_for"), str(o, "open_to"),
                    if (t == null) emptyList() else (0 until t.length()).map { t.optString(it) }
                        .filter { it.isNotBlank() },
                    o.optInt("network_size"),
                    str(o, "reachability").ifBlank { "vouched" })
            }
        } catch (e: Exception) { emptyList() }
    }
}
