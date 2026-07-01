package com.agentos.shell.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.WorkspaceStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class CwAction(val done: Boolean, val message: String, val tool: String, val name: String, val note: String, val content: String)

/** Parse the delimiter protocol (no JSON escaping, so large code files stay intact). */
private fun parseCw(raw: String): CwAction? {
    var s = raw.trim()
    if (s.startsWith("```")) s = s.substringAfter('\n', s)
    if (s.endsWith("```")) s = s.substringBeforeLast("```")
    s = s.trim()
    val toolM = Regex("(?im)^\\s*TOOL[:\\s]+([a-z_]+)").find(s)
    if (toolM == null) {
        return if (Regex("(?im)^\\s*DONE\\b").containsMatchIn(s)) {
            val msg = Regex("(?is)\\bDONE\\b[ \\t]*\\r?\\n?(.*)").find(s)?.groupValues?.get(1)?.trim().orEmpty()
            CwAction(true, msg.ifBlank { "Done." }, "", "", "", "")
        } else null
    }
    val tool = toolM.groupValues[1].lowercase()
    val name = Regex("(?im)^\\s*NAME:\\s*(.+)$").find(s)?.groupValues?.get(1)?.trim().orEmpty()
    val note = Regex("(?im)^\\s*NOTE:\\s*(.+)$").find(s)?.groupValues?.get(1)?.trim().orEmpty()
    val content = Regex("(?s)CONTENT>>>[ \\t]*\\r?\\n(.*?)\\r?\\n?<<<END").find(s)?.groupValues?.get(1) ?: ""
    return CwAction(false, "", tool, name, note, content)
}

private fun queryName(ctx: Context, uri: Uri): String {
    var n = "upload.txt"
    try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { n = it }
            }
        }
    } catch (e: Exception) {}
    return n.replace(Regex("[\\\\/]+"), "_")
}

private fun readUpload(ctx: Context, uri: Uri): Pair<String, String> {
    val name = queryName(ctx, uri)
    return try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return name to ""
        val text = if (name.endsWith(".pdf", true)) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val d = PDDocument.load(bytes); val t = PDFTextStripper().getText(d); d.close(); t
        } else String(bytes)
        name to text.take(200000)
    } catch (e: Exception) { name to "" }
}

/**
 * Local Cowork: a desktop-style agent workspace on the phone. Give it a task; it iterates with tools
 * (list/read/write/append REAL files) until done. Uses a delimiter protocol so big code files never
 * break, and runs on whichever model you've set (Claude/GPT/Gemini).
 */
