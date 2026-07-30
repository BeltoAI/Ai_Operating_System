package com.agentos.shell.screens

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
import com.agentos.shell.tools.NetworkProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where you stand — the three lines your agent acts on.
 *
 * This is the whole input to the network, and it is three sentences rather than a profile because a
 * profile is a thing people abandon halfway through filling in.
 *
 * The three do genuinely different jobs, and it is worth being precise about which is which, because
 * a network where everybody declares what they want and nobody declares what they give is a room
 * full of askers:
 *
 *  - **OFFER** — what your agent can say YES to when somebody else's agent comes asking. This is the
 *    reason you are useful to the network rather than only a consumer of it.
 *  - **LOOKING FOR** — what your agent goes out and asks about, on its own, while you are asleep.
 *  - **OPEN TO** — the filter. What reaches you at all, and what never does.
 *
 * The brain writes the first draft of all three. Nobody composes three thoughtful sentences about
 * themselves on a screen they opened out of curiosity — but editing a draft that is already 80% right
 * takes twenty seconds, and the brain has months of evidence about what somebody actually does.
 */
@Composable
fun StandingScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var prof by remember { mutableStateOf(NetworkProfile.get(ctx)) }
    var drafting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Where you stand", onBack)
        Spacer(Modifier.height(4.dp))
        Text("your agent acts on these three lines — and so does everyone else's",
            fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)

        // The brain goes first, because an empty form is what kills this.
        Spacer(Modifier.height(14.dp))
        Text(if (drafting) "reading your brain…" else
                if (prof.isEmpty) "Write these for me →" else "Rewrite from my brain →",
            fontSize = T.caption, color = T.accent, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(enabled = !drafting) {
                drafting = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val d = withContext(Dispatchers.IO) {
                        try { NetworkProfile.draft(ctx) } catch (e: Exception) { prof }
                    }
                    // Never blank out something they wrote by hand with an empty model result.
                    prof = prof.copy(
                        offer = d.offer.ifBlank { prof.offer },
                        lookingFor = d.lookingFor.ifBlank { prof.lookingFor },
                        openTo = d.openTo.ifBlank { prof.openTo },
                        tags = if (d.tags.isNotEmpty()) d.tags else prof.tags)
                    drafting = false
                }
            }.padding(vertical = 6.dp))

        Field("WHAT I OFFER",
            "what your agent says yes to when someone asks",
            prof.offer) { prof = prof.copy(offer = it) }

        Field("WHAT I'M LOOKING FOR",
            "what your agent goes out and asks about",
            prof.lookingFor) { prof = prof.copy(lookingFor = it) }

        Field("WHAT I'M OPEN TO",
            "and what should never reach you",
            prof.openTo) { prof = prof.copy(openTo = it) }

        if (prof.tags.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("HOW YOU'RE FOUND")
            Spacer(Modifier.height(3.dp))
            // Said plainly, because a routing tag is the one thing here that leaves the phone
            // without being read first, and people should know exactly which words those are.
            Text("the only words the network sees. everything else stays on this phone.",
                fontSize = 10.sp, color = T.inkFaint)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                prof.tags.forEach { t ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(t, fontSize = 10.sp, color = T.inkSoft, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("WHO CAN REACH ME")
        Spacer(Modifier.height(8.dp))
        NetworkProfile.REACHABILITY.forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { prof = prof.copy(reachability = key) }
                .padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (prof.reachability == key) "●" else "○", fontSize = 13.sp,
                    color = if (prof.reachability == key) T.accent else T.inkFaint)
                Spacer(Modifier.height(0.dp))
                Text("  $label", fontSize = T.caption,
                    color = if (prof.reachability == key) T.ink else T.inkSoft)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(if (drafting) "…" else "Save and publish", fontSize = T.small, color = Color.White,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (prof.isEmpty) T.hairline else T.accent)
                .clickable(enabled = !prof.isEmpty) {
                    NetworkProfile.save(ctx, prof)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val (ok, msg) = withContext(Dispatchers.IO) {
                            try { NetworkProfile.publish(ctx) }
                            catch (e: Exception) { false to (e.message ?: "couldn't publish") }
                        }
                        note = msg
                    }
                }.padding(vertical = 15.dp))

        if (note.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(note, fontSize = T.caption,
                color = if (note.contains("✓")) T.good else T.danger, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
private fun Field(title: String, hint: String, value: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(18.dp))
    SectionLabel(title)
    Spacer(Modifier.height(3.dp))
    Text(hint, fontSize = 10.sp, color = T.inkFaint)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bg)
        .padding(horizontal = 15.dp, vertical = 14.dp)) {
        if (value.isEmpty())
            Text("…", fontSize = T.caption, color = T.inkFaint)
        BasicTextField(value, onChange,
            textStyle = TextStyle(color = T.ink, fontSize = T.caption, lineHeight = 20.sp),
            modifier = Modifier.fillMaxWidth())
    }
}
