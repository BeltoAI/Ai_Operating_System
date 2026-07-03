package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Everything happening now — an auto "what you missed" briefing, then people who need you as
 *  swipeable cards grouped per person: tap to open, swipe left to dismiss, ✦ to reply. */
@Composable
fun NowScreen(modifier: Modifier = Modifier, onReconnect: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = NotificationStore.notes
    var digest by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var briefHidden by remember { mutableStateOf(false) }
    var briefDragX by remember { mutableStateOf(0f) }

    fun catchUp() {
        if (loading) return
        loading = true; digest = ""
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val snapshot = notes.toList()
            val awaiting = snapshot.filter { it.isConversational && it.text.isNotBlank() }
                .map { "${it.title.ifBlank { it.app }} (${it.app}): \"${it.text.take(120)}\"" }
            val otherNotifs = snapshot.filter { !it.isConversational && !it.isLikelyBot && it.text.isNotBlank() }
                .map { "${it.app}: ${it.text.take(120)}" }
            digest = withContext(Dispatchers.IO) { AgentClient.catchUp(otherNotifs, awaiting, mem) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (notes.isNotEmpty()) catchUp() }
    val dateStr = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()) }

    Column(modifier) {
        ScreenHeader("Now", onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(dateStr, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
            Text("Reconnect", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onReconnect() })
        }
        Spacer(Modifier.height(14.dp))

        // ── Briefing card (swipe left to dismiss) ──
        if (!briefHidden) Column(Modifier.fillMaxWidth()
            .offset { IntOffset(briefDragX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (briefDragX < -120f) briefHidden = true; briefDragX = 0f },
                    onDragCancel = { briefDragX = 0f }
                ) { _, dx -> briefDragX = (briefDragX + dx).coerceAtMost(0f) }
            }
            .clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨  What you missed", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                Text(if (loading) "reading…" else "⟳", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable(enabled = !loading) { catchUp() }.padding(4.dp))
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading && digest.isBlank() -> Text("Reading your day…", fontSize = T.small, color = T.accent)
                digest.isBlank() -> Text(if (notes.isEmpty()) "You're all caught up — nothing waiting. ✨" else "Tap ⟳ for a summary of what's waiting.", fontSize = T.small, color = T.inkSoft)
                else -> {
                    val idx = digest.indexOf("Text back", ignoreCase = true)
                    if (idx > 0) {
                        Text(digest.substring(0, idx).trim(), fontSize = T.small, color = T.ink)
                        Spacer(Modifier.height(8.dp))
                        Text(digest.substring(idx).trim(), fontSize = T.small, color = T.accent)
                    } else Text(digest, fontSize = T.small, color = T.ink)
                }
            }
        }

        if (notes.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Nothing waiting right now.", fontSize = T.body, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text("Send yourself a message — or grant access:\nSettings → Notifications → Notification access → enable SlyOS.",
                fontSize = T.caption, color = T.inkFaint)
            return@Column
        }

        Spacer(Modifier.height(20.dp))
        val groups = notes.groupBy { it.title.ifBlank { it.app } }.map { it.key to it.value }
        Text("WAITING ON YOU · ${groups.size}", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(groups, key = { it.first }) { (contact, group) -> NoteGroupCard(ctx, contact, group) }
        }
    }
}

private fun appColor(pkg: String): Color = when {
    pkg.contains("whatsapp") -> Color(0xFF25D366)
    pkg.contains("telegram") -> Color(0xFF26A5E4)
    pkg.contains("instagram") -> Color(0xFFC13584)
    pkg.contains("gm") -> Color(0xFFEA4335)
    pkg.contains("messaging") -> Color(0xFF1A73E8)
    pkg.contains("twitter") || pkg.contains("x.android") -> Color(0xFF111111)
    pkg.contains("linkedin") -> Color(0xFF0A66C2)
    pkg.contains("slack") -> Color(0xFF4A154B)
    pkg.contains("discord") -> Color(0xFF5865F2)
    pkg.contains("securesms") || pkg.contains("signal") -> Color(0xFF3A76F0)
    else -> T.accent
}

@Composable
private fun NoteGroupCard(ctx: android.content.Context, contact: String, group: List<NotificationStore.Note>) {
    val latest = group.first()
    var expanded by remember(latest.key) { mutableStateOf(false) }
    var dragX by remember(latest.key) { mutableStateOf(0f) }
    val staged = NotificationStore.stagedDrafts[latest.key]

    Column(Modifier.fillMaxWidth()
        .offset { IntOffset(dragX.roundToInt(), 0) }
        .pointerInput(latest.key) {
            detectHorizontalDragGestures(
                onDragEnd = { if (dragX < -130f) group.forEach { NotificationStore.dismiss(it.key) }; dragX = 0f },
                onDragCancel = { dragX = 0f }
            ) { _, dx -> dragX = (dragX + dx).coerceAtMost(0f) }
        }
        .clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
    ) {
        Row(Modifier.fillMaxWidth().clickable { NotificationStore.open(ctx, latest) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(appColor(latest.pkg)), contentAlignment = Alignment.Center) {
                Text(contact.trim().firstOrNull()?.uppercase() ?: "•", color = Color.White, fontSize = T.body)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.take(30), fontSize = T.body, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (group.size > 1) Text("${group.size}", fontSize = T.caption, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(CircleShape).background(T.accent).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                if (latest.text.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(latest.text.take(100), fontSize = T.caption, color = T.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(2.dp)); Text(latest.app + (if (staged != null) "  ·  ✦ reply ready" else ""), fontSize = T.caption, color = if (staged != null) T.accent else T.inkFaint)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (latest.canReply || staged != null)
                Text(if (expanded) "Close" else if (staged != null) "✦ Reply ready" else "✦ Reply", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp))
            Spacer(Modifier.weight(1f))
            Text("Open ↗", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { NotificationStore.open(ctx, latest) }.padding(4.dp))
        }
        if (expanded) Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) { ReplyCard(latest) }
    }
    Spacer(Modifier.height(10.dp))
}
