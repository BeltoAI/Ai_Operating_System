package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.ConversationStore
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
        Text("Nobody you've chatted with has gone quiet.", fontSize = T.body, color = T.inkSoft)
        return
    }
    LazyColumn {
        items(stale, key = { "${it.app}|${it.title}|${it.lastTime}" }) { s ->
            val days = ((System.currentTimeMillis() - s.lastTime) / 86_400_000L).toInt()
            OutreachCard(ctx, s.title, "quiet ${days}d · ${s.app}", s.app) {
                val mem = com.agentos.shell.tools.ReplyContext.forSender(ctx, s.app, s.title)
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
    // ── Autonomous reconnect: message N of your never-reached network via tap-send ──
    run {
        val NO = com.agentos.shell.tools.NetworkOutreach
        var tick by remember { mutableStateOf(0) }
        tick.let { }   // subscribe so onUpdate() recomposes this card
        var n by remember { mutableStateOf(minOf(50, never.size).coerceAtLeast(1)) }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(T.bgElevated).border(1.dp, T.hairline, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Reconnect for me — autonomously", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
            Text("SlyOS opens LinkedIn and messages people you've never reached, one by one, human-paced. Needs SlyOS accessibility ON — it drives the screen while it runs.",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            if (!NO.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", fontSize = T.body, color = T.ink, modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(T.hairline).clickable { n = (n - 5).coerceAtLeast(1) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    Text("$n", fontSize = T.body, color = T.ink, modifier = Modifier.padding(horizontal = 14.dp))
                    Text("+", fontSize = T.body, color = T.ink, modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(T.hairline).clickable { n = (n + 5).coerceAtMost(minOf(50, never.size).coerceAtLeast(1)) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    Text("people today", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Start — message $n for me", fontSize = T.small, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { NO.start(ctx, "reconnect warmly and catch up", n) { tick++ } }
                        .padding(horizontal = 18.dp, vertical = 10.dp))
            } else {
                Text("Stop", fontSize = T.small, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.danger)
                        .clickable { NO.stop(); tick++ }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            if (NO.lastMsg.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(NO.lastMsg, fontSize = T.caption, color = if (NO.running) T.accent else T.inkSoft) }
            if (NO.sent + NO.failed > 0) Text("${NO.sent} sent · ${NO.failed} skipped of ${NO.total}", fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(8.dp))
    }

    Text("NEVER REACHED OUT", fontSize = T.caption, color = T.inkFaint)
    Spacer(Modifier.height(6.dp))
    LazyColumn {
        items(never.take(25), key = { it.name + "|" + it.source }) { c ->
            val sub = listOfNotNull(c.role.ifBlank { null }, c.company.ifBlank { null }).joinToString(" · ")
            OutreachCard(ctx, c.name, (if (sub.isNotBlank()) "$sub · " else "") + c.source, c.source,
                openUrl = c.url, onReached = { ConnectionStore.markReachedOut(ctx, c.name) }) {
                val mem = com.agentos.shell.tools.ReplyContext.forSender(ctx, c.source, c.name)
                AgentClient.introMessage(c.name, c.company, c.role, c.source, mem)
            }
        }
    }
}

/** A person + an auto-drafted message + Copy / Open app / done. [draft] returns the message text. */
@Composable
private fun OutreachCard(
    ctx: android.content.Context, name: String, subtitle: String, appLabel: String,
    openUrl: String = "", onReached: () -> Unit = {}, draft: suspend () -> String
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var msg by remember(name) { mutableStateOf("") }
    var copied by remember(name) { mutableStateOf(false) }
    var done by remember(name) { mutableStateOf(false) }
    var sendMsg by remember(name) { mutableStateOf("") }

    LaunchedEffect(name) {
        if (msg.isEmpty()) {
            val d = withContext(Dispatchers.IO) { draft() }
            if (!d.startsWith("[couldn't")) msg = d
        }
    }
    if (done) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(T.bgElevated).border(1.dp, T.hairline, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(T.accentSoft), contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase(), fontSize = T.body, color = T.accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = T.caption, color = T.inkFaint)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (msg.isEmpty()) "drafting an opener…" else msg,
            fontSize = T.small, color = if (msg.isEmpty()) T.inkFaint else T.inkSoft)
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copy", fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { clipboard.setText(AnnotatedString(msg)); copied = true }
                        .padding(horizontal = 18.dp, vertical = 9.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (openUrl.isNotBlank()) "Open profile" else "Open $appLabel", fontSize = T.small, color = T.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable {
                            clipboard.setText(AnnotatedString(msg)); copied = true
                            if (openUrl.isNotBlank()) openProfile(ctx, openUrl, appLabel) else openByLabel(ctx, appLabel)
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
                if (openUrl.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(if (sendMsg == "…") "Sending…" else "Send for me", fontSize = T.small, color = T.ink,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable(enabled = sendMsg != "…") {
                                sendMsg = "…"
                                scope.launch {
                                    val (ok, detail) = com.agentos.shell.tools.TapSend.sendViaProfile(ctx, openUrl, msg)
                                    sendMsg = detail
                                    if (ok) onReached()
                                }
                            }.padding(horizontal = 16.dp, vertical = 9.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Done", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { onReached(); done = true }.padding(vertical = 9.dp, horizontal = 6.dp))
            }
            if (copied) { Spacer(Modifier.height(6.dp)); Text("Copied — paste it in $appLabel", fontSize = T.caption, color = T.accent) }
            if (sendMsg.isNotBlank() && sendMsg != "…") { Spacer(Modifier.height(6.dp)); Text(sendMsg, fontSize = T.caption, color = if (sendMsg.contains("✓")) T.accent else T.danger) }
        }
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
