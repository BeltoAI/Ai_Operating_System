package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * A work SHIFT for one employee. It reads its goal, its own recent log and the owner's brain context, decides
 * the single most useful next step, does the thinking/drafting, and reports back in one line — flagging when it
 * needs something from the owner. Supervised by default (it proposes; the owner approves), so nothing fires
 * unattended. Everything is written to the employee's log AND the brain, so the whole team's work is tracked.
 */
object EmployeeRunner {
    private const val TAG = "SlyOS-Shift"
    private val lock = Any()   // serialize shifts so token usage is attributed to the right worker

    private fun parseIso(s: String?): Long = try {
        if (s.isNullOrBlank()) 0L else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    // Full JSON schema for an executable action, shared by the shift + chat + chain prompts.
    private const val ACTION_SCHEMA =
        "{\"type\":\"send_email|add_event|save_lead|post|note|make_doc|edit_doc|outreach|deploy|build_app|provision_db|none\",\"to\":\"\",\"subject\":\"\",\"body\":\"\"," +
        "\"title\":\"\",\"start\":\"2026-07-15T15:00\",\"end\":\"2026-07-15T15:30\",\"meet\":false,\"attendees\":[]," +
        "\"target\":\"\",\"text\":\"\",\"kind\":\"\",\"name\":\"\",\"email\":\"\",\"role\":\"\",\"company\":\"\",\"extra\":{}}"

    // How the doc actions work — appended to the chain prompt so a designer agent knows to use them.
    private const val DOC_HELP =
        " make_doc: create a high-end DECK, ONE-PAGER, or WEBSITE — set kind (\"deck\", \"onepager\", or \"site\" for a full responsive web app with a real Supabase backend), a title, and put a CONCISE outline " +
        "into \"text\" (a handful of short lines — the designer expands each into polished copy; keep it brief so the JSON stays valid). " +
        "It's designed as a beautiful LIVE DRAFT (HTML that opens in any browser), saved to the SlyOS folder + brain, and sent into the " +
        "chat for review — the owner iterates on it, then says 'make it a PDF' when happy and it's converted once. Don't claim you made a PDF; call it a draft. " +
        "CRITICAL: if the owner asked for a deck/one-pager/document, your job is NOT done until you have actually run make_doc and the " +
        "file is produced — do NOT stop after only researching or saving a note. Do at most ONE quick research step, then call make_doc. " +
        "NEVER keep asking for missing details on a doc request: if the owner gives any go-ahead — or says 'just create it', 'agnostic', " +
        "'it's fine', 'go ahead' — call make_doc IMMEDIATELY and use tasteful placeholders like [Client] for anything unknown. A solid " +
        "draft they can edit always beats questions. Do NOT set 'needs' for a doc unless you genuinely cannot render at all. " +
        "edit_doc: revise the CURRENT document — put the requested change in \"text\" (e.g. 'make the cover bolder, add a pricing slide'); it re-renders and re-sends." +
        " outreach: send an email to MANY relevant people from the CRM as a spam-safe drip (one every ~hour, not a blast). Put the audience in " +
        "\"target\" (e.g. 'vcs', 'investors', 'all leads', 'design agencies' — whatever the context demands), a \"subject\", and a warm personalized \"body\" " +
        "(use [Name] and it's filled per-recipient). If a document is in progress it's attached automatically. Optionally set \"extra\":{\"everyMin\":60}. " +
        "Only use outreach when the owner clearly asks to reach out to a group; confirm the audience makes sense first." +
        " deploy: ship the CURRENT site LIVE to Vercel — returns a public URL you paste back in the chat. Build the site first with make_doc " +
        "(kind \"site\"), then call deploy. Wire any backend (DB/auth/storage) with Supabase client-side using the creds in your context. " +
        "If a Vercel token isn't set, say in one line that the owner needs to add one in Brain → API keys, and hand back the finished code meanwhile." +
        " build_app: for a REAL full-stack app (marketplace, SaaS, dashboard — anything with users, data, auth, or payments) hand it to LOVABLE, which ships React + a Supabase backend + hosting END-TO-END. Put a crisp, complete build brief in \"text\" " +
        "(what it does, the key pages, the data model, auth, payments, and the look/feel). I turn it into a one-tap Lovable build link the owner opens — no key needed. Use build_app for full apps; use make_doc kind \"site\" + deploy only for a simple static/marketing page." +
        " provision_db: run the app's backend SQL (CREATE TABLE, Row-Level-Security policies, seed data) against the owner's Supabase — put the SQL in \"text\". Do this so a deployed site WORKS on first load instead of hitting missing tables. Typical full flow you own end-to-end: make_doc kind \"site\" (frontend wired to Supabase) → provision_db (schema) → deploy (live URL). To refine later: edit_doc the site, then deploy again."

    /** Execute ONE action fully (MAX automation — reversible things just happen). Returns a human result line. */
    private fun execAction(ctx: Context, emp: EmployeeStore.Employee, act: org.json.JSONObject?, srcMessage: String): String {
        val type = act?.optString("type").orEmpty()
        return try {
            when (type) {
                "send_email" -> {
                    val to = act!!.optString("to").trim(); val body = act.optString("body").trim()
                    // NEVER email the owner — you're already talking to them in the chat. Emailing yourself
                    // a "team intro" / status is pure noise. Say it in the chat instead.
                    val ownerEmail = (GoogleAuth.account(ctx).ifBlank { AccountStore.email(ctx) }).trim()
                    if (ownerEmail.isNotBlank() && to.equals(ownerEmail, ignoreCase = true)) {
                        ""   // silently skip; the chain will just reply in chat
                    } else if (to.contains("@") && body.isNotBlank() && !AgentClient.looksLikeError(body)) {
                        val (ok, msg) = GmailClient.send(ctx, to, act.optString("subject").ifBlank { "(no subject)" }, body)
                        if (ok) "sent email to $to ✓" else "couldn't send to $to ($msg)"
                    } else ""
                }
                "add_event" -> {
                    val title = act!!.optString("title").trim()
                    val s = parseIso(act.optString("start")); val e2 = parseIso(act.optString("end"))
                    val end = if (e2 > s) e2 else s + 1_800_000L
                    val attendees = act.optJSONArray("attendees")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                    val wantMeet = act.optBoolean("meet", false) || Regex("(?i)meet|video ?call|zoom|hangout|google meet").containsMatchIn(srcMessage)
                    if (title.isBlank() || s <= 0) "" else if (wantMeet && GoogleAuth.isConnected(ctx)) {
                        val r = GoogleCalendarClient.createEvent(ctx, title, s, end, attendees, true)
                        if (r.ok) "created “$title”" + (if (r.meetLink.isNotBlank()) " ✓ Meet: ${r.meetLink}" else " ✓") else "couldn't create it (${r.error.take(40)})"
                    } else if (CalendarTool.hasPermission(ctx)) {
                        val r = CalendarTool.addEvent(ctx, title, s, end, attendees)
                        if (!r.startsWith("ERR")) "added “$title” to your calendar ✓" else "couldn't add event"
                    } else ""
                }
                "save_lead" -> {
                    val nm = act!!.optString("name").trim(); val em = act.optString("email").trim()
                    if (nm.isNotBlank() || em.contains("@")) {
                        val extra = act.optJSONObject("extra")?.toString() ?: "{}"
                        LeadStore.add(ctx, nm, em, act.optString("role").trim(), act.optString("company").trim(), "agent", "", extra)
                        "saved ${nm.ifBlank { em }} to your CRM ✓"
                    } else ""
                }
                "post" -> {
                    // Only a social/reddit/growth agent drafts posts — an inbox manager shouldn't.
                    val isSocial = Regex("(?i)reddit|growth|social|market|content|community").containsMatchIn(emp.role + " " + emp.goal + " " + emp.tools)
                    val target = act!!.optString("target").trim(); val title = act.optString("title").trim(); val txt = act.optString("text").trim()
                    if (isSocial && txt.isNotBlank() && !AgentClient.looksLikeError(txt)) {
                        AgentDraft.set(ctx, emp.id, "post", target, title, txt)
                        "drafted a post" + (if (target.isNotBlank()) " for $target" else "") + " — ready to review & post"
                    } else ""
                }
                "note" -> {
                    val n = act!!.optString("text").trim().ifBlank { act.optString("body").trim() }
                    if (n.isNotBlank()) {
                        try { MemoryLog.add(ctx, "note", "${emp.name}: note", n.take(500), "Team") } catch (e: Exception) {}
                        // Write to the SEARCHABLE brain too (MessageStore), else Home AI can't recall it — the
                        // "note isn't real" bug. Now "what did Riri note?" finds it.
                        try { MessageStore.insertOne(ctx, emp.name, "Note", emp.name, "me", "${emp.name} noted: ${n.take(500)}") } catch (e: Exception) {}
                        "saved a note to your brain ✓"
                    } else ""
                }
                "make_doc" -> {
                    val kind = act!!.optString("kind").ifBlank { act.optString("target") }.ifBlank { "deck" }
                    val title = act.optString("title").ifBlank { "Deck" }
                    val brief = act.optString("text").ifBlank { act.optString("body") }
                    // Build even with thin input — a solid draft they can edit beats asking questions.
                    val effBrief = if (brief.length >= 20) brief else
                        "$title. Context: $srcMessage. Design a clean, professional structure with sensible sections; use tasteful placeholders like [Client], [Metric], [Date] where a specific is unknown."
                    renderAndShare(ctx, emp, kind, title, effBrief)
                }
                "edit_doc" -> {
                    val instruction = act!!.optString("text").ifBlank { act.optString("body") }
                    val cur = DesignStore.get(ctx, emp.id)
                    if (cur == null) "no document in progress to edit — make one first" else if (instruction.isBlank()) "" else {
                        val html = AgentClient.editHtml(cur.html, instruction)
                        if (html.length < 100) "couldn't apply that edit" else finalizeDoc(ctx, emp, cur.title, cur.kind, html, "edited")
                    }
                }
                "outreach" -> {
                    val audience = act!!.optString("target").trim().ifBlank { "leads" }
                    val subject = act.optString("subject").trim().ifBlank { "Quick hello from ${MemoryStore.ownerName(ctx).ifBlank { "us" }}" }
                    val bodyT = act.optString("body").trim()
                    if (bodyT.isBlank()) "" else {
                        val recips = resolveAudience(ctx, audience)
                        if (recips.isEmpty()) "no one in your CRM matches “$audience” — save some contacts first (or widen the audience)" else {
                            val everyMin = act.optJSONObject("extra")?.optInt("everyMin", 60)?.takeIf { it > 0 } ?: 60
                            val attach = DesignStore.get(ctx, emp.id)?.pdfPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() } ?: ""
                            // Personalize per-recipient at send time via [Name]; enqueue once each.
                            val queued = recips.map { OutreachQueue.Recipient(it.name, it.email) }
                            val n = OutreachQueue.enqueue(ctx, queued, subject, bodyT, attach, everyMin, campaign = audience)
                            if (n > 0) "queued outreach to $n ${if (n == 1) "person" else "people"} matching “$audience” — sending ~1 every ${everyMin}m so we stay out of spam ✓" +
                                (if (attach.isNotBlank()) " (your document attached)" else "")
                            else "those contacts are already queued"
                        }
                    }
                }
                "build_app" -> {
                    // Hand a REAL full-stack app to Lovable (React + Supabase + hosting, end-to-end). We compose a
                    // crisp build brief; the owner taps the link to build it in their Lovable account.
                    val brief = act!!.optString("text").ifBlank { act.optString("body") }.ifBlank { srcMessage }
                    if (brief.length < 8) "tell me what the app should do and I'll spin up a Lovable build" else {
                        val url = LovableClient.buildUrl(brief)
                        try { MessageStore.insertOne(ctx, emp.name, "Build", emp.name, "me", "Lovable build for “${brief.take(60)}”: $url") } catch (e: Exception) {}
                        "Tap to build it end-to-end on Lovable (React + Supabase + hosting):\n$url"
                    }
                }
                "provision_db" -> {
                    // Run the agent's generated SQL (create tables + RLS + seed) so the deployed app works on load.
                    val sql = act!!.optString("text").ifBlank { act.optString("body") }
                    if (sql.length < 10) "give me the SQL (tables/policies) and I'll set up the database" else {
                        val r = SupabaseAdmin.runSql(ctx, sql)
                        if (r.ok) { try { MessageStore.insertOne(ctx, emp.name, "Database", emp.name, "me", "Applied DB schema (${sql.length} chars of SQL)") } catch (e: Exception) {} }
                        r.message
                    }
                }
                "deploy" -> {
                    val cur = DesignStore.get(ctx, emp.id)
                    val html = act!!.optString("text").ifBlank { cur?.html.orEmpty() }
                    val name = act.optString("title").ifBlank { cur?.title ?: "site" }
                    if (html.length < 60) "there's no site built yet to deploy — create it first, then deploy" else {
                        // FREE + zero-config host by default (real URL, no user keys). Vercel only if a token is set.
                        val (ok, url, err) = if (MemoryStore.vercelToken(ctx).isNotBlank()) {
                            val r = DeployClient.deployHtml(ctx, name, html); Triple(r.ok, r.url, r.error)
                        } else { val r = SiteHost.publish(html, name); Triple(r.ok, r.url, r.error) }
                        if (ok) {
                            try { MessageStore.insertOne(ctx, emp.name, "Deploy", emp.name, "me", "Shipped “$name” live: $url") } catch (e: Exception) {}
                            "It's live ✓ $url"
                        } else err
                    }
                }
                else -> ""
            }
        } catch (e: Exception) { "action failed: ${e.message}" }
    }

