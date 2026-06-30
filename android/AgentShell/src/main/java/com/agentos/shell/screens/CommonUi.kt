package com.agentos.shell.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
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
private fun NavTab(icon: ImageVector, label: String, active: Boolean, badge: Int = 0, onClick: () -> Unit) =
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // No clip here — a rounded clip would crop the notification badge that sits above the icon.
        modifier = Modifier.clickable { onClick() }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Box {
            Icon(icon, label, tint = if (active) T.accent else T.inkFaint, modifier = Modifier.size(24.dp))
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
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = if (active) T.accent else T.inkFaint,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
    }

/**
 * Persistent bottom navigation shared by every main panel — the Memory "brain" sits dead center,
 * always emphasized, with a clear indicator for the panel you're on.
 */
@Composable
fun SlyBottomNav(current: Screen, nowCount: Int = 0, onNav: (Screen) -> Unit) =
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
