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
fun NowScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
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

    Column(modifier) {
        ScreenHeader("Now", onBack)
        Spacer(Modifier.height(12.dp))

        // "What did I miss?" — a quick digest + who to text back personally.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (loading) "thinking…" else "✨ What did I miss?",
                fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !loading) { catchUp() }
                    .padding(horizontal = 18.dp, vertical = 10.dp))
            if (digest.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("clear", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { digest = "" })
            }
        }
        if (digest.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp)) {
                Text(digest, fontSize = T.small, color = T.ink)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (notes.isEmpty()) {
            Text("Nothing here yet.", fontSize = T.body, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text(
                "Send yourself a message — or grant access:\n" +
                    "Settings → Notifications → Notification access → enable SlyOS.",
                fontSize = T.small, color = T.inkFaint
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(notes, key = { it.key }) { note -> ReplyCard(note) }
        }
    }
}
