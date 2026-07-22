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
            // Small brand anchor above the hero word.
            Text("SlyOS", fontFamily = T.scriptFamily, fontSize = 26.sp, color = T.inkFaint, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
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
