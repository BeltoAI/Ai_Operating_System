package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    /**
     * What the owner already said this email is about, before the draft is written.
     *
     * Carries whatever the previous screen knows: the sentence typed into the heads-up box, the new
     * time an event just moved to, or — for the notes email — what was actually decided in the
     * recording. Without it the draft is written from the title alone and reads like a template,
     * which is the failure this whole screen exists to prevent.
     */
    seed: String = "",
    onSent: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var subject by remember { mutableStateOf(MailDraft.subject(purpose, eventTitle)) }
    var body by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(seed) }
    var writing by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    /** A file the owner picked off their phone: what to send, and what to call it. */
    data class Picked(val uri: String, val name: String)
    var files by remember { mutableStateOf(listOf<Picked>()) }
    /** The deck generated on this screen, if any — kept apart so "revise" edits it in place. */
    var madeDeck by remember { mutableStateOf<Picked?>(null) }
    var making by remember { mutableStateOf(false) }
    var makeBrief by remember { mutableStateOf("") }
    var showMake by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // OpenMultipleDocuments rather than GetContent: it returns a persistable content:// that can
    // still be read when the send happens, which GetContent's one-shot uri cannot promise.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        files = files + uris.mapNotNull { u ->
            try {
                ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) Picked(u.toString(), c.getString(i))
                    else Picked(u.toString(), u.lastPathSegment.orEmpty().substringAfterLast('/'))
                } ?: Picked(u.toString(), "attachment")
            } catch (e: Exception) { null }
        }.filterNot { p -> files.any { it.uri == p.uri } }
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
                                recipients.map { it.email }, files.joinToString(", ") { f -> f.name }, extra), 900)
                } catch (e: Exception) { "" }
            }
            if (out.isNotBlank()) body = MailDraft.plain(out)
            writing = false
        }
    }
    LaunchedEffect(Unit) { draft() }

    if (editing) {
        SlideEditor(
            modifier = modifier,
            onDone = { uri ->
                // Whatever came back is what gets attached — the edited deck replaces the old one
                // rather than joining it, so nobody sends two versions of the same slides.
                val name = DocForge.lastTitle(ctx) + ".pdf"
                files = files.filterNot { it.uri == madeDeck?.uri } + Picked(uri, name)
                madeDeck = Picked(uri, name)
                editing = false
                note = "“$name” updated and attached."
            },
            onBack = { editing = false })
        return
    }

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MailDraft.TWEAKS.forEach { (label, _) ->
                Pill(label, false, enabled = !writing, modifier = Modifier.weight(1f)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress); draft(label)
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
        // FILES OFF THE PHONE. NOTHING ELSE.
        //
        // What was here offered a row of documents SlyOS had generated, plus a box for describing a
        // document you wanted written. Both were real features and together they answered a question
        // nobody was asking — the thing people mean by "attach" is the file already sitting in their
        // Downloads. It went through the system picker in one tap and everything else went away.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
            .clickable { pick.launch(arrayOf("*/*")) }
            .padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center) {
            Text(if (files.isEmpty()) "Choose files" else "Add another",
                fontSize = T.small, color = T.inkSoft)
        }
        Spacer(Modifier.height(8.dp))
        // SLIDES, MADE HERE.
        //
        // The one thing people attach that they do not already have. Everything else is a file
        // sitting in Downloads; a deck is the thing they were going to go and build somewhere else
        // and come back for. Generated, opened to read, revised until right, and attached — without
        // leaving the email it belongs to.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
            .clickable(enabled = !making) { showMake = !showMake }
            .padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center) {
            Text(if (making) "designing…" else "Make slides",
                fontSize = T.small, color = if (making) T.inkFaint else T.inkSoft)
        }
        if (showMake) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (makeBrief.isEmpty())
                    Text(if (madeDeck == null) "What should the deck cover?"
                         else "What should change? e.g. add a slide on pricing",
                        fontSize = T.caption, color = T.inkFaint)
                BasicTextField(makeBrief, { makeBrief = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Pill(if (madeDeck == null) "Design it" else "Revise it", true,
                    enabled = !making && makeBrief.isNotBlank()) {
                    making = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            try {
                                // Revising edits the SAME deck rather than generating a different
                                // one — the whole point of iterating is that slide 4 survives.
                                if (madeDeck == null)
                                    DocForge.create(ctx, eventTitle.ifBlank { subject }.take(50),
                                        makeBrief, "pdf", "deck")
                                else DocForge.refine(ctx, makeBrief, "pdf")
                            } catch (e: Exception) { null }
                        }
                        if (made != null && made.ok && made.uri != null) {
                            madeDeck = Picked(made.uri.toString(), made.name)
                            files = files.filterNot { it.name == made.name } + madeDeck!!
                            makeBrief = ""
                            note = "“${made.name}” attached. Open it to read, or say what to change."
                        } else note = "Couldn't design that one — try saying more about it."
                        making = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                // Read it before deciding it is right. A deck you cannot see is a deck you cannot
                // approve, and this one is going out under the owner's name.
                // PREVIEW AND EDIT, not "open in whatever app handles PDFs". Seeing a deck you
                // cannot change is not a review — the point of looking is to fix what is wrong.
                if (madeDeck != null) Pill("Preview & edit", false) { editing = true }
            }
        }

        files.forEach { f ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(T.bg)
                .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, fontSize = T.caption, color = T.ink, maxLines = 1,
                    modifier = Modifier.weight(1f))
                Text("✕", fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.clickable { files = files.filterNot { it.uri == f.uri } })
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
                                // ALWAYS send_email, never send_document. Routing an email with an
                                // attachment through send_document threw away the subject and the
                                // body the owner had just read and edited, and sent the file under
                                // a generated covering note instead — the one screen whose entire
                                // purpose is "what you approved is what goes" quietly did not.
                                val arg = JSONObject()
                                    .put("to", to).put("subject", subject).put("body", body)
                                if (files.isNotEmpty()) arg.put("attachments",
                                    org.json.JSONArray().apply {
                                        files.forEach { f ->
                                            put(JSONObject().put("uri", f.uri).put("name", f.name))
                                        }
                                    })
                                ToolRouter.executeAction(ctx, "send_email", arg.toString())
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
                                        (if (files.isEmpty()) ""
                                         else "\n\nAttached: " + files.joinToString(", ") { f -> f.name }),
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
private fun Pill(
    label: String, on: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier.padding(end = 7.dp, bottom = 7.dp),
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (on) 1.04f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 800f), label = "p")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = T.caption,
            color = if (on) Color.White else if (enabled) T.inkSoft else T.inkFaint,
            maxLines = 1, softWrap = false)
    }
}
