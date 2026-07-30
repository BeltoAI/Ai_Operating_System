package com.agentos.shell.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.RelationGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One unit in a column. [key] is a person key for people, blank for channels and companies. */
private data class GNode(val layer: Int, val rank: Int, val label: String,
                         val key: String, val weight: Int)

/** [w] is a real message count — it becomes the line's weight, exactly like a trained one. */
private data class GLink(val from: Int, val to: Int, val w: Int, val why: String)

/**
 * Your network as the thing it actually is: a network, with weights.
 *
 * The first version was a force-directed cloud, and it was both ugly and thin — 160 nodes joined by
 * 7 lines, because shared employment was the only relationship it could prove. Correct, and not
 * worth looking at.
 *
 * Layers tell a truer story and fill in for the right reason. Everything you know about anybody
 * arrived through a channel, so the columns are the actual path information takes:
 *
 *      YOU  →  CHANNELS  →  PEOPLE  →  WHERE THEY WORK
 *
 * Every line is a fact and not an inference. A person is joined to a channel because you have
 * exchanged messages there, and the WEIGHT of the line is how many — which is why it reads like a
 * trained network rather than a picture of one. The heavy lines are your wife and your closest
 * friends; the faint ones are people you spoke to once. Nobody chose those weights. They are simply
 * what happened, and this is the first screen in the app where that is visible.
 *
 * The density is real density: people reach you on several channels at once, and no other view of
 * this data showed it.
 *
 * Animated once as a forward pass — column by column, left to right, the way a signal would actually
 * propagate — then still. A graph that never settles is a graph you cannot read. Weights use a
 * square-root scale so one two-thousand-message thread does not render every other line invisible.
 */