    /** Design a brand-new document/deck: research + templates + brain → HTML → PDF → SlyOS folder + brain + chat. */
    private fun renderAndShare(ctx: Context, emp: EmployeeStore.Employee, kind: String, title: String, brief: String): String {
        val templates = try { AgentKnowledge.retrieve(ctx, emp.id, "template layout design example", 2500) } catch (e: Exception) { "" }
        val brainSnip = try { BrainContext.build(ctx, "$title $brief").take(2500) } catch (e: Exception) { "" }
        val html = AgentClient.designHtml(kind, title, brief, templates, brainSnip)
        if (html.length < 100) return "couldn't design “$title” just now"
        return finalizeDoc(ctx, emp, title, kind, html, "designed")
    }

    /** Render the HTML to PDF, store it (SlyOS folder + brain + editable source), and share it into the chat. */
    /**
     * Deliver the working document. During iteration [finalize]=false → we send the live HTML (instant, always
     * renders, edit it as many times as you want). When you say you're happy ([finalize]=true) we do the ONE
     * heavy HTML→PDF conversion and send the polished PDF. Cheaper, faster, and no per-edit render failures.
     */
    private fun finalizeDoc(ctx: Context, emp: EmployeeStore.Employee, title: String, kind: String, html: String,
                            verb: String, finalize: Boolean = false): String {
        val isDeck = kind.lowercase().contains("deck") || kind.lowercase().contains("slide") || kind.lowercase().contains("present")
        val isSite = kind.lowercase().let { it.contains("site") || it.contains("web") || it.contains("app") || it.contains("landing") }
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(50).ifBlank { "document" }
        val dir = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "SlyOS").apply { mkdirs() }
        // A website is never a PDF — keep it as HTML and offer to deploy it instead.
        val pdf = if (finalize && !isSite) try { HtmlPdf.render(ctx, html, title, landscape = isDeck) } catch (e: Exception) { null } else null
        // Always keep an HTML copy on disk (that's what we iterate on and what we send while drafting).
        val htmlFile = try { java.io.File(dir, "$safe.html").apply { writeText(html) } } catch (e: Exception) { null }
        val file = pdf ?: htmlFile
        DesignStore.set(ctx, emp.id, title, kind, html, file?.absolutePath ?: "")
        try {
            val fields = org.json.JSONObject()
                .put("file", file?.absolutePath ?: "")
                .put("format", if (pdf != null) "PDF" else "HTML (open in any browser)")
            DocStore.addText(ctx, kind, title,
                "A $kind ${emp.name} designed — saved as a file in your SlyOS folder (NOT Google Docs). Open the file path shown, or ask ${emp.name} to resend it to your chat.",
                fields, "designer")
        } catch (e: Exception) {}
        try { DocText.add(ctx, title, "design", html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(40000)) } catch (e: Exception) {}
        val cap = if (finalize && pdf != null)
            "$title — final PDF, ready to send. Nice working with you on this one."
        else if (finalize)
            "$title — couldn't render the PDF on-device, so here's the final HTML (opens in any browser, looks identical). You can print-to-PDF from there."
        else if (isSite)
            "$title — here's the working site (open it in any browser). Tell me any changes. When it's ready, say “deploy it” and I'll ship it to a live public URL."
        else {
            val ask = if (isDeck) "What do you think — tighten the cover, cut a slide, shift the color?"
                      else "What do you think — punchier, shorter, different accent color?"
            "$title — here's the live draft (open it in any browser). $ask When you're happy, just say “make it a PDF” and I'll finalize it."
        }
        val sent = if (file != null) try { TeamChat.postDocument(ctx, file, cap) } catch (e: Exception) { false } else false
        val what = if (finalize && pdf != null) "finalized" else verb
        return when {
            sent -> "$what “$title” and sent it to your chat ✓"
            file != null -> "$what “$title” ✓ — saved to your SlyOS folder (turn on the team chat to get it sent here)"
            else -> "$what “$title”, but couldn't produce a file"
        }
    }

    /** User said they're happy / wants the PDF → convert the current working doc once and send it. */
    private fun exportPdf(ctx: Context, emp: EmployeeStore.Employee): String {
        val cur = DesignStore.get(ctx, emp.id) ?: return ""
        return finalizeDoc(ctx, emp, cur.title, cur.kind, cur.html, "finalized", finalize = true)
    }

    /** Resolve a plain-language audience ("vcs", "all leads", "design agencies") to real CRM contacts with emails. */
    private fun resolveAudience(ctx: Context, audience: String): List<LeadStore.Lead> {
        val all = try { LeadStore.all(ctx) } catch (e: Exception) { emptyList() }.filter { it.email.contains("@") }
        val a = audience.lowercase().trim()
        val everyone = Regex("(?i)\\b(all|every(one|body)?|leads?|contacts?|my crm|the crm|whole list)\\b").containsMatchIn(a)
        if (everyone) return all
        // Map common audiences to keyword sets matched against role/company/notes/extra.
        val kw: List<String> = when {
            Regex("(?i)vc|investor|capital|fund|angel|partner|ventures?").containsMatchIn(a) ->
                listOf("vc", "investor", "capital", "fund", "angel", "partner", "venture")
            else -> a.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        }
        if (kw.isEmpty()) return all
        val hits = all.filter { l ->
            val hay = (l.role + " " + l.company + " " + l.notes + " " + l.extra + " " + l.source).lowercase()
            kw.any { hay.contains(it) }
        }
        // Fall back to a name/company text search if keyword-matching found nothing.
        return if (hits.isNotEmpty()) hits else try { LeadStore.search(ctx, audience, 50).filter { it.email.contains("@") } } catch (e: Exception) { emptyList() }
    }

    data class ChainResult(val summary: String, val needs: String, val actions: Int, val inTok: Int, val outTok: Int)

    /**
     * The AGENTIC LOOP — the agent works toward [task] over multiple steps: each step it may take one action,
     * sees the result, and continues, until it's done, needs the owner, or hits the step/token cap. MAX
     * automation: reversible actions (email, event, lead, note) just execute. Grounded in fed docs + brain + web.
     */
    fun runChain(ctx: Context, emp: EmployeeStore.Employee, task: String, history: String = "", speaker: String = "",
                 maxSteps: Int = 6, tokenCap: Int = 45000): ChainResult {
        // FAST PATH: "make it a PDF" / "finalize" / "export it" / "I'm happy, convert it" on a doc that's already
        // in progress → skip the model entirely and do the single HTML→PDF conversion. Reliable + instant.
        // FAST PATH: "deploy it / ship it / go live" on a SITE in progress → deploy to Vercel now (skip the model).
        val cur0 = DesignStore.get(ctx, emp.id)
        val curIsSite = cur0?.kind?.lowercase()?.let { it.contains("site") || it.contains("web") || it.contains("app") || it.contains("landing") } ?: false
        val wantsDeploy = Regex("(?i)\\b(deploy( it| this)?|ship it( live)?|go live|publish( it| this)?|put (it|this) live|make (it|this) live)\\b").containsMatchIn(task)
        if (wantsDeploy && cur0 != null && curIsSite) {
            val (ok, url, err) = try {
                if (MemoryStore.vercelToken(ctx).isNotBlank()) { val r = DeployClient.deployHtml(ctx, cur0.title, cur0.html); Triple(r.ok, r.url, r.error) }
                else { val r = SiteHost.publish(cur0.html, cur0.title); Triple(r.ok, r.url, r.error) }
            } catch (e: Exception) { Triple(false, "", "deploy failed: ${e.message}") }
            if (ok) { try { MessageStore.insertOne(ctx, emp.name, "Deploy", emp.name, "me", "Shipped “${cur0.title}” live: $url") } catch (e: Exception) {} }
            return ChainResult(if (ok) "It's live — $url" else err, "", if (ok) 1 else 0, 0, 0)
        }
        val wantsPdf = Regex("(?i)\\b(make (it|this).*(pdf|final)|to pdf|as (a )?pdf|export|finali[sz]e|convert.*pdf|i'?m happy|we'?re done|looks good.*(send|final|pdf)|lock it in)\\b").containsMatchIn(task)
        if (wantsPdf && DesignStore.get(ctx, emp.id) != null && !curIsSite) {
            val r = try { exportPdf(ctx, emp) } catch (e: Exception) { "couldn't render the PDF" }
            if (r.isNotBlank()) return ChainResult(r, "", if (r.startsWith("couldn't")) 0 else 1, 0, 0)
        }
        val owner = MemoryStore.ownerName(ctx).ifBlank { "the owner" }
        val brain = try { BrainContext.build(ctx, task) } catch (e: Exception) { "" }
        val kb = try { AgentKnowledge.retrieve(ctx, emp.id, task, 2200) } catch (e: Exception) { "" }
        val caps = try { Capabilities.summary(ctx) } catch (e: Exception) { "" }
        val cal = try { if (CalendarTool.hasPermission(ctx)) CalendarTool.upcoming(ctx) else "" } catch (e: Exception) { "" }
        val roster = try { EmployeeStore.all(ctx).filter { it.id != emp.id && it.name.isNotBlank() }.joinToString("; ") { "${it.name} (${it.role})" } } catch (e: Exception) { "" }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(java.util.Date())
        val forget = try { EmployeeStore.forgetList(ctx, emp.id) } catch (e: Exception) { "" }
        val isDev = Regex("(?i)dev|engineer|full.?stack|deploy|coder?").containsMatchIn(emp.role + " " + emp.tools)
        val devBlock = if (isDev) {
            val u = try { DeployClient.supabaseUrl(ctx) } catch (e: Exception) { "" }
            val a = try { DeployClient.supabaseAnon(ctx) } catch (e: Exception) { "" }
            "DEPLOY STACK (you own this end-to-end): make_doc kind \"site\" to build the site → provision_db to create its Supabase tables/policies → deploy to ship a live Vercel URL. To refine later: edit_doc then deploy again. " +
            (if (u.isNotBlank()) "Backend = Supabase: wire DB/auth/storage CLIENT-SIDE via the supabase-js CDN with SUPABASE_URL=\"$u\" and SUPABASE_ANON_KEY=\"$a\". " else "Backend = Supabase, but no creds set — build the frontend and note a Supabase URL + anon key + access token are needed. ") +
            (if (try { SupabaseAdmin.configured(ctx) } catch (e: Exception) { false }) "Database provisioning IS set up — call provision_db with the SQL." else "Database provisioning NOT set up yet (needs a Supabase access token) — still hand over the SQL in provision_db so it runs once the owner adds it.") + "\n\n"
        } else ""
        val ctxBlock = "Current time: $now\n" + devBlock +
            (if (forget.isNotBlank()) "DROPPED BY THE OWNER — do NOT work on, suggest, or bring up ANY of these ever again:\n$forget\n\n" else "") +
            (if (roster.isNotBlank()) "YOUR TEAMMATES (you know these people; refer work to them when it's their lane, but you can't do their jobs): $roster\n\n" else "") +
            (if (kb.isNotBlank()) "YOUR OWN DOCUMENTS (your PRIMARY source):\n$kb\n\n" else "") +
            (if (cal.isNotBlank()) "YOUR CALENDAR:\n${cal.take(1000)}\n\n" else "") +
            (if (history.isNotBlank()) "RECENT TEAM-CHAT (each line 'Sender: message'):\n$history\n\n" else "") +
            "What you know about $owner:\n${brain.take(2600)}\n"
        val steps = ArrayList<String>()
        var inSum = 0; var outSum = 0; var didAny = 0; var needs = ""
        for (i in 1..maxSteps.coerceIn(1, 8)) {
            val sys = "You are ${emp.name}, the ${emp.role} on $owner's team. Goal for this run: \"$task\". $caps " +
                "STAY STRICTLY IN YOUR LANE: only do work that fits YOUR role (${emp.role}) — for example an inbox manager triages " +
                "email and drafts replies, it does NOT post to Reddit or research LinkedIn profiles. If the most useful step is " +
                "outside your job, say so briefly ('that's for the research/reddit teammate') and take action 'none' instead of doing it. " +
                "You work in STEPS: each step you may take ONE action; you'll then see its result and continue. You have live " +
                "web search every step. MAX AUTOMATION — actually DO things (send the email, create the event, save the lead, " +
                "draft the post) without asking permission for reversible actions. Only set \"needs\" if you literally cannot " +
                "proceed without $owner (a missing address, a private detail, a genuine judgment call). " +
                "\"say\" is a SHORT, NATURAL, FIRST-PERSON message to $owner — talk like a real teammate who's on it, warm and human, the way ${emp.name} the ${emp.role} actually would. NEVER narrate your own internal reasoning or routing: no 'this is inbox territory', no 'handing to Riri', no bullet-point status log. Just say the human thing. If it's genuinely another teammate's job, say ONE friendly line like 'that's more Kai's area — want me to loop them in?' and set done:true. For casual chat or a simple reply, answer in ONE step and set done:true — never repeat yourself across steps. " +
                "Output ONLY compact JSON {\"say\":\"your natural message to $owner\",\"action\":$ACTION_SCHEMA," +
                "\"needs\":\"empty unless truly blocked\",\"done\":false}. Set done:true the moment the goal is met (or immediately for chit-chat)." + DOC_HELP + " No prose, no fences."
            val proceed = Regex("(?i)just (do|create|build|make) it|agnostic|it'?s fine|go ahead|without.*(info|details)|don'?t need|no info|proceed").containsMatchIn(task)
            val user = ctxBlock + "\nSTEPS DONE SO FAR:\n" + (if (steps.isEmpty()) "(none yet)" else steps.joinToString("\n")) +
                "\n\n" + (if (speaker.isNotBlank() && !speaker.equals(owner, true) && speaker != "You") "Request from $speaker: " else "") + task +
                (if (proceed) "\n\nThe owner has told you to PROCEED WITHOUT more info — take the real action NOW (for a doc: call make_doc with placeholders). Do NOT ask again." else "") +
                "\n\nDo the next step now (or set done:true if the goal is fully met)."
            val (raw, inTok, outTok) = AgentClient.work(sys, user, 850, web = true)
            inSum += inTok; outSum += outTok
            val js = raw.indexOf('{'); val je = raw.lastIndexOf('}')
            val o = try { if (js in 0 until je) org.json.JSONObject(raw.substring(js, je + 1)) else null } catch (e: Exception) { null }
            val say = o?.optString("say")?.trim().orEmpty()
            val done = o?.optBoolean("done", false) ?: true
            val n = o?.optString("needs")?.trim().orEmpty()
            val act = o?.optJSONObject("action")
            val result = execAction(ctx, emp, act, task)
            if (result.isNotBlank() && !result.startsWith("couldn't") && !result.startsWith("action failed")) didAny++
            val line = "• " + say.ifBlank { result.ifBlank { "…" } } + (if (result.isNotBlank() && say.isNotBlank()) " → $result" else "")
            if (say.isNotBlank() || result.isNotBlank()) steps.add(line)
            if (n.isNotBlank()) { needs = n; break }
            if (o == null && say.isBlank() && result.isBlank()) break   // nothing happening → stop
            if (done) break
            if (inSum + outSum > tokenCap) { steps.add("• (paused — hit this run's budget)"); break }
        }
        // GUARANTEE: a deck/doc request ALWAYS yields a file. If the chain didn't actually build one (model
        // flaked or kept asking), build it now from the task + brain + fed docs — no more "Worked on it".
        val wantsSite = Regex("(?i)\\b(web ?site|web ?app|web ?page|landing ?page|marketplace|storefront|online store|build.*site|make.*site)\\b").containsMatchIn(task)
        if ((wantsSite || Regex("(?i)\\b(deck|one.?pager|onepager|document|slides?|presentation|brochure|pitch|proposal)\\b").containsMatchIn(task)) &&
            steps.none { it.contains("designed", true) || it.contains("edited", true) || it.contains("ready to review", true) || it.contains("working site", true) }) {
            val docKind = when { wantsSite -> "site"; Regex("(?i)deck|slide|present|pitch").containsMatchIn(task) -> "deck"; else -> "onepager" }
            val docTitle = Regex("(?i)\\b(vera|bastard|build|create|make|design|me|my|a|an|the|please|for|can|you|u|just|do|it|agnostic|short|quick|send|to)\\b").replace(task, " ")
                .replace(Regex("[^A-Za-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().split(" ").filter { it.length > 1 }.take(5).joinToString(" ").ifBlank { "Document" }.replaceFirstChar { it.uppercase() }
            val fb = task + (if (kb.isNotBlank()) "\n\nKnown context:\n${kb.take(1500)}" else "") + (if (brain.isNotBlank()) "\n\nAbout the company/owner:\n${brain.take(1500)}" else "")
            val r = try { renderAndShare(ctx, emp, docKind, docTitle, fb) } catch (e: Exception) { "couldn't build the document" }
            steps.add("• $r"); if (!r.startsWith("couldn't")) { didAny++; needs = "" }
        }
        // Build a HUMAN reply, not the internal step log: strip the "• " bullets, separate the agent's natural
        // message from the internal action-result, DEDUPE (so a looped model doesn't repeat itself 5×), and only
        // surface concrete outcomes (added/sent/created) — never internal routing chatter.
        val seen = LinkedHashSet<String>()
        steps.forEach { raw ->
            val s = raw.removePrefix("• ").trim()
            val arrow = s.indexOf(" → ")
            val msg = if (arrow >= 0) s.substring(0, arrow).trim() else s
            val res = if (arrow >= 0) s.substring(arrow + 3).trim() else ""
            if (msg.isNotBlank()) seen.add(msg)
            if (res.isNotBlank() && !res.startsWith("couldn't") &&
                Regex("(?i)✓|\\badded\\b|\\bsent\\b|\\bcreated\\b|\\bsaved\\b|\\bscheduled\\b|\\bqueued\\b|\\bdrafted\\b|\\bdesigned\\b|\\bfinalized\\b").containsMatchIn(res))
                seen.add(res)
        }
        val body = seen.joinToString("\n")
        val summary = (if (body.isBlank()) "On it." else body) +
            (if (needs.isNotBlank()) "\n\nNeeds you: $needs" else "")
        return ChainResult(summary, needs, didAny, inSum, outSum)
    }

    /**
     * Run one real shift. The worker pulls its live context (brain, calendar, inbox signals), takes the
     * single most useful step toward its goal — actually SENDING/SCHEDULING when it helps — and reports.
     * Everything is billed to the worker's ledger (tokens spent vs. value delivered) and written to the brain.
     */
    fun runShift(ctx: Context, emp: EmployeeStore.Employee): String = synchronized(lock) {
        EmployeeStore.setStatus(ctx, emp.id, "working")
        try {
            val owner = MemoryStore.ownerName(ctx).ifBlank { "the owner" }
            val tools = emp.tools.lowercase()
            val recent = EmployeeStore.logFor(ctx, emp.id, 8).joinToString("\n") { "• ${it.line}" }
            val brain = try { BrainContext.build(ctx, emp.goal) } catch (e: Exception) { "" }
            val caps = try { Capabilities.summary(ctx) } catch (e: Exception) { "" }

            // Live signals the worker can actually act on
            val live = StringBuilder()
            if ((tools.contains("calendar") || tools.contains("schedule")) && CalendarTool.hasPermission(ctx)) {
                val up = try { CalendarTool.upcoming(ctx) } catch (e: Exception) { "" }
                if (up.isNotBlank()) live.append("YOUR CALENDAR (next 30 days):\n").append(up.take(1200)).append("\n\n")
            }
            if ((tools.contains("email") || tools.contains("inbox")) && GoogleAuth.isConnected(ctx)) {
                val atts = try { Inbox.emailAttachments(ctx, 6).joinToString("\n") { "• ${it.name} (from ${it.who})" } } catch (e: Exception) { "" }
                if (atts.isNotBlank()) live.append("RECENT EMAIL ATTACHMENTS:\n").append(atts).append("\n\n")
            }
            // Recent messages — INCLUDING replies the owner sent back to you. Use these to CONTINUE or WRAP UP
            // a task you started (e.g. you emailed them a question and they answered).
            run {
                val msgs = try { MessageStore.recentLines(ctx, 12).joinToString("\n") } catch (e: Exception) { "" }
                if (msgs.isNotBlank()) live.append("RECENT MESSAGES (newest last — if the owner replied to something YOU sent, act on their answer now):\n").append(msgs.take(1600)).append("\n\n")
            }

            val sys = "You are ${emp.name}, the ${emp.role} on $owner's autonomous AI team. Standing goal: \"${emp.goal}\". " +
                caps + " STAY STRICTLY IN YOUR LANE — only do work that fits YOUR role (${emp.role}); do NOT drift into another " +
                "teammate's job (an inbox manager triages email, it does NOT post to Reddit or research LinkedIn people). " +
                "IMPORTANT — DON'T NAG: if your recent log shows you ALREADY asked $owner something that's still unanswered, do NOT " +
                "ask it again, re-email it, or re-flag it. Either make progress on a DIFFERENT part of your goal, or if everything is " +
                "blocked on that pending answer, use action 'none' with needs empty and say you're waiting. " +
                "You run UNSUPERVISED — take the SINGLE most useful next step toward YOUR goal right now, and when " +
                "it genuinely helps, actually DO it with one executable action. " +
                "You HAVE live web search — for anything about news, people, companies, prices, or current events, actually SEARCH " +
                "now and put CONCRETE findings in \"detail\": specific names, numbers, dates, and headlines, each with its source. " +
                "Never return an empty result or say 'no updates' without having searched. If you genuinely cannot browse this " +
                "shift, say that plainly in \"did\" rather than pretending. Output ONLY compact JSON: " +
                "{\"did\":\"past-tense line, under 14 words\",\"detail\":\"a short note on what you did (NOT the post text), or empty\"," +
                "\"needs\":\"what you need from $owner to go further, or empty\"," +
                "\"action\":{\"type\":\"send_email|add_event|note|post|save_lead|none\",\"to\":\"\",\"subject\":\"\",\"body\":\"\"," +
                "\"title\":\"\",\"start\":\"2026-07-15T15:00\",\"end\":\"2026-07-15T15:30\"," +
                "\"target\":\"\",\"text\":\"\",\"name\":\"\",\"email\":\"\",\"role\":\"\",\"company\":\"\",\"extra\":{}}}. " +
                "send_email only to a REAL EXTERNAL person, in $owner's own voice — NEVER email $owner (you talk to them in the app/chat, not their inbox). add_event uses local ISO times. " +
                "note saves a finding to $owner's brain. " +
                "post: for a Reddit comment/post or any social reply. Put the subreddit in \"target\" (e.g. \"r/LocalLLaMA\") — " +
                "VARY the subreddit across shifts to reach different relevant communities, don't always pick the same one. " +
                "If it's a standalone POST (a new thread), ALSO fill \"title\" with a real, specific post title (Reddit posts REQUIRE a title). " +
                "For a reply/comment, leave \"title\" empty. " +
                "CRITICAL — \"text\" is ONLY the body $owner would paste: NO meta like 'Subreddit:', 'Target thread', 'Exact comment', " +
                "NO markdown headers, NO '---', NO quotes around it, and do NOT repeat the title inside the body. Just the human message, ready to paste. " +
                "save_lead: whenever you find or correspond with a REAL person worth remembering — set name + email (+role, +company). It goes into $owner's CRM. " +
                "none = you only researched/thought this shift. No prose, no fences."
            val kb = try { AgentKnowledge.retrieve(ctx, emp.id, emp.goal, 2000) } catch (e: Exception) { "" }
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(java.util.Date())
            val forget = try { EmployeeStore.forgetList(ctx, emp.id) } catch (e: Exception) { "" }
            val user = "Current time: $now\n\n" + (if (live.isNotEmpty()) live.toString() else "") +
                (if (forget.isNotBlank()) "DROPPED BY $owner — never work on, retry, or mention ANY of these again (pick something else):\n$forget\n\n" else "") +
                (if (kb.isNotBlank()) "YOUR OWN DOCUMENTS (fed to you — your PRIMARY source, use these first):\n$kb\n\n" else "") +
                "Your recent log:\n${recent.ifBlank { "(nothing yet)" }}\n\n" +
                "What you know about $owner:\n${brain.take(3500)}\n\nDo your next step now."

            val (raw, inTok, outTok) = AgentClient.work(sys, user, 900, web = true)
            val jstart = raw.indexOf('{'); val jend = raw.lastIndexOf('}')
            val o = try { if (jstart in 0 until jend) JSONObject(raw.substring(jstart, jend + 1)) else null } catch (e: Exception) { null }
            var did = o?.optString("did")?.trim().orEmpty()
            var detail = o?.optString("detail")?.trim().orEmpty()
            val needs = o?.optString("needs")?.trim().orEmpty()
            // After web-searching the model often replies in PROSE, not JSON — keep that as the real finding
            // instead of discarding it and showing an empty "Worked on…". This is the fix for empty research.
            if (detail.isBlank() && o == null && raw.isNotBlank()) {
                detail = raw.trim().removePrefix("```").removeSuffix("```").trim().take(1600)
                if (did.isBlank()) did = "Put together a brief on ${emp.goal.take(30)}"
            }
            did = did.ifBlank { if (raw.isBlank()) "Couldn't finish — check your model key allows web search (Anthropic or Gemini)." else "Worked on: ${emp.goal.take(40)}" }
            val act = o?.optJSONObject("action")
            val actType = act?.optString("type").orEmpty()

            // Execute one safe headless action (owner set the team to fully autonomous)
            var didAction = 0; var outcome = ""; var postNeed = ""
            try {
                when (actType) {
                    "send_email" -> {
                        val to = act!!.optString("to").trim(); val subj = act.optString("subject").trim(); val body = act.optString("body").trim()
                        val ownerEmail = (GoogleAuth.account(ctx).ifBlank { AccountStore.email(ctx) }).trim()
                        if (ownerEmail.isNotBlank() && to.equals(ownerEmail, ignoreCase = true)) {
                            // never email the owner — that's what the app/chat is for
                        } else if (to.contains("@") && body.isNotBlank() && !AgentClient.looksLikeError(body)) {
                            val (ok, msg) = GmailClient.send(ctx, to, subj.ifBlank { "(no subject)" }, body)
                            outcome = if (ok) "Sent email to $to" else "Couldn't send: $msg"; if (ok) didAction = 1
                        }
                    }
                    "add_event" -> {
                        val title = act!!.optString("title").trim()
                        val s = parseIso(act.optString("start")); val e2 = parseIso(act.optString("end"))
                        if (title.isNotBlank() && s > 0 && CalendarTool.hasPermission(ctx)) {
                            val r = CalendarTool.addEvent(ctx, title, s, if (e2 > s) e2 else s + 1_800_000L)
                            if (!r.startsWith("ERR")) { outcome = "Added to calendar: $title"; didAction = 1 } else outcome = "Couldn't add event ($r)"
                        }
                    }
                    "note" -> {
                        if (detail.isNotBlank()) {
                            try { MemoryLog.add(ctx, "note", "${emp.name}: note", detail.take(600), "Team") } catch (e: Exception) {}
                            try { MessageStore.insertOne(ctx, emp.name, "Note", emp.name, "me", "${emp.name} noted: ${detail.take(600)}") } catch (e: Exception) {}
                            outcome = "Saved a note to your brain"; didAction = 1
                        }
                    }
                    "post" -> {
                        val target = act!!.optString("target").trim()
                        val title = act.optString("title").trim()
                        val text = act.optString("text").trim()
                        if (text.isNotBlank() && !AgentClient.looksLikeError(text)) {
                            AgentDraft.set(ctx, emp.id, "post", target, title, text)   // title + clean body, ready to post
                            outcome = "Drafted a post" + (if (target.isNotBlank()) " for $target" else "") + " — ready for your approval"
                            postNeed = "Approval to post" + (if (target.isNotBlank()) " to $target" else "") + " — open the card to review and post."
                        }
                    }
                    "save_lead" -> {
                        val nm = act!!.optString("name").trim(); val em = act.optString("email").trim()
                        if (nm.isNotBlank() || em.contains("@")) {
                            val extra = act.optJSONObject("extra")?.toString() ?: "{}"
                            LeadStore.add(ctx, nm, em, act.optString("role").trim(), act.optString("company").trim(), "${emp.name} (${emp.role})", detail.take(200), extra)
                            outcome = "Saved ${nm.ifBlank { em }} to your CRM"; didAction = 1
                        }
                    }
                }
            } catch (e: Exception) { outcome = "Action failed: ${e.message}" }

            // ── Ledger: what it cost (tokens) vs. what it delivered (actions + minutes saved) ──
            val valueMin = EmployeeStats.valueOf(if (didAction == 1) actType else "research", detail.isNotBlank() || didAction == 1)
            EmployeeStats.record(ctx, emp.id, AgentClient.lastProvider, AgentClient.lastModel, inTok, outTok, didAction, valueMin)
            if (valueMin > 0) try { MetricsStore.record(ctx, valueMin * 60) } catch (e: Exception) {}   // feeds the Settings efficiency score

            val needsEff = postNeed.ifBlank { needs }   // a drafted post needs your approval to go out
            EmployeeStore.log(ctx, emp.id, did, false)
            if (outcome.isNotBlank()) EmployeeStore.log(ctx, emp.id, outcome, false)
            if (detail.isNotBlank()) EmployeeStore.log(ctx, emp.id, detail.take(600), false)
            if (needsEff.isNotBlank()) EmployeeStore.log(ctx, emp.id, "Needs you: $needsEff", true)
            try { MemoryLog.add(ctx, "action", "${emp.name} (${emp.role})", (did + (if (outcome.isNotBlank()) "\n$outcome" else "") + (if (detail.isNotBlank()) "\n$detail" else "")).take(800), "Team") } catch (e: Exception) {}

            EmployeeStore.setStatus(ctx, emp.id, if (needsEff.isNotBlank()) "needs_you" else "idle", touchRun = true)
            // Don't SPAM the same ask every shift: only ping/post a "needs you" the FIRST time (or when it
            // genuinely changes). If nothing's blocked, clear the memory so a future ask can surface again.
            val repeatAsk = needsEff.isNotBlank() && EmployeeStore.alreadyAsked(ctx, emp.id, needsEff)
            if (needsEff.isBlank()) EmployeeStore.clearAsked(ctx, emp.id)
            try {
                when {
                    needsEff.isNotBlank() && !repeatAsk -> { EmployeeNotify.post(ctx, emp.id, "${emp.name} needs you", needsEff, true); EmployeeStore.rememberAsked(ctx, emp.id, needsEff) }
                    needsEff.isBlank() && didAction == 1 -> EmployeeNotify.post(ctx, emp.id, "${emp.name} · ${emp.role}", outcome.ifBlank { did }, false)
                }
            } catch (e: Exception) {}
            try {
                if (TeamChat.isConnected(ctx)) {
                    when {
                        needsEff.isNotBlank() && !repeatAsk -> TeamChat.post(ctx, emp.name, "needs you: $needsEff")
                        needsEff.isBlank() && didAction == 1 -> TeamChat.post(ctx, emp.name, outcome.ifBlank { did })
                    }
                }
            } catch (e: Exception) {}
            did
        } catch (e: Exception) {
            Log.w(TAG, "runShift: ${e.message}")
            EmployeeStore.setStatus(ctx, emp.id, "idle", touchRun = true)
            "Couldn't finish this shift — I'll try again."
        }
    }

    /**
     * Answer a direct message (team chat or in-app) — now a MULTI-STEP chain: the agent researches and takes
     * as many reversible actions as needed to fulfil the request, then returns a compact step summary.
     */
    fun answer(ctx: Context, emp: EmployeeStore.Employee, message: String, history: String = "", speaker: String = ""): String {
        return try {
            val cr = runChain(ctx, emp, message, history, speaker, maxSteps = 6)
            val valueMin = if (cr.actions > 0) 8 * cr.actions else 4
            EmployeeStats.record(ctx, emp.id, AgentClient.lastProvider, AgentClient.lastModel, cr.inTok, cr.outTok, cr.actions, valueMin)
            try { MetricsStore.record(ctx, valueMin * 60) } catch (e: Exception) {}
            EmployeeStore.log(ctx, emp.id, "You (chat): ${message.take(60)}", false)
            EmployeeStore.log(ctx, emp.id, "${emp.name}: ${cr.summary.take(220)}", cr.needs.isNotBlank())
            try { MemoryLog.add(ctx, "response", "${emp.name} · team chat", cr.summary.take(700), "Team") } catch (e: Exception) {}
            if (cr.needs.isNotBlank()) try { EmployeeStore.setStatus(ctx, emp.id, "needs_you") } catch (e: Exception) {}
            cr.summary.take(1600)
        } catch (e: Exception) {
            Log.w(TAG, "answer: ${e.message}"); "Couldn't get to that just now."
        }
    }

    /** Draft an employee config from a plain "build me an employee that…" request. Returns name/role/goal/tools. */
    fun draftFromRequest(request: String): JSONObject {
        val sys = "Turn a request to hire an AI employee into a config. Output ONLY compact JSON " +
            "{\"name\":\"a short human first name\",\"role\":\"2-4 word job title\",\"goal\":\"one clear standing " +
            "instruction in second person, e.g. 'Keep my inbox triaged and draft replies I can approve'\"," +
            "\"tools\":\"comma-separated from: email, calendar, contacts, web, files, expenses, notes\"," +
            "\"interval_min\":<how often in minutes it should run itself, 0 if only on demand>}. No prose."
        val raw = AgentClient.complete(sys, "Request: $request", 300)
        return try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (e: Exception) { JSONObject() }
    }
}
