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
     * WHAT SLYOS CAN ACTUALLY DO RIGHT NOW, with live connection state.
     *
     * Asked to schedule a call and send a Meet link, Home AI answered "No — I genuinely can't do it. I don't
     * have the ability to connect to Google Calendar or create Meet links. You have to do it manually." Google
     * was connected, the calendar permission was granted, and the planner emits exactly the right action for
     * that request. The refusal came from the ANSWER path, whose prompt describes memory and web search and
     * never mentions that this app operates the phone — so when a request phrased as a question routes there
     * instead of to the planner, it denies capabilities the product ships.
     *
     * That is the same failure as an agent inventing integrations it doesn't have, pointed the other way, and
     * it is worse: it teaches the owner a working feature is missing. Both directions need ONE honest list,
     * built from live state rather than from either prompt's imagination — which is why connection status is
     * read here and not hardcoded.
     */
    fun capabilities(ctx: Context): String {
        val google = try { GoogleAuth.isConnected(ctx) } catch (e: Exception) { false }
        val cal = try { CalendarTool.hasPermission(ctx) } catch (e: Exception) { false }
        val screen = try { com.agentos.shell.tools.TapSend.available() } catch (e: Exception) { false }
        val telegram = try { TelegramClient.configured() } catch (e: Exception) { false }
        return buildString {
            append("\nWHAT YOU CAN ACTUALLY DO (you are an agent that operates this phone — never tell them you ")
            append("cannot do something on this list; say you're doing it and it gets carried out):\n")
            append("• Calendar: create, move and cancel events, invite attendees by email")
            append(if (google && cal) " — Google IS connected, so you CAN create real events WITH Google Meet links.\n"
                   else if (cal) " — on-device calendar only; Google is NOT connected, so no Meet links until they connect it in Settings.\n"
                   else " — calendar permission is OFF; ask them to grant it in Settings.\n")
            append("• Email: read their mail and send email")
            append(if (google) " — Gmail IS connected.\n" else " — needs Google connected in Settings first.\n")
            append("• Messages: send SMS, WhatsApp, Telegram, Instagram and LinkedIn messages, and reply to notifications in their voice.\n")
            append("• Documents: generate real PDFs, slide decks, and spreadsheets from a brief, grounded in their data.\n")
            append("• Phone control: open and operate any app on screen, tap, type and navigate")
            append(if (screen) ".\n" else " — accessibility is currently OFF, so this needs turning on in Settings.\n")
            append("• Time: alarms, timers and reminders that really fire.\n")
            append("• Also: web search, camera/vision, reading and filing documents and receipts")
            append(if (telegram) ", and a Telegram bot interface.\n" else ".\n")
            append("If a request needs something switched on, say exactly which switch — never a flat \"I can't\".\n")
            // GUARD THE OTHER DIRECTION. Told what it can do, the answer path immediately over-corrected to
            // "Done. Here's what was set up." — but this path only ANSWERS; the planner is what executes.
            // A false completion is worse than the false refusal it replaced: the owner stops checking.
            // "Was the invite sent to Joslyn?" → a confident yes. She never received it. The brain held a
            // memory saying the invite went out, so the answer was honest and completely wrong. Your own
            // past sentences are stored in this brain alongside real action records and look identical at
            // retrieval time — a promise reads exactly like a receipt. Answering a did-this-happen question
            // from a promise is how the owner ends up trusting something that never occurred.
            append("WHAT COUNTS AS PROOF THAT SOMETHING HAPPENED: only a RECORD OF THE ACTION — a calendar, ")
            append("outbox, document or message entry describing the completed action, with its details. ")
            append("A message where you SAID you would do something, or described yourself doing it, is NOT ")
            append("proof and never becomes proof by being repeated. When asked whether something was ")
            append("actually sent, invited, scheduled, emailed or created, look for the action record. If ")
            append("there isn't one, say plainly that you can't confirm it happened and tell them how to ")
            append("check — never answer yes because you find yourself having promised it. Being wrong here ")
            append("costs them a missed meeting and their trust in every other answer you give. ")
            append("BUT NEVER CLAIM SOMETHING IS ALREADY DONE. You are the answering path — the action layer ")
            append("carries these out separately. Say what you're doing or about to do (\"creating that event ")
            append("now, inviting them with a Meet link\"), never \"Done\", never \"here's what was set up\", ")
            append("and never invent a confirmation, link, or invite you have not been shown.\n")
        }
    }

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
        // Pull MORE candidates than we can show and let ranking decide — 8+8 was too tight to survive
        // dedupe, so a good memory could be crowded out by near-duplicates before it was ever considered.
        try { VectorStore.search(ctx, q, 40).forEach { cands.add(Cand(fmt(it.role, it.contact, it.body), it.score)) } } catch (e: Exception) {}
        // Keyword hits used to get a FLAT 0.62 — higher than most genuine semantic matches, so exact-word
        // noise consistently outranked true meaning matches. Score them by how much of the query they
        // actually contain, capped below a strong semantic hit.
        try {
            val terms = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.distinct()
            MessageStore.search(ctx, q, 60).forEach { h ->
                val low = h.body.lowercase()
                val hit = if (terms.isEmpty()) 0 else terms.count { low.contains(it) }
                val frac = if (terms.isEmpty()) 0f else hit.toFloat() / terms.size
                cands.add(Cand(fmt(h.role, h.contact, h.body, h.ts), 0.35f + 0.30f * frac))
            }
        } catch (e: Exception) {}
        if (cands.isEmpty()) {
            try { Fail.log(ctx, "Brain", "recall for \"${q.take(40)}\"",
                "NOTHING found by meaning or keyword across the whole brain", "warn") } catch (e: Exception) {}
            return ""
        }
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
            val line = c.text.take(400)
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
        val tBuild = System.currentTimeMillis()
        // HEALTH NUMBERS, WHEREVER THE QUESTION IS ASKED.
        //
        // Every path that answers anything comes through here — Home, the Telegram bot, the team
        // agents, Research. Putting the vitals only in the Health screen's own ask box would mean
        // the assistant knew less than one of its own pages, and "how did I sleep this week?" typed
        // on Home would be answered from a vague memory of a summary rather than from the series.
        //
        // Gated on the question so the ordinary path pays nothing: this block is a few hundred
        // characters of a context window that is already contested.
        val vitals = try {
            if (VitalsInsight.isHealthQuestion(q) && VitalsStore.present(ctx).isNotEmpty())
                VitalsInsight.contextFor(ctx, q) else ""
        } catch (e: Exception) { "" }
        // CAP THE PROFILE. Measured on a real device: profileBlock alone was 27,490 characters, and it is
        // emitted FIRST — while answerWell() truncates the whole context at 20,000. The settings card was
        // therefore consuming the entire window before a single remembered message, relationship line, or
        // calendar entry was reached, and everything else was silently discarded. That is exactly, and
        // literally, "the AI only knows what's in my characteristics card".
        // The identity essentials (name, contact details, about-me, learned facts) lead this block, so a cap
        // keeps what every answer needs and drops the long LinkedIn work-history tail that no single question
        // ever needed in full.
        // WHO PEOPLE ARE, WHEREVER THE QUESTION IS ASKED.
        //
        // The CRM resolved one human out of ten scattered rows and then only the CRM screen could
        // read it, so "what's Joslyn's Instagram" was unanswerable from Home while the answer sat
        // in the same database. Gated on the question, exactly like the vitals block, so an ordinary
        // question pays nothing for it.
        val crm = try {
            if (Crm.isPeopleQuestion(ctx, q)) Crm.contextFor(ctx, q) else ""
        } catch (e: Exception) { "" }
        val mem = profileBlock(ctx).take(9000)
        val tProfile = System.currentTimeMillis()
        val cal = CalendarTool.upcoming(ctx)
        // AUTHORITATIVE EVENT FACTS, FETCHED WHEN THE QUESTION IS ABOUT THEM.
        // "Was the invite sent to Joslyn?" was answered from memory and came back a confident yes for an
        // invite that Google's own record shows went to nobody (attendees: 0). Memory cannot settle that
        // question — it holds what the app SAID as readily as what it DID, and the two are indistinguishable
        // at retrieval time. When the owner asks who was invited, who replied, or whether something actually
        // went out, fetch the event from Google and answer from that. Gated on the question so the ordinary
        // path pays nothing.
        val asksAboutInvites = Regex("(?i)\\b(invite[ds]?|invitation|attendee|rsvp|accepted|declined|" +
            "confirm(ed)?|did .{0,20}(get|receive)|was .{0,20}sent|send out|going to attend|meet link|" +
            "google meet)\\b").containsMatchIn(q)
        val eventFacts = if (asksAboutInvites && GoogleAuth.isConnected(ctx)) try {
            GoogleCalendarClient.findEvents(ctx).take(8).joinToString("\n") { e ->
                "• \"${e.title}\" ${e.startIso.take(16)} — " +
                (if (e.attendees.isEmpty()) "NOBODY IS INVITED (attendee list is empty — no invitation was sent to anyone)"
                 else "invited: " + e.attendees.joinToString(", ") { a ->
                     a.email + " (" + when (a.responseStatus) {
                         "accepted" -> "accepted"; "declined" -> "DECLINED"
                         "tentative" -> "maybe"; else -> "no reply yet" } + ")" }) +
                (if (e.meetLink.isNotBlank()) " · Meet link: ${e.meetLink}" else " · no Meet link on it")
            }
        } catch (e: Exception) { "" } else ""
        android.util.Log.i("SlyOS-Perf", "profile ${tProfile - tBuild}ms · calendar ${System.currentTimeMillis() - tProfile}ms")
        val now = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val recall = if (MemoryStore.recallEnabled(ctx)) InteractionStore.retrieve(ctx, q, 10) else ""
        // P4: merge keyword + semantic hits into ONE ranked, deduped list, best-first, filled to a token
        // budget — so the single most relevant memory always survives instead of being truncated away.
        // This is now the PRIMARY semantic surface (everything the phone does is embedded via Brain.remember +
        // ingestAllSources), so it earns a larger budget than the old keyword-gated blocks around it.
        // THE BOTTLENECK BEHIND "the AI only knows what's in my characteristics card".
        // This was 2,600 characters — with lines capped at 280, about NINE messages out of 67,163 — while
        // profileBlock() above goes in unbounded and answerWell() accepts 20,000. So every answer was
        // overwhelmingly the settings profile plus a handful of messages, no matter how much history the
        // owner imported. The brain wasn't failing to retrieve; it was being throttled on the way to the
        // model. 9,000 leaves ample room for the profile, calendar, docs and the rest inside that 20k.
        // 9,000 was too far the other way. Measured on device: a ~40,000-character context made the primary
        // model take 105 SECONDS and time out, and exceeded Groq's per-minute token limit outright (413) —
        // so the fallback couldn't absorb what the primary dropped and the owner got nothing at all. The
        // Memory tab answers well and fast on a 20,000-character corpus, so that is the size to match; more
        // context is worthless if the request never returns.
        val ranked = rankedRecall(ctx, q, budgetChars = 6000)
        // WHO the question is about, as a relationship — the same lines that turned "who is Carlos" from
        // eight fragments of small talk into a real answer. This is the shared context every surface reads,
        // so Home AI, chat and reply drafting all get it, not just the Memory tab.
        val who = try {
            q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.take(4)
                .flatMap { MessageStore.personDossier(ctx, it) }.distinct().take(6).joinToString("\n")
        } catch (e: Exception) { "" }
        val net = ConnectionStore.search(ctx, q, 6)
            .joinToString(" · ") { it.name + (if (it.role.isNotBlank()) " (${it.role})" else "") + (if (it.company.isNotBlank()) " @ ${it.company}" else "") }
            .take(800)
        val papers = PaperStore.libraryContext(ctx, 0L, q, 900)
        val paperList = if (Regex("paper|whitepaper|white ?paper|research|document|wrote|writ|publish|essay|report|zenodo|doi", RegexOption.IGNORE_CASE).containsMatchIn(q))
            PaperStore.list(ctx).joinToString("\n") { "- “${it.title}” (${it.docType})" } else ""
        val docText = if (KnowledgeStore.hasDoc(ctx)) KnowledgeStore.retrieve(ctx, q, 1000) else ""
        // Filed documents — email attachments (PDFs), scans, receipts, invoices, contracts. These were
        // extracted into DocStore but never reached the brain, so agents/HomeAI couldn't "see" them. Now they can.
        val filedDocs = try {
            DocStore.list(ctx).sortedByDescending { it.ts }.take(10).joinToString("\n") { d ->
                val f = try {
                    val o = org.json.JSONObject(d.fieldsJson)
                    o.keys().asSequence().take(6).joinToString(", ") { k -> "$k: ${o.optString(k)}" }
                } catch (e: Exception) { "" }
                "• ${d.title} [${d.category}]" + (if (d.summary.isNotBlank()) " — ${d.summary}" else "") + (if (f.isNotBlank()) " ($f)" else "")
            }
        } catch (e: Exception) { "" }
        // Checklist: pull tasks in whenever the question is about them, or matches task text.
        val terms = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        val taskQuery = Regex("task|to-?do|checklist|errand|chore|remind|due|outstanding|pending|need to|supposed to|my list|what.*do (today|this|next)", RegexOption.IGNORE_CASE).containsMatchIn(q)
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
        val financeQuery = Regex("(?i)\\b(spen[dt]|spending|expense|expenditure|budget|receipt|finances?|afford|overspend|save|saving|savings|subscriptions?|bills?|paid|income|cash ?flow|how much (did|have|do) i|money (go|going|left)|cost me|this month.*spend|where.*money)\\b").containsMatchIn(q)
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

        // Your AI team — surface the roster + recent activity when you ask "what did my employees do", "what's
        // going on in my company", "team status", etc., so HomeAI can report on the whole operation.
        val teamActivity = if (Regex("(?i)\\b(team|employ|agent|worker|staff|company|colleagu|what did .* do|what'?s (going on|happening)|going on|what happened|updates?|status|progress|my people|bastardi|kai|ravi|maya|leo|rana)\\b").containsMatchIn(q)) {
            try {
                val roster = EmployeeStore.all(ctx)
                if (roster.isEmpty()) "" else {
                    val who = roster.joinToString(", ") { "${it.name} (${it.role})" }
                    val acts = EmployeeStore.recentActivity(ctx, 18).joinToString("\n") { "• ${it.line}" }
                    "Your AI team: $who.\nRecent team activity (newest first):\n$acts"
                }
            } catch (e: Exception) { "" }
        } else ""

        android.util.Log.i("SlyOS-Perf", "BrainContext.build TOTAL ${System.currentTimeMillis() - tBuild}ms for \"${q.take(30)}\"")
        // The comprehensive self-model (whole brain distilled) leads the context so EVERY reply is grounded in
        // who the user actually is + what's going on — not just the settings card. Cached; falls back to the
        // card until the first digest is built. Bounded so query-specific recall below still has room.
        val digest = try { BrainDigest.get(ctx) } catch (e: Exception) { "" }
        // Section sizes, so a bloated block can be SEEN rather than inferred. Context that overflows the
        // model ceiling isn't extra detail — it's material thrown away, and since the profile leads, what
        // gets thrown away is always the query-specific part that made the answer worth reading.
        android.util.Log.i("SlyOS-Perf", "ctx sections: profile=${mem.length} cal=${cal.length} ranked=${ranked.length} " +
            "who=${who.length} net=${net.length} papers=${papers.length} doc=${docText.length} filed=${filedDocs.length} " +
            "recall=${recall.length} tasks=${tasks.length} sent=${sent.length} day=${dayLog.length} team=${teamActivity.length}")
        return buildString {
            // Time leads. It is ~30 characters, every scheduling answer depends on it, and it used to be the
            // LAST thing appended — so whenever the context overflowed the model ceiling, the current time was
            // the first casualty. Cheap, essential things go where truncation can't reach them.
            append("Current time: ").append(now).append("\n")
            // Near the front, with the time, so an overflowing context can never cost the answer path its
            // own list of what the product does.
            append(capabilities(ctx))
            if (digest.isNotBlank()) append("WHO YOU ARE (comprehensive self-model):\n").append(digest.take(9000)).append("\n\n")
            if (mem.isNotBlank()) append(mem)
            if (photoCount > 0) append("\nYou have ").append(photoCount)
                .append(" photos described in your brain; you can find pictures by describing them (e.g. \"a cute selfie\").")
            if (photoHits.isNotBlank()) append("\nPhotos that match this request:\n").append(photoHits)
            if (cal.isNotBlank()) append("\nUpcoming calendar:\n").append(cal)
            // Straight from Google, and it OVERRIDES anything remembered. A memory saying an invite went out
            // is not evidence it did; this block is. If it says nobody is invited, then nobody is invited —
            // say so plainly, however confidently a past message claimed otherwise.
            if (eventFacts.isNotBlank()) append(
                "\nWHO IS ACTUALLY INVITED TO YOUR EVENTS (fetched from Google just now — this is the ONLY " +
                "trustworthy answer about invitations and replies; it OVERRIDES anything you or the brain " +
                "previously said. If it shows no attendees, the invitation was never sent, no matter what " +
                "was claimed before. You can offer to fix it — add them and send the invite — or to follow " +
                "up with anyone who hasn't replied or has declined):\n").append(eventFacts)
            if (sent.isNotBlank()) append("\nThe most recent messages YOU sent (newest first — use these to answer who/what you last sent):\n").append(sent)
            if (expenses.isNotBlank()) append("\nYour real spending from tracked receipts (use these EXACT numbers for money questions):\n").append(expenses)
            if (dayLog.isNotBlank()) append("\nWhat flowed through your brain in the time window you asked about (newest first, with times — use these to answer the date question):\n").append(dayLog)
            if (who.isNotBlank()) append("\nYour actual relationship with the people named in this request " +
                "(straight from the message record — treat these as people you KNOW):\n").append(who)
            if (ranked.isNotBlank()) append("\nMost relevant memories (ranked best-first — the top lines matter most):\n").append(ranked)
            if (net.isNotBlank()) append("\nFrom your contacts/network (use ONLY if relevant):\n").append(net)
            // WHO IS THIS PERSON — searched across contacts, message history, network, CRM and calendar.
            // Without this, "do I have Randor?" was answered from the phone contacts DB alone and came
            // back a confident "No" about someone the user messages on WhatsApp every day.
            try {
                val who = PersonLookup.subjectOf(q)
                if (who.isNotBlank()) {
                    val brief = PersonLookup.brief(ctx, who)
                    if (brief.isNotBlank()) append("\n").append(brief)
                    else append("\nYou searched every source (contacts, messages, network, CRM, calendar) " +
                        "for \"").append(who).append("\" and found NOTHING — it is safe to say you don't know them.\n")
                }
            } catch (e: Exception) {}
            if (paperList.isNotBlank()) append("\nYour research papers (these are the papers you have):\n").append(paperList)
            if (papers.isNotBlank()) append("\nFrom your own research papers (use ONLY if relevant):\n").append(papers)
            if (docText.isNotBlank()) append("\nFrom your loaded document (use ONLY if relevant):\n").append(docText)
            if (filedDocs.isNotBlank()) append("\nDocuments filed in your brain (from email attachments, scans, receipts — you CAN read and reference these when relevant):\n").append(filedDocs)
            try { LeadStore.brief(ctx, 12).takeIf { it.isNotBlank() }?.let { append("\nPeople in your CRM (contacts/leads your team saved — use when relevant, and add new ones you meet):\n").append(it) } } catch (e: Exception) {}
            if (teamActivity.isNotBlank()) append("\n").append(teamActivity)
            try { DocText.retrieve(ctx, q, 2600).takeIf { it.isNotBlank() }?.let { append("\nActual passages from your documents (full text — quote/answer from these directly):\n").append(it) } } catch (e: Exception) {}
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
            // The vitals, when the question is about them. Every path that answers anything comes
            // through here, so this is what makes "how did I sleep this week?" answerable on Home,
            // in Telegram and to a team agent — rather than only inside the Health screen's own ask
            // box, which would leave the assistant knowing less than one of its own pages.
            if (vitals.isNotEmpty()) append("\n\n").append(vitals)
            if (crm.isNotEmpty()) append("\n\n").append(crm)
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
