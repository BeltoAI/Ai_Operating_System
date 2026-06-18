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
