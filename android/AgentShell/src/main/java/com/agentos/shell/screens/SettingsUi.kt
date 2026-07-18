package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.KeyValidator

/**
 * The building blocks of the redesigned Settings: a tap-to-open card, a live status dot, a natural-language
 * filter bar, and a labeled field. Every setting is a calm, collapsed card that opens only when you want it —
 * so the screen reads as a clean stack of cards, and the search bar narrows the stack to just what you need.
 */

/** Shared, observable search text — the bar sets it, every Collapsible reads it and hides if it doesn't match. */
object SettingsFilter {
    var query by mutableStateOf("")
    fun matches(vararg haystacks: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isBlank()) return true
        val hay = haystacks.joinToString(" ").lowercase()
        return q.split(Regex("\\s+")).filter { it.isNotBlank() }.any { hay.contains(it) }
    }
}

/** A function-matched professional icon for each Settings card, so it says what the card does at a glance. */
fun iconFor(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        t.contains("api key") || t.contains("& model") -> Icons.Outlined.Key
        t.contains("backup") -> Icons.Outlined.Backup
        t.contains("about you") -> Icons.Outlined.Person
        t.contains("character") -> Icons.Outlined.Face
        t.contains("bank") || t.contains("vault") -> Icons.Outlined.AccountBalance
        t.contains("account") -> Icons.Outlined.Cloud
        t.contains("efficiency") -> Icons.Outlined.Schedule
        t.contains("on-device") -> Icons.Outlined.PhoneAndroid
        t.contains("chess") -> Icons.Outlined.Extension
        t.contains("teach") || t.contains("reflex") -> Icons.Outlined.TouchApp
        t.contains("floating") || t.contains("nav") -> Icons.Outlined.Explore
        t.contains("diagnostic") || t.contains("health") -> Icons.Outlined.Favorite
        t.contains("detail") -> Icons.Outlined.Badge
        t.contains("appearance") -> Icons.Outlined.Palette
        t.contains("invest") -> Icons.Outlined.TrendingUp
        t.contains("booking") -> Icons.Outlined.Event
        t.contains("talk") -> Icons.Outlined.Chat
        t.contains("voice") -> Icons.Outlined.Mic
        t.contains("persona") -> Icons.Outlined.RecordVoiceOver
        t.contains("upload") -> Icons.Outlined.CloudUpload
        t.contains("import") -> Icons.Outlined.Download
        t.contains("model") || t.contains("spending") -> Icons.Outlined.Psychology
        t.contains("connection") -> Icons.Outlined.Link
        t.contains("per-app") || t.contains("response") -> Icons.Outlined.Apps
        t.contains("document") -> Icons.Outlined.Description
        t.contains("lock") -> Icons.Outlined.Lock
        else -> Icons.Outlined.Settings
    }
}

/** Search bar for Settings — type what you need in plain words ("add my api key", "change my voice"). */
@Composable
fun SettingsSearchBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
            .border(1.dp, T.hairline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("⌕", fontSize = T.body, color = T.inkSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (SettingsFilter.query.isEmpty())
                Text("Search settings", fontSize = T.small, color = T.inkFaint)
            BasicTextField(
                value = SettingsFilter.query, onValueChange = { SettingsFilter.query = it }, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small)
            )
        }
        if (SettingsFilter.query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text("✕", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { SettingsFilter.query = "" })
        }
    }
}

@Composable
fun Collapsible(
    title: String,
    subtitle: String = "",
    keywords: String = "",
    initiallyOpen: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Filter: search matches the visible card text (title + grey subtitle); hide non-matches, auto-open matches.
    if (!SettingsFilter.matches(title, subtitle)) return
    val searching = SettingsFilter.query.isNotBlank()
    var open by remember { mutableStateOf(initiallyOpen) }
    val show = open || searching

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
            .border(1.dp, if (show) T.accent.copy(alpha = 0.35f) else T.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
            // Function-matched professional icon so each card is recognizable at a glance.
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(T.accentSoft), contentAlignment = Alignment.Center) {
                Icon(iconFor(title), contentDescription = null, tint = T.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = T.caption, color = T.inkFaint)
                }
            }
            if (trailing != null) { trailing(); Spacer(Modifier.width(10.dp)) }
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (show) T.accent else T.hairline),
                contentAlignment = Alignment.Center
            ) { Text(if (show) "▾" else "▸", fontSize = T.caption, color = if (show) Color.White else T.inkSoft) }
        }
        if (show) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** A clean labeled input row — used for personal-info fields so they read as a tidy form. */
@Composable
fun LabeledField(label: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp)) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint)
            BasicTextField(value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small), modifier = Modifier.fillMaxWidth())
        }
    }
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
