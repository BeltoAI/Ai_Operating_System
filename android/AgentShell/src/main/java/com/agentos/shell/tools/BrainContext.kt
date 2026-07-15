package com.agentos.shell.tools

import android.content.Context

/**
 * THE single source of truth for what the brain knows on any given request.
 *
 * Every answer path — Home AI, Conversation mode, and (via [profileBlock]) Memory search — pulls
 * its context from here, so they can never drift apart. If a piece of the user's life should be
 * knowable, it belongs in this function. The rule is simple: the brain knows everything about the
 * user, and every surface reads from the same place.
 */
object BrainContext {

    /**
     * The always-on core of who the user is: contact details (address/email/phone), the About text,
     * learned facts, LinkedIn work history + education. This must be present in EVERY answer,
     * regardless of the question, so "what's my address / email / phone" always works.
     */
    fun profileBlock(ctx: Context): String = MemoryStore.fullProfile(ctx)

    /**
     * P4: rank+dedupe recall. Merges semantic (VectorStore, real cosine score) and keyword (MessageStore)
     * hits, weights semantic higher, dedupes by normalized text keeping the best score, and fills a char
     * budget best-first — so the most relevant memory is guaranteed into the prompt (no fixed truncation).
     */
    private fun rankedRecall(ctx: Context, q: String, budgetChars: Int): String {
        data class Cand(val text: String, val score: Float)
        val cands = ArrayList<Cand>()
        val dfmt = java.text.SimpleDateFormat("MMM d yyyy", java.util.Locale.getDefault())
        fun fmt(role: String, contact: String, body: String, ts: Long = 0L) =
            (if (ts > 0) "[" + dfmt.format(java.util.Date(ts)) + "] " else "") +
            (if (role == "me") "you→$contact" else contact) + ": " + body.trim()
        try { VectorStore.search(ctx, q, 8).forEach { cands.add(Cand(fmt(it.role, it.contact, it.body), it.score)) } } catch (e: Exception) {}
        try { MessageStore.search(ctx, q, 8).forEach { cands.add(Cand(fmt(it.role, it.contact, it.body, it.ts), 0.62f)) } } catch (e: Exception) {}
        if (cands.isEmpty()) return ""
        // Dedupe by normalized text, keeping the highest score.
        val best = HashMap<String, Cand>()
        for (c in cands) {
            val key = c.text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            if (key.length < 3) continue
            val prev = best[key]
            if (prev == null || c.score > prev.score) best[key] = c
        }
        val sb = StringBuilder(); var used = 0
        for (c in best.values.sortedByDescending { it.score }) {
            val line = c.text.take(280)
            if (used + line.length + 3 > budgetChars) continue
            sb.append("• ").append(line).append("\n"); used += line.length + 3
        }
        return sb.toString().trim()
    }

