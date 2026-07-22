package com.agentos.shell.tools

import android.content.Context

/**
 * "DO I KNOW <NAME>?" — across EVERY place a person can exist.
 *
 * The bug this fixes: asking "do I have Randor?" searched only the Android contacts database, so SlyOS
 * answered a confident "No" about someone the user messages on WhatsApp. Most people you actually deal
 * with are never saved as phone contacts — they live in your message history, your LinkedIn network,
 * your CRM, or your calendar. Answering "no" from one narrow source is worse than not answering, because
 * it sounds authoritative and is wrong.
 *
 * This searches all five sources and reports WHERE each match came from, so the answer can be specific
 * ("not in your contacts, but you've messaged him on WhatsApp and he's in tonight's calendar").
 */
object PersonLookup {

    /** where: contacts · messages · network · crm · calendar */
    data class Match(val name: String, val where: String, val detail: String, val score: Int)

    /**
     * Every person matching [query], best first. Empty ONLY when the person genuinely appears nowhere —
     * which is the only case where "no" is an honest answer.
     */
    fun find(ctx: Context, query: String, limit: Int = 12): List<Match> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val out = ArrayList<Match>()

        // 1) Phone contacts — the only source the old code looked at.
        try {
            ContactsTool.findCandidates(ctx, query, 6).forEach {
                out.add(Match(it.name, "contacts", it.number, score(it.name, q) + 20))
            }
        } catch (e: Exception) {}

        // 2) MESSAGE HISTORY — WhatsApp/Telegram/SMS/email senders. This is where the missed match lived.
        try {
            MessageStore.topContacts(ctx, 400)
                .filter { it.first.lowercase().contains(q) }
                .take(6)
                .forEach { (name, count, platform) ->
                    out.add(Match(name, "messages",
                        "$count message(s)" + (if (platform.isNotBlank()) " on $platform" else ""),
                        score(name, q) + 18))
                }
        } catch (e: Exception) {}

        // 2b) DIRECT message search — finds someone with even ONE message (a fresh email/DM contact, e.g. a new
        // LinkedIn connection like Sharon) who won't appear in the top-contacts list of a large brain. This is
        // the gap that made recently-met people invisible even though they're in the brain.
        try {
            MessageStore.search(ctx, query, 12)
                .map { it.contact }.filter { it.isNotBlank() && it.lowercase().contains(q) }.distinct().take(4)
                .forEach { name -> if (out.none { it.name.equals(name, true) }) out.add(Match(name, "messages", "you've corresponded with them", score(name, q) + 16)) }
        } catch (e: Exception) {}

        // 3) LinkedIn / imported network.
        try {
            ConnectionStore.search(ctx, query, 6).forEach { c ->
                out.add(Match(c.name, "network",
                    listOfNotNull(c.role.takeIf { it.isNotBlank() }, c.company.takeIf { it.isNotBlank() })
                        .joinToString(" at ").ifBlank { c.source },
                    score(c.name, q) + 12))
            }
        } catch (e: Exception) {}

        // 4) CRM.
        try {
            LeadStore.all(ctx).filter { it.name.lowercase().contains(q) }.take(6).forEach { l ->
                out.add(Match(l.name, "crm",
                    listOfNotNull(l.role.takeIf { it.isNotBlank() }, l.company.takeIf { it.isNotBlank() },
                        l.email.takeIf { it.isNotBlank() }).joinToString(" · "),
                    score(l.name, q) + 14))
            }
        } catch (e: Exception) {}

