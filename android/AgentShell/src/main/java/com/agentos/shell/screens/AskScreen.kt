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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.agentos.shell.tools.Crm
import com.agentos.shell.tools.NetworkProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks — both directions on one screen, because they are the same object seen from two ends.
 *
 * What you asked for sits above what you have been asked. That order is deliberate: a network where
 * answering feels like a chore people opt out of is a network that stops working within a month, so
 * the request you might answer is on the same screen as the one you sent, and the cost of answering
 * is one tap.
 *
 * The thing that is NOT here is a list of who was approached. Two hundred phones may have looked at
 * your ask; a hundred and ninety-eight of them found nothing and said nothing, and neither you nor
 * we can tell which. That absence is the feature.
 */
@Composable
fun AskScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<Pair<Asks.Ask, List<Asks.Answer>>>>(emptyList()) }
    var funnels by remember { mutableStateOf<Map<String, Asks.Funnel>>(emptyMap()) }
    var reopened by remember { mutableStateOf(-1) }
    var incoming by remember { mutableStateOf<List<Asks.Incoming>>(emptyList()) }
    var bridges by remember { mutableStateOf<List<Asks.Bridge>>(emptyList()) }
    var busy by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            incoming = try { Asks.inbox(ctx) } catch (e: Exception) { emptyList() }
            bridges = try { Asks.bridgesByPerson(ctx) } catch (e: Exception) { emptyList() }
            // An open ask keeps working: every visit re-offers it to anybody who has joined or
            // published tags since. Idempotent, so nobody is reached twice.
            reopened = try { Asks.refresh(ctx) } catch (e: Exception) { 0 }
            mine = try { Asks.myAsks(ctx).map { it to Asks.answers(ctx, it.id) } }
                   catch (e: Exception) { emptyList() }
            funnels = try {
                mine.mapNotNull { (a, _) -> Asks.funnel(ctx, a.id)?.let { a.id to it } }.toMap()
            } catch (e: Exception) { emptyMap() }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Asks", onBack)
        Spacer(Modifier.height(4.dp))
        Text("your agent asks everyone's agents · nobody is told they were asked",
            fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
            .padding(horizontal = 15.dp, vertical = 14.dp)) {
            if (text.isEmpty())
                Text("who can introduce me to…", fontSize = T.caption, color = T.inkFaint)
            BasicTextField(text, { text = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption, lineHeight = 20.sp),
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        // Said here, once, because it is the one thing somebody could get wrong in a way that
        // matters: this line is read by strangers' agents.
        Text("this line is public — say what you want, never why",
            fontSize = 9.sp, color = T.inkFaint)

        Spacer(Modifier.height(11.dp))
        Text(if (sending) "sending…" else "Ask the network", fontSize = T.caption,
            color = Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (text.isBlank()) T.hairline else T.accent)
                .clickable(enabled = !sending && text.isNotBlank()) {
                    sending = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val (id, msg) = withContext(Dispatchers.IO) {
                            // The tags you already published are the routing. Asking somebody to
                            // pick tags for every request is a form nobody fills in twice.
                            val tags = try { NetworkProfile.get(ctx).tags } catch (e: Exception) { emptyList() }
                            try { Asks.create(ctx, text, tags) } catch (e: Exception) { null to (e.message ?: "failed") }
                        }
                        sending = false; note = msg
                        if (id != null) { text = ""; round++ }
                    }
                }.padding(vertical = 14.dp))
        if (note.isEmpty() && reopened > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Reached $reopened new ${if (reopened == 1) "person" else "people"} since last time.",
                fontSize = 10.sp, color = T.good)
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(note, fontSize = 10.sp,
                color = if (note.contains("Sent")) T.good else T.danger, lineHeight = 15.sp)
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            if (incoming.isNotEmpty()) item {
                Spacer(Modifier.height(24.dp))
                SectionLabel("ASKED OF YOU")
                Spacer(Modifier.height(8.dp))
            }
            items(incoming.size) { i ->
                val inc = incoming[i]
                val hit = remember(inc.askId) {
                    try { Asks.bestMatch(ctx, inc.criteria) } catch (e: Exception) { null }
                }
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(15.dp)).background(T.bgElevated).padding(16.dp)) {
                    Text(inc.criteria, fontSize = T.caption, color = T.ink, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    if (hit == null) {
                        // Honest, and the common case. Nothing is sent when nothing is known.
                        Text("You don't know anyone matching this.",
                            fontSize = 10.sp, color = T.inkFaint)
                        Spacer(Modifier.height(9.dp))
                        Text("Dismiss", fontSize = T.caption, color = T.inkSoft,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Asks.decline(ctx, inc.askId) }
                                    round++
                                }
                            })
                    } else {
                        Text("You know ${hit.first.name}" +
                             (if (hit.first.company.isNotBlank()) " · ${hit.first.company}" else ""),
                            fontSize = 10.sp, color = T.inkSoft)
                        Text("closeness ${(hit.second * 100).toInt()}%",
                            fontSize = 9.sp, color = T.inkFaint)
                        Spacer(Modifier.height(11.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(if (busy == inc.askId) "…" else "Introduce them",
                                fontSize = T.caption, color = T.accent,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable(enabled = busy.isEmpty()) {
                                    busy = inc.askId
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            try {
                                                val asker = Asks.askerOf(ctx, inc.askId)
                                                if (asker.isBlank()) false to "couldn't find the asker"
                                                else Asks.accept(ctx, inc.askId, asker, hit.first, "")
                                            } catch (e: Exception) { false to (e.message ?: "failed") }
                                        }
                                        busy = ""; note = ok.second; round++
                                    }
                                })
                            Text("No", fontSize = T.caption, color = T.inkSoft,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Asks.decline(ctx, inc.askId) }
                                        round++
                                    }
                                })
                        }
                    }
                }
            }

            if (mine.isNotEmpty()) item {
                Spacer(Modifier.height(24.dp))
                SectionLabel("YOU ASKED")
                Spacer(Modifier.height(8.dp))
            }
            items(mine.size) { i ->
                val (ask, ans) = mine[i]
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(15.dp)).background(T.bg).padding(16.dp)) {
                    Text(ask.criteria, fontSize = T.caption, color = T.ink, lineHeight = 19.sp)
                    Spacer(Modifier.height(6.dp))
                    // Proof of life. "Your agent is working on it" with no numbers and no clock is
                    // how every abandoned assistant feature read on the day it stopped working.
                    val f = funnels[ask.id]
                    Text(buildString {
                            if (f != null) {
                                append("reached ${f.reached}")
                                if (f.stillThinking > 0) append("  ·  ${f.stillThinking} still looking")
                                if (f.foundNothing > 0) append("  ·  ${f.foundNothing} found nobody")
                            }
                            if (isNotEmpty()) append("  ·  ")
                            append(ask.closesIn)
                        }, fontSize = 9.sp,
                        color = if (ask.live) T.inkSoft else T.inkFaint, lineHeight = 14.sp)
                    Spacer(Modifier.height(7.dp))
                    // Group by person first. Three offers of the same person is ONE introduction
                    // with three routes, and reporting it as three would flatter the numbers.
                    val named = ans.filter { it.person != null }
                        .groupBy { it.person!!.lowercase().trim() }
                        .map { (_, r) -> r.maxByOrNull { it.strength }!! to r.size }
                        .sortedByDescending { it.first.strength }
                    val waiting = ans.filter { it.person == null && it.state == "interested" }
                    Text(when {
                            named.isNotEmpty() -> named.size.toString() +
                                (if (named.size == 1) " introduction" else " introductions")
                            waiting.isNotEmpty() ->
                                "${waiting.size} agents know someone · waiting on their owner"
                            else -> "nothing back yet"
                        }, fontSize = 10.sp, color = if (named.isNotEmpty()) T.good else T.inkFaint)
                    named.forEachIndexed { n, (a, routes) ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.person.orEmpty(), fontSize = T.caption, color = T.accent,
                                fontWeight = FontWeight.Medium)
                            // The strongest route, named as such. This is the whole answer to
                            // "ten people know them" — you are shown the closest one first.
                            if (n == 0 && named.size > 1) {
                                Spacer(Modifier.height(0.dp))
                                Text("  closest", fontSize = 9.sp, color = T.good)
                            }
                        }
                        Text("closeness ${(a.strength * 100).toInt()}%" +
                             (if (routes > 1) " · $routes people know them" else "") +
                             (if (a.note.isNotBlank()) " · ${a.note}" else ""),
                            fontSize = 9.sp, color = T.inkFaint)
                    }
                }
            }

            if (bridges.isNotEmpty()) item {
                Spacer(Modifier.height(24.dp))
                SectionLabel("SHARED")
                Spacer(Modifier.height(3.dp))
                Text("people two networks turned out to have in common",
                    fontSize = 9.sp, color = T.inkFaint)
                Spacer(Modifier.height(8.dp))
            }
            items(bridges.size) { i ->
                val b = bridges[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("◇", fontSize = 11.sp, color = T.accent)
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(b.person, fontSize = T.caption, color = T.ink)
                        Text(if (b.mine) "introduced to you" else "you introduced them",
                            fontSize = 9.sp, color = T.inkFaint)
                    }
                }
            }
            item { Spacer(Modifier.height(70.dp)) }
        }
    }
}
