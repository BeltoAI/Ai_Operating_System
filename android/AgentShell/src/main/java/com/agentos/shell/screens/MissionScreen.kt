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
import com.agentos.shell.tools.ChecklistStore
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.MissionStore
import com.agentos.shell.tools.PaperStore
import com.agentos.shell.tools.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACC = Color(0xFFE8642C)

/** The Mission, on its own clean screen: set a goal, get a plan, track progress, and have SlyOS draft
 *  your next move. Big buttons, plain language — hard to get wrong. */
@Composable
fun MissionScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var mission by remember { mutableStateOf(MissionStore.mission(ctx)) }
    var saved by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf(MissionStore.milestones(ctx)) }
    var lastCheck by remember { mutableStateOf(MissionStore.latest(ctx)) }
    var busy by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var moveLabel by remember { mutableStateOf("") }
    var moveText by remember { mutableStateOf("") }
    var moveTask by remember { mutableStateOf("") }

    fun ctxBlob(): String {
        val about = MemoryStore.about(ctx)
        val tasks = ChecklistStore.load(ctx).joinToString("\n") { "- ${it.text} (${if (it.done) "done" else "todo"})" }
        val papers = PaperStore.list(ctx).joinToString("\n") { "Paper: ${it.title}" }
        val hits = MessageStore.search(ctx, mission, 40).joinToString("\n") { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
        val matches = ConnectionStore.search(ctx, mission, 40).joinToString("\n") { it.name + (if (it.role.isNotBlank()) " — ${it.role}" else "") + (if (it.company.isNotBlank()) " @ ${it.company}" else "") }
        val sem = VectorStore.search(ctx, mission, 10).joinToString("\n") { it.contact + ": " + it.body }
        val done = plan.filter { it.done }.joinToString("; ") { it.text }
        return buildString {
            if (about.isNotBlank()) append("About me: ").append(about).append("\n")
            append("LinkedIn connections: ").append(ConnectionStore.count(ctx)).append("\n")
            if (matches.isNotBlank()) append("People in my network relevant to this goal:\n").append(matches).append("\n")
            if (done.isNotBlank()) append("Milestones I've completed: ").append(done).append("\n")
            if (tasks.isNotBlank()) append("Checklist:\n").append(tasks).append("\n")
            if (papers.isNotBlank()) append(papers).append("\n")
            if (sem.isNotBlank()) append("Relevant memories:\n").append(sem).append("\n")
            if (hits.isNotBlank()) append("Messages related to the goal:\n").append(hits)
        }.take(9000)
    }
    fun saveGoal() {
        MissionStore.setMission(ctx, mission); saved = true
        plan = MissionStore.milestones(ctx); lastCheck = MissionStore.latest(ctx)
    }
    fun makePlan() {
        if (busy.isNotEmpty() || mission.isBlank()) return
        if (!saved) saveGoal()
        busy = "plan"; err = ""
        scope.launch {
            val steps = withContext(Dispatchers.IO) { AgentClient.planMission(mission, ctxBlob()) }
            if (steps.isNotEmpty()) { MissionStore.setPlan(ctx, steps); plan = MissionStore.milestones(ctx) }
            else err = "Couldn't build a plan — likely a rate limit. Route replies/Heavy to Claude in Settings and retry."
            busy = ""
        }
    }
    fun assess() {
        if (busy.isNotEmpty() || mission.isBlank()) return
        if (!saved) saveGoal()
        busy = "assess"; err = ""
        scope.launch {
            val days = ((System.currentTimeMillis() - MissionStore.since(ctx)) / 86_400_000L)
            val a = withContext(Dispatchers.IO) { AgentClient.assessMission(mission, ctxBlob(), "$days days ago") }
            if (a.percent >= 0) {
                MissionStore.addCheck(ctx, a.percent, a.argument, a.next)
                lastCheck = MissionStore.latest(ctx)
                MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Mission check — ${a.percent}%: ${a.argument.take(400)}")
            } else err = a.argument
            busy = ""
        }
    }
    fun act() {
        if (busy.isNotEmpty() || mission.isBlank()) return
        if (!saved) saveGoal()
        busy = "act"; err = ""; moveText = ""
        scope.launch {
            val open = plan.firstOrNull { !it.done }?.text ?: ""
            val mv = withContext(Dispatchers.IO) { AgentClient.missionNextMove(mission, ctxBlob(), open) }
            if (mv.draft.isNotBlank()) {
                moveLabel = mv.label; moveText = mv.draft; moveTask = mv.task
                if (mv.task.isNotBlank()) ChecklistStore.add(ctx, mv.task)
                MessageStore.insertOne(ctx, "Mission", "Mission", "me", "me", "Toward my goal — ${mv.label}: ${mv.draft.take(600)}")
                MetricsStore.record(ctx, 600)
            } else err = "Couldn't draft a move — likely a rate limit. Route replies/Heavy to Claude and retry."
            busy = ""
        }
    }

    @Composable
    fun bigBtn(label: String, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, fontSize = T.body, color = if (accent) T.bgElevated else T.ink, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (accent) (if (enabled) ACC else T.hairline) else T.bgElevated)
                .clickable(enabled = enabled) { onClick() }.padding(vertical = 15.dp))
    }

    val pct = lastCheck?.percent ?: (if (plan.isNotEmpty()) plan.count { it.done } * 100 / plan.size else null)

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Mission") { saveGoal(); onBack() }
        Spacer(Modifier.height(10.dp))
        Text("Tell SlyOS a goal. It makes a plan, tracks how close you are, and writes your next move.",
            fontSize = T.small, color = T.inkFaint)

        Spacer(Modifier.height(16.dp))
        BasicTextField(mission, { mission = it; saved = false }, textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(16.dp),
            decorationBox = { inner -> if (mission.isEmpty()) Text("Your goal… e.g. find me 5 CTO candidates, or grow my IG from 2k to 5k.", fontSize = T.body, color = T.inkFaint); inner() })
        Spacer(Modifier.height(10.dp))
        bigBtn(if (saved) "Saved" else "Save goal", accent = !saved) { saveGoal() }

        if (pct != null) {
            Spacer(Modifier.height(18.dp))
            Text("$pct% there", fontSize = T.body, color = ACC)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)) {
                Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(ACC))
            }
        }

        if (mission.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            bigBtn(if (busy == "plan") "Making a plan…" else if (plan.isEmpty()) "1. Make me a plan" else "Redo my plan", accent = false, enabled = busy.isEmpty()) { makePlan() }
            Spacer(Modifier.height(10.dp))
            bigBtn(if (busy == "act") "Working…" else "2. Do the next step for me", accent = true, enabled = busy.isEmpty()) { act() }
            Spacer(Modifier.height(10.dp))
            bigBtn(if (busy == "assess") "Checking…" else "3. Check my progress", accent = false, enabled = busy.isEmpty()) { assess() }
        }

        if (err.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(err, fontSize = T.caption, color = T.danger) }

        // The next move — big and clear.
        if (moveText.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF6F1E7)).padding(16.dp)) {
                Text("DO THIS NEXT", fontSize = T.caption, color = T.inkFaint)
                if (moveLabel.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(moveLabel, fontSize = T.body, color = T.ink) }
                Spacer(Modifier.height(10.dp))
                Text("Here's what I wrote — copy and send/post it:", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(moveText.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("(?m)^---+$"), "· · ·"),
                    fontSize = T.small, color = T.ink, modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(12.dp))
                bigBtn("Copy it", accent = true) { clip.setText(AnnotatedString(moveText)) }
                if (moveTask.isNotBlank()) Text("Saved to your checklist too.", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // The plan — tick things off.
        if (plan.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("YOUR PLAN · ${plan.count { it.done }}/${plan.size}", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            plan.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { MissionStore.toggleMilestone(ctx, m.id); plan = MissionStore.milestones(ctx) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (m.done) ACC else T.hairline), contentAlignment = Alignment.Center) {
                        if (m.done) Text("✓", fontSize = T.small, color = T.bgElevated)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(m.text, fontSize = T.small, color = if (m.done) T.inkFaint else T.ink, modifier = Modifier.weight(1f))
                }
            }
        }

        // The latest read.
        lastCheck?.let { c ->
            if (c.note.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("WHERE YOU STAND", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(c.note, fontSize = T.small, color = T.inkSoft)
                if (c.next.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Next: ${c.next}", fontSize = T.small, color = T.ink)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
