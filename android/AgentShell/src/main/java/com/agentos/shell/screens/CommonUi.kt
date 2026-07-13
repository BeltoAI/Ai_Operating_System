package com.agentos.shell.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.Screen
import com.agentos.shell.theme.T

/** Cursive "SlyOS" wordmark. Inspired-by handwritten energy — not a copy of any logo. */
@Composable
fun Wordmark(big: Boolean = false) = Text(
    "SlyOS",
    fontFamily = T.scriptFamily,
    fontSize = if (big) T.wordmarkBig else T.wordmark,
    color = T.ink,
    fontWeight = FontWeight.Medium
)

@Composable
fun OrangeDot(modifier: Modifier = Modifier) =
    Spacer(modifier.size(7.dp).clip(CircleShape).background(T.accent))

@Composable
fun Heading(text: String) =
    Text(text, fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium)

/** Title row with a back arrow that returns to Home. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "Back",
            tint = T.ink,
            modifier = Modifier.size(24.dp).clickable { onBack() }
        )
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium)
    }

@Composable
fun Hairline() =
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))

/** The device's real display corner radius in px (0 if unknown / square) — so the edge line hugs any phone. */
@Composable
private fun deviceCornerPx(): Int {
    val ctx = LocalContext.current
    return remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val d = ctx.display
                maxOf(
                    d?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
                    d?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
                )
            } else 0
        } catch (e: Exception) { 0 }
    }
}

/**
 * The pixel dog now trots all the way AROUND the phone's edge, along the shimmer line, while anything is
 * generating. Driven by the global Busy signal; soft haptic when work finishes. Feet stay on the line,
 * body toward the middle, oriented to the border's tangent so it runs the rim.
 */
@Composable
fun BusyDog() {
    val active = com.agentos.shell.tools.Busy.active
    val ctx = LocalContext.current
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        val busyNow = active > 0
        if (wasBusy && !busyNow) {
            try {
                val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    v?.vibrate(android.os.VibrationEffect.createOneShot(18L, 60))
                else @Suppress("DEPRECATION") v?.vibrate(18L)
            } catch (e: Exception) {}
        }
        wasBusy = busyNow
    }
    if (active <= 0) return

    val t = rememberInfiniteTransition(label = "dog")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "x")
    val legUp by t.animateFloat(0f, 1f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "leg")
    val cornerPx = deviceCornerPx()

    Canvas(Modifier.fillMaxSize()) {
        val p = 3.4f
        val ink = T.ink
        val cr = if (cornerPx > 0) cornerPx.toFloat() else 28f.dp.toPx(); val inset = 0.5f.dp.toPx()
        val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(cr, cr))) }
        val pm = PathMeasure().apply { setPath(path, false) }
        val len = pm.length
        if (len <= 0f) return@Canvas
        val d = (1f - (x % 1f)) * len                   // run the other way (counter-clockwise)
        val pos = pm.getPosition(d)
        val tan = pm.getTangent(d)
        val ang = Math.toDegrees(atan2(tan.y, tan.x).toDouble()).toFloat()
        rotate(ang, pos) {
            // feet on the line, body toward centre; sprite mirrored so the dog faces its travel direction.
            fun px(c: Float, r: Float) = drawRect(ink, Offset(pos.x + (3f - c) * p, pos.y + (3.5f - r) * p), Size(p, p))
            for (bx in 0..4) px(bx.toFloat(), 1.5f)
            for (bx in 0..4) px(bx.toFloat(), 2.5f)
            px(5f, 0.5f); px(5f, 1.5f); px(6f, 1.5f); px(5f, 2.5f)   // head + snout
            px(4.5f, 0.5f); px(-0.5f, 0.5f)                         // ear + tail
            val a = if (legUp > 0.5f) 0f else 0.6f
            val b = if (legUp > 0.5f) 0.6f else 0f
            px(0.5f, 3.5f + a); px(4f, 3.5f + b)                    // legs
        }
    }
}

