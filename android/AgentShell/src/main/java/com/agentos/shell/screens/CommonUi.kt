package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var wasBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(active) {
        val busyNow = active > 0
        if (wasBusy && !busyNow) {
            try {
                val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    v?.vibrate(android.os.VibrationEffect.createOneShot(18, 60))
                else @Suppress("DEPRECATION") v?.vibrate(18)
            } catch (e: Exception) {}
        }
        wasBusy = busyNow
    }
    if (active <= 0) return

    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dog")
    val x by t.animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "x")
    val legUp by t.animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(140), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "leg")

    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val p = 4f                                   // pixel size
        val baseX = x * (size.width + 10 * p) - 10 * p
        val gy = size.height - p                     // ground line
        val ink = T.ink
        fun px(cx: Float, cy: Float) = drawRect(ink, androidx.compose.ui.geometry.Offset(baseX + cx * p, cy * p), androidx.compose.ui.geometry.Size(p, p))
        // body
        for (bx in 0..4) px(bx.toFloat(), 1.5f)
        for (bx in 0..4) px(bx.toFloat(), 2.5f)
        // head + snout
        px(5f, 0.5f); px(5f, 1.5f); px(6f, 1.5f); px(5f, 2.5f)
        // ear + tail
        px(4.5f, 0.5f); px(-0.5f, 0.5f)
        // legs (alternating)
        val a = if (legUp > 0.5f) 0f else 0.6f
        val b = if (legUp > 0.5f) 0.6f else 0f
        px(0.5f, 3.5f + a); px(4f, 3.5f + b)
        // faint dust puff behind
        drawCircle(ink.copy(alpha = 0.12f), p, androidx.compose.ui.geometry.Offset(baseX - p, gy))
    }
}

/** One bottom-nav tab with a clear active state. */
@Composable
private fun NavTab(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) =
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, label, tint = if (active) T.accent else T.inkFaint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = if (active) T.accent else T.inkFaint,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
    }

/**
 * Persistent bottom navigation shared by every main panel — the Memory "brain" sits dead center,
 * always emphasized, with a clear indicator for the panel you're on.
 */
@Composable
fun SlyBottomNav(current: Screen, onNav: (Screen) -> Unit) =
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab(Icons.Filled.Home, "Home", current == Screen.Home) { onNav(Screen.Home) }
        NavTab(Icons.Filled.Bolt, "Now", current == Screen.Now) { onNav(Screen.Now) }

        // The brain — center, always emphasized.
        val memActive = current == Screen.Memory || current == Screen.MemorySettings
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onNav(Screen.Memory) }
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (memActive) T.accent else T.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Memory, "Memory",
                    tint = if (memActive) Color.White else T.accent, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text("Brain", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Medium)
        }

        NavTab(Icons.Filled.Science, "Research", current == Screen.Research) { onNav(Screen.Research) }
        NavTab(Icons.Filled.Apps, "Apps", current == Screen.Apps) { onNav(Screen.Apps) }
    }
