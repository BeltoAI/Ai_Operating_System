package com.agentos.shell.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.EmailDraftStore
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OutreachScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateListOf<EmailDraftStore.Draft>().apply { addAll(EmailDraftStore.load(ctx)) } }
    fun refresh() { drafts.clear(); drafts.addAll(EmailDraftStore.load(ctx)) }

    fun generateFor(emails: List<String>) {
        if (emails.isEmpty()) { status = "No email addresses found."; return }
        working = true
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val capped = emails.distinct().take(20)
            capped.forEachIndexed { i, addr ->
                status = "Drafting ${i + 1}/${capped.size}…"
                val (subj, body) = withContext(Dispatchers.IO) { AgentClient.draftOutreach(addr, topic, "", mem) }
                EmailDraftStore.add(ctx, addr, subj, body)
                MemoryLog.add(ctx, "response", "Outreach: $addr", "$subj — $body", "Outreach")
            }
            refresh(); working = false; status = "Drafted ${capped.size}. Review and send each."
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "" }
                    catch (e: Exception) { "" }
                }
                val emails = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+").findAll(text).map { it.value }.toList()
                generateFor(emails)
            }
        }
    }

    fun send(d: EmailDraftStore.Draft) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${d.to}"))
                .putExtra(Intent.EXTRA_SUBJECT, d.subject).putExtra(Intent.EXTRA_TEXT, d.body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            com.agentos.shell.tools.MetricsStore.record(ctx, com.agentos.shell.tools.MetricsStore.secondsFor("outreach"))
            status = "Opening email to ${d.to} — review and send."
        } catch (e: Exception) { status = "No email app found." }
    }

    Column(modifier) {
        ScreenHeader("Outreach", onBack)
        Spacer(Modifier.height(6.dp))
        Text("Bring your own list (CSV). The agent personalizes a draft for each — you review and send every one.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))

        BasicTextField(
            value = topic, onValueChange = { topic = it }, singleLine = true,
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (topic.isEmpty()) Text("What is the outreach about?", color = T.inkFaint, fontSize = T.body); inner() }
        )
        Spacer(Modifier.height(10.dp))
        Text(if (working) "…" else "Import CSV & draft", fontSize = T.small, color = T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (topic.isBlank() || working) T.hairline else T.accent)
                .clickable(enabled = topic.isNotBlank() && !working) { csvPicker.launch("*/*") }
                .padding(horizontal = 18.dp, vertical = 10.dp))
        if (status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(status, fontSize = T.small, color = T.accent) }

        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(drafts, key = { it.id }) { d ->
                var subject by remember(d.id) { mutableStateOf(d.subject) }
                var body by remember(d.id) { mutableStateOf(d.body) }
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(d.to, fontSize = T.caption, color = T.inkFaint)
                    BasicTextField(subject, { subject = it; EmailDraftStore.update(ctx, d.id, it, body) },
                        textStyle = TextStyle(color = T.ink, fontSize = T.body), singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    BasicTextField(body, { body = it; EmailDraftStore.update(ctx, d.id, subject, it) },
                        textStyle = TextStyle(color = T.inkSoft, fontSize = T.small),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(T.bgElevated).padding(10.dp))
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text("Send", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { send(d) })
                        Spacer(Modifier.width(16.dp))
                        Text("Delete", fontSize = T.small, color = T.inkFaint,
                            modifier = Modifier.clickable { EmailDraftStore.remove(ctx, d.id); refresh() })
                    }
                }
                Hairline()
            }
        }
    }
}
