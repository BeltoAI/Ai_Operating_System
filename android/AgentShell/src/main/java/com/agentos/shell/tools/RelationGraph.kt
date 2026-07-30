package com.agentos.shell.tools

import android.content.Context

/**
 * Who knows whom, and how you know that.
 *
 * The CRM answers "who is this person". It cannot answer the question that actually gets things done,
 * which is "who do I know who knows them" — and every edge needed to answer it was already on the
 * device, unread.
 *
 * The design changed once the real data was looked at. The obvious approach — scan message bodies for
 * one contact's name appearing in another's thread — produced 1,798 edges of almost pure noise,
 * because message bodies are full of system text: the strongest edge in the whole graph was
 * "Carlos — Post sent" with 1,880 hits, evidenced by the immortal sentence "Carlos sent an
 * attachment." Meanwhile the thing sitting in plain sight was a contact literally named
 * **"Oliver, Carlos XOG and 2 others"** — a group chat, which is an explicit, unambiguous statement
 * that those people know each other and know you. It had been classified as a junk contact and
 * discarded. The best edge source in the dataset was being thrown away as noise.
 *
 * So, four sources, strongest first, each carrying the evidence that produced it — because "these
 * two are connected" is worth nothing without "how", and a graph you cannot interrogate is a
 * decoration:
 *
 *  1. **A group chat.** Everyone in it knows everyone else in it. No inference at all.
 *  2. **The same employer**, from the CRM and the LinkedIn export.
 *  3. **A shared surname**, which is family often enough to be worth showing and never asserted
 *     as fact — it says "possibly family", because a shared surname is also a coincidence.
 *  4. **One named in the other's conversation**, and only in a sentence a human wrote — every
 *     system line and notification body is excluded, which is what made source 4 usable at all.
 */
object RelationGraph {

    data class Edge(
        val a: String,          // person key
        val b: String,
        val kind: String,       // group · company · family · mention
        val weight: Int,
        /** Why these two are joined, in words, for the tap. */
        val why: String
    )

    data class Graph(val people: List<Crm.Person>, val edges: List<Edge>) {
        fun edgesFor(key: String) = edges.filter { it.a == key || it.b == key }
        fun degree(key: String) = edges.count { it.a == key || it.b == key }
    }

    /** Bodies that no human wrote — the reason source 4 was unusable before. */
    private val SYSTEM_BODY = Regex("(?i)(sent an attachment|welcome back to|sign in to|" +
        "liked your|reacted to|shared a (post|reel|story)|sent a (voice|photo|video|sticker)|" +
        "missed (call|video)|this message was deleted|view once|" +
        "you (sent|shared) an|started following|is now following|mentioned you in|" +
        "tagged you|replied to your (story|status)|joined using|changed the (subject|group)|" +
        "unsubscribe|view in browser|no longer wish to receive)")

    /**
     * The members named in a group-chat title.
     *
     * "Anni Kroete, Marcelschmitz, Philipp and 11 others" names three people and admits to eleven
     * more. " and N others" is the reliable marker; a bare comma is not, because LinkedIn writes
     * "Angélica S. Gutiérrez, Ph.D." and a title like "DMV Requires 11,000 Drivers to Retake Test"
     * splits into nonsense. Suffixes are stripped rather than counted as members.
     */
    private val SUFFIX = Regex("(?i)^(mba|emba|phd|ph\\.?d|m\\.?a|m\\.?s|md|jd|cfa|pmp®?|" +
        "gcerts?|gcp|pbc|inc|llc|ltd|founder|ceo|cto|cpa|rn|esq)\\.?$")

    fun groupMembers(title: String): List<String> {
        val t = title.trim()
        val hasOthers = Regex("(?i)\\band\\s+\\d+\\s+others?$").containsMatchIn(t)
        val body = t.replace(Regex("(?i)\\s*and\\s+\\d+\\s+others?$"), "")
        val parts = body.split(Regex(",|\\band\\b"))
            .map { it.trim() }
            .filter { it.isNotBlank() && !SUFFIX.matches(it) && it.length in 2..40 &&
                      !it.any { c -> c.isDigit() } }
        // Two named members plus the "and N others" marker, or three named outright — below that it
        // is far more likely to be one person with a qualification after their name.
        return if (hasOthers && parts.size >= 2 || parts.size >= 3) parts else emptyList()
    }

