package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.Brain
import com.agentos.shell.tools.Directory
import com.agentos.shell.tools.DocForge
import com.agentos.shell.tools.MailDraft
import com.agentos.shell.tools.SlyFolder
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The email, before it goes.
 *
 * The thing this replaces sent a one-line message straight at a meeting's attendees with no preview
 * — "Running a little late for X — sorry". Fine for a text to a friend; unacceptable for anything
 * with colleagues or clients on it, which is most of a calendar. Nobody should find out what their
 * assistant said on their behalf by asking the person who received it.
 *
 * So: a draft that is generated, shown in full, editable in place, regenerable with one tap, and
 * sent only when someone decides to send it.
 *
 * Attachments work both ways round, because both are real. Sometimes the file exists and you pick
 * it. Sometimes it does not, and what you have is a sentence about what it should say — so you can
 * describe one, watch it be written, read it, and ask for it again until it is right. A flow that
 * only supports the first case sends people out to make the document somewhere else and hope they
 * come back.
 */
@Composable
fun MailReview(
    purpose: MailDraft.Purpose,
    eventTitle: String,
    whenText: String,
    where: String,
    recipients: List<Directory.Entry>,
    modifier: Modifier = Modifier,
    onSent: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var subject by remember { mutableStateOf(MailDraft.subject(purpose, eventTitle)) }
    var body by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(true) }
    var attach by remember { mutableStateOf<SlyFolder.Doc?>(null) }
    var making by remember { mutableStateOf(false) }
    var makeBrief by remember { mutableStateOf("") }
    var showMake by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var docs by remember { mutableStateOf(listOf<SlyFolder.Doc>()) }
    LaunchedEffect(Unit) {
        docs = withContext(Dispatchers.IO) {
            try { DocForge.library(ctx).take(14) } catch (e: Exception) { emptyList() }
        }
    }

    fun draft(tweak: String? = null) {
        writing = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    if (tweak != null && body.isNotBlank())
                        AgentClient.complete("You revise email bodies precisely.",
                            MailDraft.tweakPrompt(body, tweak), 900)
                    else
                        AgentClient.complete("You write email bodies. Body only.",
                            MailDraft.prompt(ctx, purpose, eventTitle, whenText, where,
                                recipients.map { it.email }, attach?.name.orEmpty(), extra), 900)
                } catch (e: Exception) { "" }
            }
            if (out.isNotBlank()) body = out.trim()
            writing = false
        }
    }
    LaunchedEffect(Unit) { draft() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader(purpose.label, onBack)

        // ── Who it goes to ──
        Spacer(Modifier.height(14.dp))
        Text("TO", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp)
        Spacer(Modifier.height(6.dp))
        Text(recipients.joinToString(", ") { it.name.ifBlank { it.email } }
            .ifBlank { "nobody yet" },
            fontSize = T.small, color = T.ink, lineHeight = 20.sp)

        // ── Subject ──
        Spacer(Modifier.height(18.dp))
        Text("SUBJECT", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(subject, { subject = it },
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth())

        // ── The draft itself, in full and editable ──
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THE EMAIL", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
            if (writing) {
                val pulse by animateFloatAsState(if (writing) 0.4f else 1f, tween(700), label = "p")
                Text("writing…", fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.graphicsLayer { alpha = pulse })
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)
            .clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
            if (body.isBlank() && !writing)
                Text("Couldn't write it just now — tap Redo.", fontSize = T.small, color = T.inkFaint)
            // Editable in place. A draft you can only accept or reject is a draft you rewrite
            // somewhere else.
            BasicTextField(body, { body = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.small, lineHeight = 22.sp),
                modifier = Modifier.fillMaxWidth())
        }

        // ── Change it ──
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            MailDraft.TWEAKS.forEach { t ->
                Pill(t, false, enabled = !writing) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress); draft(t)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (extra.isEmpty())
                Text("Anything it should say? Then Redo.", fontSize = T.caption, color = T.inkFaint)
            BasicTextField(extra, { extra = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }

        // ── Attach: pick one, or describe one ──
        Spacer(Modifier.height(22.dp))
        Text("ATTACH  ·  OPTIONAL", fontSize = 9.sp, color = T.inkFaint,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            docs.forEach { d ->
                Pill(d.name.substringBeforeLast('.').take(20), attach?.name == d.name) {
                    attach = if (attach?.name == d.name) null else d
                }
            }
            // The other half of the case: the file does not exist yet and what you have is a
            // sentence about what it should say.
            Pill(if (making) "writing…" else "＋ Create one", false, enabled = !making) {
                showMake = !showMake
            }
        }
        if (showMake) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (makeBrief.isEmpty())
                    Text("What should it say? e.g. a one-page brief on the pilot numbers",
                        fontSize = T.caption, color = T.inkFaint)
                BasicTextField(makeBrief, { makeBrief = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Pill(if (making) "writing…" else if (attach != null) "Write it again" else "Write it",
                    true, enabled = !making && makeBrief.isNotBlank()) {
                    making = true
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            try {
                                ToolRouter.executeAction(ctx, "create_document", JSONObject()
                                    .put("title", makeBrief.take(50))
                                    .put("brief", MailDraft.attachmentBrief(makeBrief, eventTitle, whenText))
                                    .put("format", "pdf").toString())
                                DocForge.library(ctx).firstOrNull()
                            } catch (e: Exception) { null }
                        }
                        if (made != null) {
                            attach = made
                            docs = withContext(Dispatchers.IO) { DocForge.library(ctx).take(14) }
                            note = "Made “${made.name}”. Open it to read, or ask for it again."
                        } else note = "Couldn't write that one."
                        making = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                // Read what was written before deciding it is right — the whole point of iterating.
                attach?.let { d ->
                    Pill("Open it", false) {
                        try { DocForge.open(ctx, d.uri, d.name) } catch (e: Exception) {}
                    }
                }
            }
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(note, fontSize = T.caption, color = T.inkSoft, lineHeight = 17.sp)
        }

        if (sent.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(sent, fontSize = T.small,
                color = if (sent.contains("✓")) T.good else T.danger, lineHeight = 20.sp)
        }

        // ── Send ──
        Spacer(Modifier.height(22.dp))
        val ready = recipients.isNotEmpty() && body.isNotBlank() && !writing && sent.isEmpty()
        Text(if (sent.isNotEmpty()) "Sent" else "Send to ${recipients.size}",
            fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (ready) T.accent else T.hairline)
                .clickable(enabled = ready) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val to = recipients.joinToString(",") { it.email }
                        val out = withContext(Dispatchers.IO) {
                            try {
                                if (attach != null)
                                    ToolRouter.executeAction(ctx, "send_document", JSONObject()
                                        .put("name", attach!!.name).put("to", to).toString())
                                else
                                    ToolRouter.executeAction(ctx, "send_email", JSONObject()
                                        .put("to", to).put("subject", subject).put("body", body).toString())
                            } catch (e: Exception) { "Couldn't send — ${e.message?.take(60)}" }
                        }
                        sent = out.ifBlank { "Sent ✓" }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // What actually went out, in the owner's own words, into the brain — so
                        // "what did I tell them about the pilot?" is answerable later.
                        withContext(Dispatchers.IO) {
                            try {
                                Brain.remember(ctx, "response", subject,
                                    "To $to:\n\n$body" +
                                        (attach?.let { "\n\nAttached: ${it.name}" } ?: ""),
                                    actors = recipients.map { it.email }, role = "me")
                            } catch (e: Exception) {}
                        }
                        onSent(sent)
                    }
                }
                .padding(vertical = 15.dp))

        Spacer(Modifier.height(10.dp))
        Text("Nothing is sent until you send it. The draft is written from this meeting, who's on " +
             "it, and how you two normally write.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
private fun Pill(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (on) 1.04f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 800f), label = "p")
    Box(
        Modifier.padding(end = 7.dp, bottom = 7.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = T.caption,
            color = if (on) Color.White else if (enabled) T.inkSoft else T.inkFaint,
            maxLines = 1, softWrap = false)
    }
}
