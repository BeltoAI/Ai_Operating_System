package com.agentos.shell.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.GoogleIntent
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calendar, Gmail and Meet — what is about to happen, before it happens.
 *
 * The reason this page exists rather than a reply in the chat: these requests were failing silently.
 * A model was asked to choose an action name, sometimes chose none, and the request became a
 * sentence saying it had been done. Measured across 24 scenarios, moving, cancelling, notifying
 * attendees and sending a document produced no action at all on either provider.
 *
 * So the parse is deterministic ([GoogleIntent]) and the result is shown as objects you can read and
 * correct. Every field is editable, every step can be dropped, and nothing runs until it is run.
 * Being able to SEE that the guest list has two people on it is worth more than any assurance that
 * it does.
 *
 * The one thing still written by a model is the prose inside an email — because that is the part it
 * is good at, and the part where being wrong is survivable.
 */
@Composable
fun GoogleScreen(
    prompt: String,
    modifier: Modifier = Modifier,
    /** Tapping an example re-enters this page with that sentence parsed. */
    onExample: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val plan = remember(prompt) { GoogleIntent.parse(ctx, prompt) }
    // Steps are copied into editable state: the parse is a proposal, not a decision.
    var steps by remember(prompt) { mutableStateOf(plan?.steps.orEmpty()) }
    var dropped by remember(prompt) { mutableStateOf(setOf<Int>()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(prompt) { appear = false; delay(40); appear = true }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Google", onBack)

        // ── OPENED WITHOUT A REQUEST ──
        //
        // Reached from the shortcut rather than from a sentence, this is a place rather than a
        // result: what is actually on the calendar right now, and the shapes of request that work.
        // Showing the phrasings is not decoration — the whole surface is typed, so the examples ARE
        // the interface, and a page that only ever appears mid-request teaches nobody what it does.
        if (prompt.isBlank()) {
            val today = remember { com.agentos.shell.tools.GoogleIntent.parse(ctx, "what's on my calendar today") }
            Spacer(Modifier.height(6.dp))
            Text("Calendar, mail and Meet. Times, guests and which event you mean are worked out " +
                 "on this phone — the same every time, on any model.",
                fontSize = T.small, color = T.inkSoft, lineHeight = 21.sp)

            today?.answer?.takeIf { it.isNotBlank() }?.let { a ->
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(T.bgElevated).padding(16.dp)) {
                    Text("TODAY", fontSize = 9.sp, color = T.accent,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(a, fontSize = T.small, color = T.ink, lineHeight = 22.sp)
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("JUST SAY")
            Spacer(Modifier.height(10.dp))
            listOf(
                "invite Joslyn to a call tomorrow at 4pm with a google meet",
                "block Thursday 10 to 11 in the Boardroom with carlos@example.com",
                "move my 2pm tomorrow to 4pm and let her know",
                "cancel my 3pm with Carlos",
                "let everyone in my 2pm know I'll be ten minutes late",
                "make a one-pager on the pilot and email it to carlos@example.com",
                "what's on my calendar this week"
            ).forEachIndexed { i, ex ->
                val fade by animateFloatAsState(if (appear) 1f else 0f, tween(220 + i * 70), label = "e$i")
                val shift by animateFloatAsState(if (appear) 0f else 14f,
                    spring(dampingRatio = 0.85f, stiffness = 300f - i * 18f), label = "s$i")
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .offset(y = shift.dp)
                    .graphicsLayer { alpha = fade }
                    .clip(RoundedCornerShape(13.dp)).background(T.bgElevated)
                    .clickable { onExample(ex) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(ex, fontSize = T.caption, color = T.inkSoft,
                        lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
            }
            Spacer(Modifier.height(50.dp))
            return@Column
        }

        if (plan == null) {
            Spacer(Modifier.height(24.dp))
            Text("I couldn't read that as a calendar or email request.",
                fontSize = T.small, color = T.inkFaint)
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        Spacer(Modifier.height(18.dp))
        Text(plan.summary, fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium,
            lineHeight = 28.sp)

        // ── A straight answer, where the request was a question ──
        if (plan.answer.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(T.bgElevated).padding(16.dp)) {
                Text(plan.answer, fontSize = T.small, color = T.ink, lineHeight = 22.sp)
            }
            Spacer(Modifier.height(10.dp))
            // Read straight off the device calendar. Worth saying, because the whole point of this
            // page is that it is not a model's recollection of a calendar.
            Text("Read from your calendar just now — not from memory.",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(50.dp))
            return@Column
        }

        // ── What could not be worked out ──
        if (plan.questions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            plan.questions.forEach { q ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(T.danger.copy(alpha = 0.12f)).padding(14.dp)) {
                    Text(q, fontSize = T.small, color = T.ink, lineHeight = 20.sp)
                }
            }
            Text("Nothing runs until that's answered — guessing a time looks exactly like getting " +
                 "it right until the meeting is missed.",
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
            Spacer(Modifier.height(50.dp))
            return@Column
        }

        // ── The steps, staggered in ──
        Spacer(Modifier.height(18.dp))
        steps.forEachIndexed { i, step ->
            val on = i !in dropped
            // Each card arrives a beat after the one above it, so a three-part request reads as
            // three things in order rather than a block landing at once.
            val shift by animateFloatAsState(
                if (appear) 0f else 26f,
                spring(dampingRatio = 0.85f, stiffness = 260f - i * 25f), label = "in$i")
            val fade by animateFloatAsState(if (appear) 1f else 0f, tween(260 + i * 90), label = "f$i")

            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .offset(y = shift.dp)
                    .graphicsLayer { alpha = fade * (if (on) 1f else 0.42f) }
                    .clip(RoundedCornerShape(18.dp))
                    .background(T.bgElevated)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepGlyph(step.action)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kindOf(step.action), fontSize = 9.sp, color = T.accent,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(step.label, fontSize = T.small, color = T.ink, lineHeight = 20.sp)
                    }
                    Text(if (on) "✕" else "＋", fontSize = T.small, color = T.inkFaint,
                        modifier = Modifier.clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dropped = if (on) dropped + i else dropped - i
                        }.padding(8.dp))
                }

                // Every field, editable. Seeing that two guests are on it beats being told.
                val fields = remember(step.arg, editing) { fieldsOf(step.arg) }
                if (fields.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    fields.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label(k), fontSize = T.caption, color = T.inkFaint,
                                modifier = Modifier.width(80.dp))
                            var text by remember(step.arg, k) { mutableStateOf(v) }
                            BasicTextField(text, {
                                text = it
                                steps = steps.toMutableList().also { l ->
                                    l[i] = step.copy(arg = withField(step.arg, k, it))
                                }
                            },
                                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                                modifier = Modifier.weight(1f))
                        }
                    }
                }

                results[i]?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text(r, fontSize = T.caption,
                        color = if (r.startsWith("✓") || r.contains("✓")) T.good else T.danger,
                        lineHeight = 17.sp)
                }
            }
        }

        // ── One button ──
        val live = steps.indices.filter { it !in dropped }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                running -> "Working…"
                live.size <= 1 -> "Do it"
                else -> "Do all ${live.size}"
            },
            fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (live.isEmpty()) T.hairline else T.accent)
                .clickable(enabled = !running && live.isNotEmpty()) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    running = true
                    scope.launch {
                        live.forEach { idx ->
                            val out = withContext(Dispatchers.IO) {
                                try { ToolRouter.executeAction(ctx, steps[idx].action, steps[idx].arg) }
                                catch (e: Exception) { "Couldn't: ${e.message?.take(60)}" }
                            }
                            results = results + (idx to out.ifBlank { "Done ✓" })
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        running = false
                    }
                }
                .padding(vertical = 15.dp))

        Spacer(Modifier.height(10.dp))
        Text("Read and change anything before it runs. Times, guests and which event this is were " +
             "worked out on the phone, not guessed — so they are the same every time.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        Spacer(Modifier.height(50.dp))
    }
}

