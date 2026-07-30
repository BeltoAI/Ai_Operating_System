package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Getting to somebody you do not know — as a plan that runs one confirmed step at a time.
 *
 * [Intro] answers "who could introduce me to X" whenever X is already somewhere in your own data. Ask
 * it about anyone OUTSIDE your network — which is the entire reason introductions exist — and it
 * returns nothing at all. No card, no suggestion. The feature could only reach people you could
 * already reach.
 *
 * The honest structure for the general case is not a computed route. Nobody can calculate a path into
 * a stranger's network from a phone, and a graph that pretends to would be inventing edges. What can
 * be done is a plan that is *discovered*:
 *
 *  1. **Find out who the target actually is** — with live web search, because their employer, their
 *     field and who they work alongside is public information and guessing it is how you end up
 *     asking the wrong person about the wrong person.
 *  2. **Guess who among your people is closest**, from what is genuinely known about them: their
 *     employer, their role, their industry. Presented as a guess, ranked, with the reasoning shown.
 *  3. **Ask two or three of them**, each on a tap, never automatically.
 *  4. **Read the replies.** A reply naming somebody becomes the next hop. A no prunes that branch.
 *     The path emerges from real answers rather than being asserted up front, which is the only
 *     version of this that is not fiction.
 *
 * Every step is a confirmation. Nothing is sent, and nothing advances, without a tap — because this
 * feature spends the owner's social capital, which is the most expensive thing the app touches.
 */
object IntroQuest {

    private const val PREFS = "slyos_quests"
    private const val KEY = "quests"

    enum class HopState { SUGGESTED, ASKED, HELPED, DEAD }

    data class Hop(
        val id: Long,
        val viaName: String,
        val viaKey: String,
        /** Why this person is a plausible bridge — shown, because a guess must be inspectable. */
        val why: String,
        val state: HopState,
        /** What they said, once they have answered. */
        val reply: String = "",
        /** Who they pointed you to — the next hop, discovered rather than predicted. */
        val nextName: String = "",
        /** Which round of the search this came from. Round 0 is your own network. */
        val depth: Int = 0,
        /** When the ask went out — the watermark for spotting their answer. */
        val askedAt: Long = 0L
    )

    data class Quest(
        val id: Long,
        val target: String,
        /** What the web says the target actually is. The basis for every guess below it. */
        val brief: String,
        val hops: List<Hop>,
        val createdAt: Long
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Quest> = try {
        val arr = JSONArray(p(ctx).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parse(it) } }
    } catch (e: Exception) { emptyList() }

    fun forTarget(ctx: Context, target: String): Quest? =
        all(ctx).firstOrNull { it.target.equals(target.trim(), true) }

    private fun parse(o: JSONObject): Quest {
        val hs = o.optJSONArray("hops")
        val hops = ArrayList<Hop>()
        if (hs != null) for (i in 0 until hs.length()) hs.optJSONObject(i)?.let { h ->
            hops.add(Hop(h.optLong("id"), h.optString("via"), h.optString("key"),
                h.optString("why"),
                runCatching { HopState.valueOf(h.optString("state")) }.getOrDefault(HopState.SUGGESTED),
                h.optString("reply"), h.optString("next"), h.optInt("depth"), h.optLong("askedAt")))
        }
        return Quest(o.optLong("id"), o.optString("target"), o.optString("brief"), hops,
            o.optLong("at"))
    }

