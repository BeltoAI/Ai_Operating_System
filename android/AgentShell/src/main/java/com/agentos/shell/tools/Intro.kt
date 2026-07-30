package com.agentos.shell.tools

import android.content.Context

/**
 * "Who could introduce me to X?"
 *
 * The map was a beautiful diagram of things already known, which is the failure mode of every
 * relationship graph ever built. Nobody wonders what their network looks like. They want a path
 * through it, to somebody specific, and that is a shortest-path question rather than a picture.
 *
 * The asset that makes it answerable was already here and unused for this: **20,005 LinkedIn
 * connections**. Almost all of them are people never messaged — which is precisely what an
 * introduction is for. A CRM of people you already talk to cannot get you anywhere new; the value is
 * in the gap between "connected to" and "actually knows you".
 *
 * Three kinds of route, and the order matters because it is the order of usefulness:
 *
 *  1. **You already know somebody there.** No introduction needed at all — the most valuable answer
 *     is often that the question was unnecessary, and a tool that routes you around a door you can
 *     already open is wasting your time.
 *  2. **You are connected but have never spoken.** No warm intro required, just a message. This is
 *     the biggest bucket by far and the one nobody ever looks at, because 20,000 names is not
 *     browsable and until now was not queryable either.
 *  3. **A genuine two-hop.** Somebody you actually talk to shares an employer or a group chat with
 *     somebody at the target. This is the real introduction, and it is rarest — so it is worth being
 *     honest that it is rare rather than manufacturing weak paths to fill the screen.
 *
 * Every route names its evidence. "Ask Dan" is worthless without "Dan works there".
 */
object Intro {

    enum class Kind { DIRECT, CONNECTED, TWO_HOP }

    data class Route(
        val kind: Kind,
        /** Who to approach. For CONNECTED this is the target themselves. */
        val via: String,
        val viaKey: String,
        val viaRole: String,
        /** Who or what it reaches. */
        val reaches: String,
        val why: String,
        /** How well you actually know [via] — messages exchanged. Ranks the list. */
        val strength: Int
    )

    data class Answer(
        val target: String,
        val routes: List<Route>,
        /** How many people at the target you are connected to but have never messaged. */
        val connectedCount: Int
    )

    /**
     * Find the ways in.
     *
     * [query] is a company or a person — deliberately not distinguished, because nobody wants to
     * declare which they meant before asking, and the same word is often both ("Belto" is a company;
     * "Anthem" is a company; "Laurie" is a person).
     */
    fun pathsTo(ctx: Context, query: String): Answer {
        val q = query.trim()
        if (q.length < 2) return Answer(q, emptyList(), 0)
        val book = Crm.peopleCached(ctx, 400)
        val routes = ArrayList<Route>()

        // ── 1. People you actually talk to, who are at the target ──
        //
        // Reciprocity matters here more than anywhere: an intro request goes to somebody who will
        // answer it, and somebody who has never replied to you is not that person.
        book.filter { it.reciprocal }.forEach { p ->
            val atCompany = p.company.contains(q, true)
            val isThem = p.name.contains(q, true)
            if (atCompany || isThem) routes.add(Route(
                kind = Kind.DIRECT,
                via = p.name, viaKey = p.key, viaRole = p.role,
                reaches = if (isThem) p.name else p.company,
                why = when {
                    isThem -> "You already talk to them — ${p.mainChannel}, " +
                        (if (p.silentDays == 0) "today" else "${p.silentDays}d ago")
                    else -> "Works at ${p.company} · you talk on ${p.mainChannel}"
                },
                strength = p.totalMessages))
        }

        // ── 2. Connected, never messaged ──
        //
        // The 20,005. Searched rather than loaded, and matched on company as well as name so
        // "who do I know at Stripe" works — which is the question, not "find me a Stripe".
        /**
         * ALREADY-KNOWN, MATCHED ON THE PERSON RATHER THAN THE STRING.
         *
         * The Stanford result listed "Dan Goncharov" under people you talk to AND "Daniel Goncharov"
         * under connected-never-messaged — the same man, in two buckets, because this compared
         * lowercase names for exact equality. Dan is not the string Daniel.
         *
         * Same surname plus one first name being a prefix of the other is the shape of every
         * Dan/Daniel, Mike/Michael and Kate/Katherine. Deliberately narrow: both parts must agree, so
         * "Dan Goncharov" and "Dan Meyer" stay separate, which matters far more than tidiness.
         */
        fun parts(n: String): Pair<String, String> {
            val t = n.lowercase().trim().replace(Regex("[^a-z ]"), " ")
                .split(Regex("\\s+")).filter { it.length > 1 }
            return (t.firstOrNull().orEmpty()) to (t.lastOrNull().orEmpty())
        }
        val knownParts = book.map { parts(it.name) }
        fun alreadyKnown(name: String): Boolean {
            val (f, l) = parts(name)
            if (f.isEmpty()) return false
            return knownParts.any { (kf, kl) ->
                kl == l && kl.isNotEmpty() &&
                    (kf == f || kf.startsWith(f) || f.startsWith(kf))
            }
        }
        // PRECISION, BECAUSE A LOOSE MATCH IS WORSE THAN NO MATCH.
        //
        // Asking for "Patrick Collison" returned Patrick Shannon, Patrick Nogacz, Patrick Bangert,
        // Patrick Granzow and Patrick Tejada — every Patrick in a 20,005-row export, because the
        // underlying search matches any token. Five confident wrong answers, and worse: they counted
        // as routes, so the whole find-a-path plan for a genuine stranger never appeared. A
        // multi-word query has to match every word.
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }
        val connected = try { ConnectionStore.search(ctx, q, 60) } catch (e: Exception) { emptyList() }
            .filterNot { alreadyKnown(it.name) }
            .filter { c ->
                if (words.size < 2) true
                else {
                    val hay = (c.name + " " + c.company + " " + c.role)
                    words.all { hay.contains(it, true) }
                }
            }
        connected.take(8).forEach { c ->
            routes.add(Route(
                kind = Kind.CONNECTED,
                via = c.name, viaKey = "", viaRole = c.role,
                reaches = c.company.ifBlank { c.name },
                why = "Already connected on LinkedIn, never messaged" +
                    (if (c.company.isNotBlank()) " · ${Crm.tidyCompany(c.company)}" else ""),
                strength = 0))
        }

