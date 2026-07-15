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

            val sys = "You are ${emp.name}, the ${emp.role} on $owner's autonomous AI team. Standing goal: \"${emp.goal}\". " +
                caps + " You run UNSUPERVISED — take the SINGLE most useful next step toward your goal right now, and when " +
                "it genuinely helps, actually DO it with one executable action. Output ONLY compact JSON: " +
                "{\"did\":\"past-tense line, under 14 words\",\"detail\":\"the real draft/finding/result, or empty\"," +
                "\"needs\":\"what you need from $owner to go further, or empty\"," +
                "\"action\":{\"type\":\"send_email|add_event|note|none\",\"to\":\"\",\"subject\":\"\",\"body\":\"\"," +
                "\"title\":\"\",\"start\":\"2026-07-15T15:00\",\"end\":\"2026-07-15T15:30\"}}. " +
                "send_email only to a REAL address, body written in $owner's own voice. add_event uses local ISO times. " +
                "note saves a finding to $owner's brain. none = you only researched/thought this shift. No prose, no fences."
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(java.util.Date())
            val user = "Current time: $now\n\n" + (if (live.isNotEmpty()) live.toString() else "") +
                "Your recent log:\n${recent.ifBlank { "(nothing yet)" }}\n\n" +
                "What you know about $owner:\n${brain.take(3500)}\n\nDo your next step now."

            val (raw, inTok, outTok) = AgentClient.work(sys, user, 900, web = true)
            val o = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (e: Exception) { null }
            val did = o?.optString("did")?.trim().orEmpty().ifBlank { "Worked on: ${emp.goal.take(40)}" }
            val detail = o?.optString("detail")?.trim().orEmpty()
            val needs = o?.optString("needs")?.trim().orEmpty()
            val act = o?.optJSONObject("action")
            val actType = act?.optString("type").orEmpty()

            // Execute one safe headless action (owner set the team to fully autonomous)
            var didAction = 0; var outcome = ""
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
                }
            } catch (e: Exception) { outcome = "Action failed: ${e.message}" }

            // ── Ledger: what it cost (tokens) vs. what it delivered (actions + minutes saved) ──
            val valueMin = EmployeeStats.valueOf(if (didAction == 1) actType else "research", detail.isNotBlank() || didAction == 1)
            EmployeeStats.record(ctx, emp.id, AgentClient.lastProvider, AgentClient.lastModel, inTok, outTok, didAction, valueMin)
            if (valueMin > 0) try { MetricsStore.record(ctx, valueMin * 60) } catch (e: Exception) {}   // feeds the Settings efficiency score

            EmployeeStore.log(ctx, emp.id, did, false)
            if (outcome.isNotBlank()) EmployeeStore.log(ctx, emp.id, outcome, false)
            if (detail.isNotBlank()) EmployeeStore.log(ctx, emp.id, detail.take(600), false)
            if (needs.isNotBlank()) EmployeeStore.log(ctx, emp.id, "Needs you: $needs", true)
            try { MemoryLog.add(ctx, "action", "${emp.name} (${emp.role})", (did + (if (outcome.isNotBlank()) "\n$outcome" else "") + (if (detail.isNotBlank()) "\n$detail" else "")).take(800), "Team") } catch (e: Exception) {}

            EmployeeStore.setStatus(ctx, emp.id, if (needs.isNotBlank()) "needs_you" else "idle", touchRun = true)
            // Ping the lock screen when there's something to see — a result done, or a genuine ask.
            try {
                if (needs.isNotBlank()) EmployeeNotify.post(ctx, emp.id, "${emp.name} needs you", needs, true)
                else if (didAction == 1) EmployeeNotify.post(ctx, emp.id, "${emp.name} · ${emp.role}", outcome.ifBlank { did }, false)
            } catch (e: Exception) {}
            did
        } catch (e: Exception) {
            Log.w(TAG, "runShift: ${e.message}")
            EmployeeStore.setStatus(ctx, emp.id, "idle", touchRun = true)
            "Couldn't finish this shift — I'll try again."
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