    /**
     * Full retrieval context for a specific query: the profile block plus everything relevant the
     * brain has stored — calendar, semantic + keyword message recall, network, papers, loaded docs,
     * on-screen recall, checklist, mission, portfolio, jobs, and the current time.
     */
    fun build(ctx: Context, q: String): String {
        val mem = profileBlock(ctx)
        val cal = CalendarTool.upcoming(ctx)
        val now = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val recall = if (MemoryStore.recallEnabled(ctx)) InteractionStore.retrieve(ctx, q, 10) else ""
        // P4: merge keyword + semantic hits into ONE ranked, deduped list, best-first, filled to a token
        // budget — so the single most relevant memory always survives instead of being truncated away.
        val ranked = rankedRecall(ctx, q, budgetChars = 1800)
        val net = ConnectionStore.search(ctx, q, 6)
            .joinToString(" · ") { it.name + (if (it.role.isNotBlank()) " (${it.role})" else "") + (if (it.company.isNotBlank()) " @ ${it.company}" else "") }
            .take(800)
        val papers = PaperStore.libraryContext(ctx, 0L, q, 900)
        val paperList = if (Regex("paper|whitepaper|white ?paper|research|document|wrote|writ|publish|essay|report|zenodo|doi", RegexOption.IGNORE_CASE).containsMatchIn(q))
            PaperStore.list(ctx).joinToString("\n") { "- “${it.title}” (${it.docType})" } else ""
        val docText = if (KnowledgeStore.hasDoc(ctx)) KnowledgeStore.retrieve(ctx, q, 1000) else ""
        // Checklist: pull tasks in whenever the question is about them, or matches task text.
        val terms = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        val taskQuery = Regex("task|to-?do|checklist|errand|chore|remind|due|outstanding|pending", RegexOption.IGNORE_CASE).containsMatchIn(q)
        val tasks = if (taskQuery || terms.isNotEmpty())
            ChecklistStore.load(ctx).filter { t -> taskQuery || terms.any { t.text.lowercase().contains(it) } }
                .joinToString(" · ") { it.text + (if (it.done) " (done)" else "") }.take(600) else ""
        // Recency question ("who did I email/message/text last?") → the keyword index can't answer it, so
        // pull the actual most-recent messages you SENT (optionally scoped to a platform) straight from the DB.
        val sentQuery = Regex("(?i)\\b(sent|send|email(?:ed|s)?|messag(?:e|ed|es)|text(?:ed|s)?|wrote|dm(?:ed|s)?|reach(?:ed)? out|last .*(email|message|text)|who did i|who have i|recent(ly)? (email|messag|text|sent))\\b").containsMatchIn(q)
        val sent = if (sentQuery) {
            val plat = when {
                Regex("(?i)email|gmail|mail").containsMatchIn(q) -> "Email"
                Regex("(?i)whatsapp").containsMatchIn(q) -> "WhatsApp"
                Regex("(?i)telegram").containsMatchIn(q) -> "Telegram"
                else -> null
            }
            MessageStore.recentSent(ctx, 8, plat).joinToString("\n").take(900)
        } else ""
        // Finance question ("how much did I spend…", "spending review") → inject REAL totals from the
        // receipt ledger (this month), gated so it never bloats unrelated prompts.
        val financeQuery = Regex("(?i)\\b(spen[dt]|spending|expense|expenditure|budget|receipt|how much (did|have) i|money (go|going)|cost me|this month.*spend)\\b").containsMatchIn(q)
        val expenses = if (financeQuery && ExpenseStore.count(ctx) > 0) {
            val (from, to) = ExpenseStore.rangeFor("this month")
            "This month — " + ExpenseStore.summaryText(ctx, from, to)
        } else ""
        // Date questions ("what did I do yesterday / this week / last Tuesday") → pull everything that
        // flowed through the brain in that window, with times, so the model can answer by date.
        val win = dateWindow(q)
        val tf = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
        val dayLog = if (win != null) MessageStore.between(ctx, win.first, win.second).joinToString("\n") {
            "[" + tf.format(java.util.Date(it.ts)) + "] " + (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body.trim()
        }.take(1700) else ""

        // Photos in the brain: how many are described, and any that match this request by meaning. Lets the
        // AI answer "what pictures do I have of…" and find images to send/edit — the brain grows with photos.
        val photoCount = try { PhotoIndex.count(ctx) } catch (e: Exception) { 0 }
        val photoHits = if (photoCount > 0) try {
            PhotoIndex.search(ctx, q, 4).joinToString("\n") { "• ${it.name} (${it.where})" }
        } catch (e: Exception) { "" } else ""

        return buildString {
            if (mem.isNotBlank()) append(mem)
            if (photoCount > 0) append("\nYou have ").append(photoCount)
                .append(" photos described in your brain; you can find pictures by describing them (e.g. \"a cute selfie\").")
            if (photoHits.isNotBlank()) append("\nPhotos that match this request:\n").append(photoHits)
            if (cal.isNotBlank()) append("\nUpcoming calendar:\n").append(cal)
            if (sent.isNotBlank()) append("\nThe most recent messages YOU sent (newest first — use these to answer who/what you last sent):\n").append(sent)
            if (expenses.isNotBlank()) append("\nYour real spending from tracked receipts (use these EXACT numbers for money questions):\n").append(expenses)
            if (dayLog.isNotBlank()) append("\nWhat flowed through your brain in the time window you asked about (newest first, with times — use these to answer the date question):\n").append(dayLog)
            if (ranked.isNotBlank()) append("\nMost relevant memories (ranked best-first — the top lines matter most):\n").append(ranked)
            if (net.isNotBlank()) append("\nFrom your contacts/network (use ONLY if relevant):\n").append(net)
            if (paperList.isNotBlank()) append("\nYour research papers (these are the papers you have):\n").append(paperList)
            if (papers.isNotBlank()) append("\nFrom your own research papers (use ONLY if relevant):\n").append(papers)
            if (docText.isNotBlank()) append("\nFrom your loaded document (use ONLY if relevant):\n").append(docText)
            if (tasks.isNotBlank()) append("\nYour checklist/tasks (use if relevant):\n").append(tasks)
            if (recall.isNotBlank()) append("\nFrom what I've seen on your screen (use ONLY if relevant to the request):\n").append(recall)
            MissionStore.mission(ctx).takeIf { it.isNotBlank() }?.let {
                append("\nYOUR STANDING MISSION (you are acting as this person; keep this goal in mind and, when relevant, proactively suggest concrete next steps toward it): ").append(it)
            }
            TradeStore.summary(ctx).takeIf { it.isNotBlank() }?.let {
                append("\n").append(it).append(" (When the user asks about investing/their portfolio/how it's doing, use these real numbers; they can manage it on the Invest screen.)")
            }
            JobStore.summary(ctx).takeIf { it.isNotBlank() }?.let {
                append("\n").append(it).append(" (Use this when the user asks what jobs they applied to or prepared.)")
            }
            append("\nCurrent time: ").append(now)
        }
    }

    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** Parse a date window from a question ("yesterday", "this week", "last Tuesday"…). null if none. */
    private fun dateWindow(q: String): Pair<Long, Long>? {
        val ql = q.lowercase()
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        val startToday = c.timeInMillis
        val day = 24L * 60 * 60 * 1000
        return when {
            Regex("\\byesterday\\b").containsMatchIn(ql) -> (startToday - day) to startToday
            Regex("\\btoday\\b").containsMatchIn(ql) -> startToday to (startToday + day)
            Regex("\\blast week\\b").containsMatchIn(ql) -> (startToday - 14 * day) to (startToday - 7 * day)
            Regex("\\bthis week\\b").containsMatchIn(ql) -> (startToday - 7 * day) to (startToday + day)
            Regex("\\b(this|last|past) month\\b").containsMatchIn(ql) -> (startToday - 31 * day) to (startToday + day)
            else -> {
                val idx = WEEKDAYS.indexOfFirst { Regex("\\b(on |last )?$it\\b").containsMatchIn(ql) }
                if (idx < 0) null else {
                    val todayDow = c.get(java.util.Calendar.DAY_OF_WEEK) - 1   // 0=Sun..6=Sat
                    var back = (todayDow - idx + 7) % 7
                    if (back == 0) back = 7
                    val start = startToday - back * day
                    start to (start + day)
                }
            }
        }
    }
}
