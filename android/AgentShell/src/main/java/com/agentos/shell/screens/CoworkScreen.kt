package com.agentos.shell.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoworkScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(WorkspaceStore.list(ctx)) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var lastUrl by remember { mutableStateOf<String?>(null) }
    var lastApk by remember { mutableStateOf<String?>(null) }
    var convoId by remember { mutableStateOf(0L) }
    var convos by remember { mutableStateOf(com.agentos.shell.tools.CoworkChatStore.list(ctx)) }
    var showChats by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var chatSearch by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("list") }   // "list" (chats) | "chat" (a conversation)
    var renaming by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    val fmt = remember { java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()) }
    val chat = remember { mutableStateListOf<Pair<String, String>>() }   // role: you|agent|step
    val turns = remember { mutableStateListOf<Pair<String, String>>() }  // model transcript

    fun persist() {
        if (convoId == 0L) return
        com.agentos.shell.tools.CoworkChatStore.saveChat(ctx, convoId, chat.toList())
        com.agentos.shell.tools.CoworkChatStore.saveTurns(ctx, convoId, turns.toList())
        val title = chat.firstOrNull { it.first == "you" }?.second ?: "New chat"
        com.agentos.shell.tools.CoworkChatStore.touch(ctx, convoId, title)
        convos = com.agentos.shell.tools.CoworkChatStore.list(ctx)
    }
    fun loadConvo(id: Long) {
        convoId = id; lastUrl = null; lastApk = null; chatSearch = ""
        chat.clear(); chat.addAll(com.agentos.shell.tools.CoworkChatStore.loadChat(ctx, id))
        turns.clear(); turns.addAll(com.agentos.shell.tools.CoworkChatStore.loadTurns(ctx, id))
        mode = "chat"
    }
    fun newConvo() {
        convoId = com.agentos.shell.tools.CoworkChatStore.create(ctx)
        chat.clear(); turns.clear(); lastUrl = null; lastApk = null
        convos = com.agentos.shell.tools.CoworkChatStore.list(ctx); mode = "chat"
    }
    LaunchedEffect(Unit) { convos = com.agentos.shell.tools.CoworkChatStore.list(ctx) }

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
            com.agentos.shell.tools.GoogleWorkspace.createSlides(ctx, a.name.ifBlank { "Deck" }, slides).let { if (it.ok) "OK: created Google Slides — ${it.url}" + (if (it.error.isNotBlank()) " (WARNING: ${it.error})" else "") else "ERROR: ${it.error}" }
        }
        "create_gsheet" -> {
            val rows = a.content.trim().lines().filter { it.isNotBlank() }.map { it.split(",") }
            com.agentos.shell.tools.GoogleWorkspace.createSheet(ctx, a.name.ifBlank { "Sheet" }, rows).let { if (it.ok) "OK: created Google Sheet — ${it.url}" else "ERROR: ${it.error}" }
        }
        "create_pdf" -> if (com.agentos.shell.tools.PdfBuilder.makePdf(ctx, a.name.ifBlank { "Document" }, a.content) != null) "OK: created PDF (Downloads/SlyOS)" else "ERROR: couldn't make PDF"
        "run_command" -> com.agentos.shell.tools.TermuxBridge.run(ctx, a.content.ifBlank { a.name })
        else -> "ERROR: unknown tool \"${a.tool}\""
    }

    fun send() {
        val task = input.trim(); if (task.isBlank() || busy) return
        chat.add("you" to task); turns.add("user" to task); input = ""; busy = true
        persist()
        com.agentos.shell.tools.MessageStore.insertOne(ctx, "Cowork", "Cowork", "me", "me", "Cowork task: $task")
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
                    Regex("[\\w./~-]+\\.apk").find(act.message)?.value?.let { lastApk = it }
                    // Cowork builds things for you — count it toward your time-saved score, and feed the brain.
                    com.agentos.shell.tools.MetricsStore.record(ctx, 600)
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "Cowork", "Cowork", "me", "me", "Cowork done: ${act.message.take(1500)}")
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
    // Handoff from Home ("build me…"): open a fresh chat prefilled with the task and run it.
    LaunchedEffect(Unit) {
        com.agentos.shell.tools.CoworkHandoff.consume()?.let { p ->
            newConvo(); input = p; send()
        }
    }

    Column(modifier) {
        ScreenHeader("Cowork") { if (mode == "chat") { persist(); convos = com.agentos.shell.tools.CoworkChatStore.list(ctx); mode = "list" } else onBack() }
        Spacer(Modifier.height(4.dp))
        Text("A local agent that builds real files — give it a task, it does it step by step.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))

        if (mode == "list") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("＋ New chat", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { newConvo() }.padding(horizontal = 18.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                Text("Files", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { files = WorkspaceStore.list(ctx); showFiles = true }.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            BasicTextField(value = chatSearch, onValueChange = { chatSearch = it }, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner -> if (chatSearch.isEmpty()) Text("🔍  Search chats…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.height(10.dp))
            val filtered = convos.filter { chatSearch.isBlank() || it.title.contains(chatSearch, true) }
            if (filtered.isEmpty()) Text(if (convos.isEmpty()) "No chats yet. Tap New chat to start." else "No matches.", fontSize = T.small, color = T.inkFaint)
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { loadConvo(c.id) }, onLongClick = { renaming = c.id; renameText = c.title })
                            .padding(vertical = 12.dp)) {
                        Text("◆", color = T.accent, fontSize = T.small)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { "New chat" }, fontSize = T.body, color = T.ink)
                            Text(fmt.format(java.util.Date(c.updated)), fontSize = T.caption, color = T.inkFaint)
                        }
                        Text("✕", fontSize = T.small, color = T.inkFaint, modifier = Modifier.clickable {
                            com.agentos.shell.tools.CoworkChatStore.delete(ctx, c.id); convos = com.agentos.shell.tools.CoworkChatStore.list(ctx)
                        }.padding(start = 10.dp))
                    }
                    Hairline()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(convos.firstOrNull { it.id == convoId }?.title?.ifBlank { "New chat" } ?: "New chat",
                    fontSize = T.small, color = T.inkSoft, modifier = Modifier.weight(1f))
                Text("Files", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { files = WorkspaceStore.list(ctx); showFiles = true }.padding(6.dp))
                Spacer(Modifier.width(10.dp))
                Text("New", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { persist(); newConvo() }.padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                if (chat.isEmpty()) item {
                    Text("Try: “draft a cold email to investors and save it”, “outline a blog post about edge AI and write the intro”, “make a Python script that renames photos by date”. Use Attach to add a file it can read.",
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
                val label = when {
                    url.contains("github.com") -> "↗ Open repo"
                    url.contains("google.com") || url.contains("docs.google") -> "↗ Open in Google"
                    else -> "↗ Open link"
                }
                Text(label, fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
            }
            lastApk?.let { apk ->
                Spacer(Modifier.height(8.dp))
                Text("Install the app you built", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { scope.launch { withContext(Dispatchers.IO) { com.agentos.shell.tools.TermuxBridge.run(ctx, "termux-open $apk") } } }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Attach", fontSize = T.small, color = T.inkSoft,
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

    if (showChats) {
        val fmt = remember { java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()) }
        Dialog(onDismissRequest = { showChats = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chats", fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                    Text("＋ New", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { chatSearch = ""; newConvo() }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
                Spacer(Modifier.height(10.dp))
                BasicTextField(value = chatSearch, onValueChange = { chatSearch = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(horizontal = 12.dp, vertical = 9.dp),
                    decorationBox = { inner -> if (chatSearch.isEmpty()) Text("🔍  Search chats…", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.height(8.dp))
                val filtered = convos.filter { chatSearch.isBlank() || it.title.contains(chatSearch, true) }
                if (filtered.isEmpty()) Text(if (convos.isEmpty()) "No chats yet." else "No matches.", fontSize = T.small, color = T.inkFaint)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(filtered, key = { it.id }) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { persist(); chatSearch = ""; loadConvo(c.id); showChats = false }.padding(vertical = 12.dp)) {
                            Text("◆", color = if (c.id == convoId) T.accent else T.inkFaint, fontSize = T.small)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title.ifBlank { "New chat" }, fontSize = T.body, color = if (c.id == convoId) T.accent else T.ink, maxLines = 1)
                                Text(fmt.format(java.util.Date(c.updated)), fontSize = T.caption, color = T.inkFaint)
                            }
                            Text("✕", fontSize = T.small, color = T.inkFaint, modifier = Modifier.clickable {
                                com.agentos.shell.tools.CoworkChatStore.delete(ctx, c.id)
                                convos = com.agentos.shell.tools.CoworkChatStore.list(ctx)
                                if (c.id == convoId) loadConvo(convos.firstOrNull()?.id ?: com.agentos.shell.tools.CoworkChatStore.create(ctx))
                            }.padding(start = 10.dp))
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { showChats = false })
            }
        }
    }

    if (showFiles) {
        Dialog(onDismissRequest = { showFiles = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                Text("Files in this workspace", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(10.dp))
                if (files.isEmpty()) Text("No files yet.", fontSize = T.small, color = T.inkFaint)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(files) { f ->
                        Text(f, fontSize = T.small, color = T.ink,
                            modifier = Modifier.fillMaxWidth().clickable { showFiles = false; viewing = f }.padding(vertical = 10.dp))
                        Hairline()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { showFiles = false })
            }
        }
    }

    renaming?.let { rid ->
        Dialog(onDismissRequest = { renaming = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Rename chat", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(10.dp))
                BasicTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(12.dp))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable {
                                com.agentos.shell.tools.CoworkChatStore.touch(ctx, rid, renameText.ifBlank { "New chat" })
                                convos = com.agentos.shell.tools.CoworkChatStore.list(ctx); renaming = null
                            }.padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { renaming = null }.padding(vertical = 10.dp))
                }
            }
        }
    }
}
