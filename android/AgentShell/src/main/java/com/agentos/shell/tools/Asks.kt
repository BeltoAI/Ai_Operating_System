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
    )

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
            id to if (reached == 0) "Sent. Nobody matches those tags yet."
                  else "Sent to $reached ${if (reached == 1) "person" else "people"}."
        } catch (e: Exception) { null to (e.message ?: "couldn't send") }
    }

    /** What came back. Names appear only where the holder chose to reveal one. */
    fun answers(ctx: Context, askId: String): List<Answer> {
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return emptyList()
        return try {
            val cands = JSONArray(SupabaseClient.get("ask_candidates",
                "select=ask_id,candidate_user,state,verdict,strength&ask_id=eq.$askId", token))
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
            if (ok) { mark(token, askId, uid, "accepted", "qualified"); true to "Introduced ✓" }
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
            SupabaseClient.patch("ask_candidates",
                "ask_id=eq.$askId&candidate_user=eq.$uid", token,
                JSONObject().put("state", "interested").put("verdict", "need_human")
                    .put("strength", strength.toDouble()))
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
                      val routes: Int = 1)

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
                best.copy(routes = rows.size)
            }
            .sortedByDescending { it.strength }
    }

    fun bridges(ctx: Context): List<Bridge> {
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(SupabaseClient.get("bridges",
                "select=person,note,strength,holder,asker&order=created_at.desc&limit=60", token))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Bridge(o.optString("person"),
                    if (o.isNull("note")) "" else o.optString("note"),
                    o.optDouble("strength", 0.0).toFloat(),
                    o.optString("holder"), o.optString("asker"),
                    o.optString("asker") == uid)
            }
        } catch (e: Exception) { emptyList() }
    }
}
