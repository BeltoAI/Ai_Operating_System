package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.ConversationStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Proactive networking: re-engage quiet contacts AND reach connections you've never messaged. */
@Composable
fun ReconnectScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("quiet") }   // quiet | network

    Column(modifier) {
        ScreenHeader("Reconnect", onBack)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("quiet" to "Quiet contacts", "network" to "My network").forEach { (id, label) ->
                val sel = tab == id
                Text(label, fontSize = T.small, color = if (sel) T.bgElevated else T.inkSoft,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (sel) T.accent else T.hairline).clickable { tab = id }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (tab == "quiet") QuietTab(ctx) else NetworkTab(ctx)
    }
}

@Composable
private fun QuietTab(ctx: android.content.Context) {
    val stale = remember { ConversationStore.staleContacts(ctx, 7).take(15) }
    Text("People you haven't spoken with in over a week — message ready to send.",
        fontSize = T.small, color = T.inkFaint)
    Spacer(Modifier.height(10.dp))
    if (stale.isEmpty()) {
        Text("Nobody you've chatted with has gone quiet. ✨", fontSize = T.body, color = T.inkSoft)
        return
    }
    LazyColumn {
        items(stale, key = { "${it.app}|${it.title}|${it.lastTime}" }) { s ->
            val days = ((System.currentTimeMillis() - s.lastTime) / 86_400_000L).toInt()
            OutreachCard(ctx, s.title, "quiet ${days}d · ${s.app}", s.app) { mem ->
                AgentClient.reconnectMessage(s.title, s.lastText, days, mem)
            }
        }
    }
}

@Composable
private fun NetworkTab(ctx: android.content.Context) {
    var count by remember { mutableStateOf(ConnectionStore.count(ctx)) }
    var messaged by remember { mutableStateOf(ConnectionStore.messagedCount(ctx)) }
    var never by remember { mutableStateOf(ConnectionStore.neverReachedOut(ctx)) }
    var status by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            status = ConnectionStore.importLinkedIn(ctx, uri)
            count = ConnectionStore.count(ctx); messaged = ConnectionStore.messagedCount(ctx)
            never = ConnectionStore.neverReachedOut(ctx)
        }
    }

    Text("Import your LinkedIn data export (Settings ▸ Data privacy ▸ Get a copy of your data). " +
        "Import Connections, messages, and Profile — SlyOS detects each. It then finds who you've " +
        "never messaged and proposes an opener.",
        fontSize = T.small, color = T.inkFaint)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Import LinkedIn CSV", fontSize = T.small, color = T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                .clickable { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }
                .padding(horizontal = 16.dp, vertical = 9.dp))
        if (count > 0) {
            Spacer(Modifier.width(12.dp))
            Text("Clear", fontSize = T.small, color = T.danger,
                modifier = Modifier.clickable { ConnectionStore.clear(ctx); count = 0; messaged = 0; never = emptyList(); status = "" })
        }
    }
    if (status.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    if (count > 0) {
        Spacer(Modifier.height(4.dp))
        Text("$count connections · messaged $messaged · ${never.size} never reached", fontSize = T.caption, color = T.inkFaint)
    }
    Spacer(Modifier.height(10.dp))

    if (count == 0) {
        Text("No network imported yet.", fontSize = T.small, color = T.inkFaint)
        return
    }
    Text("NEVER REACHED OUT", fontSize = T.caption, color = T.inkFaint)
    Spacer(Modifier.height(6.dp))
    LazyColumn {
        items(never.take(25), key = { it.name + "|" + it.source }) { c ->
            val sub = listOfNotNull(c.role.ifBlank { null }, c.company.ifBlank { null }).joinToString(" · ")
            OutreachCard(ctx, c.name, (if (sub.isNotBlank()) "$sub · " else "") + c.source, c.source,
                openUrl = c.url, onReached = { ConnectionStore.markReachedOut(ctx, c.name) }) { mem ->
                AgentClient.introMessage(c.name, c.company, c.role, c.source, mem)
            }
        }
    }
}

/** A person + an auto-drafted message + Copy / Open app / done. [draft] returns the message text. */
@Composable
private fun OutreachCard(
    ctx: android.content.Context, name: String, subtitle: String, appLabel: String,
    openUrl: String = "", onReached: () -> Unit = {}, draft: suspend (String) -> String
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var msg by remember(name) { mutableStateOf("") }
    var copied by remember(name) { mutableStateOf(false) }
    var done by remember(name) { mutableStateOf(false) }

    LaunchedEffect(name) {
        if (msg.isEmpty()) {
            val mem = MemoryStore.about(ctx)
            val d = withContext(Dispatchers.IO) { draft(mem) }
            if (!d.startsWith("[couldn't")) msg = d
        }
    }
    if (done) return
    Column(Modifier.padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
            Text(subtitle, fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(6.dp))
        Text(if (msg.isEmpty()) "drafting an opener…" else msg,
            fontSize = T.small, color = if (msg.isEmpty()) T.inkFaint else T.inkSoft)
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copy", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { clipboard.setText(AnnotatedString(msg)); copied = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (openUrl.isNotBlank()) "Open profile" else "Open $appLabel", fontSize = T.small, color = T.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable {
                            clipboard.setText(AnnotatedString(msg)); copied = true
                            if (openUrl.isNotBlank()) openProfile(ctx, openUrl, appLabel) else openByLabel(ctx, appLabel)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Text("Done", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { onReached(); done = true }.padding(vertical = 8.dp))
            }
            if (copied) { Spacer(Modifier.height(4.dp)); Text("Copied — paste it in $appLabel ✓", fontSize = T.caption, color = T.accent) }
        }
        Hairline()
    }
}

/** Open a specific profile URL — in the platform's app when possible, so "Message" is one tap. */
private fun openProfile(ctx: android.content.Context, url: String, source: String) {
    val pkg = mapOf(
        "linkedin" to "com.linkedin.android", "x" to "com.twitter.android", "twitter" to "com.twitter.android",
        "instagram" to "com.instagram.android"
    )[source.lowercase()]
    val uri = android.net.Uri.parse(if (url.startsWith("http")) url else "https://$url")
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) {
            try { ctx.packageManager.getPackageInfo(pkg, 0); i.setPackage(pkg) } catch (e: Exception) {}
        }
        ctx.startActivity(i)
    } catch (e: Exception) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) { openByLabel(ctx, source) }
    }
}

/** Open an app by source/label → package. */
private fun openByLabel(ctx: android.content.Context, label: String) {
    val known = mapOf(
        "linkedin" to "com.linkedin.android", "x" to "com.twitter.android", "twitter" to "com.twitter.android",
        "instagram" to "com.instagram.android", "whatsapp" to "com.whatsapp", "telegram" to "org.telegram.messenger"
    )
    try {
        val pm = ctx.packageManager
        val pkg = known[label.lowercase()] ?: pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().equals(label, true)
        }?.packageName ?: return
        pm.getLaunchIntentForPackage(pkg)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    } catch (e: Exception) {}
}
