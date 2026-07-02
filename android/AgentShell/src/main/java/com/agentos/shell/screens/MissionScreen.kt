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
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.MissionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACC = Color(0xFFE8642C)

/** Missions as one-tap campaigns with the specifics you (or your brain) fill in: pick a type, confirm
 *  WHAT you're selling / WHAT job / WHO to email, then get people + tailored messages to send. */
@Composable
fun MissionScreen(modifier: Modifier = Modifier, initialGoal: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var type by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var suggesting by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf(MissionStore.mission(ctx)) }
    var query by remember { mutableStateOf(MissionStore.query(ctx)) }
    var message by remember { mutableStateOf(MissionStore.message(ctx)) }
    var people by remember { mutableStateOf<List<ConnectionStore.Conn>>(emptyList()) }
    var emails by remember { mutableStateOf<List<AgentClient.EmailContact>>(emptyList()) }
    var contacted by remember { mutableStateOf(MissionStore.contacted(ctx)) }
    var replied by remember { mutableStateOf(MissionStore.replied(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }

    fun firstName(n: String) = n.trim().split(" ").firstOrNull().orEmpty()
    fun placeholder(t: String) = when (t) {
        "sell" -> "What are you selling? (product or service)"
        "job" -> "What job & where? (role, company, location)"
        "intros" -> "What are you looking for? (partners, advisors, customers)"
        "email" -> "Who to email? (e.g. PMs at fintech startups in SF)"
        else -> "Details"
    }
    fun goalFor(t: String, d: String) = when (t) {
        "sell" -> "Find people in my network who would buy: $d. Reach out to sell it."
        "job" -> "Find people in my network who can help me get this job: $d. Reach out for referrals or intros."
        "intros" -> "Find valuable people in my network for: $d. Reach out to reconnect and get introductions."
        "email" -> "Email outreach: $d"
        else -> d
    }
    fun queryFor(t: String) = when (t) {
        "sell" -> "founder OR ceo OR owner OR director OR manager OR head OR vp"
        "job" -> "recruiter OR talent OR hiring OR founder OR ceo OR manager OR head"
        "intros" -> "founder OR ceo OR partner OR director OR investor OR advisor OR head"
        else -> ""
    }

    fun runNetwork(g: String, q: String) {
        if (busy) return
        busy = true
        MissionStore.startCampaign(ctx, g, q, 10)
        goal = g; query = q; contacted = emptySet(); replied = emptySet(); message = ""; people = emptyList(); emails = emptyList()
        scope.launch {
            val ppl = withContext(Dispatchers.IO) { ConnectionStore.search(ctx, q, 20) }
            val msg = withContext(Dispatchers.IO) { AgentClient.networkOutreach(g, MemoryStore.fullProfile(ctx)) }
            people = ppl; message = msg; MissionStore.setMessage(ctx, msg)
            MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Started mission: $g (${ppl.size} people)")
            MetricsStore.record(ctx, 600)
            busy = false
        }
    }
    fun runEmail(g: String, d: String) {
        if (busy) return
        busy = true
        MissionStore.startCampaign(ctx, g, "", 10)
        goal = g; query = ""; contacted = emptySet(); replied = emptySet(); people = emptyList(); emails = emptyList()
        scope.launch {
            val cs = withContext(Dispatchers.IO) { AgentClient.findContactEmails(d, MemoryStore.fullProfile(ctx)) }
            emails = cs
            MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Email mission: $d (${cs.size} contacts)")
            MetricsStore.record(ctx, 600)
            busy = false
        }
    }
    fun pick(t: String) {
        type = t; detail = ""; suggesting = true
        scope.launch { detail = withContext(Dispatchers.IO) { AgentClient.suggestMissionDetail(t, MemoryStore.fullProfile(ctx)) }; suggesting = false }
    }
    fun run() {
        if (detail.isBlank()) return
        if (type == "email") runEmail(goalFor("email", detail), detail) else runNetwork(goalFor(type, detail), queryFor(type))
    }

    LaunchedEffect(Unit) {
        if (initialGoal.isNotBlank() && initialGoal != goal) runNetwork(initialGoal, initialGoal)
        else if (query.isNotBlank()) people = withContext(Dispatchers.IO) { ConnectionStore.search(ctx, query, 20) }
    }
    fun send(c: ConnectionStore.Conn) {
        if (sending.isNotEmpty()) return
        sending = c.name
        scope.launch {
            val msg = withContext(Dispatchers.IO) { AgentClient.tailoredOutreach(goal, c.name, c.role, c.company, MemoryStore.fullProfile(ctx)) }
            clip.setText(AnnotatedString(msg))
            val url = c.url.ifBlank { "https://www.linkedin.com/search/results/people/?keywords=" + java.net.URLEncoder.encode(c.name, "UTF-8") }
            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            MissionStore.addContacted(ctx, c.name); contacted = MissionStore.contacted(ctx); sending = ""
        }
    }
    fun emailSend(c: AgentClient.EmailContact) {
        if (sending.isNotEmpty()) return
        sending = c.email
        scope.launch {
            val body = withContext(Dispatchers.IO) { AgentClient.tailoredOutreach(goal, c.name, "", c.company, MemoryStore.fullProfile(ctx)) }
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                    .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(c.email))
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Reaching out")
                    .putExtra(android.content.Intent.EXTRA_TEXT, body).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(android.content.Intent.createChooser(i, "Email").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
            MissionStore.addContacted(ctx, c.email); contacted = MissionStore.contacted(ctx); sending = ""
        }
    }

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
            Text("$pct% · ${replied.size} replied · ${contacted.size} reached", fontSize = T.caption, color = ACC)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(ACC))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("PICK A MISSION", fontSize = T.caption, color = T.inkFaint)
        typeCard("Find buyers for my product", "sell", "Reach people who'd buy what you sell")
        typeCard("Find a job through my network", "job", "Reach people who can refer or hire you")
        typeCard("Get intros & opportunities", "intros", "Reconnect and get introductions")
        typeCard("Email outreach (find emails)", "email", "Search the web for contacts + their emails")

        if (type.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(if (suggesting) "Suggesting from your brain…" else "Confirm the details (edit as you like):", fontSize = T.caption, color = if (suggesting) ACC else T.inkFaint)
            Spacer(Modifier.height(6.dp))
            field(detail, placeholder(type), 52) { detail = it }
            Spacer(Modifier.height(8.dp))
            bigBtn(if (busy) "Working…" else "Run mission", accent = true, enabled = !busy && detail.isNotBlank()) { run() }
        }

        Spacer(Modifier.height(14.dp))
        field(custom, "Or type your own mission…", 52) { custom = it }
        Spacer(Modifier.height(8.dp))
        bigBtn(if (busy) "Working…" else "Run custom mission", accent = false, enabled = !busy && custom.isNotBlank()) { runNetwork(custom, custom) }

        if (busy) { Spacer(Modifier.height(10.dp)); Text("Finding people & writing your message…", fontSize = T.caption, color = ACC) }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text("MESSAGE STYLE — each person gets their own tailored version when you tap Send", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            field(message, "", 90) { message = it; MissionStore.setMessage(ctx, it) }
        }

        // Network people.
        if (people.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("${people.size} people — tap Send to message them", fontSize = T.caption, color = T.inkFaint)
            people.forEach { c ->
                val isMsg = c.name in contacted; val isRep = c.name in replied
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(c.name, fontSize = T.body, color = T.ink)
                    if (c.role.isNotBlank() || c.company.isNotBlank())
                        Text(listOf(c.role, c.company).filter { it.isNotBlank() }.joinToString(" @ "), fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sending == c.name) "Writing…" else if (isMsg) "Messaged" else "Send", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (isMsg) T.inkFaint else ACC)
                                .clickable(enabled = sending.isEmpty()) { send(c) }.padding(vertical = 10.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRep) "Replied" else "Mark reply", fontSize = T.small, color = if (isRep) T.bgElevated else ACC, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (isRep) ACC else T.hairline)
                                .clickable { MissionStore.toggleReplied(ctx, c.name); replied = MissionStore.replied(ctx) }.padding(vertical = 10.dp))
                    }
                }
            }
        }

        // Email contacts (from the web).
        if (emails.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("${emails.size} contacts found — tap Email to reach out", fontSize = T.caption, color = T.inkFaint)
            emails.forEach { c ->
                val isMsg = c.email in contacted
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(c.name.ifBlank { c.email }, fontSize = T.body, color = T.ink)
                    Text(listOf(c.email, c.company).filter { it.isNotBlank() }.joinToString(" · "), fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Text(if (sending == c.email) "Writing…" else if (isMsg) "Emailed" else "Email", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(if (isMsg) T.inkFaint else ACC)
                            .clickable(enabled = sending.isEmpty()) { emailSend(c) }.padding(vertical = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