        // 5) TOTAL RECALL — anyone whose name has been on your screen. This is the strongest signal of
        // all: if you were literally reading their WhatsApp chat, you obviously know them, regardless of
        // whether they were ever saved anywhere. Searched by NAME (BrainContext only ever searched recall
        // using the whole question, which rarely matches a bare name).
        try {
            if (MemoryStore.recallEnabled(ctx)) {
                InteractionStore.search(ctx, query, 8)
                    .groupBy { it.app }
                    .entries.take(4)
                    .forEach { (app, entries) ->
                        val when0 = entries.maxOfOrNull { it.time } ?: 0L
                        val ago = if (when0 > 0) friendly(System.currentTimeMillis() - when0) else ""
                        out.add(Match(query, "on-screen",
                            "seen in $app" + (if (ago.isNotBlank()) " $ago" else "") +
                                " (${entries.size} time" + (if (entries.size == 1) "" else "s") + ")",
                            30))
                    }
            }
        } catch (e: Exception) {}

        // 6) Calendar — someone you're literally meeting today counts as knowing them.
        try {
            if (CalendarTool.hasPermission(ctx)) {
                CalendarTool.upcoming(ctx).lines()
                    .filter { it.lowercase().contains(q) }
                    .take(4)
                    .forEach { line -> out.add(Match(query, "calendar", line.trim().take(120), 22)) }
            }
        } catch (e: Exception) {}

        // Merge duplicates across sources — one person, all the places they appear.
        return out.groupBy { it.name.lowercase().trim() }
            .map { (_, ms) ->
                val best = ms.maxByOrNull { it.score }!!
                val wheres = ms.map { it.where }.distinct()
                Match(best.name, wheres.joinToString("+"),
                    ms.joinToString(" · ") { "${it.where}: ${it.detail}" }.take(220),
                    ms.sumOf { it.score })
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun friendly(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 2 -> "just now"
            m < 60 -> "${m}m ago"
            m < 1440 -> "${m / 60}h ago"
            else -> "${m / 1440}d ago"
        }
    }

    private fun score(name: String, q: String): Int {
        val n = name.lowercase()
        return when {
            n == q -> 40
            n.startsWith(q) -> 30
            n.split(" ").any { it == q } -> 28
            n.contains(q) -> 15
            else -> 0
        }
    }

    /**
     * A short block for the model describing who this person is and where they're known from — so the
     * answer can never be a flat "no" when the person demonstrably exists somewhere.
     */
    fun brief(ctx: Context, query: String): String {
        val ms = find(ctx, query, 5)
        if (ms.isEmpty()) return ""
        return buildString {
            append("PEOPLE MATCHING \"").append(query).append("\" (found in your data — do NOT say you don't know them):\n")
            ms.forEach { append("• ").append(it.name).append(" — ").append(it.detail).append("\n") }
        }
    }

    /** Words that look like names but aren't — so we don't look up "Should" or "Monday". */
    private val NOT_NAMES = setOf(
        "i", "the", "a", "an", "is", "are", "do", "does", "did", "can", "could", "should", "would",
        "what", "when", "where", "who", "why", "how", "yes", "no", "ok", "okay", "hey", "hi",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december", "today", "tomorrow", "yesterday", "tonight",
        "whatsapp", "telegram", "gmail", "slyos", "linkedin", "instagram", "google")

    /**
     * Who is this question about? Tries explicit phrasings first ("do I have Randor"), then falls back to
     * any capitalised name in the sentence — because real questions rarely follow a template. The original
     * failure was phrased "there literally is... Randor.... I just checked myself in whatsapp", which no
     * question-shaped pattern would ever have matched.
     */
    fun subjectOf(question: String): String {
        Regex("(?i)\\b(?:do i (?:have|know)|who is|who's|anything from|heard from|talked to|spoke to|" +
            "message from|messaged|contact for|number for|email for|reach out to|text|call)\\s+" +
            "([A-Za-z][\\w'’-]{1,30})").find(question)
            ?.groupValues?.get(1)?.trim()
            ?.takeIf { it.lowercase() !in NOT_NAMES }
            ?.let { return it }
        // Fallback: a capitalised word that isn't a sentence-opener or a common word.
        val words = question.split(Regex("[^A-Za-z'’-]+")).filter { it.length in 2..30 }
        return words.drop(1)
            .firstOrNull { w -> w[0].isUpperCase() && w.lowercase() !in NOT_NAMES }
            .orEmpty()
    }
}
