package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.OutboxStore

/**
 * P5.1 / P5.5 — "Sent for you": a live, readable log of everything SlyOS did autonomously (replies, sends),
 * each with WHY it happened + the memory/persona it used, and a Recall for messaging. This is what makes
 * aggressive automation safe — it's all visible and reversible.
 */
@Composable
fun OutboxScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(OutboxStore.recent(ctx, 100)) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(modifier) {
        ScreenHeader("Sent for you", onBack)
        Spacer(Modifier.height(4.dp))
        Text("Everything the agent did on your behalf — what, to whom, and why. Recall copies a retraction you can paste.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            Text("Nothing yet. When the agent replies or sends for you, it shows up here.", fontSize = T.small, color = T.inkSoft)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { e ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
                    .border(1.dp, T.hairline, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val who = e.contact.ifBlank { e.channel }
                        Box(Modifier.size(36.dp).clip(CircleShape).background(T.accentSoft), contentAlignment = Alignment.Center) {
                            Text(who.trim().take(1).uppercase(), fontSize = T.body, color = T.accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(who, fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                            Text(e.channel + " · " + ago(e.time), fontSize = T.caption, color = T.inkFaint)
                        }
                        val chip = when (e.status) { "held" -> "held" to T.accent; "undone" -> "recalled" to T.inkFaint; else -> "sent" to Color(0xFF4E9A5B) }
                        Text(chip.first, fontSize = T.caption, color = chip.second)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(e.body, fontSize = T.small, color = T.inkSoft, maxLines = 4)
                    if (e.reason.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("↳ " + e.reason, fontSize = T.caption, color = T.inkFaint) }
                    if (e.status == "sent") {
                        Spacer(Modifier.height(10.dp))
                        Text("Recall", fontSize = T.small, color = T.danger, modifier = Modifier.clickable {
                            clip.setText(AnnotatedString("Ignore my last message — that was sent by mistake."))
                            OutboxStore.setStatus(ctx, e.id, "undone")
                            items = OutboxStore.recent(ctx, 100)
                        })
                    }
                }
            }
        }
    }
}

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago"
    }
}
