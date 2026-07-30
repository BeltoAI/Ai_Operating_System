package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Everything happening now — an auto "what you missed" briefing, then people who need you as
 *  swipeable cards grouped per person: tap to open, swipe left to dismiss, ✦ to reply. */
@Composable
fun NowScreen(modifier: Modifier = Modifier, onReconnect: () -> Unit = {}, onOutbox: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = NotificationStore.notes
    var digest by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var briefHidden by remember { mutableStateOf(false) }
    var briefDragX by remember { mutableStateOf(0f) }

    fun catchUp() {
        if (loading) return
        loading = true; digest = ""
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val snapshot = notes.toList()
            val awaiting = snapshot.filter { it.isConversational && it.text.isNotBlank() }
                .map { "${it.title.ifBlank { it.app }} (${it.app}): \"${it.text.take(120)}\"" }
            val otherNotifs = snapshot.filter { !it.isConversational && !it.isLikelyBot && it.text.isNotBlank() }
                .map { "${it.app}: ${it.text.take(120)}" }
            digest = withContext(Dispatchers.IO) { AgentClient.catchUp(otherNotifs, awaiting, mem) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (notes.isNotEmpty()) catchUp() }
    val dateStr = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()) }

    Column(modifier) {
        ScreenHeader("Now", onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(dateStr, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
            Text("Sent for you", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onOutbox() }.padding(end = 14.dp))
            Text("Reconnect", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onReconnect() })
        }
        // If drafts have piled up (e.g. lots of X/social replies), offer a one-tap clear-all.
        val draftCount = NotificationStore.stagedDrafts.size
        if (draftCount >= 5) {
            Spacer(Modifier.height(6.dp))
            Text("Clear $draftCount unsent drafts", fontSize = T.caption, color = T.danger,
                modifier = Modifier.clickable { NotificationStore.clearAllDrafts() })
        }
        Spacer(Modifier.height(14.dp))

        val groups = notes.groupBy { it.title.ifBlank { it.app } }.map { it.key to it.value }
        // ONE scrollable list for the whole feed. These cards used to sit ABOVE the notes LazyColumn as fixed
        // content, so as they stacked up they squeezed the message list to nothing — the feed became unscrollable
        // and messages below were unreachable. Everything is now items in the same list.
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column {
            // ── The brain asks BACK. Short questions that clear up wrong inferences (e.g. which "Anna" you mean)
            //    or fill real gaps. Answering writes a durable fact, so the correction sticks instead of the brain
            //    guessing wrong forever.
            com.agentos.shell.tools.BrainQuestions.ensureLoaded(ctx)
            val questions = com.agentos.shell.tools.BrainQuestions.items
            // Swiping the card left hides it for THIS visit only — reopen Now and it's asking again, so a
            // question is deferred, never lost. ("Don't ask again" is the permanent one.)
            var qHidden by remember { mutableStateOf(false) }
            var qDragX by remember { mutableStateOf(0f) }
            // Attach real material to an answer — a pitch deck, a spec, a contract — so the brain learns from
            // the SOURCE rather than a one-line summary. Text is extracted and stored with the answer.
            var attachName by remember { mutableStateOf("") }
            var attachText by remember { mutableStateOf("") }
            val pickForAnswer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                    attachName = com.agentos.shell.tools.FileOps.displayName(ctx, uri)
                    scope.launch {
                        attachText = withContext(Dispatchers.IO) {
                            try {
                                if (com.agentos.shell.tools.FileOps.isPdf(ctx, uri)) com.agentos.shell.tools.FileOps.pdfText(ctx, uri)
                                else ctx.contentResolver.openInputStream(uri)?.use { i -> i.readBytes().decodeToString() } ?: ""
                            } catch (e: Exception) { "" }
                        }
                    }
                }
            }
            // Rotate + refresh on every visit: the same stored questions were re-rendered forever because
            // this always took questions.first() and the batch only regenerated after hours.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { try { com.agentos.shell.tools.BrainQuestions.refresh(ctx) } catch (e: Exception) {} }
            }
            val rotated = remember(questions.size) { com.agentos.shell.tools.BrainQuestions.nextToAsk(ctx) }
            if (questions.isNotEmpty() && !qHidden && rotated != null) {
                val q = rotated
                Text("YOUR BRAIN IS ASKING", fontSize = 11.sp, color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth()
                    .offset { IntOffset(qDragX.roundToInt(), 0) }
                    .pointerInput(q.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = { if (qDragX < -110f) qHidden = true; qDragX = 0f },
                            onDragCancel = { qDragX = 0f }
                        ) { _, dx -> qDragX = (qDragX + dx).coerceAtMost(0f) }
                    }
                    .clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(q.text, fontSize = T.body, color = T.ink)
                    Spacer(Modifier.height(10.dp))
                    // Options are SUGGESTIONS, never the only path: a fixed 4-item list ("Co-founder /
                    // Colleague / Advisor / Friend") can't cover a real answer like "my wife". Unless the
                    // question is strictly yes/no, the owner can always type their own answer.
                    if (q.options.isNotEmpty()) {
                        q.options.forEach { opt ->
                            Text(opt, fontSize = T.small, color = T.ink,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(T.hairline)
                                    .clickable { com.agentos.shell.tools.BrainQuestions.answer(ctx, q, opt) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                    }
                    if (q.freeform) {
                        if (q.options.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        var typed by remember(q.id) { mutableStateOf("") }
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(10.dp)) {
                            if (typed.isEmpty()) Text(
                                if (q.options.isNotEmpty()) "…or say it in your own words" else "Type your answer…",
                                fontSize = T.small, color = T.inkFaint)
                            BasicTextField(typed, { typed = it }, textStyle = TextStyle(color = T.ink, fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (attachName.isBlank()) "Attach a file" else attachName,
                                fontSize = T.caption, color = if (attachName.isBlank()) T.inkSoft else T.accent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                                    .clickable { try { pickForAnswer.launch(arrayOf("*/*")) } catch (e: Exception) {} }
                                    .padding(vertical = 6.dp))
                            if (attachName.isNotBlank())
                                Text("Remove", fontSize = T.caption, color = T.inkFaint,
                                    modifier = Modifier.clickable { attachName = ""; attachText = "" }.padding(6.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        val canSave = typed.isNotBlank() || attachName.isNotBlank()
                        Text("Save", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (!canSave) T.hairline else T.accent)
                                .clickable(enabled = canSave) {
                                    com.agentos.shell.tools.BrainQuestions.answer(
                                        ctx, q, typed.trim(), attachName, attachText)
                                    attachName = ""; attachText = ""
                                }
                                .padding(vertical = 9.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Not now", fontSize = T.caption, color = T.inkSoft,
                            modifier = Modifier.clickable { qHidden = true }.padding(4.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Don't ask again", fontSize = T.caption, color = T.inkFaint,
                            modifier = Modifier.clickable { com.agentos.shell.tools.BrainQuestions.dismiss(ctx, q) }.padding(4.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── THE BUDGET, BEFORE IT RUNS OUT ──
            //
            // The cap works — over the limit only free brains are used — but silently. Everything is
            // normal until the good models are suddenly gone and nothing said why. Once per
            // threshold per month, so it is a warning rather than a nag.
            run {
                var alert by remember { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    alert = withContext(Dispatchers.IO) {
                        try { com.agentos.shell.tools.CostStore.budgetAlert(ctx) } catch (e: Exception) { null }
                    }
                }
                alert?.let { a ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(T.bgElevated).padding(16.dp)) {
                        Text("SPENDING", fontSize = 9.sp, color = T.accent,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(a, fontSize = T.caption, color = T.ink, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            // ── WHAT YOU DID TODAY ──
            //
            // A workout finished at three o'clock was invisible until somebody opened the Health page,
            // which is the opposite of what a home screen is for. Swipe RIGHT opens Whoop; swipe LEFT
            // dismisses the card for today — and only the card. Ending a workout from here is not
            // possible (Whoop exposes nothing for it) and a gesture that appeared to would be lying
            // about the one thing on this card that matters.
            run {
                var day by remember { mutableStateOf<com.agentos.shell.tools.TrainingToday.Day?>(null) }
                var gone by remember { mutableStateOf(false) }
                var dragX by remember { mutableStateOf(0f) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!com.agentos.shell.tools.TrainingToday.dismissedToday(ctx))
                        day = withContext(Dispatchers.IO) {
                            try { com.agentos.shell.tools.TrainingToday.today(ctx) } catch (e: Exception) { null }
                        }
                }
                val d = day
                if (d != null && !gone && com.agentos.shell.tools.TrainingToday.line(d).isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()
                        .offset { IntOffset(dragX.roundToInt(), 0) }
                        .pointerInput(d) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        dragX < -110f -> {
                                            com.agentos.shell.tools.TrainingToday.dismissToday(ctx)
                                            gone = true
                                        }
                                        dragX > 110f -> com.agentos.shell.tools.TrainingToday.openWhoop(ctx)
                                    }
                                    dragX = 0f
                                },
                                onDragCancel = { dragX = 0f }
                            ) { _, dx -> dragX += dx }
                        }
                        .clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                        Text(com.agentos.shell.tools.TrainingToday.line(d),
                            fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium)
                        val detail = com.agentos.shell.tools.TrainingToday.detail(d)
                        if (detail.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(detail, fontSize = 10.sp, color = T.inkFaint)
                        }
                        Spacer(Modifier.height(8.dp))
                        // The gestures, named honestly. "End activity" is absent because it cannot be done.
                        Text("swipe right for Whoop  ·  left to dismiss",
                            fontSize = 9.sp, color = T.inkFaint)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            // ── BIRTHDAYS, WHICH ARE ONLY USEFUL ON THE DAY ──
            //
            // A birthday noted on a person's page and never surfaced is a fact you still forget. It
            // is read from facts already extracted rather than by sweeping the book, so it costs
            // nothing here — and it only appears within a week, because that is the window in which
            // you could still do something about it.
            run {
                var bdays by remember { mutableStateOf(listOf<com.agentos.shell.tools.PersonFacts.Upcoming>()) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    bdays = withContext(Dispatchers.IO) {
                        try {
                            val ppl = com.agentos.shell.tools.Crm.people(ctx, 200)
                            com.agentos.shell.tools.PersonFacts.upcoming(ctx, ppl, 7)
                        } catch (e: Exception) { emptyList() }
                    }
                }
                if (bdays.isNotEmpty()) {
                    Text("BIRTHDAYS", fontSize = 11.sp, color = T.accent,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    bdays.take(3).forEach { b ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                            .background(T.bgElevated).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(com.agentos.shell.tools.PersonFacts.birthdayLine(b),
                                fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f),
                                lineHeight = 19.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            // ── Team approvals: a teammate wants to send an email or change your calendar — you decide.
            //    Swipe LEFT to decline, swipe RIGHT to open the full details and approve. Nothing leaves the
            //    phone in your name until you say so.
            com.agentos.shell.tools.ApprovalStore.ensureLoaded(ctx)
            val approvals = com.agentos.shell.tools.ApprovalStore.items
            if (approvals.isNotEmpty()) {
                Text("NEEDS YOUR OK · ${approvals.size}", fontSize = 11.sp, color = T.accent,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                approvals.toList().forEach { a ->
                    Spacer(Modifier.height(8.dp))
                    ApprovalCard(a)
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── Proactive proposals (P5.3): one-tap suggestions like "add this booking to your calendar" ──
            com.agentos.shell.tools.ProposalStore.ensureLoaded(ctx)
            val proposals = com.agentos.shell.tools.ProposalStore.items
            if (proposals.isNotEmpty()) {
                Text("SUGGESTED", fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                proposals.toList().forEach { p ->
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(14.dp)) {
                        Text(p.title, fontSize = T.body, color = T.ink)
                        if (p.subtitle.isNotBlank()) Text(p.subtitle, fontSize = T.caption, color = T.inkFaint)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Confirm", fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable {
                                    scope.launch {
                                        val msg = withContext(Dispatchers.IO) { com.agentos.shell.tools.ToolRouter.executeActions(ctx, p.actions, userInitiated = true) }
                                        com.agentos.shell.tools.OutboxStore.record(ctx, "Proposal", p.title, "proposal", msg.ifBlank { p.subtitle }, "you confirmed a suggestion")
                                        com.agentos.shell.tools.ProposalStore.remove(ctx, p.id)
                                    }
                                }.padding(horizontal = 20.dp, vertical = 9.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("Dismiss", fontSize = T.small, color = T.inkSoft,
                                modifier = Modifier.clickable { com.agentos.shell.tools.ProposalStore.remove(ctx, p.id) }.padding(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── Briefing card (swipe left to dismiss) ──
            if (!briefHidden) Column(Modifier.fillMaxWidth()
                .offset { IntOffset(briefDragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (briefDragX < -120f) briefHidden = true; briefDragX = 0f },
                        onDragCancel = { briefDragX = 0f }
                    ) { _, dx -> briefDragX = (briefDragX + dx).coerceAtMost(0f) }
                }
                .clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WHAT YOU MISSED", fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    if (loading) SlyOrbit(12)
                    else Text("↻", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { catchUp() }.padding(4.dp))
                }
                Spacer(Modifier.height(10.dp))
                when {
                    loading && digest.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        SlyOrbit(20); Spacer(Modifier.width(12.dp)); Text("reading your day", fontSize = T.small, color = T.accent)
                    }
                    digest.isBlank() -> Text(if (notes.isEmpty()) "You're all caught up." else "Tap ↻ for a summary.", fontSize = T.small, color = T.inkSoft)
                    else -> {
                        val idx = digest.indexOf("Text back", ignoreCase = true)
                        if (idx > 0) {
                            Text(digest.substring(0, idx).trim(), fontSize = T.small, color = T.ink)
                            Spacer(Modifier.height(8.dp))
                            Text(digest.substring(idx).trim(), fontSize = T.small, color = T.accent)
                        } else Text(digest, fontSize = T.small, color = T.ink)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
                // Clear-all lives on the section header, next to the count it clears. Swiping every card left
            // one at a time was the only way to empty this screen.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("WAITING · ${groups.size}", fontSize = 11.sp, color = T.inkFaint,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("Clear all", fontSize = T.caption, color = T.danger,
                    modifier = Modifier.clickable { NotificationStore.dismissAll() }.padding(4.dp))
            }
            Spacer(Modifier.height(10.dp))
                }
            }
            if (notes.isEmpty()) item {
                Spacer(Modifier.height(16.dp))
                Text("All caught up.", fontSize = T.body, color = T.inkSoft)
                Spacer(Modifier.height(8.dp))
                Text("Grant notification access in Settings to see what's waiting.",
                    fontSize = T.caption, color = T.inkFaint)
            }
            items(groups, key = { it.first }) { (contact, group) -> NoteGroupCard(ctx, contact, group) }
        }
    }
}

/**
 * A pending team approval. Compact by default (who + what). Swipe RIGHT to open the full details (the actual
 * email, or the event's time + attendees) and approve; swipe LEFT to decline. Approving runs the real action
 * through the same gated ToolRouter path; declining drops it and records that you said no.
 */
@Composable
private fun ApprovalCard(a: com.agentos.shell.tools.ApprovalStore.Approval) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dragX by remember { mutableStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val isEmail = a.kind == "email"
    val icon = if (isEmail) "✉" else "📅"
    val verb = if (isEmail) "Approve & send" else "Approve & add"
    val whatLine = "${a.agent} wants to ${if (isEmail) "email" else "add to your calendar"}"

    fun decline() {
        com.agentos.shell.tools.OutboxStore.record(ctx, a.agent, a.title, a.kind,
            "You declined ${a.agent}'s ${a.kind}", "you swiped left / declined", "declined")
        com.agentos.shell.tools.ApprovalStore.remove(ctx, a.id)
    }
    fun approve() {
        if (working) return
        working = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                com.agentos.shell.tools.ToolRouter.executeActions(ctx, a.actions, userInitiated = true)
            }
            com.agentos.shell.tools.OutboxStore.record(ctx, a.agent, a.title, a.kind,
                msg.ifBlank { "done" }, "you approved ${a.agent}'s ${a.kind}")
            com.agentos.shell.tools.ApprovalStore.remove(ctx, a.id)
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .pointerInput(a.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -120f) decline() else if (dragX > 120f) expanded = true
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f }
                ) { _, dx -> dragX += dx }
            }
            .clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = T.body)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(whatLine, fontSize = T.caption, color = T.inkFaint)
                Text(a.title, fontSize = T.body, color = T.ink,
                    maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(a.detail, fontSize = T.small, color = T.inkSoft)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (working) "Working…" else verb, fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable(enabled = !working) { approve() }.padding(horizontal = 20.dp, vertical = 9.dp))
                Spacer(Modifier.width(14.dp))
                Text("Decline", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clickable { decline() }.padding(6.dp))
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Swipe → to review  ·  ← to decline", fontSize = T.caption, color = T.inkFaint)
        }
    }
}

private fun appColor(pkg: String): Color = when {
    pkg.contains("whatsapp") -> Color(0xFF25D366)
    pkg.contains("telegram") -> Color(0xFF26A5E4)
    pkg.contains("instagram") -> Color(0xFFC13584)
    pkg.contains("gm") -> Color(0xFFEA4335)
    pkg.contains("messaging") -> Color(0xFF1A73E8)
    pkg.contains("twitter") || pkg.contains("x.android") -> Color(0xFF111111)
    pkg.contains("linkedin") -> Color(0xFF0A66C2)
    pkg.contains("slack") -> Color(0xFF4A154B)
    pkg.contains("discord") -> Color(0xFF5865F2)
    pkg.contains("securesms") || pkg.contains("signal") -> Color(0xFF3A76F0)
    pkg.contains("facebook") || pkg.contains("orca") -> Color(0xFF0866FF)
    pkg.contains("snapchat") -> Color(0xFFFFFC00)
    pkg.contains("reddit") -> Color(0xFFFF4500)
    pkg.contains("teams") -> Color(0xFF6264A7)
    pkg.contains("viber") -> Color(0xFF7360F2)
    pkg.contains("line") -> Color(0xFF06C755)
    pkg.contains("outlook") -> Color(0xFF0078D4)
    else -> T.accent
}

/** Real launcher icon for a package — this is what gives the Now feed true per-app recognition
 *  (any installed app, not just the hardcoded colors). Rasterized once and cached per package. */
private val iconCache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
private fun appIcon(ctx: android.content.Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (pkg.isBlank()) return null
    iconCache[pkg]?.let { return it }
    if (iconCache.containsKey(pkg)) return null
    val img = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val w = d.intrinsicWidth.coerceIn(1, 144); val h = d.intrinsicHeight.coerceIn(1, 144)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
        bmp.asImageBitmap()
    } catch (e: Exception) { null }
    iconCache[pkg] = img
    return img
}

/** Human app name for a package (e.g. "WhatsApp"), falling back to the note's own label. */
private fun appName(ctx: android.content.Context, pkg: String, fallback: String): String = try {
    if (pkg.isBlank()) fallback
    else ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { fallback }

@Composable
private fun NoteGroupCard(ctx: android.content.Context, contact: String, group: List<NotificationStore.Note>) {
    val latest = group.first()
    val scope = rememberCoroutineScope()
    var expanded by remember(latest.key) { mutableStateOf(false) }
    var dragX by remember(latest.key) { mutableStateOf(0f) }
    val staged = NotificationStore.stagedDrafts[latest.key]
    var draft by remember(latest.key) { mutableStateOf(staged ?: "") }
    var replyBusy by remember(latest.key) { mutableStateOf(false) }
    var sendMsg by remember(latest.key) { mutableStateOf("") }

    // Draft a reply the first time this card is opened — in your voice, from the brain.
    LaunchedEffect(expanded) {
        if (expanded && draft.isBlank() && latest.worthDrafting) {
            replyBusy = true
            val d = withContext(Dispatchers.IO) { run {
                val th = com.agentos.shell.tools.ConversationStore.thread(ctx, latest.app, latest.title).map { it.role to it.text }
                val m = com.agentos.shell.tools.ReplyContext.forSender(ctx, latest.app, latest.title, latest.text)
                if (th.isNotEmpty()) AgentClient.draftReplyThread(latest.title.ifBlank { latest.app }, th, m, null, latest.text, latest.isGroup)
                else AgentClient.draftReply(latest.title.ifBlank { latest.app }, latest.text, m)
            } }
            if (!AgentClient.looksLikeError(d)) draft = d
            replyBusy = false
        }
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        // Reveal layer behind the card: swipe RIGHT to open, LEFT to close.
        Row(Modifier.matchParentSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open ↗", fontSize = T.small, color = if (dragX > 20f) T.accent else T.hairline)
            Spacer(Modifier.weight(1f))
            Text("Close ✕", fontSize = T.small, color = if (dragX < -20f) T.danger else T.hairline)
        }
    Column(Modifier.fillMaxWidth()
        .offset { IntOffset(dragX.roundToInt(), 0) }
        .pointerInput(latest.key) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        dragX < -130f -> group.forEach { NotificationStore.dismiss(it.key) }   // left → close
                        dragX > 130f  -> NotificationStore.open(ctx, latest)                    // right → open
                    }
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f }
            ) { _, dx -> dragX = (dragX + dx).coerceIn(-320f, 320f) }
        }
        .clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
    ) {
        // Header — tap opens the actual conversation/app.
        Row(Modifier.fillMaxWidth().clickable { NotificationStore.open(ctx, latest) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Avatar = contact initial in the app's brand color, with the REAL app icon as a corner
            // badge — so you recognize at a glance which app each card came from.
            val icon = appIcon(ctx, latest.pkg)
            Box(Modifier.size(46.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(appColor(latest.pkg)), contentAlignment = Alignment.Center) {
                    Text(contact.trim().firstOrNull()?.uppercase() ?: "•", color = Color.White, fontSize = T.body)
                }
                if (icon != null) androidx.compose.foundation.Image(
                    bitmap = icon, contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
                        .clip(CircleShape).background(Color.White).padding(1.dp).clip(CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.take(30), fontSize = T.body, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (group.size > 1) Text("${group.size}", fontSize = T.caption, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(CircleShape).background(T.accent).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("via ${appName(ctx, latest.pkg, latest.app)}", fontSize = T.caption, color = appColor(latest.pkg))
                    // Human or machine, said plainly. Automated mail is still shown — a booking or a
                    // code is often the thing that matters — it just isn't worth writing a reply to.
                    if (latest.senderKind.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        val person = latest.senderKind == "person"
                        Text(if (person) "person" else "automated", fontSize = T.caption,
                            color = if (person) T.accent else T.inkFaint,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (person) T.accent.copy(alpha = 0.16f) else T.hairline)
                                .padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                // Show a real chunk of the ACTUAL message (the full text is captured), not a one-line stub —
                // so you can see what's going on without opening the app. Expands to the whole message on tap.
                if (latest.text.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(if (expanded) latest.text else latest.text.take(320),
                        fontSize = T.small, color = T.inkSoft,
                        maxLines = if (expanded) 40 else 6, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
        }
        // Actions — Reply (opens inline draft) and Open. Nothing else.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (latest.worthDrafting)
                Text(if (expanded) "Close" else if (staged != null) "✦ Reply ready" else "✦ Reply", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp))
            Spacer(Modifier.weight(1f))
            Text("Open ↗", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { NotificationStore.open(ctx, latest) }.padding(4.dp))
        }
        // Clean inline reply — draft box + Send. No second header, no event icons.
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                if (replyBusy && draft.isBlank()) {
                    Text("drafting in your voice…", fontSize = T.small, color = T.accent)
                } else {
                    BasicTextField(draft, { draft = it },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(T.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp))
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send", fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (draft.isBlank()) T.hairline else T.accent)
                            .clickable(enabled = draft.isNotBlank()) {
                                val d = draft
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        // Mail goes out through Gmail, not through the notification
                                        // — there is no reply box on it to send through.
                                        if (latest.isEmail && !latest.canReply) {
                                            val to = com.agentos.shell.tools.Directory
                                                .search(ctx, latest.title, 1).firstOrNull()?.email
                                                ?: try {
                                                    com.agentos.shell.tools.PersonResolver
                                                        .resolve(ctx, latest.title).email
                                                } catch (e: Exception) { "" }
                                            if (to.isBlank()) false
                                            else com.agentos.shell.tools.ToolRouter.executeAction(
                                                ctx, "send_email",
                                                org.json.JSONObject().put("to", to)
                                                    .put("subject", "Re: " + latest.text.take(60))
                                                    .put("body", d).toString()
                                            ).contains("✓")
                                        } else NotificationStore.sendReply(ctx, latest, d)
                                    }
                                    sendMsg = if (ok) "sent ✓" else "couldn't send"
                                    // Into the brain either way: what you actually said, in your
                                    // own words, is the highest-value thing this app stores.
                                    if (ok) withContext(Dispatchers.IO) {
                                        try {
                                            com.agentos.shell.tools.Brain.remember(ctx, "response",
                                                "Replied to ${latest.title}", d,
                                                actors = listOf(latest.title), role = "me")
                                        } catch (e: Exception) {}
                                    }
                                }
                            }.padding(horizontal = 22.dp, vertical = 9.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (replyBusy) "…" else "Regenerate", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable(enabled = !replyBusy) {
                            scope.launch {
                                replyBusy = true
                                val d = withContext(Dispatchers.IO) { run {
                val th = com.agentos.shell.tools.ConversationStore.thread(ctx, latest.app, latest.title).map { it.role to it.text }
                val m = com.agentos.shell.tools.ReplyContext.forSender(ctx, latest.app, latest.title, latest.text)
                if (th.isNotEmpty()) AgentClient.draftReplyThread(latest.title.ifBlank { latest.app }, th, m, null, latest.text, latest.isGroup)
                else AgentClient.draftReply(latest.title.ifBlank { latest.app }, latest.text, m)
            } }
                                if (!AgentClient.looksLikeError(d)) draft = d
                                replyBusy = false
                            }
                        }.padding(6.dp))
                    if (sendMsg.isNotBlank()) { Spacer(Modifier.width(12.dp)); Text(sendMsg, fontSize = T.caption, color = T.accent) }
                }
            }
        }
    }
    }
    Spacer(Modifier.height(10.dp))
}
