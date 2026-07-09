package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.KeyValidator

/**
 * The building blocks of the redesigned Settings: a tap-to-open card and a live status dot. Every
 * setting becomes a calm, collapsed card that opens only when you want it — so the screen reads as a
 * clean stack of cards instead of one endless form.
 */
@Composable
fun Collapsible(
    title: String,
    subtitle: String = "",
    initiallyOpen: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = T.caption, color = T.inkFaint)
                }
            }
            if (trailing != null) { trailing(); Spacer(Modifier.width(10.dp)) }
            Text(if (open) "▾" else "▸", fontSize = T.body, color = T.inkSoft)
        }
        if (open) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Small colored dot + word describing a key's live validation state. */
@Composable
fun StatusDot(state: KeyValidator.State) {
    val col: Color; val label: String
    when (state) {
        KeyValidator.State.VALID -> { col = Color(0xFF1FA855); label = "Valid" }
        KeyValidator.State.INVALID -> { col = T.danger; label = "Invalid" }
        KeyValidator.State.CHECKING -> { col = T.accent; label = "Checking…" }
        KeyValidator.State.ERROR -> { col = T.inkFaint; label = "Can't reach" }
        KeyValidator.State.EMPTY -> { col = T.inkFaint; label = "Not set" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = T.caption, color = col, fontWeight = FontWeight.Medium)
    }
}
