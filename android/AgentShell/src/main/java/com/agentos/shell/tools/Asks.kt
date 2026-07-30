package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asking the network, and answering it.
 *
 * The question this exists to answer is "who can introduce me to X", and the hard part is not
 * finding them — it is finding them without anybody uploading their address book. Ten people might
 * know the same person. The server cannot know that, because it has never seen one contact, and it
 * never will.
 *
 * So the overlap is discovered rather than precomputed, in four steps:
 *
 *  1. **Fan out.** The server narrows on tag overlap — words off a business card — and writes a
 *     candidacy row per plausible person. That is the whole of its contribution.
 *  2. **Each phone decides privately.** It searches its OWN CRM for the person asked about. Of two
 *     hundred candidates roughly a hundred and ninety-eight find nothing, mark themselves `ignored`
 *     and never tell their owner anything happened. Receiving costs nothing, so nobody has to
 *     defend an inbox, and that is what makes a network like this survivable.
 *  3. **The ones who do know them reply with a number.** Computed locally from their own message
 *     history — how much they talk, how recently, whether it goes both ways. When ten people know
 *     the same person, this is what routes the ask to the one who is actually close rather than the
 *     one who answered first.
 *  4. **The name crosses only on acceptance.** The holder writes a bridge row, deliberately, and
 *     that row is the shared node on the map. Before it exists, no overlap between two address
 *     books is visible to anyone — including us.
 *
 * The exit criteria are not manners, they are structure: the turn cap and the clock are triggers in
 * the database, and step 2 terminates every candidacy in one pass with a verdict. Two agents
 * politely qualifying each other forever is the failure mode this whole shape is built to prevent.
 */
object Asks {

