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
import androidx.compose.foundation.horizontalScroll
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
 * Your paperwork — the documents other people sent you.
 *
 * The index behind this already works and feeds the brain, so "where's my Verizon bill" is
 * answerable by asking. This is for the other half of how people look for a document, which is not
 * asking a question but glancing down a list until they recognise it — usually because they cannot
 * remember what the thing is called, only roughly what it was and roughly when.
 *
 * So it leads with the CATEGORY filter rather than a search box. "Bills" and "Insurance" are how
 * paperwork is remembered; filenames are not, and a screen that opens on an empty search field asks
 * you to recall the one thing you never knew.
 *
 * Nothing is downloaded until it is opened. The list is an index, and an index of three hundred
 * documents costs nothing while three hundred PDFs on the phone would.
 */
@Composable
fun PapersScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var docs by remember { mutableStateOf(listOf<ReceivedDocs.AnyDoc>()) }
    var source by remember { mutableStateOf<ReceivedDocs.Source?>(null) }
    var kind by remember { mutableStateOf<ReceivedDocs.Kind?>(null) }
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // EVERYTHING, not just the emailed ones. Three stores, one list — see ReceivedDocs.everything.
    fun reload() { docs = ReceivedDocs.everything(ctx) }

    LaunchedEffect(Unit) {
        reload()
        // Catch anything that arrived since the worker's last pass, so opening the screen is never
        // the stale view — the mistake the health page made for months.
        scanning = true
        val n = withContext(Dispatchers.IO) { try { ReceivedDocs.scan(ctx, 60) } catch (e: Exception) { 0 } }
        scanning = false
        if (n > 0) { reload(); note = "$n new" }
    }

    val shown = remember(docs, kind, query, source) {
        var list = docs
        if (query.isNotBlank()) list = list.filter {
            it.name.contains(query, true) || it.subtitle.contains(query, true) ||
                ReceivedDocs.label(it.kind).contains(query, true)
        }
        if (kind != null) list = list.filter { it.kind == kind }
        if (source != null) list = list.filter { it.source == source }
        list
    }
    val counts = remember(docs) { docs.groupingBy { it.kind }.eachCount() }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Papers", onBack)

        Spacer(Modifier.height(4.dp))
        Text(when {
                scanning -> "checking your mail…"
                docs.isEmpty() -> "nothing filed yet"
                else -> "${docs.size} documents" +
                    (if (note.isNotBlank()) " · $note" else "")
            }, fontSize = 10.sp, color = T.inkFaint)

        // ── Categories, because that is how paperwork is remembered ──
        // Where it came from — a filter, not an address. Only shown when there is more than one
        // source to choose between, because a single-option filter is furniture.
        val srcCounts = docs.groupingBy { it.source }.eachCount()
        if (srcCounts.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Everything", source == null) { source = null }
                ReceivedDocs.Source.values().filter { (srcCounts[it] ?: 0) > 0 }.forEach { sc ->
                    Chip("${ReceivedDocs.sourceLabel(sc)} ${srcCounts[sc]}", source == sc) {
                        source = if (source == sc) null else sc
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", kind == null) { kind = null }
            // Only categories that actually have something in them — an empty filter is a dead end
            // dressed as a choice.
            ReceivedDocs.Kind.values().filter { (counts[it] ?: 0) > 0 }
                .sortedByDescending { counts[it] ?: 0 }
                .forEach { k ->
                    Chip("${ReceivedDocs.label(k)} ${counts[k]}", kind == k) {
                        kind = if (kind == k) null else k
                    }
                }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (query.isEmpty())
                Text("a sender, a subject, a filename", fontSize = T.caption, color = T.inkFaint)
            BasicTextField(query, { query = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.source.name + it.name + it.ts }) { d ->
                Column(Modifier.fillMaxWidth().clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        note = "opening ${d.name}…"
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    when {
                                        d.received != null -> ReceivedDocs.open(ctx, d.received)
                                        d.uri.isNotBlank() ->
                                            com.agentos.shell.tools.DocForge.open(ctx, d.uri, d.name)
                                        else -> false
                                    }
                                } catch (e: Exception) { false }
                            }
                            // Said plainly. A tap that silently does nothing is worse than an error.
                            note = if (ok) "" else "Couldn't open ${d.name}"
                        }
                    }.padding(vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ReceivedDocs.label(d.kind).uppercase(), fontSize = 8.sp,
                            color = T.accent, fontWeight = FontWeight.Bold)
                        Text("  ·  " + ReceivedDocs.sourceLabel(d.source).lowercase(),
                            fontSize = 8.sp, color = T.inkFaint)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(d.name, fontSize = T.caption, color = T.ink, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(d.subtitle + " · " + java.text.SimpleDateFormat(
                            "d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(d.ts)),
                        fontSize = 10.sp, color = T.inkFaint, maxLines = 1)
                }
                Hairline()
            }
            if (shown.isEmpty() && !scanning) item {
                Spacer(Modifier.height(30.dp))
                Text(if (docs.isEmpty())
                        "Nothing yet. Anything emailed to you, made here, or scanned gets filed here automatically."
                     else "Nothing matches.",
                    fontSize = T.caption, color = T.inkFaint, lineHeight = 18.sp)
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 9.dp)
    ) {
        Text(label, fontSize = T.caption, color = if (on) Color.White else T.inkSoft,
            maxLines = 1, softWrap = false)
    }
}