@Composable
fun CoworkScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(WorkspaceStore.list(ctx)) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var lastUrl by remember { mutableStateOf<String?>(null) }
    val chat = remember { mutableStateListOf<Pair<String, String>>() }   // role: you|agent|step
    val turns = remember { mutableStateListOf<Pair<String, String>>() }  // model transcript

    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (n, t) = withContext(Dispatchers.IO) { readUpload(ctx, uri) }
            if (t.isNotBlank()) {
                WorkspaceStore.write(ctx, n, t); files = WorkspaceStore.list(ctx)
                chat.add("step" to "• attached $n (${t.length} chars) — Cowork can read it now")
                turns.add("user" to "I've added a file to the workspace: $n. You can read_file it.")
            } else chat.add("step" to "• couldn't read that file")
        }
    }

    fun turnsJson(): JSONArray {
        val arr = JSONArray()
        turns.takeLast(30).forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        return arr
    }

    fun execTool(a: CwAction): String = when (a.tool) {
        "list_files" -> WorkspaceStore.list(ctx).let { if (it.isEmpty()) "(no files yet)" else it.joinToString("\n") }
        "read_file" -> if (!WorkspaceStore.exists(ctx, a.name)) "ERROR: \"${a.name}\" doesn't exist" else WorkspaceStore.read(ctx, a.name).take(14000)
        "write_file" -> if (a.name.isBlank()) "ERROR: no name" else { WorkspaceStore.write(ctx, a.name, a.content); "OK: wrote ${a.name} (${a.content.length} chars)" }
        "append_file" -> if (a.name.isBlank()) "ERROR: no name" else { WorkspaceStore.append(ctx, a.name, a.content); "OK: appended ${a.content.length} chars to ${a.name}" }
        "create_gdoc" -> com.agentos.shell.tools.GoogleWorkspace.createDoc(ctx, a.name.ifBlank { "Untitled" }, a.content).let { if (it.ok) "OK: created Google Doc — ${it.url}" else "ERROR: ${it.error}" }
        "create_gslides" -> {
            val slides = a.content.split(Regex("(?m)^===\\s*$")).mapNotNull { blk ->
                val ls = blk.trim().lines(); if (blk.isBlank() || ls.isEmpty()) null else ls.first().trim() to ls.drop(1).joinToString("\n").trim()
            }
            com.agentos.shell.tools.GoogleWorkspace.createSlides(ctx, a.name.ifBlank { "Deck" }, slides).let { if (it.ok) "OK: created Google Slides — ${it.url}" else "ERROR: ${it.error}" }
        }
        "create_gsheet" -> {
            val rows = a.content.trim().lines().filter { it.isNotBlank() }.map { it.split(",") }
            com.agentos.shell.tools.GoogleWorkspace.createSheet(ctx, a.name.ifBlank { "Sheet" }, rows).let { if (it.ok) "OK: created Google Sheet — ${it.url}" else "ERROR: ${it.error}" }
        }
        "create_pdf" -> if (com.agentos.shell.tools.PdfBuilder.makePdf(ctx, a.name.ifBlank { "Document" }, a.content) != null) "OK: created PDF (Downloads/SlyOS)" else "ERROR: couldn't make PDF"
        else -> "ERROR: unknown tool \"${a.tool}\""
    }

    fun send() {
        val task = input.trim(); if (task.isBlank() || busy) return
        chat.add("you" to task); turns.add("user" to task); input = ""; busy = true
        scope.launch {
            var steps = 0; var fails = 0
            while (steps < 22) {
                steps++
                val raw = withContext(Dispatchers.IO) { AgentClient.coworkTurn(turnsJson(), MemoryStore.fullProfile(ctx)) }
                turns.add("assistant" to raw)
                val truncated = raw.contains("CONTENT>>>") && !raw.contains("<<<END")
                val act = parseCw(raw)
                if (act == null || truncated) {
                    if (fails >= 2) { chat.add("agent" to "I got stuck on that step. Try a smaller ask, or say “continue”. (If it keeps failing, route Heavy work to Claude in Settings.)"); break }
                    fails++
                    chat.add("step" to "• (that step got cut off — retrying smaller)")
                    turns.add("user" to "Your last reply was cut off or not in the required format. Keep each file chunk small (a few hundred lines): write_file a first chunk ending with <<<END, then append_file the rest. Reply with ONE correctly-formatted action now.")
                    continue
                }
                fails = 0
                if (act.done) {
                    chat.add("agent" to act.message)
                    Regex("https?://[^\\s]+").find(act.message)?.value?.let { lastUrl = it.trimEnd('.', ')', ',') }
                    break
                }
                chat.add("step" to "• " + act.note.ifBlank { act.tool + (if (act.name.isNotBlank()) " ${act.name}" else "") })
                val result = withContext(Dispatchers.IO) { execTool(act) }
                Regex("https?://[^\\s]+").find(result)?.value?.let { lastUrl = it.trimEnd('.', ')', ',') }
                turns.add("user" to "RESULT (${act.tool}):\n${result.take(8000)}")
                files = WorkspaceStore.list(ctx)
            }
            if (steps >= 22) chat.add("agent" to "Paused after many steps — send another message to keep going.")
            busy = false
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, busy) { val n = chat.size + (if (busy) 1 else 0); if (n > 0) listState.animateScrollToItem(n - 1) }

    Column(modifier) {
        ScreenHeader("Cowork", onBack)
        Spacer(Modifier.height(4.dp))
        Text("A local agent that builds real files — give it a task, it does it step by step.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        if (files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                files.forEach { f ->
                    Text("📄 $f", fontSize = T.caption, color = T.ink,
                        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
                            .clickable { viewing = f }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (chat.isEmpty()) item {
                Text("Try: “draft a cold email to investors and save it”, “outline a blog post about edge AI and write the intro”, “make a Python script that renames photos by date”. Attach a file with 📎 and it can read it.",
                    fontSize = T.small, color = T.inkFaint, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(chat) { (role, text) ->
                when (role) {
                    "you" -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        Text(text, fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(T.accent).padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                    "step" -> Text(text, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(vertical = 2.dp))
                    else -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
                        Text(text, fontSize = T.small, color = T.ink,
                            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(T.hairline).padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                }
            }
            if (busy) item { Text("⚙ working…", fontSize = T.small, color = T.accent, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        lastUrl?.let { url ->
            Spacer(Modifier.height(8.dp))
            Text("↗ Open in Google", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📎", fontSize = T.body, color = T.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
                    .clickable { attachPicker.launch(arrayOf("*/*")) }.padding(horizontal = 12.dp, vertical = 9.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(value = input, onValueChange = { input = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp),
                decorationBox = { inner -> if (input.isEmpty()) Text("Give Cowork a task…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (busy || input.isBlank()) T.hairline else T.accent)
                    .clickable(enabled = !busy && input.isNotBlank()) { send() }.padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }

    if (viewing != null) {
        val name = viewing!!
        Dialog(onDismissRequest = { viewing = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                Text(name, fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(8.dp))
                var edited by remember(name) { mutableStateOf(WorkspaceStore.read(ctx, name)) }
                var statusMsg by remember(name) { mutableStateOf("") }
                BasicTextField(value = edited, onValueChange = { edited = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                    modifier = Modifier.weight(1f, fill = false).heightIn(min = 120.dp, max = 380.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(10.dp).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(10.dp))
                if (statusMsg.isNotBlank()) { Text(statusMsg, fontSize = T.caption, color = T.accent); Spacer(Modifier.height(6.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { WorkspaceStore.write(ctx, name, edited); files = WorkspaceStore.list(ctx); statusMsg = "Saved ✓" }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("↓ Export", fontSize = T.small, color = T.ink,
                        modifier = Modifier.clickable {
                            WorkspaceStore.write(ctx, name, edited)
                            statusMsg = if (WorkspaceStore.exportToDownloads(ctx, name)) "Exported to Downloads/SlyOS ✓" else "Couldn't export."
                        }.padding(end = 14.dp))
                    Text("Delete", fontSize = T.small, color = T.danger,
                        modifier = Modifier.clickable { WorkspaceStore.delete(ctx, name); files = WorkspaceStore.list(ctx); viewing = null }.padding(end = 14.dp))
                    Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { viewing = null })
                }
            }
        }
    }
}