    private fun save(ctx: Context, quests: List<Quest>) {
        val arr = JSONArray()
        quests.sortedByDescending { it.createdAt }.take(12).forEach { q ->
            val hs = JSONArray()
            q.hops.take(20).forEach { h ->
                hs.put(JSONObject().put("id", h.id).put("via", h.viaName).put("key", h.viaKey)
                    .put("why", h.why).put("state", h.state.name).put("reply", h.reply)
                    .put("next", h.nextName).put("depth", h.depth).put("askedAt", h.askedAt))
            }
            arr.put(JSONObject().put("id", q.id).put("target", q.target).put("brief", q.brief)
                .put("hops", hs).put("at", q.createdAt))
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun put(ctx: Context, q: Quest) {
        save(ctx, all(ctx).filterNot { it.id == q.id } + q)
    }

    fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filterNot { it.id == id })

    // MARK: - Step 1 — who is the target

    /**
     * What the web knows about them.
     *
     * Deliberately factual and short. The point is not a biography, it is the handful of facts that
     * make step 2 possible: where they are, what field, who they are around. A target described only
     * by the name typed into a box produces bridge guesses based on nothing.
     */
    fun resolveTarget(target: String): String = try {
        // The web answer comes back with **markdown** in it — "co-founder and CEO of **Stripe**" —
        // which renders as literal asterisks. Fourth place this defect has appeared: email drafts,
        // slides, documents, and now search results. Same stripper, applied at the fourth door.
        MailDraft.plain(AgentClient.webSearchText(
            "Who or what is \"$target\"? Answer in 3-4 sentences of plain fact: what they do, the " +
            "organisation they are at, their field or industry, the city if known, and any " +
            "well-known people, companies or institutions they are closely associated with. If " +
            "several people share the name, describe the most prominent and say so."))
    } catch (e: Exception) { "" }

    // MARK: - Step 2 — who of yours is closest

    /**
     * Rank your own people as possible bridges.
     *
     * The model is given ONLY a numbered list of your people with their role and employer, plus the
     * brief, and asked for indices and reasons. It never invents a name, because it is choosing from
     * a list rather than writing one — the same discipline as the CRM's own filter, and for the same
     * reason: a made-up contact in a directory is worse than no answer.
     *
     * Called speculative everywhere it is shown. It IS speculative, and dressing a guess as a
     * computed path would be the dishonest version of this feature.
     */
    fun suggestBridges(ctx: Context, target: String, brief: String, max: Int = 4): List<Hop> {
        // NOT EVERY "RECIPROCAL" CONTACT IS SOMEBODY WHO WOULD TAKE YOUR CALL.
        //
        // The first run suggested asking ELON MUSK for an introduction to Patrick Collison, reasoning
        // that he "likely knows" him — which is true and completely useless. He is in the book because
        // of a followed account on X, and reciprocity vouched for him because the app has posted
        // there. Suggesting it is exactly the failure the prompt below warns against: an implausible
        // ask costs a real favour and the owner's credibility.
        //
        // So a bridge has to be somebody reached on a channel where people actually talk to each
        // other. Broadcast platforms are excluded no matter what the message counts say.
        val broadcast = setOf("X", "Twitter", "Reddit", "TikTok")
        val book = Crm.peopleCached(ctx, 300)
            .filter { it.reciprocal && it.mainChannel !in broadcast }
            // Somebody you barely speak to will not spend a favour on you.
            .filter { it.totalMessages >= 8 }
            .sortedByDescending { it.totalMessages }.take(70)
        if (book.isEmpty()) return emptyList()
        val listing = book.mapIndexed { i, p ->
            "$i. ${p.name}" +
                (if (p.role.isNotBlank()) " — ${p.role.take(40)}" else "") +
                (if (p.company.isNotBlank()) " at ${p.company}" else "") +
                " · you talk on ${p.mainChannel}"
        }.joinToString("\n")

        val raw = try {
            AgentClient.complete(
                "You pick the most plausible introducers from a list. Output ONLY a JSON array of " +
                "{\"i\":<index>,\"why\":\"<one sentence, under 20 words, saying what makes them " +
                "plausible>\"}. Choose at most $max. Pick nobody rather than pad the list — an " +
                "implausible suggestion costs the user a favour and their credibility. If none are " +
                "plausible, output [].",
                "TARGET: $target\n\nWhat is known about the target:\n$brief\n\n" +
                "MY CONTACTS:\n$listing\n\n" +
                "Which of my contacts are most likely to know the target, or to know somebody who " +
                "does? Judge on their industry, employer and role against the target's. JSON only.",
                700)
        } catch (e: Exception) { "" }

        val out = ArrayList<Hop>()
        try {
            val arr = JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = book.getOrNull(o.optInt("i", -1)) ?: continue
                val why = o.optString("why").trim()
                if (why.isBlank()) continue
                out.add(Hop(System.currentTimeMillis() + i, p.name, p.key, why,
                    HopState.SUGGESTED, depth = 0))
            }
        } catch (e: Exception) {}
        return out.distinctBy { it.viaKey }.take(max)
    }

    // MARK: - Step 4 — read the reply, and extend the path

    data class Read(val helped: Boolean, val nextName: String, val note: String)

