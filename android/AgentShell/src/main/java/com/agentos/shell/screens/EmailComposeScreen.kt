package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.agentos.shell.tools.GmailClient
import com.agentos.shell.tools.GoogleAuth
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editable email draft: SlyOS writes it in your voice, you tweak To/Subject/Body by hand or with a
 * prompt ("make it shorter", "warmer", "add a line about pricing"), then Send via Gmail.
 */
@Composable
fun EmailComposeScreen(modifier: Modifier = Modifier, initialTo: String, topic: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(initialTo) }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    val attachPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = try {
                var name = "attachment"
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                val out = java.io.File(ctx.cacheDir, "attach_" + System.currentTimeMillis() + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out
            } catch (e: Exception) { null }
            if (f != null) attachments = attachments + f
        }
    }

    fun generate() {
        if (working) return
        working = true; status = ""
        scope.launch {
            val mem = MemoryStore.fullProfile(ctx)
            val (s, b) = withContext(Dispatchers.IO) { AgentClient.composeEmail(initialTo.ifBlank { "the recipient" }, topic, mem) }
            if (!AgentClient.looksLikeError(b)) { subject = s; body = b } else status = "Couldn't draft it — tap Regenerate."
            working = false
        }
    }
    fun revise() {
        if (working || editPrompt.isBlank() || body.isBlank()) return
        val instr = editPrompt; working = true; status = ""
        scope.launch {
            val mem = MemoryStore.fullProfile(ctx)
            val (s, b) = withContext(Dispatchers.IO) { AgentClient.reviseEmail(subject, body, instr, mem) }
            // Never overwrite a good draft with an error string.
            if (!AgentClient.looksLikeError(b)) { subject = s; body = b; editPrompt = "" } else status = "Edit failed — your draft is unchanged."
            working = false
        }
    }
    fun send() {
        if (!to.contains("@") || !to.contains(".")) { status = "Add a valid email address up top."; return }
        if (body.isBlank()) { status = "The email is empty."; return }
        if (!GoogleAuth.isConnected(ctx)) { status = "Connect Google (Gmail) in Brain → settings first."; return }
        working = true; status = "Sending…"
        scope.launch {
            val subj = subject.ifBlank { "(no subject)" }
            val (ok, msg) = withContext(Dispatchers.IO) {
                if (attachments.isNotEmpty()) GmailClient.sendWithAttachments(ctx, to.trim(), subj, body, attachments)
                else GmailClient.send(ctx, to.trim(), subj, body)
            }
            status = msg; working = false
            if (ok) kotlinx.coroutines.delay(900).let { onBack() }
        }
    }
    LaunchedEffect(topic) { if (topic.isNotBlank()) generate() }

    @Composable
    fun line(label: String, value: String, hint: String, onChange: (String) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, fontSize = T.small, color = T.inkFaint, modifier = Modifier.width(58.dp))
            BasicTextField(value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.body),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(T.bgElevated).padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner -> if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint); inner() })
        }
    }

    Column(modifier) {
        ScreenHeader("Email", onBack)
        Spacer(Modifier.height(8.dp))
        line("To", to, "name@email.com") { to = it }
        line("Subject", subject, if (working) "drafting…" else "subject") { subject = it }
        Spacer(Modifier.height(8.dp))
        BasicTextField(value = if (working && body.isEmpty()) "writing your email…" else body, onValueChange = { body = it },
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp))
                .background(T.bgElevated).padding(14.dp).verticalScroll(rememberScrollState()))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = editPrompt, onValueChange = { editPrompt = it }, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 9.dp),
                decorationBox = { inner -> if (editPrompt.isEmpty()) Text("ask for changes — shorter, warmer, add a line about…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.width(8.dp))
            Text(if (working) "…" else "Revise", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.ink)
                    .clickable(enabled = !working && editPrompt.isNotBlank()) { revise() }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (working) "…" else "Send", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !working && body.isNotBlank()) { send() }.padding(horizontal = 22.dp, vertical = 10.dp))
            Spacer(Modifier.width(10.dp))
            Text("Regenerate", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable(enabled = !working) { generate() }.padding(horizontal = 16.dp, vertical = 10.dp))
            Spacer(Modifier.width(10.dp))
            Text("📎 Attach", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable(enabled = !working) { attachPicker.launch("*/*") }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(attachments.joinToString(", ") { it.name.substringAfter("_").substringAfter("_") } + "  ·  tap Send to attach",
                fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.clickable { attachments = emptyList() })
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(status, fontSize = T.small, color = T.accent) }
    }
}
