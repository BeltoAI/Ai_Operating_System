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
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

/** Stable per-node depth so the cloud has a consistent 3D shape frame to frame. */
private fun depthZ(id: Int): Float { val h = (id * 374761393 + 668265263); return ((h and 0x7fffffff) % 640 - 320).toFloat() }

/** Project a node to the screen with yaw (spin) + tilt + perspective. Returns screen point and a
 *  depth scale (≈1 near, <1 far) used for size/ordering. Shared by the renderer and tap hit-testing
 *  so taps land on what you see. */
private fun project(n: MemoryGraphStore.Node, cx: Float, cy: Float, scale: Float, yaw: Float, tilt: Float): Pair<Offset, Float> {
    val z = depthZ(n.id)
    val ca = cos(yaw); val sa = sin(yaw)
    val x2 = n.x * ca - z * sa
    val z2 = n.x * sa + z * ca
    val ct = cos(tilt); val st = sin(tilt)
    val y2 = n.y * ct - z2 * st
    val z3 = n.y * st + z2 * ct
    val focal = 1100f
    val p = (focal / (focal - z3)).coerceIn(0.45f, 1.9f)
    return Offset(cx + x2 * scale * p, cy + y2 * scale * p) to p
}

/** Color a person node by the platform they're from, so the brain reads as colorful lobes. */
private fun platformColor(p: String): Color {
    val s = p.lowercase()
    return when {
        s.contains("whatsapp") -> Color(0xFF1FA855)
        s.contains("instagram") -> Color(0xFFC13584)
        s.contains("linkedin") -> Color(0xFF2E6F9E)
        s.contains("telegram") -> Color(0xFF2AABEE)
        s.contains("messen") || s.contains("orca") -> Color(0xFF7B3FF2)
        s.contains("signal") -> Color(0xFF3A76F0)
        else -> Color(0xFF9A8B77)
    }
}

/** Strip markdown so answers render as clean text (no **, #, backticks; tidy bullets). */
private fun prettify(s: String): String {
    var t = s
    t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")        // **bold**
    t = t.replace(Regex("__(.+?)__"), "$1")               // __bold__
    t = t.replace(Regex("`([^`]*)`"), "$1")               // `code`
    t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")       // # headers
    t = t.replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")       // - / * bullets
    t = t.replace(Regex("(?<![\\w*])\\*(\\S.*?\\S)\\*(?![\\w*])"), "$1")  // *italic*
    t = t.replace(Regex("\n{3,}"), "\n\n")
    return t.trim()
}

