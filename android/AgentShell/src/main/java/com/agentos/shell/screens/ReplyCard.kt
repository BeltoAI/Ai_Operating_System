package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recognizable color per source app. */
private fun appColor(pkg: String): Color = when {
    pkg.contains("whatsapp") -> Color(0xFF1FA855)
    pkg.startsWith("org.telegram") -> Color(0xFF2AABEE)
    pkg == "com.google.android.gm" -> Color(0xFFD93025)
    pkg.contains("instagram") -> Color(0xFFC13584)
    pkg.contains("signal") -> Color(0xFF3A76F0)
    pkg.contains("slack") -> Color(0xFF611F69)
    pkg.contains("twitter") -> Color(0xFF1DA1F2)
    pkg.contains("reddit") -> Color(0xFFFF4500)
    pkg.contains("discord") -> Color(0xFF5865F2)
    pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> Color(0xFF1FA855)
    pkg.contains("outlook") || pkg.contains("email") -> Color(0xFF0078D4)
    else -> Color(0xFFE8642C)
}

/**
 * One notification with the full "ask before action" reply flow:
 * draft → review/edit → Send (or Cancel), plus dismiss. Self-contained state, so it can be
 * dropped into both the Now and People lists.
 */
@Composable
fun ReplyCard(note: NotificationStore.Note) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var eventBusy by remember { mutableStateOf(false) }
    var eventStatus by remember { mutableStateOf("") }

    val appCol = appColor(note.pkg)
    Column(Modifier.padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Colored app badge so the source (WhatsApp / Telegram / Gmail …) is obvious.
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(appCol),
                contentAlignment = Alignment.Center
            ) {
                Text(note.app.firstOrNull()?.uppercase() ?: "•",
                    fontSize = T.body, color = Color.White, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(note.app.uppercase(), fontSize = T.caption, color = appCol, fontWeight = FontWeight.Medium)
                if (note.title.isNotBlank())
                    Text(note.title, fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                if (note.text.isNotBlank())
                    Text(note.text, fontSize = T.small, color = T.inkSoft)
            }
            Text(
                "✕", fontSize = T.body, color = T.inkFaint,
                modifier = Modifier.clickable { NotificationStore.dismiss(note.key) }.padding(start = 12.dp)
            )
        }

        val autoPending = NotificationStore.pendingAuto.contains(note.key)
        when {
            sent.isNotEmpty() -> {
                Spacer(Modifier.height(6.dp))
                Text(sent, fontSize = T.small, color = T.accent)
            }
            autoPending -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("auto-replying…", fontSize = T.small, color = T.inkSoft)
                    Spacer(Modifier.width(12.dp))
                    Text("cancel", fontSize = T.small, color = T.danger,
                        modifier = Modifier.clickable { NotificationStore.pendingAuto.remove(note.key) })
                }
            }
            note.canReply && !approving -> {
                Text(
                    if (busy) "drafting…" else "↩ agent reply",
                    fontSize = T.small, color = T.accent,
                    modifier = Modifier.padding(top = 6.dp).clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            val memory = MemoryStore.about(ctx)
                            val d = withContext(Dispatchers.IO) {
                                if (note.isEmail) {
                                    val doc = com.agentos.shell.tools.KnowledgeStore.retrieve(ctx, note.text)
                                    AgentClient.draftEmailReply(note.title, note.text, doc, memory)
                                } else {
                                    val img = note.picture?.let { com.agentos.shell.tools.ImageUtil.encodeBitmap(it) }
                                    val thread = com.agentos.shell.tools.ConversationStore
                                        .thread(ctx, note.app, note.title).map { it.role to it.text }
                                    AgentClient.draftReplyThread(note.title.ifBlank { note.app }, thread, memory, img)
                                }
                            }
                            draft = d; approving = true; busy = false
                        }
                    }
                )
            }
        }

        if (note.isEmail && note.isLikelyBot && sent.isEmpty()) {
            Text("looks automated — probably a bot, skip replying", fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.padding(top = 4.dp))
        }

        // "Add event" — works on any message that implies a time/plan, repliable or not.
        if (!approving && sent.isEmpty() && note.text.isNotBlank()) {
            Text(
                if (eventBusy) "checking…" else "📅 add event",
                fontSize = T.small, color = T.inkSoft,
                modifier = Modifier.padding(top = 6.dp).clickable(enabled = !eventBusy) {
                    eventBusy = true; eventStatus = ""
                    scope.launch {
                        val now = java.text.SimpleDateFormat(
                            "EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        val json = withContext(Dispatchers.IO) {
                            AgentClient.eventFromText(note.text, now)
                        }
                        eventStatus = if (json.isBlank()) "No event found in this message."
                            else withContext(Dispatchers.IO) {
                                ToolRouter.executeAction(ctx, "add_event", json)
                            }
                        eventBusy = false
                    }
                }
            )
            if (eventStatus.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(eventStatus, fontSize = T.caption, color = T.inkSoft)
            }
        }

        if (approving) {
            Spacer(Modifier.height(8.dp))
            Text("Review reply — edit if needed:", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(T.bgElevated)
                    .padding(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    "Send", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(T.accent)
                        .clickable {
                            val toSend = draft
                            scope.launch {
                                val ok = NotificationStore.sendReply(ctx, note, toSend)
                                if (ok) {
                                    NotificationStore.dismiss(note.key)
                                    sent = "Sent: \"$toSend\""
                                } else sent = "Send failed (see log)."
                                approving = false
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Cancel", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { approving = false }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
        Hairline()
    }
}