/** One bottom-nav tab with a clear active state, plus an optional unread-count badge. */
@Composable
private fun NavTab(icon: ImageVector, label: String, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, tween(130), label = "navtab")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // No clip here — a rounded clip would crop the notification badge that sits above the icon.
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Box {
            Icon(icon, label, tint = if (active) T.accent else T.inkFaint, modifier = Modifier.size(26.dp))
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp)
                        .size(if (badge > 9) 16.dp else 14.dp).clip(CircleShape).background(T.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (badge > 9) "9+" else badge.toString(),
                        fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Persistent bottom navigation shared by every main panel — the Memory "brain" sits dead center,
 * always emphasized, with a clear indicator for the panel you're on.
 */
@Composable
fun SlyBottomNav(current: Screen, nowCount: Int = 0, onBrainHold: () -> Unit = {}, onNav: (Screen) -> Unit) =
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab(Icons.Filled.Home, "Home", current == Screen.Home) { onNav(Screen.Home) }
        NavTab(Icons.Filled.Bolt, "Now", current == Screen.Now, badge = nowCount) { onNav(Screen.Now) }

        // The brain — center, always emphasized.
        val memActive = current == Screen.Memory || current == Screen.MemorySettings
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Tap → Brain. Press-and-hold ~3s → conversational voice mode.
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onNav(Screen.Memory) },
                    onPress = { if (kotlinx.coroutines.withTimeoutOrNull(3000L) { tryAwaitRelease() } == null) onBrainHold() }
                )
            }
        ) {
            Box(
                Modifier.size(50.dp).clip(CircleShape)
                    .background(if (memActive) T.accent else T.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Memory, "Memory",
                    tint = if (memActive) Color.White else T.accent, modifier = Modifier.size(28.dp))
            }
        }

        NavTab(Icons.Filled.Science, "Research", current == Screen.Research) { onNav(Screen.Research) }
        NavTab(Icons.Filled.Storefront, "Powers", current == Screen.Store) { onNav(Screen.Store) }
    }

/**
 * EDGE SHIMMER — while anything is generating, a soft accent light glides around the phone's border.
 * App-wide (driven by the global Busy signal), non-interactive, and gone the instant work finishes.
 */
private val EDGE_PALETTE = listOf(
    Color(0xFFE8642C),  // accent orange
    Color(0xFFE39A3C),  // warm amber
    Color(0xFFC85A7C),  // rose
    Color(0xFF8A6DBE)   // soft violet
)

/**
 * A thin, iridescent hairline around the phone's edge: all four brand hues visible at once as a sweep
 * gradient, gently rotating so the colours flow — a shimmer, not a comet. Whisper-quiet, never in your face.
 */
@Composable
fun EdgeShimmer() {
    if (com.agentos.shell.tools.Busy.active <= 0) return
    val t = rememberInfiniteTransition(label = "edge")
    val ph by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ph")
    val cornerPx = deviceCornerPx()
    Canvas(Modifier.fillMaxSize()) {
        val cr = if (cornerPx > 0) cornerPx.toFloat() else 28f.dp.toPx(); val inset = 0.5f.dp.toPx()
        val path = Path().apply { addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(cr, cr))) }
        val m = EDGE_PALETTE.size
        val samples = 13
        val cols = ArrayList<Color>(samples)
        for (s in 0 until samples) {
            val tt = ((s.toFloat() / (samples - 1) + ph) % 1f) * m
            val i = tt.toInt() % m
            cols.add(lerp(EDGE_PALETTE[i], EDGE_PALETTE[(i + 1) % m], tt - tt.toInt()))
        }
        val brush = Brush.sweepGradient(cols, Offset(size.width / 2f, size.height / 2f))
        drawPath(path, brush, alpha = 0.26f, style = Stroke(3.5f.dp.toPx()))   // soft halo
        drawPath(path, brush, alpha = 0.9f, style = Stroke(1.2f.dp.toPx()))    // crisp hairline
    }
}

// One point on the Fermat-quintic Calabi-Yau cross-section (Hanson's parametrization), degree n=5.
// Returns the raw 3D surface point [x,y,z] before animation.
private fun cyPoint(k1: Int, k2: Int, u: Double, v: Double): FloatArray {
    val n = 5.0; val p = 2.0 / n
    // z1 = (cos(u+iv))^(2/n) · e^(i·2πk1/n)
    var re = cos(u) * cosh(v); var im = -sin(u) * sinh(v)
    var mag = Math.pow(re * re + im * im, p / 2.0); var ang = atan2(im, re) * p
    var z1re = mag * cos(ang); var z1im = mag * sin(ang)
    val f1 = 2 * PI * k1 / n
    val a1re = z1re * cos(f1) - z1im * sin(f1); val a1im = z1re * sin(f1) + z1im * cos(f1)
    z1re = a1re; z1im = a1im
    // z2 = (sin(u+iv))^(2/n) · e^(i·2πk2/n)
    re = sin(u) * cosh(v); im = cos(u) * sinh(v)
    mag = Math.pow(re * re + im * im, p / 2.0); ang = atan2(im, re) * p
    var z2re = mag * cos(ang); var z2im = mag * sin(ang)
    val f2 = 2 * PI * k2 / n
    val a2re = z2re * cos(f2) - z2im * sin(f2); val a2im = z2re * sin(f2) + z2im * cos(f2)
    z2re = a2re; z2im = a2im
    val alpha = PI / 4.0
    return floatArrayOf(z1re.toFloat(), z2re.toFloat(), (cos(alpha) * z1im + sin(alpha) * z2im).toFloat())
}