@Composable
fun GraphScreen(
    modifier: Modifier = Modifier,
    onPerson: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var nodes by remember { mutableStateOf(listOf<GNode>()) }
    var links by remember { mutableStateOf(listOf<GLink>()) }
    var picked by remember { mutableStateOf(-1) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var loaded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    /** Routes in to whatever was typed — the reason to have a graph at all. */
    var answer by remember { mutableStateOf<com.agentos.shell.tools.Intro.Answer?>(null) }
    var asking by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val built = withContext(Dispatchers.IO) { build(ctx) }
        loaded = true
        if (built != null) {
            nodes = built.first; links = built.second
            reveal.animateTo(1f, tween(1600, easing = EaseOutCubic))
        }
    }

    // THE QUESTION, ASKED OF WHATEVER WAS TYPED.
    //
    // The same box that dims the graph also answers "who could introduce me to this" — one input,
    // because making someone choose a mode before they can ask is the tax that stops them asking.
    LaunchedEffect(filter) {
        if (filter.trim().length < 3) { answer = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(320)
        answer = withContext(Dispatchers.IO) {
            try { com.agentos.shell.tools.Intro.pathsTo(ctx, filter) } catch (e: Exception) { null }
        }
    }

    // A slow breath on whatever is live, so it reads as alive rather than printed.
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        0.8f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "pv")

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 20.dp)) { ScreenHeader("Your network", onBack) }

        if (nodes.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(if (loaded) "Open People first so the book is built." else "reading…",
                fontSize = T.small, color = T.inkFaint,
                modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }

        Text("${nodes.count { it.layer == 2 }} people · ${nodes.count { it.layer == 1 }} channels · " +
             "${links.size} links · line weight is how much you actually talk",
            fontSize = 10.sp, color = T.inkFaint, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(4.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val counts = remember(nodes) { (0..3).associateWith { l -> nodes.count { it.layer == l } } }
            val maxW = remember(links) { (links.maxOfOrNull { it.w } ?: 1).coerceAtLeast(1) }

            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f); pan += panChange
                        }
                    }
                    .pointerInput(nodes) {
                        detectTapGestures { tap ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            var best = -1; var bestD = Float.MAX_VALUE
                            nodes.forEachIndexed { i, n ->
                                val d = (place(n, counts[n.layer] ?: 1, w, h, scale, pan) - tap).getDistance()
                                if (d < bestD) { bestD = d; best = i }
                            }
                            picked = if (bestD < 44f && picked != best) best else -1
                        }
                    }
            ) {
                val w = size.width; val h = size.height
                fun at(i: Int): Offset {
                    val n = nodes[i]
                    return place(n, counts[n.layer] ?: 1, w, h, scale, pan)
                }

                // ── The weights ──
                links.forEach { l ->
                    val a = nodes.getOrNull(l.from) ?: return@forEach
                    val b = nodes.getOrNull(l.to) ?: return@forEach
                    val gate = ((reveal.value * 4.2f) - minOf(a.layer, b.layer)).coerceIn(0f, 1f)
                    if (gate <= 0f) return@forEach
                    val fa = matches(a, filter); val fb = matches(b, filter)
                    val touched = picked == l.from || picked == l.to ||
                        (filter.isNotBlank() && fa && fb)
                    val s = Math.sqrt((l.w.toFloat() / maxW).coerceIn(0f, 1f).toDouble()).toFloat()
                    val alpha = when {
                        touched -> 0.95f * pulse
                        picked >= 0 -> 0.05f
                        filter.isNotBlank() -> if (fa || fb) 0.30f else 0.03f
                        else -> 0.16f + 0.72f * s
                    }
                    val p0 = at(l.from); val p1 = at(l.to)
                    val end = Offset(p0.x + (p1.x - p0.x) * gate, p0.y + (p1.y - p0.y) * gate)
                    // Curved, because a hundred straight lines converging on one point is a smear.
                    val path = Path().apply {
                        moveTo(p0.x, p0.y)
                        val mx = (p0.x + end.x) / 2f
                        cubicTo(mx, p0.y, mx, end.y, end.x, end.y)
                    }
                    drawPath(path, color = (if (touched) T.accent else T.ink).copy(alpha = alpha),
                        style = Stroke(width = (1.0f + 3.6f * s) * (if (touched) 1.8f else 1f)))
                }

                // ── The names ──
                //
                // Without labels this is abstract art. Channels always carry theirs; people carry a
                // first name once there is room for it, which is what zoom is for.
                val showPeople = scale > 1.25f
                nodes.forEachIndexed { i, n ->
                    val gate = ((reveal.value * 4.2f) - n.layer).coerceIn(0f, 1f)
                    if (gate < 0.9f) return@forEachIndexed
                    // A filter is itself a request for names: whatever matches gets labelled at any zoom.
                    val hit = filter.isNotBlank() && matches(n, filter)
                    if (n.layer == 2 && !showPeople && picked != i && !hit) return@forEachIndexed
                    val c = at(i)
                    val text = if (n.layer == 2) n.label.split(' ').first().take(11)
                               else n.label.take(13)
                    // A native draw rather than a TextMeasurer: this runs on every frame of a pan.
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(
                                (if (picked == i) 235 else 150), 235, 235, 240)
                            textSize = if (n.layer == 0) 26f else 21f
                            isAntiAlias = true
                            textAlign = if (n.layer >= 2) android.graphics.Paint.Align.LEFT
                                        else android.graphics.Paint.Align.RIGHT
                        }
                        val dx = if (n.layer >= 2) 16f else -16f
                        drawText(text, c.x + dx, c.y + 7f, paint)
                    }
                }

                // ── The units ──
                nodes.forEachIndexed { i, n ->
                    val gate = ((reveal.value * 4.2f) - n.layer).coerceIn(0f, 1f)
                    if (gate <= 0f) return@forEachIndexed
                    val c = at(i)
                    val base = when (n.layer) { 0 -> 15f; 1 -> 11f; 3 -> 8f; else -> 6.5f }
                    val r = (base + Math.sqrt(n.weight.toDouble()).toFloat() * 0.42f)
                        .coerceAtMost(20f) * gate * scale.coerceIn(0.7f, 1.5f)
                    val on = picked == i
                    val dim = (picked >= 0 && !on && links.none {
                        (it.from == picked && it.to == i) || (it.to == picked && it.from == i)
                    }) || (filter.isNotBlank() && !matches(n, filter))
                    if (n.layer == 0 || on) drawCircle(
                        color = T.accent.copy(alpha = 0.15f * pulse), radius = r * 2.2f, center = c)
                    drawCircle(
                        color = when {
                            on || n.layer == 0 -> T.accent
                            n.layer == 1 -> T.good
                            n.layer == 3 -> Color(0xFFD08770)
                            else -> T.inkSoft
                        }.copy(alpha = if (dim) 0.1f else 1f),
                        radius = r, center = c)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).align(Alignment.TopCenter)) {
                listOf("you", "channels", "people", "where").forEach { label ->
                    Text(label, fontSize = 8.sp, color = T.inkFaint,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }

            val sel = picked.takeIf { it >= 0 && it < nodes.size }?.let { nodes[it] }
            // FILTERING IS WHAT MAKES A GRAPH USEFUL RATHER THAN IMPRESSIVE.
            //
            // Twenty-two people and a hundred lines is a picture. "Stanford", "whatsapp", "belto"
            // turns it into an answer: everything not matching goes to almost nothing and what is
            // left is the shape of that one question. Typed at the bottom because that is where a
            // thumb already is, and matched on names, channels and companies at once so it never
            // matters which of the three you happen to have in mind.
            val ans = answer
            if (sel == null && ans != null && ans.routes.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                    .padding(bottom = 74.dp).align(Alignment.BottomCenter)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(T.bgElevated).padding(16.dp)) {
                        Text("WAYS IN TO “${ans.target.uppercase()}”", fontSize = 9.sp,
                            color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        if (ans.connectedCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("${ans.connectedCount} already connected on LinkedIn, never messaged",
                                fontSize = 10.sp, color = T.inkFaint)
                        }
                        var lastKind: com.agentos.shell.tools.Intro.Kind? = null
                        ans.routes.take(5).forEach { r ->
                            if (r.kind != lastKind) {
                                lastKind = r.kind
                                Spacer(Modifier.height(10.dp))
                                Text(com.agentos.shell.tools.Intro.kindLabel(r.kind),
                                    fontSize = 8.sp, color = T.inkFaint,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.fillMaxWidth().clickable {
                                    if (r.viaKey.isNotBlank()) onPerson(r.viaKey)
                                }) {
                                Text(r.via + (if (r.viaRole.isNotBlank()) " · ${r.viaRole.take(26)}" else ""),
                                    fontSize = T.caption, color = T.ink, maxLines = 1)
                                Text(r.why, fontSize = 10.sp, color = T.inkFaint, lineHeight = 14.sp)
                            }
                            // Only a real introduction needs asking for; the other two you just do.
                            if (r.kind == com.agentos.shell.tools.Intro.Kind.TWO_HOP) {
                                Spacer(Modifier.height(5.dp))
                                Text(if (asking) "writing…" else "Ask ${r.via.split(' ').first()} →",
                                    fontSize = 10.sp, color = T.accent,
                                    modifier = Modifier.clickable(enabled = !asking) {
                                        asking = true; draft = ""
                                        scope.launch {
                                            draft = withContext(Dispatchers.IO) {
                                                try {
                                                    com.agentos.shell.tools.AgentClient.complete(
                                                        "You write short messages in the owner's voice. Message only.",
                                                        com.agentos.shell.tools.Intro.askPrompt(ctx, r, filter), 400)
                                                } catch (e: Exception) { "" }
                                            }.ifBlank { "Couldn't write that one." }
                                            asking = false
                                        }
                                    })
                            }
                        }
                        if (draft.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(draft, fontSize = 10.sp, color = T.ink, lineHeight = 15.sp)
                        }
                    }
                }
            }
            if (sel == null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                    .align(Alignment.BottomCenter)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                        .background(T.bgElevated).padding(horizontal = 18.dp, vertical = 13.dp)) {
                        if (filter.isEmpty())
                            Text("who could introduce me to… a company, a name",
                                fontSize = T.caption, color = T.inkFaint)
                        androidx.compose.foundation.text.BasicTextField(
                            filter, { filter = it },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = T.ink, fontSize = T.caption),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (sel != null) {
                Box(Modifier.fillMaxWidth().padding(14.dp).align(Alignment.BottomCenter)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(T.bgElevated).padding(16.dp)) {
                        Text(sel.label, fontSize = T.small, color = T.ink,
                            fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(when (sel.layer) {
                                0 -> "everything reaches you through the next column"
                                1 -> "${sel.weight} people reach you here"
                                3 -> "${sel.weight} people you know here"
                                else -> "${sel.weight} messages · " +
                                    "${links.count { it.to == picked || it.from == picked }} links"
                            }, fontSize = 10.sp, color = T.inkFaint)
                        // The evidence behind each line — a weight you cannot interrogate is decoration.
                        links.filter { it.from == picked || it.to == picked }
                            .sortedByDescending { it.w }.take(5).forEach { l ->
                                Spacer(Modifier.height(6.dp))
                                Text(l.why, fontSize = 10.sp, color = T.inkSoft, lineHeight = 14.sp)
                            }
                        if (sel.key.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text("Open ${sel.label.split(' ').first()} →", fontSize = T.caption,
                                color = T.accent,
                                modifier = Modifier.clickable { onPerson(sel.key) })
                        }
                    }
                }
            }
        }
    }
}

