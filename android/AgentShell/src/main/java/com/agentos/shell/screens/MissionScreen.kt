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

    fun key(p: AgentClient.Prospect) = p.company.ifBlank { p.name }.ifBlank { p.email }
    fun cleanEmail(s: String): String = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(s)?.value ?: ""
    fun goalFor(t: String, d: String) = when (t) {
        "sell" -> "Find organizations that would buy: $d. (Reach out to sell it.)"
        "job" -> "Find companies hiring for this and people who can refer me: $d."
        "intros" -> "Find people/organizations worth connecting with for: $d."
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
            else { MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Mission: $g — ${ps.size} targets found"); MetricsStore.record(ctx, 900) }
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
            draft = withContext(Dispatchers.IO) { AgentClient.tailoredOutreach(goal, p.name.ifBlank { p.company }, "", p.company, MemoryStore.fullProfile(ctx)) }
            reviewBusy = false
        }
    }
    fun sendNow(p: AgentClient.Prospect, body: String) {
        val email = cleanEmail(p.email)
        val subject = "Reaching out" + (if (p.company.isNotBlank()) " — ${p.company}" else "")
        scope.launch {
            if (email.isNotBlank() && GoogleAuth.isConnected(ctx)) {
                val (ok, msg) = withContext(Dispatchers.IO) { GmailClient.send(ctx, email, subject, body) }
                sendStatus = if (ok) "Sent to $email ✓" else "Gmail error: $msg"
                if (ok) { MissionStore.addContacted(ctx, key(p)); contacted = MissionStore.contacted(ctx); MetricsStore.record(ctx, 300); review = null }
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
                    Text(p.name.ifBlank { p.company }, fontSize = T.body, color = T.ink)
                    if (p.company.isNotBlank() && p.name.isNotBlank()) Text(p.company, fontSize = T.caption, color = T.inkFaint)
                    if (p.why.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(p.why, fontSize = T.caption, color = T.inkSoft) }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.website.isNotBlank()) {
                            Text("Website", fontSize = T.small, color = ACC, modifier = Modifier.clickable {
                                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.website)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                            }.padding(end = 16.dp, top = 4.dp, bottom = 4.dp))
                        }
                        Text(if (done) "Reviewed ✓ · again" else if (cleanEmail(p.email).isNotBlank()) "Draft & review" else "Draft (no email found)",
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
        }
        Spacer(Modifier.height(28.dp))
    }

    // Draft REVIEW dialog — always see it before anything is sent.
    review?.let { p ->
        Dialog(onDismissRequest = { review = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(18.dp)).background(T.bg).padding(18.dp)) {
                Text("Review before sending", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(p.name.ifBlank { p.company } + (if (cleanEmail(p.email).isNotBlank()) "  ·  ${cleanEmail(p.email)}" else "  ·  no email — will copy"), fontSize = T.small, color = T.ink)
                Spacer(Modifier.height(10.dp))
                if (reviewBusy) Text("Writing your draft…", fontSize = T.small, color = ACC)
                else BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp).verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp))
                if (sendStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(sendStatus, fontSize = T.caption, color = ACC) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasEmail = cleanEmail(p.email).isNotBlank()
                    Text(if (hasEmail && GoogleAuth.isConnected(ctx)) "Send via Gmail" else if (hasEmail) "Open email" else "Copy",
                        fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(ACC)
                            .clickable(enabled = !reviewBusy) { if (hasEmail) sendNow(p, draft) else { clip.setText(AnnotatedString(draft)); review = null } }.padding(vertical = 11.dp))
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