    /**
     * What their answer actually means.
     *
     * A reply is rarely a clean yes or no — "I don't know him but Priya used to work with him" is
     * both a no and the most useful thing that could have happened. So this looks for a NAME first
     * and treats finding one as the path continuing, regardless of how the sentence was phrased.
     */
    fun readReply(reply: String): Read {
        if (reply.isBlank()) return Read(false, "", "")
        val raw = try {
            AgentClient.complete(
                "You read a reply to an introduction request. Output ONLY JSON: " +
                "{\"helped\":true|false,\"next\":\"<the name of any person they pointed to, or " +
                "empty>\",\"note\":\"<one short sentence on what they said>\"}. " +
                "\"helped\" is true if they offered an introduction OR named somebody useful. " +
                "Never invent a name that is not in the reply.",
                "The reply:\n$reply\n\nJSON only.", 300)
        } catch (e: Exception) { "" }
        return try {
            val o = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            Read(o.optBoolean("helped", false), o.optString("next").trim(), o.optString("note").trim())
        } catch (e: Exception) {
            // No model, or unusable output: a name-shaped word is still better than dropping the reply.
            val n = Regex("\\b([A-Z][a-z]{2,})\\b").find(reply)?.groupValues?.get(1).orEmpty()
            Read(n.isNotEmpty(), n, "")
        }
    }

    /** Record an answer, and open the next hop when one was named. */
    fun recordReply(ctx: Context, questId: Long, hopId: Long, reply: String): Quest? {
        val q = all(ctx).firstOrNull { it.id == questId } ?: return null
        val read = readReply(reply)
        val hops = q.hops.toMutableList()
        val i = hops.indexOfFirst { it.id == hopId }
        if (i < 0) return q
        val was = hops[i]
        hops[i] = was.copy(
            state = if (read.helped) HopState.HELPED else HopState.DEAD,
            reply = reply.take(400), nextName = read.nextName)
        // THE PATH CONTINUING. A named person becomes the next step, one hop further out, and is
        // never auto-contacted — it is a suggestion for the owner to approve like the first round.
        if (read.nextName.isNotBlank() && hops.none { it.viaName.equals(read.nextName, true) }) {
            hops.add(Hop(System.currentTimeMillis(), read.nextName, "",
                "${was.viaName.split(' ').first()} pointed you to them" +
                    (if (read.note.isNotBlank()) " — ${read.note}" else ""),
                HopState.SUGGESTED, depth = was.depth + 1))
        }
        val updated = q.copy(hops = hops)
        put(ctx, updated)
        try {
            Brain.remember(ctx, "note", "Intro path to ${q.target}",
                "${was.viaName}: ${reply.take(200)}" +
                    (if (read.nextName.isNotBlank()) "\nPoints to: ${read.nextName}" else ""),
                actors = listOf(was.viaName), role = "system")
        } catch (e: Exception) {}
        return updated
    }

    fun stateLabel(s: HopState): String = when (s) {
        HopState.SUGGESTED -> "worth asking"
        HopState.ASKED -> "asked, waiting"
        HopState.HELPED -> "helped"
        HopState.DEAD -> "no route this way"
    }

    fun markAsked(ctx: Context, questId: Long, hopId: Long) {
        val q = all(ctx).firstOrNull { it.id == questId } ?: return
        put(ctx, q.copy(hops = q.hops.map {
            if (it.id == hopId) it.copy(state = HopState.ASKED,
                askedAt = System.currentTimeMillis()) else it
        }))
    }

    /**
     * Notice their answer, rather than asking to have it pasted in.
     *
     * The first version put a text box on the card labelled "what did they say?" — and nobody is ever
     * going to copy a WhatsApp reply out of WhatsApp and paste it into a graph screen. The app already
     * reads every message on the device; asking the owner to retype one is asking them to do the job
     * the app exists to do.
     *
     * So it watches the thread. Anything inbound from that person after the ask went out is their
     * answer, read once and folded into the path — and the hop only advances when something actually
     * arrived, so an unanswered ask stays honestly unanswered.
     */
    fun checkReplies(ctx: Context, questId: Long): Quest? {
        var q = all(ctx).firstOrNull { it.id == questId } ?: return null
        q.hops.filter { it.state == HopState.ASKED && it.askedAt > 0 }.forEach { hop ->
            val person = try { Crm.find(ctx, hop.viaName) } catch (e: Exception) { null }
            val handles = person?.identities?.map { it.handle }?.distinct()
                ?: listOf(hop.viaName)
            // Rows, not rendered lines — so "theirs" and "after the ask" are real fields rather
            // than something parsed out of a string prefix.
            val said = try {
                MessageStore.threadAcross(ctx, handles, 30)
                    .filter { !it.role.equals("me", true) && it.ts > hop.askedAt && it.body.length > 8 }
                    .maxByOrNull { it.ts }?.body.orEmpty()
            } catch (e: Exception) { "" }
            if (said.isNotBlank()) {
                val updated = recordReply(ctx, questId, hop.id, said.take(400))
                if (updated != null) q = updated
            }
        }
        return q
    }
}
