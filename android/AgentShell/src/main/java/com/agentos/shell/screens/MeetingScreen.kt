package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.MeetingService
import com.agentos.shell.theme.T
import com.agentos.shell.tools.MeetingNotes
import com.agentos.shell.tools.MeetingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Meetings: recording, and everything that has been recorded.
 *
 * This was a button on Home that produced one blob of text and died with the screen. It is its own
 * place now because a meeting is a *record* — something you come back to, name the people in, and
 * send on — and none of that fits under a prompt box.
 */
@Composable
fun MeetingScreen(
    modifier: Modifier = Modifier,
    /** Arrived by asking to record, so recording starts on the way in rather than after another tap. */
    autoStart: Boolean = false,
    onStartConsumed: () -> Unit = {},
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tick by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("") }

    // POLLED, NOT OBSERVED — deliberately.
    //
    // The recording belongs to the service, not to this screen, which is the entire point: it has to
    // survive the screen being gone. That means the state it exposes is a plain @Volatile field,
    // and reading one of those in a composable does not make Compose redraw when it changes — the
    // service started correctly and the panel simply never appeared. A tick while this screen is in
    // front is the honest cost of not making the recording depend on the UI.
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }

    // "Record my meeting" means record it. Landing on a screen with a Record button on it is asking
    // someone to say the same thing twice.
    LaunchedEffect(autoStart) {
        if (autoStart && !MeetingService.recording) {
            MeetingService.start(ctx, MeetingStore.start(ctx))
            tick++
        }
        if (autoStart) onStartConsumed()
    }

    val meetings = remember(tick) { MeetingStore.all(ctx) }
    val live = remember(tick) { MeetingStore.live(ctx) }
    val recording = remember(tick) { MeetingService.recording }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Meetings", onBack)
        Spacer(Modifier.height(18.dp))

        if (recording && live != null) {
            RecordingPanel(live) {
                busy = true
                MeetingService.stop(ctx)
                scope.launch {
                    // Give the service a moment to write its last segment before reading it back.
                    delay(1200)
                    val m = withContext(Dispatchers.IO) { MeetingStore.get(ctx, live.id) }
                    if (m != null) {
                        val r = withContext(Dispatchers.IO) { MeetingNotes.make(ctx, m) }
                        flash = when {
                            r.tasksAdded > 0 -> "Summarised — ${r.tasksAdded} of your commitments went on your list."
                            r.tasksAdded < 0 -> "Summarised. Tap your own name in the transcript " +
                                "and choose \"This is me\" — then your commitments go on your list."
                            r.verbatim -> "Too short to summarise — kept word for word."
                            else -> "Summarised."
                        }
                        open = m.id
                    }
                    tick++; busy = false
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !busy) {
                        val id = MeetingStore.start(ctx)
                        MeetingService.start(ctx, id)
                        tick++
                    }
                    .padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●", fontSize = 12.sp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Record a meeting", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Keeps recording with the screen off. Decisions, who owes what, and your own " +
                 "commitments onto your list.",
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        }

        if (flash.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(flash, fontSize = T.caption, color = T.good)
        }

        Spacer(Modifier.height(26.dp))
        val past = meetings.filterNot { it.running }
        if (past.isEmpty()) {
            if (!recording) Text("Nothing recorded yet.", fontSize = T.small, color = T.inkFaint)
        } else {
            SectionLabel("RECORDED")
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                past.forEach { m ->
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(15.dp)).background(T.bgElevated)
                            .clickable { open = m.id }
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Text(m.title, fontSize = T.small, color = T.ink)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            MeetingStore.clock(m.durationMs) + " · " +
                                (if (m.summary.isBlank()) "not summarised" else "${m.segments.size} turns"),
                            fontSize = T.caption, color = T.inkFaint)
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    open?.let { id ->
        MeetingStore.get(ctx, id)?.let { m ->
            MeetingSheet(m, onChanged = { tick++ }) { open = null }
        }
    }
}

