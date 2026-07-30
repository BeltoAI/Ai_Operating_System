package com.agentos.shell.tools

import android.content.Context

/**
 * Your own network, prospected against a goal — before anybody cold-emails a stranger.
 *
 * Mission and the CRM have been living in the same app without knowing about each other. Mission
 * takes a goal and crawls the WEB for 8–12 targets, then drafts a cold email to each. Meanwhile the
 * CRM holds 143 people the owner actually talks to and 20,005 LinkedIn connections, and Mission has
 * never once looked at either. So it will cold-email a stranger at a company where the owner already
 * has a colleague, a friend, or an unmessaged connection — the single most expensive mistake outreach
 * can make, because a warm route converts many times better and costs nothing to try first.
 *
 * That is what this fixes, and the reason it is the highest-value wiring left in SlyOS: not a new
 * feature, but two existing ones finally pointed at each other.
 *
 * Three tiers of warmth, in the order any competent person would work them:
 *
 *  1. **People you actually talk to.** A reply is near-certain and there is no introduction needed.
 *  2. **Connected, never messaged.** No warm intro required either — you are already in each other's
 *     networks, and nobody browses twenty thousand names, so these have been invisible until now.
 *  3. **Companies where you have a foothold.** You know somebody there; the person you need is
 *     somebody else. That is a two-step, and knowing it exists beats cold-emailing the front door.
 *
 * Everything is returned as [AgentClient.Prospect] — the exact type Mission's review, draft, send and
 * contacted/replied tracking already speak — so the warm routes flow through the pipeline that is
 * already built and tested rather than a parallel one.
 *
 * The model only ever SELECTS from a list of real people and gives a reason. It never writes a name,
 * a company or an email, because inventing a contact for an outreach campaign is the one failure that
 * would be unrecoverable — a fabricated prospect gets a real email sent to it.
 */
object MissionNetwork {

    enum class Warmth { TALKING, CONNECTED, FOOTHOLD }

    data class Warm(
        val prospect: AgentClient.Prospect,
        val warmth: Warmth,
        /** Why this person fits the goal — the model's reasoning, shown. */
        val why: String,
        /** How you know them, in plain words — the evidence, never inferred. */
        val how: String,
        /** Messages exchanged. Ranks within a tier: better-known first. */
        val strength: Int
    )

    fun warmthLabel(w: Warmth): String = when (w) {
        Warmth.TALKING -> "You talk to them"
        Warmth.CONNECTED -> "Connected, never messaged"
        Warmth.FOOTHOLD -> "You know someone there"
    }

    /**
     * The whole warm list for a goal.
     *
     * Ordered by warmth and then by how well you know them, which is the order these should actually
     * be worked. Bounded, because a goal with sixty suggestions gets none of them done.
     */
    fun prospects(ctx: Context, goal: String, max: Int = 12): List<Warm> {
        if (goal.trim().length < 4) return emptyList()
        val book = Crm.peopleCached(ctx, 400)
        val out = ArrayList<Warm>()

        // ── 1. People you talk to ──
        val talking = book.filter { it.reciprocal && it.totalMessages >= 5 }
            .sortedByDescending { it.totalMessages }.take(80)
        pickFrom(goal, talking.map { p ->
            "${p.name}${if (p.role.isNotBlank()) " — ${p.role.take(46)}" else ""}" +
                "${if (p.company.isNotBlank()) " at ${p.company}" else ""}"
        }, max = 6).forEach { (i, why) ->
            val p = talking.getOrNull(i) ?: return@forEach
            out.add(Warm(
                AgentClient.Prospect(
                    name = p.name, company = p.company,
                    email = p.emails.firstOrNull().orEmpty(),
                    website = "", why = why, linkedin = "", role = p.role),
                Warmth.TALKING, why,
                "${p.mainChannel} · " +
                    (if (p.silentDays == 0) "spoke today" else "${p.silentDays}d ago"),
                p.totalMessages))
        }

        // ── 2. Connected, never messaged ──
        //
        // Searched by the goal's own words rather than loaded: twenty thousand rows cannot be ranked
        // by a model, and the useful subset is whoever matches the industry or role the goal names.
        val known = book.map { it.name.lowercase() }.toSet()
        val terms = goalTerms(goal)
        val pool = LinkedHashMap<String, ConnectionStore.Conn>()
        terms.take(6).forEach { t ->
            try {
                ConnectionStore.search(ctx, t, 25)
                    .filterNot { it.name.lowercase() in known }
                    .forEach { pool.putIfAbsent(it.name.lowercase(), it) }
            } catch (e: Exception) {}
        }
        val conns = pool.values.toList().take(90)
        if (conns.isNotEmpty()) pickFrom(goal, conns.map { c ->
            "${c.name}${if (c.role.isNotBlank()) " — ${c.role.take(46)}" else ""}" +
                "${if (c.company.isNotBlank()) " at ${c.company}" else ""}"
        }, max = 6).forEach { (i, why) ->
            val c = conns.getOrNull(i) ?: return@forEach
            out.add(Warm(
                AgentClient.Prospect(
                    name = c.name, company = Crm.tidyCompany(c.company), email = "",
                    website = "", why = why, linkedin = c.url, role = c.role),
                Warmth.CONNECTED, why, "LinkedIn · never messaged", 0))
        }

        // ── 3. Companies where you already have a foothold ──
        //
        // Not a person but a door: you know somebody inside, and the person the goal needs is
        // somebody else in the same building. Worth surfacing precisely because Mission's instinct
        // is to cold-email the front desk of a company where the owner has a colleague.
        val footholds = book.filter { it.reciprocal && it.company.length >= 3 }
            .groupBy { it.company }
        if (footholds.isNotEmpty()) {
            val names = footholds.keys.toList()
            pickFrom(goal, names.map { co ->
                "$co — you know ${footholds[co]?.size ?: 0} there" }, max = 4).forEach { (i, why) ->
                val co = names.getOrNull(i) ?: return@forEach
                val inside = footholds[co].orEmpty().sortedByDescending { it.totalMessages }
                val first = inside.firstOrNull() ?: return@forEach
                // Skip when that person is already in tier 1 — the same route twice is noise.
                if (out.any { it.prospect.name == first.name }) return@forEach
                out.add(Warm(
                    AgentClient.Prospect(
                        name = first.name, company = co,
                        email = first.emails.firstOrNull().orEmpty(),
                        website = "", why = why, linkedin = "", role = first.role),
                    Warmth.FOOTHOLD, why,
                    "Your way into $co — ${inside.size} " +
                        (if (inside.size == 1) "person" else "people") + " you know there",
                    first.totalMessages))
            }
        }

        return out.distinctBy { it.prospect.name.lowercase() }
            .sortedWith(compareBy({ it.warmth.ordinal }, { -it.strength }))
            .take(max)
    }

