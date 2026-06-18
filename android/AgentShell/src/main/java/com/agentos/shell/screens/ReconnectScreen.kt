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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConversationStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Proactive networking: people you've gone quiet on, each with a ready-to-send reconnect message. */
@Composable
fun ReconnectScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val stale = remember { ConversationStore.staleContacts(ctx, 7).take(15) }

    Column(modifier) {
        ScreenHeader("Reconnect", onBack)
        Spacer(Modifier.height(6.dp))
        Text("People you haven't spoken with in over a week — with a message ready to send.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))

        if (stale.isEmpty()) {
            Text("Nobody's gone quiet — you're on top of your people. ✨", fontSize = T.body, color = T.inkSoft)
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(stale, key = { "${it.app}|${it.title}|${it.lastTime}" }) { s -> ReconnectCard(s) }
        }
    }
}

@Composable
private fun ReconnectCard(s: ConversationStore.Stale) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var msg by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val days = ((System.currentTimeMillis() - s.lastTime) / 86_400_000L).toInt()

    LaunchedEffect(s.title, s.lastTime) {
        if (msg.isEmpty()) {
            val mem = com.agentos.shell.tools.ReplyContext.forSender(ctx, s.app, s.title)
            val d = withContext(Dispatchers.IO) { AgentClient.reconnectMessage(s.title, s.lastText, days, mem) }
            if (!d.startsWith("[couldn't")) msg = d
        }
    }

    if (dismissed) return
    Column(Modifier.padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
            Text("quiet ${days}d · ${s.app}", fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(6.dp))
        Text(if (msg.isEmpty()) "drafting a message…" else msg,
            fontSize = T.small, color = if (msg.isEmpty()) T.inkFaint else T.inkSoft)
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copy", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { clipboard.setText(AnnotatedString(msg)); copied = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Text("Open ${s.app}", fontSize = T.small, color = T.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable {
                            clipboard.setText(AnnotatedString(msg)); copied = true
                            openByLabel(ctx, s.app)   // best-effort open by app label → package
                        }.padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Text("Skip", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { dismissed = true }.padding(vertical = 8.dp))
            }
            if (copied) { Spacer(Modifier.height(4.dp)); Text("Copied — paste it in ${s.app} ✓", fontSize = T.caption, color = T.accent) }
        }
        Hairline()
    }
}

/** Launch an app by its visible label (we store the app label, not the package, per conversation). */
private fun openByLabel(ctx: android.content.Context, label: String) {
    try {
        val pm = ctx.packageManager
        val pkg = pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().equals(label, true)
        }?.packageName ?: return
        pm.getLaunchIntentForPackage(pkg)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    } catch (e: Exception) {}
}
