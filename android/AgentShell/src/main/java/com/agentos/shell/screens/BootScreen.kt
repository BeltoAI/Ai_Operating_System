package com.agentos.shell.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/**
 * The startup moment — a calm, premium cursive welcome that breathes while the brain warms up. Replaces the
 * old static "waking up…". A soft accent glow pulses behind, a light sweeps across the script, the line
 * below gently breathes.
 */
/**
 * The brand mark, animated — the owner's own 11-second trace of the SlyOS S.
 *
 * Played through [android.graphics.drawable.AnimatedImageDrawable], which handles animated WebP
 * natively from API 28, so this needs no Coil, no Glide and no Lottie — nothing added to the build
 * for one asset.
 *
 * The source recording sat on a #090909 background and the app's is #12100C, a warm near-black, so
 * dropping the clip in as-is would have put a subtly wrong black square in the middle of the screen.
 * The background is keyed out to TRANSPARENT instead, which means the mark sits on whatever the theme
 * is — and stays correct in light mode too, where a baked-in black would have been unmissable.
 *
 * Trimmed to six seconds at 24fps and 144px: the full 60fps original was 1.4MB for a splash nobody
 * watches twice.
 */
@Composable
private fun BrandMark(size: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.size(size.dp),
        factory = { c ->
            android.widget.ImageView(c).apply {
                try {
                    val src = android.graphics.ImageDecoder.createSource(
                        c.resources, com.agentos.shell.R.raw.s_boot)
                    val d = android.graphics.ImageDecoder.decodeDrawable(src)
                    setImageDrawable(d)
                    (d as? android.graphics.drawable.AnimatedImageDrawable)?.apply {
                        // ONCE, ALL THE WAY THROUGH. A mark that loops has no ending, and one that
                        // restarts halfway through reads as a stutter rather than a signature. The
                        // boot delay below is set to the clip's length so it always completes —
                        // at 2300ms against a six-second clip, only the first third was ever seen.
                        repeatCount = 0
                        start()
                    }
                } catch (e: Exception) {
                    // A splash must never be the reason the app fails to open.
                }
            }
        })
}

@Composable
fun BootScreen(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "boot")

    // Entrance: fade + settle in.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }

    // A light sweeping across the cursive wordmark.
    val sweep by t.animateFloat(-400f, 900f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
    // The glow behind, slowly breathing.
    val glow by t.animateFloat(0.85f, 1.18f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    // The subtitle, breathing.
    val breathe by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Box(modifier.background(T.bg), contentAlignment = Alignment.Center) {
        // Soft radial glow.
        Box(
            Modifier.size(320.dp).scale(glow).alpha(0.9f * appear.value)
                .background(Brush.radialGradient(listOf(T.accent.copy(alpha = 0.16f), Color.Transparent)))
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(appear.value).scale(0.94f + 0.06f * appear.value)
        ) {
            // The animated mark, where the small text wordmark used to sit — the owner's own trace
            // of the S, which says the same thing and moves.
            BrandMark(size = 92)
            Spacer(Modifier.height(4.dp))
            // The cursive hero, with a light sweeping across it.
            Text(
                "welcome",
                fontFamily = T.scriptFamily,
                fontSize = 66.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(T.ink, T.accent, T.ink),
                        start = Offset(sweep - 220f, 0f),
                        end = Offset(sweep + 220f, 0f)
                    )
                )
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "setting up your brain…",
                fontSize = 15.sp,
                color = T.inkSoft,
                modifier = Modifier.alpha(0.5f + 0.5f * breathe)
            )
        }
    }
}
