package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.ReceivedDocs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every document you have, in one place.
 *
 * The first version of this screen was rejected on sight, and correctly. It stacked two rows of
 * chips and a search box above the content, shouted an ALL-CAPS category over every row, and made
 * its two actions 10sp text links. Those links are not merely ugly — they are genuinely hard to
 * hit, which is why "couldn't open it" and "this looks unprofessional" arrived as one complaint.
 * A twelve-pixel tap target is a design failure that presents as a functional one.
 *
 * What it is now:
 *
 *  - **Cards, not rows.** A document is an object and should look like one: real padding, one
 *    strong line for its name, one quiet line for everything else.
 *  - **One filter, not two.** Categories only. Where a document came from is a word on the card,
 *    because it is something you notice, not something you go hunting through.
 *  - **Real buttons.** Full-width pills with proper hit areas, on the selected card.
 *  - **Search behind a tap.** People arrive knowing roughly what they want to see, not the exact
 *    filename, so a text field is not the first thing in their way.
 */
@Composable
fun PapersScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var docs by remember { mutableStateOf(listOf<ReceivedDocs.AnyDoc>()) }
    var kind by remember { mutableStateOf<ReceivedDocs.Kind?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<ReceivedDocs.AnyDoc?>(null) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    /** Confirm before removing — a one-tap delete in a list is a mis-tap waiting to happen. */
    var confirmDelete by remember { mutableStateOf<ReceivedDocs.AnyDoc?>(null) }

    fun reload() { docs = ReceivedDocs.everything(ctx) }

    LaunchedEffect(Unit) {
        reload()
        scanning = true
        val n = withContext(Dispatchers.IO) { try { ReceivedDocs.scan(ctx, 60) } catch (e: Exception) { 0 } }
        scanning = false
        if (n > 0) reload()
    }

    val shown = remember(docs, kind, query) {
        var list = docs
        if (query.isNotBlank()) list = list.filter {
            it.name.contains(query, true) || it.subtitle.contains(query, true) ||
                ReceivedDocs.label(it.kind).contains(query, true)
        }
        if (kind != null) list = list.filter { it.kind == kind }
        list
    }
    val counts = remember(docs) { docs.groupingBy { it.kind }.eachCount() }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenHeader("Papers", onBack) }
            Text(if (searching) "Close" else "Search", fontSize = T.caption, color = T.inkSoft,
                modifier = Modifier.clickable {
                    searching = !searching; if (!searching) query = ""
                }.padding(8.dp))
        }

        if (searching) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bg)
                .padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (query.isEmpty())
                    Text("a name, a sender, a kind", fontSize = T.caption, color = T.inkFaint)
                BasicTextField(query, { query = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                    modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(when {
                scanning && docs.isEmpty() -> "reading your mail…"
                docs.isEmpty() -> "nothing filed yet"
                kind != null -> "${shown.size} ${ReceivedDocs.label(kind!!).lowercase()}"
                else -> "${docs.size} documents"
            }, fontSize = 10.sp, color = T.inkFaint)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Pill("All", kind == null) { kind = null }
            // The catch-all goes LAST despite being the biggest. Sorting purely by count put
            // "Document 81" first and pushed "Contract 8" and "Invoice 2" off the edge of the
            // screen — burying the only two filters anybody would actually use behind the one
            // that means "we could not tell".
            ReceivedDocs.Kind.values().filter { (counts[it] ?: 0) > 0 }
                .sortedWith(compareBy({ it == ReceivedDocs.Kind.OTHER }, { -(counts[it] ?: 0) }))
                .forEach { k ->
                    Pill("${ReceivedDocs.label(k)} ${counts[k]}", kind == k) {
                        kind = if (kind == k) null else k
                    }
                }
        }

        Spacer(Modifier.height(8.dp))
        // Gestures nobody is told about are gestures nobody uses.
        Text("tap for options  ·  swipe right to open  ·  left to file away",
            fontSize = 9.sp, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.source.name + it.name + it.ts }) { d ->
                val on = picked === d
                val lift by animateFloatAsState(if (on) 1f else 0.994f,
                    spring(dampingRatio = 0.8f, stiffness = 400f), label = "c")
                // SWIPE, WITH THE CARD TELLING YOU WHAT IT WILL DO.
                //
                // Two faults in the first version. The drag detector SWALLOWED the tap, so tapping a
                // card no longer revealed Send — a gesture added for convenience quietly removed the
                // one that already worked. Tap and drag now live in the same pointerInput, which is
                // the only way the two reliably coexist.
                //
                // And a swipe with no feedback is a guess. Nobody should have to commit to a gesture
                // to find out whether it deletes something. The intent appears behind the card as
                // soon as it moves, and only turns solid past the point where releasing would act.
                var dx by remember(d) { mutableStateOf(0f) }
                val armed = kotlin.math.abs(dx) > 110f
                Box(Modifier.fillMaxWidth()) {
                    if (dx != 0f) {
                        Box(
                            Modifier.matchParentSize().clip(RoundedCornerShape(15.dp))
                                .background(
                                    (if (dx > 0) T.accent else T.danger)
                                        .copy(alpha = if (armed) 0.9f else 0.35f)
                                ),
                            contentAlignment = if (dx > 0) Alignment.CenterStart else Alignment.CenterEnd
                        ) {
                            Text(if (dx > 0) "Open" else "File away",
                                fontSize = T.caption, color = Color.White,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 26.dp))
                        }
                    }
                Column(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { scaleX = lift; scaleY = lift; translationX = dx }
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (on) T.bgElevated else T.bg)
                        .pointerInput(d) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        dx < -110f -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ReceivedDocs.forget(ctx, d); picked = null; reload()
                                        }
                                        dx > 110f -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) { openDoc(ctx, d) }
                                                if (!ok) { picked = d; note = "Couldn't open this one." }
                                            }
                                        }
                                    }
                                    dx = 0f
                                },
                                onDragCancel = { dx = 0f }
                            ) { _, amount -> dx += amount }
                        }
                        .pointerInput(d) {
                            detectTapGestures {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                picked = if (on) null else d; note = ""
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 15.dp)
                ) {
                    Text(d.name.removeSuffix(".pdf").trim(),
                        fontSize = T.small, color = T.ink, maxLines = 2,
                        fontWeight = FontWeight.Medium, lineHeight = 20.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ReceivedDocs.label(d.kind), fontSize = 10.sp, color = T.accent)
                        Text("  ·  " + java.text.SimpleDateFormat(
                                "d MMM", java.util.Locale.getDefault()).format(java.util.Date(d.ts)) +
                             "  ·  " + ReceivedDocs.sourceLabel(d.source).lowercase(),
                            fontSize = 10.sp, color = T.inkFaint, maxLines = 1)
                    }

                    if (on) {
                        Spacer(Modifier.height(11.dp))
                        // Tap still reveals Send, because sharing has no natural direction and a
                        // third gesture would be one to remember rather than one to guess.
                        Action("Send it somewhere", primary = false, busy = working,
                            modifier = Modifier.fillMaxWidth()) {
                            working = true; note = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { shareDoc(ctx, d) }
                                working = false
                                if (!ok) note = "Couldn't share this one."
                            }
                        }
                        if (note.isNotEmpty()) {
                            Spacer(Modifier.height(9.dp))
                            Text(note, fontSize = 10.sp, color = T.danger)
                        }
                    }
                }   // card Column
                }   // swipe Box
            }
            if (shown.isEmpty() && !scanning) item {
                Spacer(Modifier.height(30.dp))
                Text(if (docs.isEmpty())
                        "Anything emailed to you, made here, or scanned gets filed here on its own."
                     else "Nothing matches.",
                    fontSize = T.caption, color = T.inkFaint, lineHeight = 19.sp)
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

@Composable
private fun Action(
    label: String, primary: Boolean, busy: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp))
            .background(if (primary) T.accent else T.hairline)
            .clickable(enabled = !busy) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (busy) "…" else label, fontSize = T.caption,
            color = if (primary) Color.White else T.ink,
            fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = T.caption, color = if (on) Color.White else T.inkSoft,
            maxLines = 1, softWrap = false)
    }
}

