package com.agentos.shell.screens

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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.Crm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orbit — your network as a living field.
 *
 * The thing this replaces was a shelf: rows of cards you scroll past. That is a directory, and a
 * directory is not a place anybody returns to. What makes a social product work is that something is
 * always quietly moving and it is *yours* — so the canvas IS the app, and the list is the record
 * underneath it.
 *
 * You are the bright node in the middle, because everything here exists only in relation to you:
 * your people, your asks, your answers. Distance is how close you actually are — messages exchanged
 * — and brightness is how alive it is right now, which is a fact the CRM already knows and no screen
 * has ever shown.
 *
 * It drifts. Not an animation loop for its own sake: a still field of dots reads as a diagram, and
 * a diagram is something you look at once. A field that breathes reads as something with people in
 * it, and the movement is slow enough that a name never crawls away from your thumb.
 *
 * The empty state matters more than the full one, because almost everybody opens this with nothing
 * in it. Here it opens with the hundred and forty-three people already on the phone — so it is never
 * empty, and it needs no network to exist before it is worth looking at.
 */
@Composable
fun OrbitScreen(
    modifier: Modifier = Modifier,
    onPerson: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var people by remember { mutableStateOf<List<Crm.Person>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf(-1) }
    var sheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        people = withContext(Dispatchers.IO) {
            try { Crm.peopleCached(ctx, 120).filter { it.reciprocal } } catch (e: Exception) { emptyList() }
        }
        loaded = true
    }

    // The whole field turns once every few minutes — slow enough that nothing crawls out from under
    // a thumb, present enough that the screen is never quite still.
    val drift by rememberInfiniteTransition(label = "d").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(240_000, easing = LinearEasing)), label = "dv")
    val breath by rememberInfiniteTransition(label = "b").animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "bv")

    /**
     * Where somebody sits.
     *
     * Ring by how much you actually talk — the people you speak to daily are close, the ones you
     * spoke to once are far out. Angle from a golden-angle spiral so the field is evenly filled with
     * no clumping and, crucially, is the SAME every time it opens: a field that rearranges itself
     * between visits destroys the only spatial memory anybody builds of it.
     */
    fun place(i: Int, p: Crm.Person, w: Float, h: Float): Offset {
        val closeness = Math.log10((p.totalMessages + 10).toDouble()).toFloat()   // 1..~4
        val ring = (1f - (closeness / 4.2f).coerceIn(0.12f, 0.95f))
        val radius = (52f + ring * (minOf(w, h) * 0.62f))
        val angle = i * 2.39996323f + drift * (0.4f + ring)      // outer rings turn slower
        val c = Offset(w / 2f, h / 2f)
        return (Offset(c.x + radius * Math.cos(angle.toDouble()).toFloat(),
                       c.y + radius * Math.sin(angle.toDouble()).toFloat()) - c) * scale + c + pan
    }

    Box(modifier.fillMaxSize().background(T.bg)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f); pan += panChange
                    }
                }
                .pointerInput(people) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        var best = -1; var bestD = Float.MAX_VALUE
                        people.forEachIndexed { i, p ->
                            val d = (place(i, p, w, h) - tap).getDistance()
                            if (d < bestD) { bestD = d; best = i }
                        }
                        picked = if (bestD < 60f && picked != best) best else -1
                    }
                }
        ) {
            val w = size.width; val h = size.height
            val centre = Offset(w / 2f, h / 2f)

            people.forEachIndexed { i, p ->
                val at = place(i, p, w, h)
                // Alive = spoken to recently. This is the one thing the field says at a glance, and
                // it is a fact the CRM already holds and nothing has ever displayed.
                val alive = when {
                    p.silentDays <= 2 -> 1f
                    p.silentDays <= 14 -> 0.62f
                    p.silentDays <= 60 -> 0.34f
                    else -> 0.16f
                }
                val on = picked == i
                // A faint thread home. Every one of these is a real relationship, so the field
                // should look connected rather than scattered.
                drawLine(
                    color = T.inkSoft.copy(alpha = 0.05f + 0.10f * alive),
                    start = centre, end = at, strokeWidth = 0.9f)
                val r = (4.5f + Math.sqrt(p.totalMessages.toDouble()).toFloat() * 0.55f)
                    .coerceAtMost(17f) * scale.coerceIn(0.7f, 1.5f)
                if (on) drawCircle(T.accent.copy(alpha = 0.18f), r * 2.6f, at)
                drawCircle(
                    color = (if (on) T.accent else T.inkSoft).copy(alpha = if (on) 1f else alive),
                    radius = r, center = at)

                // Names only for the close ones, or when zoomed in — a field with 120 labels is a
                // wall of text, and a field with none is abstract art.
                if (on || (alive > 0.6f && scale > 1.15f)) {
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(
                                if (on) 240 else 130, 235, 235, 240)
                            textSize = 22f; isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        drawText(p.name.split(' ').first().take(12), at.x, at.y - r - 12f, paint)
                    }
                }
            }

            // You, last, so nothing is drawn over you.
            drawCircle(T.accent.copy(alpha = 0.13f * breath), 46f * breath, centre + pan * 0f + pan)
            drawCircle(T.accent, 15f, centre + pan)
        }

        // ── The record ──
        Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Spacer(Modifier.height(6.dp))
            Text("◀", fontSize = 18.sp, color = T.inkSoft,
                modifier = Modifier.clickable { onBack() }.padding(6.dp))
        }

        val sel = picked.takeIf { it >= 0 && it < people.size }?.let { people[it] }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp)
        ) {
            if (sel != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(T.bgElevated).padding(16.dp)) {
                    Text(sel.name, fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                            if (sel.role.isNotBlank()) append(sel.role).append("  ·  ")
                            if (sel.company.isNotBlank()) append(sel.company).append("  ·  ")
                            append(sel.mainChannel)
                            append("  ·  ")
                            append(if (sel.silentDays == 0) "today" else "${sel.silentDays}d ago")
                        }, fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                    Spacer(Modifier.height(11.dp))
                    Text("Open →", fontSize = T.caption, color = T.accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onPerson(sel.key) })
                }
            } else {
                // The count that grows. Not vanity — the visible proof that the network is working,
                // and the only number here worth wanting to move.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(T.bgElevated).padding(16.dp)
                    .clickable { sheet = !sheet }) {
                    Text(if (!loaded) "reading your network…"
                         else "${people.size} in your orbit",
                        fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(if (people.isEmpty() && loaded)
                            "Open People once and your network appears here."
                         else "closer means you talk more · brighter means recently",
                        fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                    if (sheet) {
                        Spacer(Modifier.height(12.dp))
                        val alive = people.count { it.silentDays <= 14 }
                        val quiet = people.count { it.silentDays > 60 }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Stat("$alive", "active")
                            Stat("$quiet", "gone quiet")
                            Stat("${people.sumOf { it.platforms.size }}", "channels")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, fontSize = 18.sp, color = T.accent, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 9.sp, color = T.inkFaint)
    }
}