        // ── 3. Two hops ──
        //
        // Somebody you talk to who shares an employer or a group chat with somebody at the target.
        // Built from the same proven edges the map draws, so a route here is never an inference the
        // graph itself would not stand behind.
        try {
            val g = RelationGraph.build(ctx, 200)
            val atTarget = g.people.filter {
                it.company.contains(q, true) || it.name.contains(q, true)
            }.map { it.key }.toSet()
            if (atTarget.isNotEmpty()) {
                g.edges.forEach { e ->
                    val (mine, theirs) = when {
                        e.a in atTarget && e.b !in atTarget -> e.b to e.a
                        e.b in atTarget && e.a !in atTarget -> e.a to e.b
                        else -> return@forEach
                    }
                    val me = book.firstOrNull { it.key == mine && it.reciprocal } ?: return@forEach
                    val them = g.people.firstOrNull { it.key == theirs } ?: return@forEach
                    // Not a route if the direct answer already covers it.
                    if (routes.any { it.viaKey == me.key }) return@forEach
                    routes.add(Route(
                        kind = Kind.TWO_HOP,
                        via = me.name, viaKey = me.key, viaRole = me.role,
                        reaches = them.name,
                        why = "${me.name.split(' ').first()} → ${them.name} — ${e.why}",
                        strength = me.totalMessages))
                }
            }
        } catch (e: Exception) {}

        // Best route first: someone you know well beats someone you barely know, and a real
        // relationship beats a dormant connection.
        val ranked = routes
            .distinctBy { it.kind to it.via }
            .sortedWith(compareBy({ it.kind.ordinal }, { -it.strength }))
            .take(14)
        return Answer(q, ranked, connected.size)
    }

    fun kindLabel(k: Kind): String = when (k) {
        Kind.DIRECT -> "You know them"
        Kind.CONNECTED -> "Connected, never messaged"
        Kind.TWO_HOP -> "Could introduce you"
    }

    /**
     * The line to put in front of someone, when the route is an actual introduction.
     *
     * Asking for an intro is a small social transaction and most people write it badly — too long,
     * too vague about what they want, and no easy way for the other person to say no. So the draft
     * is short, names the person, says what it is about, and leaves the door open.
     */
    fun askPrompt(ctx: Context, r: Route, about: String): String = buildString {
        append("Write a short message to ").append(r.via)
        append(" asking for an introduction to ").append(r.reaches).append(".\n\n")
        append("Why they can help: ").append(r.why).append("\n")
        if (about.isNotBlank()) append("What it is about: ").append(about).append("\n")
        try {
            val hist = PersonResolver.historyFor(ctx, r.via.split(' ').first(), 6)
            if (hist.isNotBlank())
                append("\nHow you two normally speak — match it:\n").append(hist.take(800)).append("\n")
        } catch (e: Exception) {}
        append("\nKeep it to three or four sentences. Say plainly what you want and why them. ")
        append("Make it easy to decline — an intro request that reads as an obligation gets ignored. ")
        append("No flattery, no preamble about hoping they are well. Return ONLY the message.")
    }
}