// Iridescent ramp for the manifold surface — violet → magenta → rose → orange → amber.
private val CY_RAMP = listOf(
    Color(0xFF6E5AA8), Color(0xFFB0468C), Color(0xFFD65A6E), Color(0xFFE8642C), Color(0xFFE0A24E)
)
private fun cyRamp(x: Float): Color {
    val f = x.coerceIn(0f, 0.999f) * (CY_RAMP.size - 1)
    val i = f.toInt()
    return lerp(CY_RAMP[i], CY_RAMP[i + 1], f - i)
}

/**
 * SLY ORBIT — an actual Calabi-Yau manifold (Fermat-quintic cross-section, 25 patches) rendered as a filled,
 * shaded, iridescent surface tumbling in 3D (painter's-sorted, back-to-front). Wherever a response is being
 * generated, the manifold quietly turns.
 */
@Composable
fun SlyOrbit(size: Int = 56) {
    val t = rememberInfiniteTransition(label = "cy")
    val twoPi = (2 * PI).toFloat()
    val ry by t.animateFloat(0f, twoPi, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ry")
    val rx by t.animateFloat(0f, twoPi, infiniteRepeatable(tween(11000, easing = LinearEasing)), label = "rx")
    val nu = 4; val nv = 3
    // Build geometry once: flat vertex arrays + quad index list + a per-quad hue.
    val geo = remember {
        val vpp = (nu + 1) * (nv + 1)
        val vx = FloatArray(25 * vpp); val vy = FloatArray(25 * vpp); val vz = FloatArray(25 * vpp)
        val quads = ArrayList<IntArray>(); val qhue = ArrayList<Float>()
        var vi = 0
        for (k1 in 0 until 5) for (k2 in 0 until 5) {
            val base = vi
            for (iu in 0..nu) for (iv in 0..nv) {
                val pt = cyPoint(k1, k2, iu.toDouble() / nu * (PI / 2.0), (iv.toDouble() / nv - 0.5) * 1.9)
                vx[vi] = pt[0]; vy[vi] = pt[1]; vz[vi] = pt[2]; vi++
            }
            for (iu in 0 until nu) for (iv in 0 until nv) {
                val a = base + iu * (nv + 1) + iv
                quads.add(intArrayOf(a, a + (nv + 1), a + (nv + 1) + 1, a + 1))
                qhue.add(((k1 + k2) % 5) / 4f)
            }
        }
        Triple(Triple(vx, vy, vz), quads.toTypedArray(), qhue.toFloatArray())
    }
    val (varr, quads, qhue) = geo
    val (vx, vy, vz) = varr
    Canvas(Modifier.size(size.dp)) {
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val scale = this.size.minDimension * 0.33f
        val cry = cos(ry); val sry = sin(ry); val crx = cos(rx); val srx = sin(rx)
        val n = vx.size
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
        for (i in 0 until n) {
            val x = vx[i]; val y = vy[i]; val z = vz[i]
            val x1 = x * cry + z * sry; val z1 = -x * sry + z * cry
            val y2 = y * crx - z1 * srx; val z2 = y * srx + z1 * crx
            sx[i] = cx + x1 * scale; sy[i] = cy - y2 * scale; sz[i] = z2
        }
        val order = quads.indices.sortedBy { qi -> val q = quads[qi]; sz[q[0]] + sz[q[1]] + sz[q[2]] + sz[q[3]] }
        val face = Path()
        val edge = Color(0xFF15120E)
        for (qi in order) {
            val q = quads[qi]
            val shade = (((sz[q[0]] + sz[q[1]] + sz[q[2]] + sz[q[3]]) / 4f + 1.4f) / 2.8f).coerceIn(0f, 1f)
            val col = lerp(Color(0xFF1A1714), cyRamp(qhue[qi]), 0.32f + 0.68f * shade)
            face.rewind()
            face.moveTo(sx[q[0]], sy[q[0]]); face.lineTo(sx[q[1]], sy[q[1]])
            face.lineTo(sx[q[2]], sy[q[2]]); face.lineTo(sx[q[3]], sy[q[3]]); face.close()
            drawPath(face, col)
            drawPath(face, edge.copy(alpha = 0.16f), style = Stroke(0.7f.dp.toPx()))
        }
    }
}