/**
 * Open whatever produced it.
 *
 * The device uri is tried FIRST. A document can be both — filed here and also sitting in Gmail —
 * and the local file opens instantly while the mail path has to download.
 */
private fun openDoc(ctx: android.content.Context, d: ReceivedDocs.AnyDoc): Boolean = try {
    when {
        d.uri.isNotBlank() -> com.agentos.shell.tools.DocForge.open(ctx, d.uri, d.name)
        d.received != null -> ReceivedDocs.open(ctx, d.received)
        else -> false
    }
} catch (e: Exception) { false }

/** Hand it to any app on the phone — mail, WhatsApp, Drive — not only an email composer. */
private fun shareDoc(ctx: android.content.Context, d: ReceivedDocs.AnyDoc): Boolean = try {
    val uri = when {
        d.uri.isNotBlank() -> d.uri
        d.received != null -> com.agentos.shell.tools.GmailClient.downloadAttachment(
            ctx, com.agentos.shell.tools.GmailClient.MailAttachment(
                d.received.msgId, d.received.attId, d.received.name, d.received.mime,
                d.received.from, d.received.subject, d.received.ts))?.toString().orEmpty()
        else -> ""
    }
    if (uri.isBlank()) false
    else com.agentos.shell.tools.DocForge.share(ctx, uri, d.name)
} catch (e: Exception) { false }