    /**
     * The words in a goal worth searching a network for.
     *
     * Industry and role words, not verbs. "Raise a seed round from fintech investors in London"
     * should search for fintech, investor, London — not for "raise" or "from", which match nothing
     * useful and everything at once.
     */
    private fun goalTerms(goal: String): List<String> {
        val stop = setOf("the", "a", "an", "and", "or", "for", "from", "with", "to", "in", "on", "at",
            "of", "my", "our", "get", "find", "raise", "want", "need", "make", "build", "sell",
            "selling", "buy", "help", "more", "some", "any", "who", "that", "this", "about", "into",
            "new", "best", "good", "people", "person", "company", "companies", "someone")
        return goal.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 && it !in stop }
            .distinct()
    }

    /**
     * Ask the model which of these fit, by index.
     *
     * Indices and reasons only. A model that returns row numbers cannot invent a person — and an
     * invented prospect in an outreach tool receives a real email, which is the one mistake here
     * that cannot be taken back.
     */
    private fun pickFrom(goal: String, listing: List<String>, max: Int): List<Pair<Int, String>> {
        if (listing.isEmpty()) return emptyList()
        val numbered = listing.mapIndexed { i, l -> "$i. $l" }.joinToString("\n")
        val raw = try {
            AgentClient.complete(
                "You select rows that serve a goal. Output ONLY a JSON array of " +
                "{\"i\":<index>,\"why\":\"<one sentence under 18 words on how they help>\"}. " +
                "At most $max. Pick NOBODY rather than pad — a weak suggestion costs a real favour " +
                "and the sender's credibility. If none fit, output [].",
                "GOAL: $goal\n\nCANDIDATES:\n$numbered\n\nWhich genuinely help with this goal? " +
                "JSON only.", 700)
        } catch (e: Exception) { "" }
        val out = ArrayList<Pair<Int, String>>()
        try {
            val arr = org.json.JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1))
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val i = o.optInt("i", -1)
                val why = o.optString("why").trim()
                if (i in listing.indices && why.isNotBlank()) out.add(i to why)
            }
        } catch (e: Exception) {}
        return out.distinctBy { it.first }.take(max)
    }

    /**
     * The mission and its warm routes, for the brain.
     *
     * So "who should I talk to about the seed round" is answerable from Home, and not only inside the
     * Mission page — the same mistake the CRM made before its own block existed.
     */
    fun brainBlock(ctx: Context): String = try {
        val goal = MissionStore.mission(ctx)
        if (goal.isBlank()) "" else buildString {
            append("MY CURRENT MISSION: ").append(goal).append("\n")
            val contacted = try { MissionStore.contacted(ctx) } catch (e: Exception) { emptyList() }
            val replied = try { MissionStore.replied(ctx) } catch (e: Exception) { emptyList() }
            if (contacted.isNotEmpty())
                append("Contacted so far (${contacted.size}): ")
                    .append(contacted.take(12).joinToString(", ")).append("\n")
            if (replied.isNotEmpty())
                append("Replied (${replied.size}): ").append(replied.take(12).joinToString(", ")).append("\n")
        }.take(1200)
    } catch (e: Exception) { "" }
}
