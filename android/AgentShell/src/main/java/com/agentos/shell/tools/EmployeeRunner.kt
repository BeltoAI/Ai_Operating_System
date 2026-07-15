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
        "{\"type\":\"send_email|add_event|save_lead|post|note|none\",\"to\":\"\",\"subject\":\"\",\"body\":\"\"," +
        "\"title\":\"\",\"start\":\"2026-07-15T15:00\",\"end\":\"2026-07-15T15:30\",\"meet\":false,\"attendees\":[]," +
        "\"target\":\"\",\"text\":\"\",\"name\":\"\",\"email\":\"\",\"role\":\"\",\"company\":\"\",\"extra\":{}}"

    /** Execute ONE action fully (MAX automation — reversible things just happen). Returns a human result line. */
    private fun execAction(ctx: Context, emp: EmployeeStore.Employee, act: org.json.JSONObject?, srcMessage: String): String {
        val type = act?.optString("type").orEmpty()
        return try {
            when (type) {
                "send_email" -> {
                    val to = act!!.optString("to").trim(); val body = act.optString("body").trim()
                    if (to.contains("@") && body.isNotBlank() && !AgentClient.looksLikeError(body)) {
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
                    val target = act!!.optString("target").trim(); val title = act.optString("title").trim(); val txt = act.optString("text").trim()
                    if (txt.isNotBlank() && !AgentClient.looksLikeError(txt)) {
                        AgentDraft.set(ctx, emp.id, "post", target, title, txt)
                        "drafted a post" + (if (target.isNotBlank()) " for $target" else "") + " — ready to review & post"
                    } else ""
                }
                "note" -> {
                    val n = act!!.optString("text").trim().ifBlank { act.optString("body").trim() }
                    if (n.isNotBlank()) { try { MemoryLog.add(ctx, "note", "${emp.name}: note", n.take(500), "Team") } catch (e: Exception) {}; "saved a note to your brain ✓" } else ""
                }
                else -> ""
            }
        } catch (e: Exception) { "action failed: ${e.message}" }
    }

    data class ChainResult(val summary: String, val needs: String, val actions: Int, val inTok: Int, val outTok: Int)

    /**
     * The AGENTIC LOOP — the agent works toward [task] over multiple steps: each step it may take one action,
     * sees the result, and continues, until it's done, needs the owner, or hits the step/token cap. MAX
     * automation: reversible actions (email, event, lead, note) just execute. Grounded in fed docs + brain + web.
     */
    fun runChain(ctx: Context, emp: EmployeeStore.Employee, task: String, history: String = "", speaker: String = "",
                 maxSteps: Int = 6, tokenCap: Int = 45000): ChainResult {
        val owner = MemoryStore.ownerName(ctx).ifBlank { "the owner" }
        val brain = try { BrainContext.build(ctx, task) } catch (e: Exception) { "" }
        val kb = try { AgentKnowledge.retrieve(ctx, emp.id, task, 2200) } catch (e: Exception) { "" }
        val caps = try { Capabilities.summary(ctx) } catch (e: Exception) { "" }
        val cal = try { if (CalendarTool.hasPermission(ctx)) CalendarTool.upcoming(ctx) else "" } catch (e: Exception) { "" }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(java.util.Date())
        val ctxBlock = "Current time: $now\n" +
            (if (kb.isNotBlank()) "YOUR OWN DOCUMENTS (your PRIMARY source):\n$kb\n\n" else "") +
            (if (cal.isNotBlank()) "YOUR CALENDAR:\n${cal.take(1000)}\n\n" else "") +
            (if (history.isNotBlank()) "RECENT TEAM-CHAT (each line 'Sender: message'):\n$history\n\n" else "") +
            "What you know about $owner:\n${brain.take(2600)}\n"
        val steps = ArrayList<String>()
        var inSum = 0; var outSum = 0; var didAny = 0; var needs = ""
        for (i in 1..maxSteps.coerceIn(1, 8)) {
            val sys = "You are ${emp.name}, the ${emp.role} on $owner's team. Goal for this run: \"$task\". $caps " +
                "You work in STEPS: each step you may take ONE action; you'll then see its result and continue. You have live " +
                "web search every step. MAX AUTOMATION — actually DO things (send the email, create the event, save the lead, " +
                "draft the post) without asking permission for reversible actions. Only set \"needs\" if you literally cannot " +
                "proceed without $owner (a missing address, a private detail, a genuine judgment call). " +
                "Output ONLY compact JSON {\"say\":\"one short progress line, past/pres tense\",\"action\":$ACTION_SCHEMA," +
                "\"needs\":\"empty unless truly blocked\",\"done\":false}. Set done:true the moment the whole goal is accomplished. No prose, no fences."
            val user = ctxBlock + "\nSTEPS DONE SO FAR:\n" + (if (steps.isEmpty()) "(none yet)" else steps.joinToString("\n")) +
                "\n\n" + (if (speaker.isNotBlank() && !speaker.equals(owner, true) && speaker != "You") "Request from $speaker: " else "") + task +
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
        val summary = (if (steps.isEmpty()) "Worked on it." else steps.joinToString("\n")) +
            (if (needs.isNotBlank()) "\n\n⏸ Needs you: $needs" else "")
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
                caps + " You run UNSUPERVISED — take the SINGLE most useful next step toward your goal right now, and when " +
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
                "send_email only to a REAL address, body written in $owner's own voice. add_event uses local ISO times. " +
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
            val user = "Current time: $now\n\n" + (if (live.isNotEmpty()) live.toString() else "") +
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
                        if (to.contains("@") && body.isNotBlank() && !AgentClient.looksLikeError(body)) {
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
                        if (detail.isNotBlank()) { try { MemoryLog.add(ctx, "note", "${emp.name}: note", detail.take(600), "Team") } catch (e: Exception) {}; outcome = "Saved a note to your brain"; didAction = 1 }
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
            // Ping the lock screen when there's something to see — a result done, or a genuine ask.
            try {
                if (needsEff.isNotBlank()) EmployeeNotify.post(ctx, emp.id, "${emp.name} needs you", needsEff, true)
                else if (didAction == 1) EmployeeNotify.post(ctx, emp.id, "${emp.name} · ${emp.role}", outcome.ifBlank { did }, false)
            } catch (e: Exception) {}
            // Post to the Telegram team chat too (if connected) so you + teammates see it live and can reply.
            try {
                if (TeamChat.isConnected(ctx)) {
                    val line = if (needsEff.isNotBlank()) "needs you: $needsEff" else outcome.ifBlank { did }
                    TeamChat.post(ctx, emp.name, line)
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
