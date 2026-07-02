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

/** Missions as one-tap outreach campaigns: pick a goal → get the people from your network + a ready
 *  message → tap Send → progress rises as people reply. Built to be idiot-simple. */
@Composable
fun MissionScreen(modifier: Modifier = Modifier, initialGoal: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var goal by remember { mutableStateOf(MissionStore.mission(ctx)) }
    var query by remember { mutableStateOf(MissionStore.query(ctx)) }
    var message by remember { mutableStateOf(MissionStore.message(ctx)) }
    var people by remember { mutableStateOf<List<ConnectionStore.Conn>>(emptyList()) }
    var contacted by remember { mutableStateOf(MissionStore.contacted(ctx)) }
    var replied by remember { mutableStateOf(MissionStore.replied(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }

    fun firstName(n: String) = n.trim().split(" ").firstOrNull().orEmpty()

    fun runCampaign(g: String, q: String, target: Int) {
        if (busy) return
        busy = true
        MissionStore.startCampaign(ctx, g, q, target)
        goal = g; query = q; contacted = emptySet(); replied = emptySet(); message = ""; people = emptyList()
        scope.launch {
            val ppl = withContext(Dispatchers.IO) { ConnectionStore.search(ctx, q, 20) }
            val msg = withContext(Dispatchers.IO) { AgentClient.networkOutreach(g, MemoryStore.fullProfile(ctx)) }
            people = ppl; message = msg; MissionStore.setMessage(ctx, msg)
            MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Started mission: $g — ${ppl.size} people found")
            MetricsStore.record(ctx, 600)
            busy = false
        }
    }
    var sending by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (initialGoal.isNotBlank() && initialGoal != goal) runCampaign(initialGoal, initialGoal, 10)
        else if (query.isNotBlank()) people = withContext(Dispatchers.IO) { ConnectionStore.search(ctx, query, 20) }
    }
    fun send(c: ConnectionStore.Conn) {
        if (sending.isNotEmpty()) return
        sending = c.name
        scope.launch {
            // Write a message tailored to THIS person (their role/company + the goal), not a template.
            val msg = withContext(Dispatchers.IO) { AgentClient.tailoredOutreach(goal, c.name, c.role, c.company, MemoryStore.fullProfile(ctx)) }
            clip.setText(AnnotatedString(msg))
            val url = c.url.ifBlank { "https://www.linkedin.com/search/results/people/?keywords=" + java.net.URLEncoder.encode(c.name, "UTF-8") }
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
            MissionStore.addContacted(ctx, c.name); contacted = MissionStore.contacted(ctx)
            sending = ""
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
    fun template(title: String, sub: String, onClick: () -> Unit) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
            .clickable(enabled = !busy) { onClick() }.padding(16.dp)) {
            Text(title, fontSize = T.body, color = T.ink)
            Text(sub, fontSize = T.caption, color = T.inkFaint)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Mission") { onBack() }

        // Active campaign header + progress.
        if (goal.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(goal, fontSize = T.body, color = T.ink)
            Spacer(Modifier.height(6.dp))
            val pct = MissionStore.campaignProgress(ctx)
            Text("$pct% · ${replied.size} replied of ${MissionStore.target(ctx)} · ${contacted.size} messaged", fontSize = T.caption, color = ACC)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(ACC))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("PICK A MISSION", fontSize = T.caption, color = T.inkFaint)
        template("Find buyers for my product", "Reach people in your network who'd buy what you sell") {
            runCampaign("Find people in my network who would buy my product or service, and reach out to them", "founder OR ceo OR owner OR director OR manager OR head of OR vp", 10)
        }
        template("Find a job through my network", "Reach people who can refer or hire you") {
            runCampaign("Find people in my network who can help me get a job — referrals, hiring managers, founders — and reach out", "recruiter OR talent OR hiring OR founder OR ceo OR manager OR head of", 10)
        }
        template("Get intros & new opportunities", "Reconnect with useful people and get introductions") {
            runCampaign("Find valuable people in my network to reconnect with and get introductions, and reach out", "founder OR ceo OR partner OR director OR investor OR advisor OR head of", 10)
        }

        Spacer(Modifier.height(14.dp))
        BasicTextField(custom, { custom = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (custom.isEmpty()) Text("Or type your own: who to reach + why", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(8.dp))
        bigBtn(if (busy) "Working…" else "Run my mission", accent = true, enabled = !busy && (custom.isNotBlank() || goal.isNotBlank())) {
            if (custom.isNotBlank()) runCampaign(custom, custom, 10)
        }

        if (busy) { Spacer(Modifier.height(8.dp)); Text("Finding people & writing your message…", fontSize = T.caption, color = ACC) }

        // The shared message (editable).
        if (message.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text("MESSAGE STYLE — each person gets their own tailored version when you tap Send", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            BasicTextField(message, { message = it; MissionStore.setMessage(ctx, it) }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp))
        }

        // The people.
        if (people.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("${people.size} people — tap Send to message them", fontSize = T.caption, color = T.inkFaint)
            people.forEach { c ->
                val isMsg = c.name in contacted
                val isRep = c.name in replied
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(c.name, fontSize = T.body, color = T.ink)
                    if (c.role.isNotBlank() || c.company.isNotBlank())
                        Text(listOf(c.role, c.company).filter { it.isNotBlank() }.joinToString(" @ "), fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sending == c.name) "Writing…" else if (isMsg) "Messaged ✓ · again" else "Send", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (isMsg) T.inkFaint else ACC)
                                .clickable(enabled = sending.isEmpty()) { send(c) }.padding(vertical = 10.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRep) "Replied 👍" else "Mark reply", fontSize = T.small, color = if (isRep) T.bgElevated else ACC, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (isRep) ACC else T.hairline)
                                .clickable { MissionStore.toggleReplied(ctx, c.name); replied = MissionStore.replied(ctx) }.padding(vertical = 10.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Send copies the message + opens their LinkedIn — paste & send. When someone replies, tap Replied and your progress climbs.",
                fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(28.dp))
    }
}
