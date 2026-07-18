package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.GmailClient
import com.agentos.shell.tools.GoogleAuth
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.MissionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACC = Color(0xFFE8642C)

/** Missions crawl the WEB for real targets (companies + contacts with websites & emails), draft a
 *  tailored message you REVIEW, then send from your Gmail. */
@Composable
fun MissionScreen(modifier: Modifier = Modifier, initialGoal: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var type by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var suggesting by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(MissionStore.mission(ctx)) }
    var prospects by remember { mutableStateOf<List<AgentClient.Prospect>>(emptyList()) }
    var contacted by remember { mutableStateOf(MissionStore.contacted(ctx)) }
    var replied by remember { mutableStateOf(MissionStore.replied(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<AgentClient.Prospect?>(null) }
    var draft by remember { mutableStateOf("") }
    var reviewBusy by remember { mutableStateOf(false) }
    var sendStatus by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(MissionStore.running(ctx)) }
    var runMsg by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<com.agentos.shell.tools.OutreachQueue.Report?>(null) }

    // Unique per person: two contacts at the same company must not collapse to one key (which would
    // make "contacted"/"replied" toggles bleed across both). Combine name + company.
    fun key(p: AgentClient.Prospect) = (p.name.trim() + "@" + p.company.trim()).trim('@').ifBlank { p.email }.ifBlank { p.website }
    fun cleanEmail(s: String): String = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(s)?.value ?: ""
    fun domainOf(url: String): String = try {
        if (url.isBlank()) "" else {
            var u = url.trim(); if (!u.startsWith("http")) u = "https://$u"
            (android.net.Uri.parse(u).host ?: "").removePrefix("www.")
        }
    } catch (e: Exception) { "" }
    // Real email if found, else a BEST-GUESS from the person's name + the company domain (first.last@ is
    // by far the most common corporate pattern), so you can still reach them.
    fun guessEmail(p: AgentClient.Prospect): String {
        val real = cleanEmail(p.email); if (real.isNotBlank()) return real
        val dom = domainOf(p.website); if (dom.isBlank()) return ""
        val parts = p.name.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.length > 1 }
        return when { parts.size >= 2 -> parts.first() + "." + parts.last() + "@" + dom; parts.size == 1 -> parts[0] + "@" + dom; else -> "" }
    }
    fun isGuessed(p: AgentClient.Prospect) = cleanEmail(p.email).isBlank() && guessEmail(p).isNotBlank()
    fun openUrl(u: String) { if (u.isBlank()) return; try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
    fun linkedInOf(p: AgentClient.Prospect): String {
        if (p.linkedin.isNotBlank()) return p.linkedin
        // Aim at the specific decision-maker: their name if known, else the CEO/leader at that company.
        val kw = if (p.name.isNotBlank()) p.name + " " + p.company else p.company + " " + p.role.ifBlank { "CEO founder" }
        return "https://www.linkedin.com/search/results/people/?keywords=" + java.net.URLEncoder.encode(kw.trim(), "UTF-8")
    }
    // Every found prospect goes INTO the brain, so it's searchable later ("who did I find for X?").
    fun storeProspects(g: String, ps: List<AgentClient.Prospect>) {
        scope.launch { withContext(Dispatchers.IO) {
            ps.forEach { p ->
                val line = "Prospect for \"$g\": ${p.name.ifBlank { p.company }} — ${p.company} — ${p.why}" +
                    (if (p.email.isNotBlank()) " — ${p.email}" else "") + (if (p.website.isNotBlank()) " — ${p.website}" else "")
                MessageStore.insertOne(ctx, "Prospects", "Mission", "me", "me", line)
            }
        } }
    }
    fun goalFor(t: String, d: String) = when (t) {
        "sell" -> "Find organizations that would buy: $d — and the SPECIFIC person to contact at each (the CEO, founder, or relevant decision-maker). Reach out to sell it."
        "job" -> "Find companies hiring for this — and the SPECIFIC person to reach at each (hiring manager, recruiter, or team lead who could refer me): $d."
        "intros" -> "Find SPECIFIC people worth connecting with (and their organization) for: $d. Reach out to each person."
        else -> d
    }
    fun placeholder(t: String) = when (t) {
        "sell" -> "What are you selling + to whom/where? (e.g. edge-AI runtime for satellite builders)"
        "job" -> "What job & where? (role, seniority, location)"
        "intros" -> "What are you looking for + where?"
        else -> "Details"
    }

    fun runMission(g: String) {
        if (busy || g.isBlank()) return
        busy = true; error = ""; prospects = emptyList()
        MissionStore.startCampaign(ctx, g, "", 10)
        goal = g; contacted = emptySet(); replied = emptySet()
        scope.launch {
            val ps = withContext(Dispatchers.IO) { AgentClient.findProspects(g, MemoryStore.fullProfile(ctx)) }
            prospects = ps
            if (ps.isEmpty()) error = "No results. Web search needs Claude — set replies/Heavy to Claude in Settings, then retry."
            else {
                // Progress is measured against the people we ACTUALLY found, not a fixed 10 — otherwise
                // reaching everyone (when only, say, 6 turn up) would cap the bar well below 40%.
                MissionStore.setTarget(ctx, ps.size)
                MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Mission: $g — ${ps.size} targets found")
                storeProspects(g, ps); MetricsStore.record(ctx, 900)
            }
            busy = false
        }
    }
    // Overnight auto-send: enqueue the emailable targets into the spam-safe drip at ~cap/day, human-paced.
    // Runs on the existing background worker; press Stop in the morning for the report.
    fun startOvernight() {
        val recips = prospects.mapNotNull { p ->
            guessEmail(p).takeIf { it.contains("@") }?.let { com.agentos.shell.tools.OutreachQueue.Recipient(p.name.ifBlank { p.company }, it) }
        }
        if (recips.isEmpty()) { runMsg = "No emailable targets yet — run a mission first. (LinkedIn-only contacts need tap-to-send, coming next.)"; return }
        busy = true
        scope.launch {
            val template = withContext(Dispatchers.IO) { AgentClient.outreachEmail(goal, "[FirstName]", "", MemoryStore.fullProfile(ctx)) }
            val cap = MissionStore.dailyCap(ctx); val spacing = (1440 / cap).coerceIn(2, 720)
            val added = withContext(Dispatchers.IO) { com.agentos.shell.tools.OutreachQueue.enqueue(ctx, recips, "Reaching out", template, "", spacing, "mission") }
            MissionStore.setRunning(ctx, true); running = true; report = null
            withContext(Dispatchers.IO) { MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Overnight mission on: $goal — $added queued (~$cap/day)") }
            runMsg = "Running — $added queued, ~$cap/day. Sleep on it; press Stop in the morning for your report."
            busy = false
        }
    }
    fun stopOvernight() {
        val n = com.agentos.shell.tools.OutreachQueue.cancelPending(ctx)
        val since = MissionStore.startedAt(ctx).takeIf { it > 0 } ?: (System.currentTimeMillis() - 86_400_000L)
        report = com.agentos.shell.tools.OutreachQueue.report(ctx, since)
        MissionStore.setRunning(ctx, false); running = false
        runMsg = "Stopped — $n queued message(s) cancelled."
    }
    fun findMore() {
        if (busy || goal.isBlank()) return
        busy = true
        scope.launch {
            val have = prospects.map { it.company.ifBlank { it.name } }.filter { it.isNotBlank() }
            val g2 = "$goal — give DIFFERENT targets, NOT any of these already found: " + have.joinToString(", ")
            val more = withContext(Dispatchers.IO) { AgentClient.findProspects(g2, MemoryStore.fullProfile(ctx)) }
            val seen = have.map { it.lowercase() }.toSet()
            val fresh = more.filter { (it.company.ifBlank { it.name }).lowercase() !in seen }
            if (fresh.isNotEmpty()) { storeProspects(goal, fresh); prospects = prospects + fresh; MissionStore.setTarget(ctx, prospects.size); MetricsStore.record(ctx, 300) }
            busy = false
        }
    }
    fun pick(t: String) {
        type = t; detail = ""; suggesting = true
        scope.launch { detail = withContext(Dispatchers.IO) { AgentClient.suggestMissionDetail(t, MemoryStore.fullProfile(ctx)) }; suggesting = false }
    }
    fun openReview(p: AgentClient.Prospect) {
        if (reviewBusy) return
        review = p; draft = ""; reviewBusy = true; sendStatus = ""
        scope.launch {
            // Professional email if we have an address; a shorter note for pasting into LinkedIn otherwise.
            draft = withContext(Dispatchers.IO) {
                if (guessEmail(p).isNotBlank()) AgentClient.outreachEmail(goal, p.name.ifBlank { p.company }, p.company, MemoryStore.fullProfile(ctx))
                else AgentClient.tailoredOutreach(goal, p.name.ifBlank { p.company }, "", p.company, MemoryStore.fullProfile(ctx))
            }
            reviewBusy = false
        }
    }
    fun sendNow(p: AgentClient.Prospect, body: String) {
        val email = guessEmail(p)
        val subject = "Reaching out" + (if (p.company.isNotBlank()) " — ${p.company}" else "")
        scope.launch {
            if (email.isNotBlank() && GoogleAuth.isConnected(ctx)) {
                val (ok, msg) = withContext(Dispatchers.IO) { GmailClient.send(ctx, email, subject, body) }
                sendStatus = if (ok) "Sent to $email ✓" else "Gmail error: $msg"
                if (ok) {
                    MissionStore.addContacted(ctx, key(p)); contacted = MissionStore.contacted(ctx); MetricsStore.record(ctx, 300); review = null
                    withContext(Dispatchers.IO) { MessageStore.insertOne(ctx, key(p), "Outreach", "me", "me", "Emailed $email — ${body.take(600)}") }
                }
            } else {
                try {
                    val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + email))
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject).putExtra(android.content.Intent.EXTRA_TEXT, body)
                    ctx.startActivity(android.content.Intent.createChooser(i, "Email").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {}
                MissionStore.addContacted(ctx, key(p)); contacted = MissionStore.contacted(ctx); MetricsStore.record(ctx, 300); review = null
            }
        }
    }

    LaunchedEffect(Unit) { if (initialGoal.isNotBlank() && initialGoal != goal) runMission(initialGoal) }

    @Composable
    fun bigBtn(label: String, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, fontSize = T.body, color = if (accent) T.bgElevated else T.ink, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (accent) (if (enabled) ACC else T.hairline) else T.bgElevated)
                .clickable(enabled = enabled) { onClick() }.padding(vertical = 15.dp))
    }
    @Composable
    fun typeCard(title: String, t: String, sub: String) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (type == t) ACC.copy(alpha = 0.12f) else T.bgElevated)
            .clickable(enabled = !busy) { pick(t) }.padding(16.dp)) {
            Text(title, fontSize = T.body, color = T.ink)
            Text(sub, fontSize = T.caption, color = T.inkFaint)
        }
    }
    @Composable
    fun field(value: String, hint: String, minH: Int, onChange: (String) -> Unit) {
        BasicTextField(value, onChange, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = minH.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint); inner() })
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Mission") { onBack() }

        if (goal.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(goal, fontSize = T.small, color = T.ink)
            Spacer(Modifier.height(6.dp))
            val pct = MissionStore.campaignProgress(ctx)
            Text("$pct% · ${contacted.size} reached · ${replied.size} replied", fontSize = T.caption, color = ACC)
            Spacer(Modifier.height(6.dp))

            // ── Overnight auto-send: start/stop + morning report ──
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                Text("Overnight auto-send", fontSize = T.body, color = T.ink)
                Text("Emails your mission targets on a human-paced drip (~${MissionStore.dailyCap(ctx)}/day) while you sleep. Stop for a report.",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(10.dp))
                if (!running) {
                    Text("Start overnight", fontSize = T.small, color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(ACC)
                            .clickable(enabled = !busy) { startOvernight() }.padding(horizontal = 18.dp, vertical = 10.dp))
                } else {
                    Text("Stop & see report", fontSize = T.small, color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.danger)
                            .clickable { stopOvernight() }.padding(horizontal = 18.dp, vertical = 10.dp))
                }
                if (runMsg.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(runMsg, fontSize = T.caption, color = ACC) }
                report?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    Text("${r.sent} sent · ${r.pending} queued · ${r.failed} failed", fontSize = T.small, color = T.ink)
                    Spacer(Modifier.height(8.dp))
                    val maxH = (r.hourly.maxOrNull() ?: 0).coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        for (h in 0 until 24) {
                            val frac = (r.hourly[h].toFloat() / maxH).coerceIn(0.03f, 1f)
                            Box(Modifier.weight(1f).fillMaxHeight(frac).padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(2.dp)).background(if (r.hourly[h] > 0) ACC else T.hairline))
                        }
                    }
                    Text("sends by hour (0–23)", fontSize = T.caption, color = T.inkFaint)
                    if (r.recent.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        r.recent.take(6).forEach { Text("· ${it.first}", fontSize = T.caption, color = T.inkSoft) }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(ACC))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("PICK A MISSION", fontSize = T.caption, color = T.inkFaint)
        typeCard("Find buyers for my product", "sell", "Web-find companies that would buy it")
        typeCard("Find a job", "job", "Web-find companies hiring + people to reach")
        typeCard("Find people & opportunities", "intros", "Web-find useful people/orgs to connect with")

        if (type.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(if (suggesting) "Suggesting from your brain…" else "Confirm the details (add a location for best results):", fontSize = T.caption, color = if (suggesting) ACC else T.inkFaint)
            Spacer(Modifier.height(6.dp))
            field(detail, placeholder(type), 52) { detail = it }
            Spacer(Modifier.height(8.dp))
            bigBtn(if (busy) "Searching the web…" else "Run mission", accent = true, enabled = !busy && detail.isNotBlank()) { runMission(goalFor(type, detail)) }
        }

        Spacer(Modifier.height(14.dp))
        field(custom, "Or type your own mission (include a location)…", 52) { custom = it }
        Spacer(Modifier.height(8.dp))
        bigBtn(if (busy) "Searching the web…" else "Run custom mission", accent = false, enabled = !busy && custom.isNotBlank()) { runMission(custom) }

        if (busy) { Spacer(Modifier.height(10.dp)); Text("Searching the web for the right targets…", fontSize = T.caption, color = ACC) }
        if (error.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(error, fontSize = T.caption, color = T.danger) }

        if (prospects.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("${prospects.size} targets found", fontSize = T.caption, color = T.inkFaint)
            prospects.forEach { p ->
                val done = key(p) in contacted
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(p.name.ifBlank { p.company } + (if (p.name.isNotBlank() && p.role.isNotBlank()) " · ${p.role}" else ""), fontSize = T.body, color = T.ink)
                    if (p.company.isNotBlank() && p.name.isNotBlank()) Text(p.company, fontSize = T.caption, color = T.inkFaint)
                    else if (p.name.isBlank() && p.role.isNotBlank()) Text("Reach the ${p.role}", fontSize = T.caption, color = T.inkFaint)
                    if (p.why.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(p.why, fontSize = T.caption, color = T.inkSoft) }
                    val em = guessEmail(p)
                    if (em.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(em + (if (isGuessed(p)) "  (guessed)" else "  (found)"), fontSize = T.caption, color = T.inkFaint) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.website.isNotBlank()) Text("Website", fontSize = T.small, color = ACC, modifier = Modifier.clickable { openUrl(p.website) }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp))
                        Text("LinkedIn", fontSize = T.small, color = ACC, modifier = Modifier.clickable { openUrl(linkedInOf(p)) }.padding(top = 4.dp, bottom = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    val hasEmail = em.isNotBlank()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (done) "Reviewed ✓ · again" else if (hasEmail) "Draft & send email" else "Draft note (paste to LinkedIn)",
                            fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (done) T.inkFaint else ACC)
                                .clickable { openReview(p) }.padding(vertical = 10.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (key(p) in replied) "Replied" else "Mark reply", fontSize = T.small, color = if (key(p) in replied) T.bgElevated else ACC, textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (key(p) in replied) ACC else T.hairline)
                                .clickable { MissionStore.toggleReplied(ctx, key(p)); replied = MissionStore.replied(ctx) }.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            bigBtn(if (busy) "Finding more…" else "Find more targets", accent = false, enabled = !busy) { findMore() }
        }
        Spacer(Modifier.height(28.dp))
    }

    // Draft REVIEW dialog — always see it before anything is sent.
    review?.let { p ->
        Dialog(onDismissRequest = { review = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(18.dp)).background(T.bg).padding(18.dp)) {
                Text("Review before sending", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(p.name.ifBlank { p.company } + (if (guessEmail(p).isNotBlank()) "  ·  ${guessEmail(p)}${if (isGuessed(p)) " (guessed)" else ""}" else "  ·  no email — will copy"), fontSize = T.small, color = T.ink)
                Spacer(Modifier.height(10.dp))
                if (reviewBusy) Text("Writing your draft…", fontSize = T.small, color = ACC)
                else BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp).verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp))
                if (sendStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(sendStatus, fontSize = T.caption, color = ACC) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasEmail = guessEmail(p).isNotBlank()
                    Text(if (hasEmail && GoogleAuth.isConnected(ctx)) "Send via Gmail" else if (hasEmail) "Open email" else "Copy & open LinkedIn",
                        fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(ACC)
                            .clickable(enabled = !reviewBusy) {
                                if (hasEmail) sendNow(p, draft)
                                else { clip.setText(AnnotatedString(draft)); openUrl(linkedInOf(p)); MissionStore.addContacted(ctx, key(p)); contacted = MissionStore.contacted(ctx); MetricsStore.record(ctx, 180); review = null }
                            }.padding(vertical = 11.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { clip.setText(AnnotatedString(draft)) }.padding(horizontal = 12.dp, vertical = 11.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Close", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { review = null }.padding(horizontal = 10.dp, vertical = 11.dp))
                }
            }
        }
    }
}