/** Does this node answer the typed filter? Name, channel and company all at once. */
private fun matches(n: GNode, q: String): Boolean {
    val t = q.trim()
    if (t.isEmpty()) return true
    return n.label.contains(t, true)
}

/**
 * Pure geometry: a column from the layer, a row from the rank inside it.
 *
 * No simulation and no randomness, so the same network draws identically every time it is opened —
 * which is the only way anyone builds up a sense of where things are.
 */
private fun place(n: GNode, count: Int, w: Float, h: Float, scale: Float, pan: Offset): Offset {
    val x = w * (0.11f + 0.78f * (n.layer / 3f))
    val y = if (count <= 1) h * 0.5f else h * (0.10f + 0.80f * (n.rank / (count - 1f)))
    val c = Offset(w / 2f, h / 2f)
    return (Offset(x, y) - c) * scale + c + pan
}

/** Assemble the four columns from the snapshot. Off the main thread; never resolves the book. */
private fun build(ctx: android.content.Context): Pair<List<GNode>, List<GLink>>? = try {
    val g = RelationGraph.build(ctx, 200)
    // Only people with real traffic. A network drawn from everyone who ever sent a message is a
    // hairball, and the ones worth seeing are the ones actually used.
    // TWENTY-TWO, NOT FORTY-SIX.
    //
    // Forty-six nodes in one column on a phone is a 30-pixel pitch: they overlapped into a vertical
    // spine that read as a decoration rather than a network. The screenshot was the only way to see
    // that — the numbers all looked fine.
    val ranked = g.people.filter { it.totalMessages >= 3 }
        .sortedByDescending { it.totalMessages }.take(22)
    // Busiest toward the middle of the column, so the heaviest lines run to the centre and the shape
    // is legible instead of top-loaded.
    val people = ArrayList<com.agentos.shell.tools.Crm.Person>().also { out ->
        ranked.forEachIndexed { i, p -> if (i % 2 == 0) out.add(p) else out.add(0, p) }
    }
    if (people.isEmpty()) null else {
        val ns = ArrayList<GNode>()
        val ls = ArrayList<GLink>()
        fun addNode(layer: Int, label: String, key: String, weight: Int): Int {
            val rank = ns.count { it.layer == layer }
            ns.add(GNode(layer, rank, label, key, weight))
            return ns.size - 1
        }

        val you = addNode(0, "You", "", 0)
        val channels = people.flatMap { p -> p.identities.map { it.platform } }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(8).map { it.key }
        val chIdx = HashMap<String, Int>()
        channels.forEach { c ->
            val i = addNode(1, c, "", people.count { it.platforms.contains(c) })
            chIdx[c] = i
            ls.add(GLink(you, i, 6, "Your $c"))
        }
        val pIdx = HashMap<String, Int>()
        // Ordered so the busiest people sit near the middle of their column rather than the top.
        people.forEach { p -> pIdx[p.key] = addNode(2, p.name, p.key, p.totalMessages) }
        people.forEach { p ->
            p.identities.forEach { id ->
                val c = chIdx[id.platform] ?: return@forEach
                ls.add(GLink(c, pIdx[p.key]!!, id.messages,
                    "${p.name} on ${id.platform} — ${id.messages} messages"))
            }
        }
        people.filter { it.company.length >= 3 }.groupBy { it.company }.entries
            .sortedByDescending { it.value.size }.take(10).forEach { (co, members) ->
                val ci = addNode(3, co, "", members.size)
                members.forEach { m -> pIdx[m.key]?.let { ls.add(GLink(it, ci, 4, "${m.name} at $co")) } }
            }
        // The proven person-to-person edges, drawn inside the people column.
        g.edges.forEach { e ->
            val a = pIdx[e.a]; val b = pIdx[e.b]
            if (a != null && b != null) ls.add(GLink(a, b, e.weight * 3, e.why))
        }
        ns.toList() to ls.toList()
    }
} catch (e: Exception) { null }
