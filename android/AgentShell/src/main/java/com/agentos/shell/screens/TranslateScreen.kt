package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.Brain
import com.agentos.shell.tools.HoldToTalk
import com.agentos.shell.tools.Translate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A conversation between two people who do not share a language, on one phone laid flat.
 *
 * The screen is split and **the far half is upside down**, so the person across the table reads
 * their side the right way up without anyone passing the phone back and forth. Each half has its own
 * hold-to-talk: you hold yours and speak, and what you said appears translated on THEIR half,
 * facing them. They hold theirs and reply, and it appears on yours.
 *
 * The two decisions that make it usable:
 *
 *  - **Hold, not tap.** A tap-to-start recogniser stops the moment it thinks you have finished, and
 *    someone speaking a sentence they are composing for a stranger pauses constantly. Holding says
 *    "I am still talking" with no ambiguity — the same reason Home uses it.
 *  - **Their words stay on screen.** A translation that disappears when the next person speaks is
 *    useless in a conversation, because the reply is a response to something. Both last turns stay
 *    visible at once.
 *
 * Translation runs on-device through ML Kit, so this works with no signal — which is exactly where
 * you need it.
 */
@Composable
fun TranslateScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // "mine" is the half nearest you; "theirs" is the upside-down half across the table.
    var myLang by remember { mutableStateOf("en") }
    var theirLang by remember { mutableStateOf("es") }
    var picking by remember { mutableStateOf<String?>(null) }

    // What each side last said, already translated INTO the other's language.
    var forThem by remember { mutableStateOf("") }
    var forMe by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf("") }   // "" | "mine" | "theirs"
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }

    val holder = remember { HoldToTalk(ctx) }
    DisposableEffect(Unit) {
        holder.onLevel = { level = it }
        holder.onPartial = { partial = it }
        holder.onError = { m -> listening = ""; partial = ""; level = 0f; note = m }
        onDispose { holder.cancel() }
    }

    fun speakDone(said: String, fromMine: Boolean) {
        listening = ""; partial = ""; level = 0f
        if (said.isBlank()) return
        val from = if (fromMine) myLang else theirLang
        val to = if (fromMine) theirLang else myLang
        // Show the original immediately on the speaker's own side, so there is no dead moment while
        // the translation runs — a pause with nothing on screen reads as a failure.
        if (fromMine) forThem = "…" else forMe = "…"
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try { Translate.translate(said, to) } catch (e: Exception) { said }
            }
            if (fromMine) forThem = out else forMe = out
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Into the brain: a conversation you had is a conversation you had, and "what did that
            // guy in Lisbon say about the ferry?" should be answerable next week.
            withContext(Dispatchers.IO) {
                try {
                    Brain.remember(ctx, "note", "Translated conversation",
                        "[$from] $said\n[$to] $out")
                } catch (e: Exception) {}
            }
        }
    }

    Box(modifier.fillMaxSize().background(T.bg)) {
        Column(Modifier.fillMaxSize()) {

            // ── THEIR HALF, upside down ──
            Box(Modifier.fillMaxWidth().weight(1f).background(T.bgElevated)) {
                Column(
                    Modifier.fillMaxSize().rotate(180f).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(Translate.nameOf(theirLang).uppercase(), fontSize = 10.sp, color = T.inkFaint,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                        modifier = Modifier.clickable { picking = "theirs" })
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (listening == "mine" && partial.isNotBlank()) "…"
                        else forThem.ifBlank { "Hold to speak" },
                        fontSize = if (forThem.length > 90) 18.sp else 24.sp,
                        color = if (forThem.isBlank()) T.inkFaint else T.ink,
                        textAlign = TextAlign.Center, lineHeight = 30.sp)
                    Spacer(Modifier.height(22.dp))
                    TalkDot(
                        active = listening == "theirs",
                        level = if (listening == "theirs") level else 0f,
                        onDown = {
                            if (listening.isEmpty()) {
                                listening = "theirs"; note = ""
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                holder.onFinal = { said -> speakDone(said, fromMine = false) }
                                holder.start()
                            }
                        },
                        onUp = { if (listening == "theirs") holder.stop() })
                }
            }

            // The seam, which is also where the phone sits between two people.
            Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))

            // ── YOUR HALF ──
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(Translate.nameOf(myLang).uppercase(), fontSize = 10.sp, color = T.inkFaint,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                        modifier = Modifier.clickable { picking = "mine" })
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (listening == "theirs" && partial.isNotBlank()) "…"
                        else forMe.ifBlank { "Hold to speak" },
                        fontSize = if (forMe.length > 90) 18.sp else 24.sp,
                        color = if (forMe.isBlank()) T.inkFaint else T.ink,
                        textAlign = TextAlign.Center, lineHeight = 30.sp)
                    Spacer(Modifier.height(22.dp))
                    TalkDot(
                        active = listening == "mine",
                        level = if (listening == "mine") level else 0f,
                        onDown = {
                            if (listening.isEmpty()) {
                                listening = "mine"; note = ""
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                holder.onFinal = { said -> speakDone(said, fromMine = true) }
                                holder.start()
                            }
                        },
                        onUp = { if (listening == "mine") holder.stop() })
                    if (note.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(note, fontSize = T.caption, color = T.danger)
                    }
                }
            }
        }

        Text("✕", fontSize = 18.sp, color = T.inkSoft,
            modifier = Modifier.align(Alignment.CenterEnd)
                .padding(end = 14.dp).clickable { onBack() }.padding(8.dp))
    }

    picking?.let { which ->
        Dialog(onDismissRequest = { picking = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated)
                .padding(18.dp)) {
                Text("Language", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(10.dp))
                Translate.COMMON.forEach { (code, name) ->
                    Text(name, fontSize = T.small,
                        color = if ((if (which == "mine") myLang else theirLang) == code) T.accent else T.ink,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (which == "mine") myLang = code else theirLang = code
                            picking = null
                        }.padding(vertical = 11.dp))
                }
            }
        }
    }
}

/**
 * The hold-to-talk control, on both halves.
 *
 * It grows while held and pulses with the voice, because the one thing someone needs to know
 * mid-sentence — especially someone speaking a language the phone is about to translate — is that
 * it is still listening.
 */
@Composable
private fun TalkDot(active: Boolean, level: Float, onDown: () -> Unit, onUp: () -> Unit) {
    val size by animateFloatAsState(
        targetValue = if (active) 84f + level * 16f else 62f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "dot")
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (active) T.accent else T.bgElevated)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onDown()
                    waitForUpOrCancellation()
                    onUp()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(if (active) "◉" else "●",
            fontSize = if (active) 26.sp else 18.sp,
            color = if (active) Color.White else T.accent)
    }
}