@Composable
fun MemoryGraphScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var version by remember { mutableStateOf(0) }
    val nodes = MemoryGraphStore.nodes
    val edges = MemoryGraphStore.edges
    var scale by remember { mutableStateOf(0.75f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Rebuild on entry, then auto-fit the zoom so ALL nodes are visible (otherwise a big graph
    // spreads past the screen edges and only the center shows — looking like it never grew).
    LaunchedEffect(Unit) {
        MemoryGraphStore.rebuild(ctx)
        val ext = MemoryGraphStore.nodes.maxOfOrNull { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) } ?: 1f
        scale = (440f / (ext + 60f)).coerceIn(0.2f, 1f)
        offset = Offset.Zero
        version++
    }
    // Live refresh: while you're looking at the brain, pull in anything new (fixed layout seed keeps
    // node positions stable, so it grows without jumping around).
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            val before = MemoryGraphStore.nodes.size
            MemoryGraphStore.rebuild(ctx)
            if (MemoryGraphStore.nodes.size != before) version++
        }
    }
    var selected by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var searchHist by remember { mutableStateOf(com.agentos.shell.tools.MemoryStore.searchHistory(ctx)) }
    var typeFilter by remember { mutableStateOf<String?>(null) }   // tap a legend color to isolate that type
    var answer by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pathNodes by remember { mutableStateOf<List<Int>>(emptyList()) }
    // The brain's standing mission + its latest self-assessment.
    var mission by remember { mutableStateOf(com.agentos.shell.tools.MissionStore.mission(ctx)) }
    var missionSaved by remember { mutableStateOf(true) }
    var missionOpen by remember { mutableStateOf(com.agentos.shell.tools.MissionStore.mission(ctx).isNotBlank()) }
    var assessing by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var missionErr by remember { mutableStateOf("") }
    var lastCheck by remember { mutableStateOf(com.agentos.shell.tools.MissionStore.latest(ctx)) }
    var plan by remember { mutableStateOf(com.agentos.shell.tools.MissionStore.milestones(ctx)) }
    var checkHist by remember { mutableStateOf(com.agentos.shell.tools.MissionStore.checks(ctx)) }
    val flow by rememberInfiniteTransition(label = "f").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ff"
    )
    // 3D brain: nodes get a stable depth and the whole cloud rotates very slowly with perspective —
    // a living neural globe. Always on now.
    val threeD = true
    val spin by rememberInfiniteTransition(label = "s").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(90000, easing = LinearEasing), RepeatMode.Restart), label = "sp"
    )
    // Manual rotation: drag to spin the globe (yaw) and tilt it (pitch), on top of the slow auto-spin.
    var userYaw by remember { mutableStateOf(0f) }
    var userTilt by remember { mutableStateOf(0.35f) }   // a slight starting tilt reads as 3D immediately

    fun recenter(id: Int) { offset = Offset(-nodes[id].x * scale, -nodes[id].y * scale) }
    fun ask() {
        if (query.isBlank()) return
        com.agentos.shell.tools.MemoryStore.addSearch(ctx, query)
        searchHist = com.agentos.shell.tools.MemoryStore.searchHistory(ctx)
        searching = true; answer = ""; pathNodes = emptyList(); selected = null
        scope.launch {
            // Semantic-ish expansion: search by meaning across messages AND the LinkedIn network.
            val expanded = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.expandQuery(query) }
            val q = (listOf(query) + expanded).joinToString(" ")
            val recall = if (com.agentos.shell.tools.MemoryStore.recallEnabled(ctx))
                com.agentos.shell.tools.InteractionStore.search(ctx, q, 40)
                    .map { "Seen in ${it.app}: ${it.text}" } else emptyList()
            // Your imported network (LinkedIn connections) — so "do I know any VCs" works.
            val conns = withContext(Dispatchers.IO) { com.agentos.shell.tools.ConnectionStore.search(ctx, q, 60) }
                .map { "Connection: ${it.name}" + (if (it.role.isNotBlank()) " — ${it.role}" else "") + (if (it.company.isNotBlank()) " at ${it.company}" else "") }
            val dbHits = withContext(Dispatchers.IO) {
                com.agentos.shell.tools.MessageStore.search(ctx, q, 70)
                    .map { (if (it.role == "me") "You to ${it.contact}" else "${it.contact}") + ": " + it.body }
            }
            // Your written papers' CONTENT (not just titles) — so Ask can answer from your research.
            val paperHits = withContext(Dispatchers.IO) { com.agentos.shell.tools.PaperStore.libraryContext(ctx, 0L, q, 3000) }
                .split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
            // The loaded PDF knowledge base — so Ask can also answer from a document you fed it.
            val docHits = if (com.agentos.shell.tools.KnowledgeStore.hasDoc(ctx))
                withContext(Dispatchers.IO) { com.agentos.shell.tools.KnowledgeStore.retrieve(ctx, q, 2500) }
                    .split("\n").map { it.trim() }.filter { it.length > 20 } else emptyList()
            // Connections first (people you know), then messages, then graph facts/recall.
            val extra = MemoryGraphStore.memoryLines() + recall
            val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
            val rankedExtra = if (terms.isEmpty()) extra.takeLast(40)
                else extra.map { it to terms.count { t -> it.lowercase().contains(t) } }
                    .filter { it.second > 0 }.sortedByDescending { it.second }.take(60).map { it.first }
            // Checklist tasks: pulled in directly so "what's on my list / what tasks do I have" works
            // even though task text rarely contains the words "task" or "checklist".
            val taskQuery = Regex("task|to-?do|checklist|errand|chore|remind|due|what.*do|need.*do|outstanding|pending",
                RegexOption.IGNORE_CASE).containsMatchIn(query)
            val taskLines = withContext(Dispatchers.IO) { com.agentos.shell.tools.ChecklistStore.load(ctx) }
                .filter { taskQuery || terms.any { t -> it.text.lowercase().contains(t) } }
                .map { "Checklist task: ${it.text} — ${if (it.done) "done" else "to do"}" }
            // Your schedule — so "am I free / what's blocked" answers from the real calendar.
            val schedQ = Regex("free|busy|schedule|calendar|meeting|available|blocked|book|when am i", RegexOption.IGNORE_CASE).containsMatchIn(query)
            val calLines = if (schedQ) withContext(Dispatchers.IO) { com.agentos.shell.tools.CalendarTool.upcoming(ctx) }
                .split("\n").map { it.trim() }.filter { it.isNotBlank() }.map { "Schedule: $it" } else emptyList()
            // Direct list of your papers by TITLE — so "what papers do I have / what did I write" reliably
            // lists them (content keyword-matching alone misses a generic 'what papers' question).
            val paperQuery = Regex("paper|whitepaper|white ?paper|research|document|wrote|writ|publish|essay|report|zenodo|doi",
                RegexOption.IGNORE_CASE).containsMatchIn(query)
            val paperTitles = if (paperQuery) withContext(Dispatchers.IO) { com.agentos.shell.tools.PaperStore.list(ctx) }
                .map { "Your paper: “${it.title}” (${it.docType})" } else emptyList()
            val corpus = ArrayList<String>(); var chars = 0
            // Lead with whatever the question is really about.
            val ordered = when {
                paperQuery -> paperTitles + paperHits + conns + dbHits + docHits + taskLines + rankedExtra
                schedQ     -> calLines + taskLines + conns + dbHits + paperHits + docHits + rankedExtra
                taskQuery  -> taskLines + conns + dbHits + paperHits + docHits + rankedExtra
                else       -> conns + dbHits + paperHits + docHits + taskLines + rankedExtra
            }
            for (l in ordered) { if (chars + l.length > 14000) break; corpus.add(l); chars += l.length }
            val a = withContext(Dispatchers.IO) {
                if (corpus.isEmpty()) "I don't have anything on that yet." else AgentClient.askMemory(query, corpus)
            }
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
        version.let { }
        Text("${nodes.size} memories mapped · drag to rotate, pinch to zoom", fontSize = T.caption, color = T.inkFaint,
            modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(10.dp))
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
            Text("⚙ Settings", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { onSettings() })
        }

        // Recent searches: visible while the box is empty / being typed and before an answer shows.
        // Filtered live as you type; tap to re-run, or Clear to wipe the history.
        if (answer.isEmpty() && !searching && searchHist.isNotEmpty()) {
            val shown = searchHist.filter { query.isBlank() || it.contains(query.trim(), true) }.take(6)
            if (shown.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                    Text("Clear", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable {
                        com.agentos.shell.tools.MemoryStore.clearSearchHistory(ctx); searchHist = emptyList()
                    })
                }
                Spacer(Modifier.height(4.dp))
                shown.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { query = h; ask() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("↺", fontSize = T.caption, color = T.inkFaint)
                        Spacer(Modifier.width(8.dp))
                        Text(h, fontSize = T.small, color = T.inkSoft, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (searching || answer.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp)) {
                Text("✦", color = ACCENT, fontSize = T.small)
                Spacer(Modifier.width(10.dp))
                // Bounded + scrollable so long answers stay readable; markdown stripped for clean text.
                Text(if (searching) "Searching your memory…" else prettify(answer),
                    fontSize = T.small, color = if (searching) T.inkFaint else T.ink,
                    modifier = Modifier.weight(1f).heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()))
                if (!searching) Text("✕", color = T.inkFaint, fontSize = T.small,
                    modifier = Modifier.clickable { answer = "" }.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        if (MemoryGraphStore.isEmpty()) {
            Text("Your brain is still filling in — as you chat and import, memories appear here. " +
                "Set a mission below to give it direction.", fontSize = T.small, color = T.inkFaint,
                modifier = Modifier.padding(vertical = 16.dp))
        }

        // Legend — tap a color to isolate that kind on the brain; tap again (or "All") to clear.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (typeFilter != null) {
                Text("✕ All", fontSize = T.caption, color = ACCENT,
                    modifier = Modifier.clickable { typeFilter = null }.padding(end = 12.dp))
            }
            listOf("Person" to "person", "Fact" to "idea", "Task" to "task",
                "Paper" to "paper", "Recall" to "recall", "Network" to "network", "Note" to "prompt").forEach { (label, type) ->
                val active = typeFilter == type
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { typeFilter = if (active) null else type }.padding(end = 12.dp)) {
                    Box(Modifier.size(if (active) 10.dp else 8.dp).clip(CircleShape).background(typeColor(type)))
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = T.caption, color = if (active) typeColor(type) else T.inkFaint)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF6F1E7))) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        // Drag rotates the globe with your finger (yaw + tilt); pinch still zooms.
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.4f, 3f)
                            userYaw += pan.x * 0.006f
                            userTilt = (userTilt - pan.y * 0.006f).coerceIn(-1.3f, 1.3f)
                        }
                    }
                    .pointerInput(version) {
                        detectTapGestures { tap ->
                            val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                            val yaw = spin + userYaw; val tilt = userTilt
                            var hit: Int? = null; var bestP = -1e9f
                            for (i in nodes.indices) {
                                val n = nodes[i]
                                val (pos, p) = project(n, cx, cy, scale, yaw, tilt)
                                val r = (7f + n.strength * 13f + 6f) * p
                                if (hypot(tap.x - pos.x, tap.y - pos.y) < r && p > bestP) { bestP = p; hit = i }
                            }
                            selected = hit; if (hit != null) pathNodes = emptyList()
                        }
                    }
            ) {
                version.let { }
                val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                val yaw = spin + userYaw; val tilt = userTilt
                fun P(n: MemoryGraphStore.Node): Pair<Offset, Float> = project(n, cx, cy, scale, yaw, tilt)
                val conn = selected?.let { s -> edges.filter { it.a == s || it.b == s }.flatMap { listOf(it.a, it.b) }.toSet() }
                val graphite = Color(0xFF8C8475)
                edges.forEach { e ->
                    if (e.a >= nodes.size || e.b >= nodes.size) return@forEach
                    val a = nodes[e.a]; val b = nodes[e.b]
                    val hot = selected != null && (e.a == selected || e.b == selected)
                    drawLine(
                        if (hot) ACCENT.copy(alpha = 0.35f) else Color(0xFF1A1714).copy(alpha = if (selected != null) 0.035f else 0.07f),
                        P(a).first, P(b).first,
                        strokeWidth = if (hot) 1.4f else 0.8f
                    )
                }
                // Synapse path: glowing connections between the memories behind the answer.
                if (pathNodes.size > 1) {
                    for (i in 0 until pathNodes.size - 1) {
                        val a = nodes[pathNodes[i]]; val b = nodes[pathNodes[i + 1]]
                        val (pa, _) = P(a); val (pb, _) = P(b)
                        val ax = pa.x; val ay = pa.y; val bx = pb.x; val by = pb.y
                        drawLine(ACCENT.copy(alpha = 0.7f), Offset(ax, ay), Offset(bx, by), strokeWidth = 2.5f)
                        drawCircle(ACCENT, 3f, Offset(ax + (bx - ax) * flow, ay + (by - ay) * flow))
                    }
                }
                // When a legend filter is on, lightly web the surviving nodes of that type together —
                // each to its 2 nearest same-type neighbors — so the isolated set reads as one constellation.
                typeFilter?.let { tf ->
                    val fil = nodes.filter { it.type == tf }
                    val tint = typeColor(tf)
                    for (a in fil) {
                        val near = fil.asSequence().filter { it !== a }
                            .sortedBy { (a.x - it.x) * (a.x - it.x) + (a.y - it.y) * (a.y - it.y) }.take(2)
                        for (b in near) drawLine(tint.copy(alpha = 0.28f),
                            P(a).first, P(b).first, strokeWidth = 1.1f)
                    }
                }
                nodes.sortedBy { if (threeD) -P(it).second else 0f }.forEach { n ->
                    val (pos, depth) = P(n)
                    val X = pos.x; val Y = pos.y
                    val sel = n.id == selected; val hub = n.type == "hub"
                    val inPath = pathNodes.contains(n.id)
                    val r = (4f + n.strength * 7f) * scale * depth
                    val dim = when {
                        typeFilter != null -> !hub && n.type != typeFilter   // legend filter takes precedence
                        pathNodes.isNotEmpty() -> !inPath
                        selected != null -> conn?.contains(n.id) != true
                        query.isNotBlank() -> !(n.label + n.content).contains(query, true) && !sel
                        else -> false
                    }
                    val a = if (dim) 0.16f else 1f
                    // Color each memory by what it IS, so the brain reads at a glance instead of
                    // looking like an undifferentiated cluster. Selection/path still glow accent.
                    val col = when {
                        sel || inPath -> ACCENT
                        hub -> ACCENT
                        n.type == "person" -> platformColor(n.source)
                        else -> typeColor(n.type)
                    }
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
                        val contact = n.key.removePrefix("person:")
                        val msgs = com.agentos.shell.tools.MessageStore.threadFor(ctx, contact, 8)
                        Spacer(Modifier.height(10.dp))
                        Text("RECENT", fontSize = T.caption, color = T.inkFaint)
                        msgs.forEach { m ->
                            Row(Modifier.padding(top = 5.dp)) {
                                Text(if (m.role == "me") "you" else "·", fontSize = T.caption,
                                    color = if (m.role == "me") ACCENT else T.inkFaint,
                                    modifier = Modifier.width(28.dp))
                                Text(m.body, fontSize = T.small, color = T.inkSoft)
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

        // ── MISSION: a standing goal for the brain, an AI plan, and an honest progress tracker ──
        // Shared evidence blob the planner + assessor both use.
        fun missionContext(): String {
            val about = com.agentos.shell.tools.MemoryStore.about(ctx)
            val tasks = com.agentos.shell.tools.ChecklistStore.load(ctx)
                .joinToString("\n") { "- ${it.text} (${if (it.done) "done" else "todo"})" }
            val papers = com.agentos.shell.tools.PaperStore.list(ctx).joinToString("\n") { "Paper: ${it.title}" }
            val hits = com.agentos.shell.tools.MessageStore.search(ctx, mission, 40)
                .joinToString("\n") { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
            val conns = com.agentos.shell.tools.ConnectionStore.count(ctx)
            val sem = com.agentos.shell.tools.VectorStore.search(ctx, mission, 10).joinToString("\n") { it.contact + ": " + it.body }
            val done = plan.filter { it.done }.joinToString("; ") { it.text }
            return buildString {
                if (about.isNotBlank()) append("About me: ").append(about).append("\n")
                append("LinkedIn connections: ").append(conns).append("\n")
                if (done.isNotBlank()) append("Milestones I've completed: ").append(done).append("\n")
                if (tasks.isNotBlank()) append("Checklist:\n").append(tasks).append("\n")
                if (papers.isNotBlank()) append(papers).append("\n")
                if (sem.isNotBlank()) append("Relevant memories:\n").append(sem).append("\n")
                if (hits.isNotBlank()) append("Messages related to the goal:\n").append(hits)
            }.take(9000)
        }

        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Divider(color = T.hairline, thickness = 1.dp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clickable { missionOpen = !missionOpen }, verticalAlignment = Alignment.CenterVertically) {
            Text("MISSION", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.weight(1f))
            val planPct = if (plan.isNotEmpty()) plan.count { it.done } * 100 / plan.size else null
            (lastCheck?.percent ?: planPct)?.let { Text("$it%", fontSize = T.small, color = ACCENT) }
            Spacer(Modifier.width(10.dp))
            Text(if (missionOpen) "–" else "+", fontSize = T.body, color = T.inkFaint)
        }
        if (missionOpen) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp)) {
                BasicTextField(
                    value = mission, onValueChange = { mission = it; missionSaved = false },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    decorationBox = { inner ->
                        if (mission.isEmpty()) Text("You are now me. Your goal is to… e.g. find 10 new customers for my studio, or grow my IG from 2k to 5k.",
                            fontSize = T.small, color = T.inkFaint); inner()
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (missionSaved) "Saved" else "Save", fontSize = T.small,
                        color = if (missionSaved) T.inkSoft else T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (missionSaved) T.hairline else T.accent)
                            .clickable {
                                com.agentos.shell.tools.MissionStore.setMission(ctx, mission)
                                missionSaved = true; plan = com.agentos.shell.tools.MissionStore.milestones(ctx)
                                lastCheck = com.agentos.shell.tools.MissionStore.latest(ctx)
                                checkHist = com.agentos.shell.tools.MissionStore.checks(ctx)
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (mission.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (planning) "Planning…" else if (plan.isEmpty()) "Make a plan" else "Re-plan",
                            fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable(enabled = !planning) {
                                    if (!missionSaved) { com.agentos.shell.tools.MissionStore.setMission(ctx, mission); missionSaved = true }
                                    planning = true
                                    scope.launch {
                                        val steps = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.planMission(mission, missionContext()) }
                                        if (steps.isNotEmpty()) { missionErr = ""; com.agentos.shell.tools.MissionStore.setPlan(ctx, steps); plan = com.agentos.shell.tools.MissionStore.milestones(ctx) }
                                        else missionErr = "Couldn't build a plan — likely a free-tier rate limit. Route Heavy/replies to Claude in Settings and retry."
                                        planning = false
                                    }
                                }.padding(horizontal = 14.dp, vertical = 8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (assessing) "Assessing…" else "Assess", fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(ACCENT)
                                .clickable(enabled = !assessing) {
                                    if (!missionSaved) { com.agentos.shell.tools.MissionStore.setMission(ctx, mission); missionSaved = true }
                                    assessing = true
                                    scope.launch {
                                        val a = withContext(Dispatchers.IO) {
                                            val days = ((System.currentTimeMillis() - com.agentos.shell.tools.MissionStore.since(ctx)) / 86_400_000L)
                                            com.agentos.shell.tools.AgentClient.assessMission(mission, missionContext(), "$days days ago")
                                        }
                                        if (a.percent >= 0) {
                                            missionErr = ""
                                            com.agentos.shell.tools.MissionStore.addCheck(ctx, a.percent, a.argument, a.next)
                                            lastCheck = com.agentos.shell.tools.MissionStore.latest(ctx)
                                            checkHist = com.agentos.shell.tools.MissionStore.checks(ctx)
                                        } else missionErr = a.argument   // surface the failure instead of doing nothing
                                        assessing = false
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }

                if (missionErr.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(missionErr, fontSize = T.caption, color = T.danger)
                }

                // Milestone plan — checkable sub-goals.
                if (plan.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("PLAN · ${plan.count { it.done }}/${plan.size}", fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(4.dp))
                    plan.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable {
                            com.agentos.shell.tools.MissionStore.toggleMilestone(ctx, m.id)
                            plan = com.agentos.shell.tools.MissionStore.milestones(ctx)
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (m.done) ACCENT else T.hairline), contentAlignment = Alignment.Center) {
                                if (m.done) Text("✓", fontSize = T.caption, color = T.bgElevated)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(m.text, fontSize = T.small, color = if (m.done) T.inkFaint else T.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Progress bar + trend.
                val planPct = if (plan.isNotEmpty()) plan.count { it.done } * 100 / plan.size else null
                val shownPct = lastCheck?.percent ?: planPct
                if (shownPct != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)) {
                        Box(Modifier.fillMaxWidth(shownPct.coerceIn(0, 100) / 100f).fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp)).background(ACCENT))
                    }
                }
                if (checkHist.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    val accentC = ACCENT
                    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                        val w = size.width; val h = size.height; val n = checkHist.size
                        fun px(i: Int) = if (n <= 1) 0f else i.toFloat() / (n - 1) * w
                        fun py(v: Int) = h - 3f - (v / 100f) * (h - 6f)
                        val path = Path(); path.moveTo(px(0), py(checkHist[0].percent))
                        for (i in 1 until n) path.lineTo(px(i), py(checkHist[i].percent))
                        drawPath(path, accentC, style = Stroke(width = 2.5f))
                        drawCircle(accentC, 3.5f, Offset(px(n - 1), py(checkHist.last().percent)))
                    }
                    Text("progress over ${checkHist.size} checks", fontSize = T.caption, color = T.inkFaint)
                }

                // Argument + next steps.
                lastCheck?.let { c ->
                    if (c.note.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(prettify(c.note), fontSize = T.small, color = T.inkSoft) }
                    if (c.next.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("NEXT", fontSize = T.caption, color = T.inkFaint)
                        Text(prettify(c.next), fontSize = T.small, color = T.ink)
                        Spacer(Modifier.height(8.dp))
                        Text("Add these to my checklist", fontSize = T.caption, color = ACCENT,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                                .clickable {
                                    c.next.split("\n").map { it.replace(Regex("^\\s*\\d+[).]?\\s*"), "").trim() }
                                        .filter { it.isNotBlank() }
                                        .forEach { com.agentos.shell.tools.ChecklistStore.add(ctx, it) }
                                }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