    /**
     * Build the graph from the snapshot, which is why this is fast enough to open a screen with.
     *
     * Never resolves the book itself — that costs fifteen seconds and is the whole reason the
     * snapshot exists.
     */
    fun build(ctx: Context, max: Int = 220): Graph {
        val people = Crm.peopleCached(ctx, max)
        if (people.isEmpty()) return Graph(emptyList(), emptyList())

        val keyByName = HashMap<String, String>()      // lowercase token → person key, if unambiguous
        val ambiguous = HashSet<String>()
        people.forEach { p ->
            p.name.split(Regex("[^\\p{L}]+")).forEach { tok ->
                val t = tok.lowercase()
                if (t.length >= 4) {
                    val prev = keyByName[t]
                    if (prev == null) keyByName[t] = p.key
                    else if (prev != p.key) ambiguous.add(t)
                }
            }
        }
        ambiguous.forEach { keyByName.remove(it) }

        val edges = HashMap<Pair<String, String>, Edge>()
        fun add(a: String, b: String, kind: String, why: String, w: Int = 1) {
            if (a == b) return
            val k = if (a < b) a to b else b to a
            val cur = edges[k]
            // A stronger KIND always wins the explanation: a group chat is a better answer than a
            // shared surname, even if the surname was found first.
            val rank = mapOf("group" to 4, "company" to 3, "family" to 2, "mention" to 1)
            edges[k] = when {
                cur == null -> Edge(k.first, k.second, kind, w, why)
                (rank[kind] ?: 0) > (rank[cur.kind] ?: 0) ->
                    cur.copy(kind = kind, why = why, weight = cur.weight + w)
                else -> cur.copy(weight = cur.weight + w)
            }
        }

        // ── 1. Group chats ──
        //
        // Read from the raw contact names, NOT from the book — the book drops group titles as fake
        // people, which is right, and they are the best edge source in the dataset, which is why
        // they are read here instead of being thrown away.
        val groupTitles = try {
            MessageStore.allContacts(ctx, 4000).filter {
                Regex("(?i)\\band\\s+(\\d+\\s+)?others?$|^[^,]{2,30},[^,]{2,30},").containsMatchIn(it)
            }
        } catch (e: Exception) { emptyList() }
        groupTitles.forEach { title ->
            val members = groupMembers(title)
            if (members.size < 2) return@forEach
            val keys = members.mapNotNull { m ->
                keyByName[m.lowercase().split(Regex("[^\\p{L}]+")).firstOrNull { it.length >= 4 }.orEmpty()]
            }.distinct()
            for (i in keys.indices) for (j in i + 1 until keys.size)
                add(keys[i], keys[j], "group", "In a group chat with you — “${title.take(46)}”", 3)
        }

        // ── 2. Same employer ──
        people.filter { it.company.length >= 3 }.groupBy { it.company.lowercase() }
            .forEach { (_, group) ->
                if (group.size < 2 || group.size > 25) return@forEach   // 25+ is a domain, not a team
                for (i in group.indices) for (j in i + 1 until group.size)
                    add(group[i].key, group[j].key, "company", "Both at ${group[i].company}", 2)
            }

        // ── 3 AND 4 WERE MEASURED, AND CUT ──
        //
        // A SHARED SURNAME was going to say "possibly family". Measured against the real book, three
        // of its four edges were wrong: "Alibaba Cloud ↔ Google Cloud — possibly family, both
        // Cloud", "Belto Bastards ↔ Drug Bastards", and two notification titles that both happened
        // to end in the owner's own surname. A shared last TOKEN is not a shared surname, and only
        // one edge in four was a real family link. A graph that tells you two companies might be
        // related is worse than a graph that says nothing.
        //
        // ONE NAMED IN THE OTHER'S CONVERSATION produced 92 edges after every filter I could think
        // of — system bodies excluded, a repetition floor, unambiguous names only — and they were
        // still almost all junk: "Angel Launch ↔ Elon Musk", "Angel Launch ↔ Constant Contact". A
        // co-mention is evidence that two names appeared near each other, which is not evidence that
        // two people know each other, and no amount of filtering turned one into the other.
        //
        // What is left is small and true: a group chat, which is an explicit statement, and a shared
        // employer, whose seven edges were all correct — three people at Stanford and three at
        // Belto, which is exactly what you would want when asking who you know where. Better a
        // sparse graph you can trust than a dense one you cannot.

        return Graph(people, edges.values.sortedByDescending { it.weight })
    }

    fun kindLabel(kind: String): String =
        if (kind == "group") "Group chat" else "Colleagues"

    /** The graph as prose, so the brain can answer "who do I know who knows Laurie". */
    fun brainBlock(g: Graph, key: String, limit: Int = 6): String {
        val name = g.people.firstOrNull { it.key == key }?.name ?: return ""
        val es = g.edgesFor(key).take(limit)
        if (es.isEmpty()) return ""
        return buildString {
            append("Who else you know who is connected to ").append(name).append(":\n")
            es.forEach { e ->
                val otherKey = if (e.a == key) e.b else e.a
                val other = g.people.firstOrNull { it.key == otherKey }?.name ?: return@forEach
                append("· ").append(other).append(" — ").append(e.why).append("\n")
            }
        }
    }
}