    data class Ask(
        val id: String, val kind: String, val criteria: String,
        val tags: List<String>, val state: String, val expiresAt: String
    ) {
        /**
         * Hours left before the server closes it.
         *
         * Shown, always. "Your agent is working on it" with no clock is the sentence every
         * abandoned assistant feature was built on — somebody should be able to tell at a glance
         * whether this is still running or quietly died three days ago.
         */
        val hoursLeft: Long get() = try {
            val t = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(expiresAt.take(19))?.time ?: 0L
            ((t - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0)
        } catch (e: Exception) { 0 }

        val live: Boolean get() = state == "open" && hoursLeft > 0

        val closesIn: String get() = when {
            !live -> "closed"
            hoursLeft >= 24 -> "closes in ${hoursLeft / 24}d"
            hoursLeft >= 1  -> "closes in ${hoursLeft}h"
            else -> "closing"
        }
    }

    /** What an ask has actually done. Counts only — never who. */
    data class Funnel(val reached: Int, val foundNothing: Int, val knewSomeone: Int,
                      val stillThinking: Int, val introductions: Int, val people: Int)

    /** A candidacy as the receiving phone sees it: an ask sent to me, and what I did about it. */
    data class Incoming(val askId: String, val state: String, val criteria: String, val kind: String)

    /** An answer as the ASKER sees it. No name until a bridge exists — only that somebody knows. */
    data class Answer(
        val askId: String, val candidate: String, val state: String,
        val verdict: String, val strength: Float, val note: String,
        /** Null until the holder accepts. This is the whole privacy boundary in one field. */
        val person: String?
    )

    // MARK: - Asking

    /**
     * Make an ask, and fan it out.
     *
     * `criteria` is public — it is read by two hundred strangers' agents — so it says what is
     * wanted and never why. The tags decide who ever sees it at all; an empty tag list reaches
     * everyone rather than nobody, because an ask that silently matches zero people is
     * indistinguishable from a broken feature.
     */
    fun create(ctx: Context, criteria: String, tags: List<String>, kind: String = "reach"):
            Pair<String?, String> {
        if (!AccountStore.signedIn(ctx)) return null to "Sign in first."
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return null to "Session expired — sign in again."
        if (criteria.trim().length < 4) return null to "Say what you're asking for."
        return try {
            val row = JSONObject()
                .put("from_user", uid).put("kind", kind)
                .put("criteria", criteria.trim())
                .put("tags", JSONArray(tags.map { it.lowercase().trim() }.filter { it.length in 2..24 }))
            val created = SupabaseClient.insertReturning("asks", token, JSONArray().put(row))
            val id = created?.optJSONObject(0)?.optString("id").orEmpty()
            if (id.isBlank()) return null to
                ("Couldn't send — " + SupabaseClient.lastError.take(140).ifBlank { "try again" })
            val reached = SupabaseClient.rpcInt("fan_out", JSONObject().put("p_ask", id), token) ?: 0
            // Into the brain, so "what am I waiting on" is answerable by every assistant in the
            // app rather than only by the screen that happened to send it.
            try {
                Brain.remember(ctx, "note", "Asked the network",
                    "Asked $reached people: ${criteria.trim()}. Open for 72 hours.", role = "system")
            } catch (e: Exception) {}
            id to if (reached == 0) "Sent. Nobody matches those tags yet — add tags in Where you stand."
                  else "Sent to $reached ${if (reached == 1) "person" else "people"}. Working for 3 days."
        } catch (e: Exception) { null to (e.message ?: "couldn't send") }
    }

    /** What came back. Names appear only where the holder chose to reveal one. */
    fun answers(ctx: Context, askId: String): List<Answer> {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return emptyList()
        return try {
            // Ask for the strength, but survive without it.
            //
            // The client ships before the migration does, and for a while `strength` is a column
            // that does not exist yet — PostgREST answers the whole select with a 400, so ONE
            // missing column turned a page with three real introductions against it into "nothing
            // back yet". A screen that reports zero because of a schema skew is worse than one that
            // reports slightly less.
            val cands = JSONArray(
                SupabaseClient.getOrNull("ask_candidates",
                    "select=ask_id,candidate_user,state,verdict,strength&ask_id=eq.$askId", token)
                ?: SupabaseClient.get("ask_candidates",
                    "select=ask_id,candidate_user,state,verdict&ask_id=eq.$askId", token))
            val bridges = JSONArray(SupabaseClient.get("bridges",
                "select=holder,person,note,strength&ask_id=eq.$askId", token))
            val byHolder = HashMap<String, JSONObject>()
            for (i in 0 until bridges.length()) bridges.optJSONObject(i)?.let {
                byHolder[it.optString("holder")] = it
            }
            (0 until cands.length()).mapNotNull { i ->
                val o = cands.optJSONObject(i) ?: return@mapNotNull null
                val who = o.optString("candidate_user")
                val b = byHolder[who]
                Answer(askId, who, o.optString("state"),
                    if (o.isNull("verdict")) "" else o.optString("verdict"),
                    // The bridge's number if it exists, otherwise the one the candidacy carries —
                    // so leads can be ranked before a single name has crossed.
                    (b?.optDouble("strength", 0.0)
                        ?: o.optDouble("strength", 0.0)).toFloat(),
                    b?.optString("note").orEmpty(), b?.optString("person"))
            }.sortedByDescending { it.strength }
        } catch (e: Exception) { emptyList() }
    }

    /** The asks I have open. */
    fun myAsks(ctx: Context): List<Ask> {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(SupabaseClient.get("asks",
                "select=id,kind,criteria,tags,state,expires_at&from_user=eq.$uid" +
                "&order=created_at.desc&limit=20", token))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optJSONArray("tags")
                Ask(o.optString("id"), o.optString("kind"), o.optString("criteria"),
                    if (t == null) emptyList() else (0 until t.length()).map { t.optString(it) },
                    o.optString("state"), o.optString("expires_at"))
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Who sent an ask I was a candidate for. Readable only because I AM a candidate. */
    fun askerOf(ctx: Context, askId: String): String {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return ""
        return try {
            val arr = JSONArray(SupabaseClient.get("asks",
                "select=from_user&id=eq.$askId&limit=1", token))
            arr.optJSONObject(0)?.optString("from_user").orEmpty()
        } catch (e: Exception) { "" }
    }

    /**
     * Keep the open asks working.
     *
     * `fan_out` is idempotent, so calling it again on a live ask reaches anybody who has joined,
     * published tags, or opened up since — and reaches nobody twice. Without this an ask is a
     * single broadcast at one instant, which is not what "my agent is looking for this" means to
     * anybody who reads it.
     *
     * Returns how many NEW people it reached, so the screen can say so.
     */
    fun refresh(ctx: Context): Int {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return 0
        var added = 0
        try {
            myAsks(ctx).filter { it.live }.forEach { a ->
                val before = funnel(ctx, a.id)?.reached ?: 0
                SupabaseClient.rpcInt("fan_out", JSONObject().put("p_ask", a.id), token)
                val after = funnel(ctx, a.id)?.reached ?: before
                added += (after - before).coerceAtLeast(0)
            }
        } catch (e: Exception) {}
        return added
    }

    /** The counts for one of your asks. A definer function, because RLS hides the silent ones. */
    fun funnel(ctx: Context, askId: String): Funnel? {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return null
        return try {
            val txt = SupabaseClient.rpcJson("ask_funnel",
                JSONObject().put("p_ask", askId), token) ?: return null
            val o = JSONArray(txt).optJSONObject(0) ?: return null
            Funnel(o.optInt("reached"), o.optInt("found_nothing"), o.optInt("knew_someone"),
                o.optInt("still_thinking"), o.optInt("introductions"), o.optInt("distinct_people"))
        } catch (e: Exception) { null }
    }

    /** Every introduction you have asked for, by what came of it. */
    fun outcomes(ctx: Context): Map<String, Int> {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return emptyMap()
        return try {
            val txt = SupabaseClient.rpcJson("intro_outcomes", JSONObject(), token) ?: return emptyMap()
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { it.optString("outcome") to it.optInt("n") }
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    // MARK: - Answering, on the receiving phone

    /**
     * Asks sent to me that still want a human.
     *
     * `sent` AND `interested`, and the second one is not optional. `handle()` runs by itself when the
     * field opens and flips anything this phone can answer to `interested` — so a query for `sent`
     * alone returns every ask about somebody I do NOT know and none of the ones I do. The section
     * would have been permanently empty in exactly the case it exists for.
     */
    fun inbox(ctx: Context): List<Incoming> {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return emptyList()
        return try {
            val cands = JSONArray(SupabaseClient.get("ask_candidates",
                "select=ask_id,state&candidate_user=eq.$uid&state=in.(sent,interested)", token))
            val byId = HashMap<String, String>()
            for (i in 0 until cands.length()) cands.optJSONObject(i)?.let {
                byId[it.optString("ask_id")] = it.optString("state")
            }
            val ids = byId.keys.filter { it.isNotBlank() }
            if (ids.isEmpty()) return emptyList()
            val list = ids.joinToString(",")
            val asks = JSONArray(SupabaseClient.get("asks",
                "select=id,kind,criteria,state&id=in.($list)&state=eq.open", token))
            (0 until asks.length()).mapNotNull { i ->
                val o = asks.optJSONObject(i) ?: return@mapNotNull null
                Incoming(o.optString("id"), byId[o.optString("id")] ?: "sent",
                    o.optString("criteria"), o.optString("kind"))
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * How close I actually am to somebody, from my own message history. 0 when I do not know them.
     *
     * Three signals, and the third does most of the work: reciprocity. A thousand messages to a
     * mailing list is not a relationship, and a person who has never once replied is not somebody
     * you can introduce anybody to.
     */
    fun closeness(p: Crm.Person): Float {
        if (!p.reciprocal) return 0f
        val volume = (Math.log10((p.totalMessages + 1).toDouble()) / 3.0).coerceIn(0.0, 1.0).toFloat()
        val recency = when {
            p.silentDays <= 7 -> 1f
            p.silentDays <= 30 -> 0.75f
            p.silentDays <= 120 -> 0.45f
            p.silentDays <= 365 -> 0.2f
            else -> 0.08f
        }
        return (0.35f * volume + 0.45f * recency + 0.20f).coerceIn(0f, 1f)
    }

    /**
     * Deal with one incoming ask, without telling anybody anything unless there is something to say.
     *
     * This is step 2, and it terminates in a single pass by construction — one local search, one
     * verdict, one write. There is no loop here to get stuck in.
     */
    fun handle(ctx: Context, inc: Incoming): String {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return "no session"

        val hit = bestMatch(ctx, inc.criteria)
        if (hit == null) {
            // Silent. Nobody is told, including my owner — which is why being in the candidate pool
            // costs nothing and the network does not become another inbox.
            mark(token, inc.askId, uid, "ignored", "not_qualified")
            return "ignored"
        }
        // Send the NUMBER but not the name. This is what makes "ten people know them, route to the
        // closest" a real behaviour rather than a claim: the asker can rank offers before anybody
        // has revealed who they know.
        markStrength(token, inc.askId, uid, hit.second)
        // Surfaced for a human decision. The NAME does not move yet — `accept` is what moves it,
        // and only my owner can call it.
        return "knows:${hit.first.name}"
    }

    /** Who I know that this ask is about, and how close. Local only; nothing leaves in this call. */
    fun bestMatch(ctx: Context, criteria: String): Pair<Crm.Person, Float>? {
        val words = criteria.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP }
        if (words.isEmpty()) return null
        val people = try { Crm.peopleCached(ctx, 400) } catch (e: Exception) { emptyList() }
        var best: Pair<Crm.Person, Float>? = null
        people.forEach { p ->
            val hay = (p.name + " " + p.company + " " + p.role).lowercase()
            // Whole words. A `contains` test against a keyword list matches "nda" inside "Agenda"
            // and "round" inside "G-round", and has been the same bug three times over.
            val hits = words.count { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(hay) }
            if (hits == 0) return@forEach
            val score = closeness(p) * (hits / words.size.toFloat()).coerceAtMost(1f)
            if (score > 0f && (best == null || score > best!!.second)) best = p to score
        }
        return best
    }

    private val STOP = setOf("who", "can", "know", "knows", "introduce", "intro", "the", "and",
        "for", "any", "someone", "anyone", "with", "from", "that", "this", "need", "want", "looking")

    /**
     * Accept: reveal the name, on purpose.
     *
     * The only moment a real person crosses between two users, and it is a deliberate act by the
     * one who holds the relationship. Everything before this is two databases that never met.
     */
    fun accept(ctx: Context, askId: String, asker: String, person: Crm.Person, note: String):
            Pair<Boolean, String> {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return false to "Session expired."
        return try {
            val row = JSONObject()
                .put("ask_id", askId).put("asker", asker).put("holder", uid)
                .put("person", person.name).put("note", note)
                .put("strength", closeness(person).toDouble())
            val ok = SupabaseClient.upsert("bridges", token, JSONArray().put(row),
                onConflict = "ask_id,holder")
            if (ok) {
                mark(token, askId, uid, "accepted", "qualified")
                try {
                    Brain.remember(ctx, "note", "Introduced ${person.name}",
                        "Someone in the network asked for a person like ${person.name}; " +
                        "I offered the introduction. ${note.trim()}", role = "system")
                } catch (e: Exception) {}
                true to "Introduced ✓"
            }
            else false to ("Couldn't send — " + SupabaseClient.lastError.take(120))
        } catch (e: Exception) { false to (e.message ?: "failed") }
    }

    fun decline(ctx: Context, askId: String) {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isNotBlank() && uid.isNotBlank()) mark(token, askId, uid, "declined", "not_qualified")
    }

    private fun markStrength(token: String, askId: String, uid: String, strength: Float) {
        try {
            val ok = SupabaseClient.patch("ask_candidates",
                "ask_id=eq.$askId&candidate_user=eq.$uid", token,
                JSONObject().put("state", "interested").put("verdict", "need_human")
                    .put("strength", strength.toDouble()))
            // Same skew, other direction: still register interest even if the number has nowhere
            // to go yet.
            if (!ok) mark(token, askId, uid, "interested", "need_human")
        } catch (e: Exception) {}
    }

    private fun mark(token: String, askId: String, uid: String, state: String, verdict: String) {
        try {
            SupabaseClient.patch("ask_candidates",
                "ask_id=eq.$askId&candidate_user=eq.$uid", token,
                JSONObject().put("state", state).put("verdict", verdict))
        } catch (e: Exception) {}
    }

    // MARK: - The shared nodes

    /**
     * Every bridge either side of me — the people two networks turned out to share.
     *
     * This is what the map draws between two galaxies, and there is exactly one way a row gets in
     * here: somebody chose to put it there.
     */
    data class Bridge(val person: String, val note: String, val strength: Float,
                      val holder: String, val asker: String, val mine: Boolean,
                      /** How many different people offered this same person. */
                      val routes: Int = 1,
                      val askId: String = "",
                      /** pending · reaching_out · connected · no_reply · not_useful */
                      val outcome: String = "pending")

    /** Who is actually going to make the introduction, and how to reach THEM. Never the target. */
    data class Handoff(val name: String, val email: String, val phone: String, val calendly: String)

    /**
     * The one hop the database will release.
     *
     * Omar said he knows Priya. That does not entitle anybody to Priya — Omar has no right to hand
     * her details over and she has not been asked yet. What it entitles you to is OMAR: whatever he
     * chose to share, released only because he tapped accept. Priya's details reach you if and when
     * Priya says yes on her own phone. Double opt-in, enforced by what the server will return.
     */
    fun handoff(ctx: Context, askId: String, holder: String): Handoff? {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return null
        return try {
            val txt = SupabaseClient.rpcJson("reveal_contact",
                JSONObject().put("p_ask", askId).put("p_holder", holder), token) ?: return null
            val o = JSONArray(txt).optJSONObject(0) ?: return null
            fun f(k: String) = if (o.isNull(k)) "" else o.optString(k)
            Handoff(f("name"), f("email"), f("phone"), f("calendly"))
        } catch (e: Exception) { null }
    }

    /** Record what actually came of it. `no_reply` matters more than `connected`. */
    fun setOutcome(ctx: Context, askId: String, holder: String, outcome: String,
                   person: String = ""): Boolean {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return false
        return try {
            val ok = SupabaseClient.patch("bridges", "ask_id=eq.$askId&holder=eq.$holder", token,
                JSONObject().put("outcome", outcome))
            // The result of an introduction is a fact about a relationship, which is exactly the
            // kind of thing the brain should hold — and the reason it can later say "you were
            // introduced to Priya in July and never heard back".
            if (ok && person.isNotBlank()) try {
                Brain.remember(ctx, "note", "Introduction: $person",
                    when (outcome) {
                        "connected"   -> "Introduced to $person through the network — it worked."
                        "no_reply"    -> "Introduced to $person through the network — no reply."
                        "not_useful"  -> "Introduced to $person through the network — not useful."
                        else          -> "Reaching out to $person through the network."
                    }, actors = listOf(person), role = "system")
            } catch (e: Exception) {}
            ok
        } catch (e: Exception) { false }
    }

    /**
     * One node per PERSON, not per route.
     *
     * When ten people know the same person, ten bridge rows come back — and drawing ten nodes with
     * the same name on them would say something false about the shape of the network. There is one
     * person; there are several ways to reach them. The strongest route wins the node and the rest
     * become a count.
     */
    fun bridgesByPerson(ctx: Context): List<Bridge> {
        val all = bridges(ctx)
        return all.groupBy { it.person.lowercase().trim() }
            .map { (_, rows) ->
                val best = rows.maxByOrNull { it.strength }!!
                // Distinct PEOPLE, not rows. The same person offering the same contact on two
                // separate asks is one route, and counting rows said "4 people know them" when
                // two did.
                best.copy(routes = rows.map { it.holder }.distinct().size)
            }
            .sortedByDescending { it.strength }
    }

    fun bridges(ctx: Context): List<Bridge> {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return emptyList()
        return try {
            // Widest select that works.
            //
            // Adding a column to a select is a breaking change until the migration runs, and
            // PostgREST rejects the WHOLE query over one unknown name — so the third time this
            // happened the introductions simply stopped appearing. Ask for everything, fall back
            // to what has always been there.
            val arr = JSONArray(
                SupabaseClient.getOrNull("bridges",
                    "select=person,note,strength,holder,asker,ask_id,outcome" +
                    "&order=created_at.desc&limit=60", token)
                ?: SupabaseClient.getOrNull("bridges",
                    "select=person,note,strength,holder,asker,ask_id" +
                    "&order=created_at.desc&limit=60", token)
                ?: SupabaseClient.get("bridges",
                    "select=person,note,strength,holder,asker&order=created_at.desc&limit=60", token))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Bridge(o.optString("person"),
                    if (o.isNull("note")) "" else o.optString("note"),
                    o.optDouble("strength", 0.0).toFloat(),
                    o.optString("holder"), o.optString("asker"),
                    o.optString("asker") == uid, 1,
                    if (o.isNull("ask_id")) "" else o.optString("ask_id"),
                    if (o.isNull("outcome")) "pending" else o.optString("outcome"))
            }
        } catch (e: Exception) { emptyList() }
    }
}