/** The live panel: how long, that it is hearing you, and what it has heard. */
@Composable
private fun RecordingPanel(m: MeetingStore.Meeting, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", fontSize = 12.sp, color = T.danger)
            Spacer(Modifier.width(9.dp))
            Text(MeetingStore.clock(m.durationMs), fontSize = 28.sp, color = T.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(14.dp))
            // A meter that tracks the voice, so "is this actually hearing me" is answered without
            // waiting for words to appear.
            Row(verticalAlignment = Alignment.Bottom) {
                repeat(5) { i ->
                    val w = listOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)[i]
                    Box(Modifier.padding(end = 3.dp).width(3.dp)
                        .height((4 + MeetingService.level * 20f * w).dp)
                        .clip(RoundedCornerShape(2.dp)).background(T.accent))
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        val recent = m.segments.takeLast(3)
        if (recent.isEmpty() && MeetingService.partial.isBlank()) {
            Text("Listening…", fontSize = T.small, color = T.inkFaint)
        } else {
            recent.forEach { s ->
                Text(m.speakerLabel(s.speaker), fontSize = T.caption, color = T.accent)
                Text(s.text, fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (MeetingService.partial.isNotBlank())
                Text(MeetingService.partial, fontSize = T.small, color = T.inkFaint, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(18.dp))
        Text("■  Stop & summarise", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(T.danger).clickable { onStop() }.padding(vertical = 13.dp))
    }
}

/** One meeting in full: summary, speakers you can name, the transcript, and a way out as a PDF. */
@Composable
private fun MeetingSheet(m: MeetingStore.Meeting, onChanged: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<Int?>(null) }
    var nameText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busySummary by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf(m.title) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.bgElevated)
                .padding(18.dp)
        ) {
            // The title is the handle you find this by six months later, and the one it was given
            // automatically is a timestamp. Tapping it renames it.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).clickable { titleText = m.title; renaming = true })
                Text("Rename", fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.clickable { titleText = m.title; renaming = true }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text(MeetingStore.clock(m.durationMs), fontSize = T.caption, color = T.inkFaint)

            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (m.summary.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(m.summary, fontSize = T.small, color = T.ink, lineHeight = 21.sp)
                }
                // SUMMARISE AGAIN.
                //
                // Summarising needs a model, and a model can be out of quota, offline or slow. Until
                // now that was permanent: the transcript survived but the meeting could never be
                // summarised again, so a provider being down for one minute cost the notes for good.
                if (m.segments.isNotEmpty() && (m.summary.isBlank() || m.summary.length < 80)) {
                    Spacer(Modifier.height(12.dp))
                    Text(if (busySummary) "Summarising…" else "Summarise this",
                        fontSize = T.small, color = T.accent,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.bg)
                            .clickable(enabled = !busySummary) {
                                busySummary = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { MeetingNotes.make(ctx, m) }
                                    note = when {
                                        r.tasksAdded > 0 -> "Summarised — ${r.tasksAdded} of your commitments went on your list."
                                        r.tasksAdded < 0 -> "Summarised. Tap your own name below and " +
                                            "choose \"This is me\" — then your commitments go on your list."
                                        else -> "Summarised."
                                    }
                                    busySummary = false; onChanged()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("TRANSCRIPT")
                Spacer(Modifier.height(4.dp))
                // The honest caveat, where it applies rather than buried in a settings screen.
                Text(if (m.me < 0)
                        "Speakers are guessed from the pauses. Tap a label to name them — and mark " +
                        "yourself, so your commitments can go on your list."
                     else "Speakers are guessed from the pauses. Tap a label to correct it — it " +
                        "fixes every line of theirs.",
                    fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
                Spacer(Modifier.height(10.dp))
                m.segments.forEach { s ->
                    Text(m.speakerLabel(s.speaker), fontSize = T.caption, color = T.accent,
                        modifier = Modifier.clickable {
                            naming = s.speaker; nameText = m.names[s.speaker].orEmpty()
                        })
                    Text(s.text, fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
                    Spacer(Modifier.height(9.dp))
                }
            }

            // HAND THE FOLLOW-UPS TO SOMEONE.
            //
            // The actions out of a meeting are the reason to have recorded it, and leaving them in a
            // summary means they get read once. Handing them to an agent puts them in a thread that
            // can be pushed on.
            val staff = remember { com.agentos.shell.tools.EmployeeStore.all(ctx) }
            // Only when there is something to hand over. A blank transcript still leaves a non-empty
            // summary field ("Kept it:", "I didn't hear anything"), and offering to pass that to a
            // teammate is offering to pass on nothing.
            if (staff.isNotEmpty() && m.segments.isNotEmpty() && m.summary.length > 40) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("HAND THE FOLLOW-UPS TO")
                Spacer(Modifier.height(8.dp))
                Row {
                    staff.take(4).forEach { emp ->
                        Text(emp.name, fontSize = T.caption, color = T.ink,
                            modifier = Modifier.padding(end = 8.dp)
                                .clip(RoundedCornerShape(999.dp)).background(T.bg)
                                .clickable {
                                    com.agentos.shell.tools.AgentThread.add(ctx, emp.id, "you",
                                        "Follow up on \"${m.title}\". Here's what came out of it:\n\n${m.summary}")
                                    com.agentos.shell.tools.AgentDraft.set(
                                        ctx, emp.id, "Follow-ups", m.title, m.title, m.summary)
                                    try {
                                        com.agentos.shell.tools.EmployeeStore.log(
                                            ctx, emp.id, "Picked up the follow-ups from ${m.title}", false)
                                    } catch (e: Exception) {}
                                    note = "${emp.name} has it — open them in Team to carry on."
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }

            if (note.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(note, fontSize = T.caption, color = T.good)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export PDF", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(T.accent)
                        .clickable {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { MeetingNotes.exportPdf(ctx, m) }
                                note = if (uri != null) "Saved to your SlyOS folder." else "Couldn't build the PDF."
                            }
                        }.padding(vertical = 12.dp))
                Spacer(Modifier.width(10.dp))
                // Two taps, because a recording is not recoverable and the transcript may be the
                // only record of what was said.
                Text(if (confirmDelete) "Really?" else "Delete", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (confirmDelete) T.danger.copy(alpha = 0.16f) else T.hairline)
                        .clickable {
                            if (confirmDelete) { MeetingStore.delete(ctx, m.id); onChanged(); onClose() }
                            else confirmDelete = true
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }

    if (renaming) {
        Dialog(onDismissRequest = { renaming = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Name this meeting", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg)
                    .padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (titleText.isEmpty()) Text("e.g. Pricing call with Carlos",
                        fontSize = T.small, color = T.inkFaint)
                    BasicTextField(titleText, { titleText = it },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                Text("Save", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent)
                        .clickable {
                            MeetingStore.rename(ctx, m.id, titleText); renaming = false; onChanged()
                        }.padding(vertical = 12.dp))
            }
        }
    }

    naming?.let { idx ->
        Dialog(onDismissRequest = { naming = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Who is ${m.speakerLabel(idx)}?", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(12.dp))
                // MARKING YOURSELF IS THE ONE THAT MATTERS.
                //
                // Not decoration: until SlyOS knows which voice is yours, it cannot tell your
                // commitments from the other person's, so nothing can go on your list.
                Text(if (m.me == idx) "✓  This is me" else "This is me",
                    fontSize = T.small, color = if (m.me == idx) T.good else T.accent,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg)
                        .clickable { MeetingStore.setMe(ctx, m.id, idx); naming = null; onChanged() }
                        .padding(vertical = 11.dp),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg)
                    .padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (nameText.isEmpty()) Text("Their name", fontSize = T.small, color = T.inkFaint)
                    BasicTextField(nameText, { nameText = it },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                Text("Save", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent)
                        .clickable {
                            if (nameText.isNotBlank()) MeetingStore.nameSpeaker(ctx, m.id, idx, nameText)
                            naming = null; onChanged()
                        }.padding(vertical = 12.dp))
            }
        }
    }
}