/** A quiet mark per step type — three cards in a row should be distinguishable at a glance. */
@Composable
private fun StepGlyph(action: String) {
    val ch = when {
        action.contains("event") -> "▣"
        action.contains("email") || action.contains("document") -> "✉"
        action.contains("sms") || action.contains("message") -> "▤"
        else -> "●"
    }
    Box(Modifier.size(34.dp).clip(CircleShape).background(T.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center) {
        Text(ch, fontSize = 15.sp, color = T.accent)
    }
}

private fun kindOf(action: String) = when (action) {
    "add_event" -> "CALENDAR"
    "move_event", "update_event" -> "RESCHEDULE"
    "cancel_event" -> "CANCEL"
    "event_followup" -> "TELL THE ATTENDEES"
    "compose_email", "send_email" -> "EMAIL"
    "send_document" -> "ATTACHMENT"
    "send_sms", "message" -> "MESSAGE"
    else -> action.uppercase()
}

private fun label(k: String) = when (k) {
    "title" -> "What"; "start" -> "From"; "end" -> "Until"; "attendees" -> "Guests"
    "location" -> "Where"; "to" -> "To"; "topic" -> "About"; "meet" -> "Meet"
    "message" -> "Message"; "text" -> "Message"; "name" -> "File"
    else -> k.replaceFirstChar { it.uppercase() }
}

/** The fields worth showing, in a sensible order and in human form. */
private fun fieldsOf(arg: String): List<Pair<String, String>> = try {
    val o = JSONObject(arg)
    listOf("title", "start", "end", "attendees", "location", "to", "topic", "message", "text", "name", "meet")
        .mapNotNull { k ->
            if (!o.has(k)) null else {
                val v = when (val raw = o.get(k)) {
                    is JSONArray -> (0 until raw.length()).joinToString(", ") { raw.optString(it) }
                    is Boolean -> if (raw) "yes" else "no"
                    else -> raw.toString()
                }
                if (v.isBlank()) null else k to v
            }
        }
} catch (e: Exception) { emptyList() }

/** Put an edited value back, keeping arrays as arrays — the bug that once lost every guest. */
private fun withField(arg: String, key: String, value: String): String = try {
    val o = JSONObject(arg)
    when {
        key == "attendees" -> o.put("attendees",
            JSONArray().apply { value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) } })
        key == "meet" -> o.put("meet", value.startsWith("y", true) || value == "true")
        else -> o.put(key, value)
    }
    o.toString()
} catch (e: Exception) { arg }

