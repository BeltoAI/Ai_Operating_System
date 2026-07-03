package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything happening now — your real notifications, newest first, each replyable inline. */
@Composable
fun NowScreen(modifier: Modifier = Modifier, onReconnect: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = NotificationStore.notes
    var digest by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun catchUp() {
        if (loading) return
        loading = true; digest = ""
        scope.launch {
            val mem = MemoryStore.about(ctx)
            // Use the CURRENT notification tray — the freshest signal of what's actually pending.
            // (Once you read/reply in the real app, the notification clears and drops off here.)
            val snapshot = notes.toList()
            val awaiting = snapshot.filter { it.isConversational && it.text.isNotBlank() }
                .map { "${it.title.ifBlank { it.app }} (${it.app}): \"${it.text.take(120)}\"" }
            val otherNotifs = snapshot.filter { !it.isConversational && !it.isLikelyBot && it.text.isNotBlank() }
                .map { "${it.app}: ${it.text.take(120)}" }
            digest = withContext(Dispatchers.IO) { AgentClient.catchUp(otherNotifs, awaiting, mem) }
            loading = false
        }
    }

    // Auto-brief on entry: "what you missed" is the first thing you see, not a button you hunt for.
    LaunchedEffect(Unit) { if (notes.isNotEmpty()) catchUp() }

    val dateStr = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()) }

    Column(modifier) {
        ScreenHeader("Now", onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(dateStr, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
            Text("Reconnect", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onReconnect() })
        }
        Spacer(Modifier.height(14.dp))

        // ── The briefing card: what you missed + who to reply to ──
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
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
                    // Style the "Text back:" line in accent so who-needs-you pops.
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
        Text("WAITING ON YOU · ${notes.size}", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(notes, key = { it.key }) { note -> ReplyCard(note) }
        }
    }
}
