package com.agentos.shell.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
    else -> Color(0xFF8C8475)
}
private val ACCENT = Color(0xFFE8642C)

@Composable
fun MemoryGraphScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    LaunchedEffect(Unit) { MemoryGraphStore.rebuild(ctx) }
    var version by remember { mutableStateOf(0) }

    val nodes = MemoryGraphStore.nodes
    val edges = MemoryGraphStore.edges
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "pp"
    )
    fun recenter(id: Int) { offset = Offset(-nodes[id].x * scale, -nodes[id].y * scale) }
    fun ask() {
        if (query.isBlank()) return
        searching = true; answer = ""
        scope.launch {
            val a = withContext(Dispatchers.IO) { AgentClient.askMemory(query, MemoryGraphStore.memoryLines()) }
            answer = a; searching = false
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
                            selected = hit
                        }
                    }
            ) {
                pulse.let { }
                val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                val conn = selected?.let { s -> edges.filter { it.a == s || it.b == s }.flatMap { listOf(it.a, it.b) }.toSet() }
                edges.forEach { e ->
                    if (e.a >= nodes.size || e.b >= nodes.size) return@forEach
                    val a = nodes[e.a]; val b = nodes[e.b]
                    val hot = selected != null && (e.a == selected || e.b == selected)
                    drawLine(
                        if (hot) ACCENT.copy(alpha = 0.4f) else Color(0xFF282A1C).copy(alpha = if (selected != null) 0.04f else 0.09f),
                        Offset(cx + a.x * scale, cy + a.y * scale), Offset(cx + b.x * scale, cy + b.y * scale),
                        strokeWidth = if (hot) 2f else 1f
                    )
                }
                nodes.forEach { n ->
                    val X = cx + n.x * scale; val Y = cy + n.y * scale
                    val r = (7f + n.strength * 13f) * scale; val col = typeColor(n.type)
                    val dim = (selected != null && conn?.contains(n.id) != true) ||
                        (query.isNotBlank() && !(n.label + n.content).contains(query, true) && n.id != selected)
                    val a = if (dim) 0.22f else 1f
                    drawCircle(col.copy(alpha = 0.16f * a), r * 1.7f, Offset(X, Y + 1.5f))
                    drawCircle(col.copy(alpha = a), r, Offset(X, Y))
                    if (n.id == selected) drawCircle(ACCENT, r + 4f, Offset(X, Y), style = Stroke(width = 2f))
                    if (n.recency > 0.7f && !dim)
                        drawCircle(ACCENT.copy(alpha = pulse), 2.6f * scale, Offset(X + r * 0.72f, Y - r * 0.72f))
                    if (scale > 0.8f || n.strength > 0.74f || n.id == selected) {
                        drawIntoCanvas { c ->
                            val p = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#4A4136")
                                textSize = 11f * density; textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true; alpha = (a * 235).toInt()
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
