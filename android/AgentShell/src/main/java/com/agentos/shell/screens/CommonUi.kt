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

/**
 * A tiny pixel dog that scampers across the top while anything is generating. Non-blocking
 * (no pointer input), driven by the global Busy signal, with a soft haptic when work finishes.
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
    val x by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x"
    )
    val legUp by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "leg"
    )

    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val p = 4f                                   // pixel size
        val baseX = x * (size.width + 10f * p) - 10f * p
        val gy = size.height - p
        val ink = T.ink
        fun px(cx: Float, cy: Float) = drawRect(ink, Offset(baseX + cx * p, cy * p), Size(p, p))
        for (bx in 0..4) px(bx.toFloat(), 1.5f)
        for (bx in 0..4) px(bx.toFloat(), 2.5f)
        px(5f, 0.5f); px(5f, 1.5f); px(6f, 1.5f); px(5f, 2.5f)   // head + snout
        px(4.5f, 0.5f); px(-0.5f, 0.5f)                         // ear + tail
        val a = if (legUp > 0.5f) 0f else 0.6f
        val b = if (legUp > 0.5f) 0.6f else 0f
        px(0.5f, 3.5f + a); px(4f, 3.5f + b)                    // legs
        drawCircle(ink.copy(alpha = 0.12f), p, Offset(baseX - p, gy))   // dust
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

@Composable
fun EdgeShimmer() {
    if (com.agentos.shell.tools.Busy.active <= 0) return
    val t = rememberInfiniteTransition(label = "edge")
    // Slow, continuous glide through the palette; a gentle brightness shimmer over the top.
    val cyc by t.animateFloat(0f, EDGE_PALETTE.size.toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "cyc")
    val shim by t.animateFloat(0.68f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "shim")
    val n = EDGE_PALETTE.size
    val idx = cyc.toInt() % n
    val col = lerp(EDGE_PALETTE[idx], EDGE_PALETTE[(idx + 1) % n], cyc - cyc.toInt())
    Canvas(Modifier.fillMaxSize()) {
        val inset = 2.5f.dp.toPx()
        val cr = 46.dp.toPx()
        val path = Path().apply {
            addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(cr, cr)))
        }
        // A single continuous line: a soft outer glow, a mid halo, and a crisp core — all one shifting colour.
        drawPath(path, col.copy(alpha = 0.10f * shim), style = Stroke(9f.dp.toPx()))
        drawPath(path, col.copy(alpha = 0.34f * shim), style = Stroke(4f.dp.toPx()))
        drawPath(path, col.copy(alpha = 0.85f * shim), style = Stroke(1.8f.dp.toPx()))
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

/**
 * SLY ORBIT — an actual Calabi-Yau manifold (the quintic cross-section, 25 patches) tumbling in 3D, meshed
 * and depth-shaded in the accent. Wherever a response is being generated, this quietly turns.
 */
@Composable
fun SlyOrbit(size: Int = 48) {
    val t = rememberInfiniteTransition(label = "cy")
    val twoPi = (2 * PI).toFloat()
    val ry by t.animateFloat(0f, twoPi, infiniteRepeatable(tween(6400, easing = LinearEasing)), label = "ry")
    val rx by t.animateFloat(0f, twoPi, infiniteRepeatable(tween(9700, easing = LinearEasing)), label = "rx")
    val nu = 5; val nv = 4
    val patches = remember {
        val out = ArrayList<Array<FloatArray>>()
        for (k1 in 0 until 5) for (k2 in 0 until 5) {
            out.add(Array((nu + 1) * (nv + 1)) { idx ->
                val iu = idx / (nv + 1); val iv = idx % (nv + 1)
                cyPoint(k1, k2, iu.toDouble() / nu * (PI / 2.0), (iv.toDouble() / nv - 0.5) * 1.8)
            })
        }
        out
    }
    Canvas(Modifier.size(size.dp)) {
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val scale = this.size.minDimension * 0.34f
        val w = 1f.dp.toPx()
        val cry = cos(ry); val sry = sin(ry); val crx = cos(rx); val srx = sin(rx)
        for (g in patches) {
            val pts = Array(g.size) { Offset.Zero }
            val dep = FloatArray(g.size)
            for (i in g.indices) {
                val x = g[i][0]; val y = g[i][1]; val z = g[i][2]
                val x1 = x * cry + z * sry; val z1 = -x * sry + z * cry           // rotate about Y
                val y2 = y * crx - z1 * srx; val z2 = y * srx + z1 * crx          // rotate about X
                pts[i] = Offset(cx + x1 * scale, cy - y2 * scale); dep[i] = z2
            }
            for (iu in 0..nu) for (iv in 0..nv) {
                val id = iu * (nv + 1) + iv
                val d = ((dep[id] + 1.4f) / 2.8f).coerceIn(0f, 1f)
                val col = T.accent.copy(alpha = (0.10f + 0.75f * d))
                if (iu < nu) drawLine(col, pts[id], pts[(iu + 1) * (nv + 1) + iv], strokeWidth = w)
                if (iv < nv) drawLine(col, pts[id], pts[iu * (nv + 1) + iv + 1], strokeWidth = w)
            }
        }
    }
}
