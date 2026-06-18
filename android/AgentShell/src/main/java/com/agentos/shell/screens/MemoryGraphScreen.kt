package com.agentos.shell.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryGraphStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private fun typeColor(t: String): Color = when (t) {
    "hub" -> Color(0xFF2E2A24)
    "project" -> Color(0xFF46403A)
    "person" -> Color(0xFF9A8B77)
    "summary" -> Color(0xFFB09356)
    "task" -> Color(0xFFBC6242)
    "prompt" -> Color(0xFF8C8475)
    "response" -> Color(0xFFB3AB9C)
    "transcript" -> Color(0xFF86907A)
    "idea" -> Color(0xFFC39A5E)
    "recall" -> Color(0xFF6E8FA6)
    "network" -> Color(0xFF2E6F9E)
    else -> Color(0xFF8C8475)
}
private val ACCENT = Color(0xFFE8642C)

@Composable
fun MemoryGraphScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { MemoryGraphStore.rebuild(ctx); version++ }

    val nodes = MemoryGraphStore.nodes
    val edges = MemoryGraphStore.edges
    var scale by remember { mutableStateOf(0.75f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pathNodes by remember { mutableStateOf<List<Int>>(emptyList()) }
    val flow by rememberInfiniteTransition(label = "f").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ff"
    )

    fun recenter(id: Int) { offset = Offset(-nodes[id].x * scale, -nodes[id].y * scale) }
    fun ask() {
        if (query.isBlank()) return
        searching = true; answer = ""; pathNodes = emptyList(); selected = null
        scope.launch {
            val recall = if (com.agentos.shell.tools.MemoryStore.recallEnabled(ctx))
                com.agentos.shell.tools.InteractionStore.search(ctx, query, 40)
                    .map { "Seen in ${it.app}: ${it.text}" } else emptyList()
            // Pull relevant people from the imported network so questions like "interesting VCs" work.
            val conns = com.agentos.shell.tools.ConnectionStore.search(ctx, query, 40)
                .map { "Connection: ${it.name}" + (if (it.role.isNotBlank()) " — ${it.role}" else "") + (if (it.company.isNotBlank()) " at ${it.company}" else "") }
            val corpus = MemoryGraphStore.memoryLines() +
                com.agentos.shell.tools.ConversationStore.all(ctx).flatMap { (k, msgs) ->
                    val who = k.substringAfter("|").ifBlank { k.substringBefore("|") }
                    msgs.map { (if (it.role == "me") "You to $who" else who) + ": " + it.text }
                } + recall + conns
            val a = withContext(Dispatchers.IO) { AgentClient.askMemory(query, corpus) }
            answer = a
            // Light up the "synapse path": the memories most related to the question + answer.
            val toks = ("$query $a").lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            pathNodes = nodes.filter { it.type != "hub" }
                .map { n -> n.id to toks.count { (n.label + " " + n.content).lowercase().contains(it) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
            if (pathNodes.isNotEmpty()) {
                val px = pathNodes.map { nodes[it].x }.average().toFloat()
                val py = pathNodes.map { nodes[it].y }.average().toFloat()
                scale = 1f; offset = Offset(-px, -py)
            }
            searching = false
        }
    }

    Column(modifier) {
        ScreenHeader("Memory", onBack)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ask() }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(T.bgElevated).padding(horizontal = 12.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Ask your memory…", color = T.inkFaint, fontSize = T.small)
                    inner()
                }
            )
            Spacer(Modifier.width(10.dp))
            Text("Ask", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { ask() }.padding(horizontal = 14.dp, vertical = 9.dp))
            Spacer(Modifier.width(8.dp))
            Text("About", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { onSettings() })
        }

        if (searching || answer.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp)) {
                Text("✦", color = ACCENT, fontSize = T.small)
                Spacer(Modifier.width(10.dp))
                Text(if (searching) "Searching your memory…" else answer,
                    fontSize = T.small, color = if (searching) T.inkFaint else T.ink, modifier = Modifier.weight(1f))
                if (!searching) Text("✕", color = T.inkFaint, fontSize = T.small,
                    modifier = Modifier.clickable { answer = "" }.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        if (MemoryGraphStore.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Your memory is empty.\nAs you get messages, learn facts, and add tasks,\nthey appear here as connected memories.",
                    fontSize = T.small, color = T.inkFaint)
            }
            return@Column
        }

        // Legend — what each color means, so the brain reads at a glance.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            listOf("Person" to "person", "Fact" to "idea", "Task" to "task",
                "Paper" to "paper", "Recall" to "recall", "Network" to "network", "Note" to "prompt").forEach { (label, type) ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(typeColor(type)))
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.width(12.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF6F1E7))) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(0.4f, 3f); offset += pan }
                    }
                    .pointerInput(version) {
                        detectTapGestures { tap ->
                            val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                            val wx = (tap.x - cx) / scale; val wy = (tap.y - cy) / scale
                            var hit: Int? = null
                            for (i in nodes.indices.reversed()) {
                                val n = nodes[i]
                                if (hypot(wx - n.x, wy - n.y) < 7f + n.strength * 13f + 5f) { hit = i; break }
                            }
                            selected = hit; if (hit != null) pathNodes = emptyList()
                        }
                    }
            ) {
                version.let { }
                val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                val conn = selected?.let { s -> edges.filter { it.a == s || it.b == s }.flatMap { listOf(it.a, it.b) }.toSet() }
                val graphite = Color(0xFF8C8475)
                edges.forEach { e ->
                    if (e.a >= nodes.size || e.b >= nodes.size) return@forEach
                    val a = nodes[e.a]; val b = nodes[e.b]
                    val hot = selected != null && (e.a == selected || e.b == selected)
                    drawLine(
                        if (hot) ACCENT.copy(alpha = 0.35f) else Color(0xFF1A1714).copy(alpha = if (selected != null) 0.035f else 0.07f),
                        Offset(cx + a.x * scale, cy + a.y * scale), Offset(cx + b.x * scale, cy + b.y * scale),
                        strokeWidth = if (hot) 1.4f else 0.8f
                    )
                }
                // Synapse path: glowing connections between the memories behind the answer.
                if (pathNodes.size > 1) {
                    for (i in 0 until pathNodes.size - 1) {
                        val a = nodes[pathNodes[i]]; val b = nodes[pathNodes[i + 1]]
                        val ax = cx + a.x * scale; val ay = cy + a.y * scale
                        val bx = cx + b.x * scale; val by = cy + b.y * scale
                        drawLine(ACCENT.copy(alpha = 0.7f), Offset(ax, ay), Offset(bx, by), strokeWidth = 2.5f)
                        drawCircle(ACCENT, 3f, Offset(ax + (bx - ax) * flow, ay + (by - ay) * flow))
                    }
                }
                nodes.forEach { n ->
                    val X = cx + n.x * scale; val Y = cy + n.y * scale
                    val sel = n.id == selected; val hub = n.type == "hub"
                    val inPath = pathNodes.contains(n.id)
                    val r = (4f + n.strength * 7f) * scale
                    val dim = when {
                        pathNodes.isNotEmpty() -> !inPath
                        selected != null -> conn?.contains(n.id) != true
                        query.isNotBlank() -> !(n.label + n.content).contains(query, true) && !sel
                        else -> false
                    }
                    val a = if (dim) 0.16f else 1f
                    // Color each memory by what it IS, so the brain reads at a glance instead of
                    // looking like an undifferentiated cluster. Selection/path still glow accent.
                    val col = when { sel || inPath -> ACCENT; hub -> ACCENT; else -> typeColor(n.type) }
                    drawCircle(col.copy(alpha = a), r, Offset(X, Y))
                    drawCircle(Color(0xFF1A1714).copy(alpha = 0.12f * a), r, Offset(X, Y), style = Stroke(width = 0.8f))
                    if (sel || inPath) drawCircle(ACCENT, r + 5f, Offset(X, Y), style = Stroke(width = 1.6f))
                    if (sel || hub || inPath || scale > 1.4f) {
                        drawIntoCanvas { c ->
                            val p = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#6B6258")
                                textSize = 10.5f * density; textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true; alpha = (a * 220).toInt()
                            }
                            val lbl = if (n.label.length > 22) n.label.take(21) + "…" else n.label
                            c.nativeCanvas.drawText(lbl, X, Y + r + 13f * density, p)
                        }
                    }
                }
            }

            selected?.let { sid ->
                if (sid >= nodes.size) { selected = null; return@let }
                val n = nodes[sid]
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xFFFCFAF5)).padding(16.dp)) {
                    Text(n.type.uppercase() + if (n.pinned) "  ·  PINNED" else "", fontSize = T.caption, color = typeColor(n.type))
                    Spacer(Modifier.height(4.dp))
                    Text(n.label, fontSize = T.body, color = T.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(n.content, fontSize = T.small, color = T.inkSoft)
                    Spacer(Modifier.height(8.dp))
                    Text("↳ ${n.source}", fontSize = T.caption, color = T.inkFaint)

                    if (n.type == "person" && n.key.startsWith("person:")) {
                        val sKey = n.key.removePrefix("person:")
                        val msgs = com.agentos.shell.tools.ConversationStore
                            .thread(ctx, sKey.substringBefore("|"), sKey.substringAfter("|"))
                        Spacer(Modifier.height(10.dp))
                        Text("RECENT", fontSize = T.caption, color = T.inkFaint)
                        msgs.takeLast(6).forEach { m ->
                            Row(Modifier.padding(top = 5.dp)) {
                                Text(if (m.role == "me") "you" else "·", fontSize = T.caption,
                                    color = if (m.role == "me") ACCENT else T.inkFaint,
                                    modifier = Modifier.width(28.dp))
                                Text(m.text, fontSize = T.small, color = T.inkSoft)
                            }
                        }
                    }

                    if (n.type == "recall" && n.key.startsWith("recall:")) {
                        val app = n.key.removePrefix("recall:")
                        val snips = com.agentos.shell.tools.InteractionStore.recentForApp(ctx, app, 8)
                        Spacer(Modifier.height(10.dp))
                        Text("RECENT ON SCREEN", fontSize = T.caption, color = T.inkFaint)
                        snips.forEach { s ->
                            Text("· ${s.text}", fontSize = T.small, color = T.inkSoft,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row {
                        if (n.type != "hub")
                            Text("Forget", fontSize = T.small, color = T.danger,
                                modifier = Modifier.clickable { MemoryGraphStore.forget(ctx, n.key); selected = null; version++ })
                        Spacer(Modifier.width(18.dp))
                        Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { selected = null })
                    }
                }
            }
        }
    }
}
