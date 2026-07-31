package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.Asks
import com.agentos.shell.tools.NetworkProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three steps, once, and then never again.
 *
 * The field is beautiful and completely silent about what it is for. Somebody opening it for the
 * first time sees twenty thousand dots and a link called "where you stand", which explains nothing
 * — and the two things the network genuinely cannot work without (what you offer, and how anybody
 * reaches you) are both a tap away down a path nobody has a reason to take.
 *
 * So there is one button, and it walks through the three answers in the order they matter:
 *
 *   1. **What you offer, and what you need.** The only prose, drafted from the brain so nobody
 *      faces an empty box.
 *   2. **How people reach you.** Skipped by everybody when it is optional, and the consequence is
 *      invisible until somebody agrees to introduce you and hands over a card with nothing on it.
 *   3. **The first ask.** Because a network you have joined but never used is one you will not
 *      come back to, and the whole thing is worth exactly one question.
 *
 * It ends by launching, not by saving. The last step sends the ask and returns to the field, where
 * lines physically reach out to other people — which is the first moment any of this looks like it
 * is doing something.
 */
@Composable
fun NetworkSetup(
    modifier: Modifier = Modifier,
    onDone: (launched: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var step by remember { mutableStateOf(0) }
    var prof by remember { mutableStateOf(NetworkProfile.get(ctx)) }
    var askText by remember { mutableStateOf("") }
    var drafting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(50) }
    var eligible by remember { mutableStateOf(-1) }
    var note by remember { mutableStateOf("") }

    val progress by animateFloatAsState((step + 1) / 3f, tween(420), label = "p")

    Column(modifier.fillMaxSize().background(T.bg)) {
        // Where you are in it, at all times. Three steps you can see the end of is a different
        // proposition from a form of unknown length.
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("STEP ${step + 1} OF 3", fontSize = 10.sp, color = T.accent,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Skip", fontSize = 10.sp, color = T.inkFaint,
                    modifier = Modifier.clickable { onDone(false) })
            }
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(999.dp))
                .background(T.hairline)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp)
                    .clip(RoundedCornerShape(999.dp)).background(T.accent))
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(26.dp))
            when (step) {
                0 -> {
                    Head("What do you offer,\nand what do you need?",
                        "Two sentences. This is all anyone else ever sees about you.")
                    Spacer(Modifier.height(16.dp))
                    Text(if (drafting) "reading your brain…" else "Write these from my brain →",
                        fontSize = T.caption, color = T.accent, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = !drafting) {
                            drafting = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                val d = withContext(Dispatchers.IO) {
                                    try { NetworkProfile.draft(ctx) } catch (e: Exception) { prof }
                                }
                                prof = prof.copy(
                                    offer = d.offer.ifBlank { prof.offer },
                                    lookingFor = d.lookingFor.ifBlank { prof.lookingFor },
                                    openTo = d.openTo.ifBlank { prof.openTo },
                                    tags = if (d.tags.isNotEmpty()) d.tags else prof.tags)
                                drafting = false
                            }
                        }.padding(vertical = 6.dp))
                    Box2("WHAT I OFFER", prof.offer) { prof = prof.copy(offer = it) }
                    Box2("WHAT I'M LOOKING FOR", prof.lookingFor) { prof = prof.copy(lookingFor = it) }
                    if (prof.tags.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("People will find you under: " + prof.tags.joinToString(", "),
                            fontSize = 12.sp, color = T.inkFaint, lineHeight = 18.sp)
                    }
                }
                1 -> {
                    Head("How should people\nreach you?",
                        "Handed over only when you agree to an introduction. Never before, never to anyone else.")
                    Spacer(Modifier.height(14.dp))
                    NetworkProfile.SHARING.forEach { (key, label) ->
                        Row(Modifier.fillMaxWidth().clickable { prof = prof.copy(shareOnIntro = key) }
                            .padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (prof.shareOnIntro == key) "●" else "○", fontSize = 13.sp,
                                color = if (prof.shareOnIntro == key) T.accent else T.inkFaint)
                            Text("  $label", fontSize = T.caption,
                                color = if (prof.shareOnIntro == key) T.ink else T.inkSoft)
                        }
                    }
                    if (prof.shareOnIntro != "none") {
                        if (prof.shareOnIntro != "calendly")
                            Box2("EMAIL", prof.contactEmail, true) { prof = prof.copy(contactEmail = it) }
                        if (prof.shareOnIntro != "email")
                            Box2("BOOKING LINK", prof.calendly, true) { prof = prof.copy(calendly = it) }
                        if (prof.shareOnIntro == "both")
                            Box2("PHONE", prof.contactPhone, true) { prof = prof.copy(contactPhone = it) }
                    }
                }
                else -> {
                    LaunchedEffect(step, prof.tags) {
                        if (step == 2) eligible = withContext(Dispatchers.IO) {
                            try { Asks.eligible(ctx, prof.tags) } catch (e: Exception) { 0 }
                        }
                    }
                    Head("Who do you want\nto reach?",
                        "One line. Your agent asks around for you, quietly.")
                    Box2("WHO CAN INTRODUCE ME TO…", askText) { askText = it }
                    // How far it goes is a choice, not a constant — and it is the number that
                    // will one day be the difference between tiers.
                    Spacer(Modifier.height(18.dp))
                    Text("HOW MANY PEOPLE SHOULD IT ASK", fontSize = 9.sp, color = T.inkFaint,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 50, 200).forEach { n ->
                            Box(Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (target == n) T.accent else T.bgElevated)
                                .clickable { target = n }
                                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("$n people", fontSize = T.caption,
                                    color = if (target == n) Color.White else T.inkSoft,
                                    maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    Text(buildString {
                            // The truth about a young network, said out loud rather than hidden
                            // behind a bar that never moves.
                            if (eligible in 0 until target)
                                if (eligible == 1) append("Only 1 other person uses SlyOS so far, " +
                                "so it will ask them now and the rest as more people join. ")
                            else append("$eligible people use SlyOS so far, so it will ask them " +
                                "now and more as people join. ")
                            append("It looks for 3 days, and stops as soon as it finds 3 people. " +
                                   "It will only interrupt you when somebody can actually help.")
                        }, fontSize = 12.sp, color = T.inkFaint, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            if (note.isNotEmpty()) {
                Text(note, fontSize = 10.sp, color = T.danger, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
            }
            val ready = when (step) {
                0 -> !prof.isEmpty
                1 -> prof.reachable
                else -> true
            }
            Text(when {
                    busy -> "…"
                    step < 2 -> "Next"
                    askText.isBlank() -> "Finish without asking"
                    else -> "Launch it"
                },
                fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                    .background(if (ready && !busy) T.accent else T.hairline)
                    .clickable(enabled = ready && !busy) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (step < 2) { step++; return@clickable }
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                NetworkProfile.save(ctx, prof)
                                val (published, msg) = try { NetworkProfile.publish(ctx) }
                                    catch (e: Exception) { false to (e.message ?: "couldn't publish") }
                                if (!published) return@withContext msg
                                if (askText.isBlank()) return@withContext ""
                                val (id, m) = try { Asks.create(ctx, askText, prof.tags, targetReach = target) }
                                    catch (e: Exception) { null to (e.message ?: "couldn't send") }
                                if (id == null) m else ""
                            }
                            busy = false
                            if (ok.isEmpty()) onDone(askText.isNotBlank()) else note = ok
                        }
                    }.padding(vertical = 15.dp))
            if (step == 1 && !prof.reachable) {
                Spacer(Modifier.height(7.dp))
                // The step everybody wants to skip, and the one whose absence is invisible until
                // it has already cost somebody an introduction.
                Text("Without this, someone could agree to introduce you and have no way to reach you.",
                    fontSize = 11.sp, color = T.inkFaint, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun Head(title: String, sub: String) {
    Text(title, fontSize = 27.sp, color = T.ink, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Text(sub, fontSize = T.caption, color = T.inkFaint, lineHeight = 19.sp)
}

@Composable
private fun Box2(label: String, value: String, single: Boolean = false, onChange: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(label, fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(7.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bgElevated)
        .padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text("…", fontSize = T.caption, color = T.inkFaint)
        BasicTextField(value, onChange, singleLine = single,
            textStyle = TextStyle(color = T.ink, fontSize = T.caption, lineHeight = 20.sp),
            modifier = Modifier.fillMaxWidth())
    }
}
