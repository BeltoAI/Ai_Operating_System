package com.agentos.shell.screens

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local Cowork: a desktop-style agent workspace on the phone. You give it a task; it iterates with
 * tools (list/read/write/edit real files) until done, narrating each step. Runs on whichever model
 * you've set (Claude, GPT, or Gemini) via a JSON tool protocol — no native tool-use API required.
 */
@Composable
fun CoworkScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(WorkspaceStore.list(ctx)) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // Display log: role = "you" | "agent" | "step"
    val chat = remember { mutableStateListOf<Pair<String, String>>() }
    // Raw transcript sent to the model (alternating user/assistant).
    val turns = remember { mutableStateListOf<Pair<String, String>>() }

    fun turnsJson(): JSONArray {
        val arr = JSONArray()
        turns.takeLast(34).forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        return arr
    }
    fun parse(raw: String): JSONObject? = try {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s in 0 until e) JSONObject(raw.substring(s, e + 1)) else null
    } catch (ex: Exception) { null }

    fun execTool(tool: String, args: JSONObject?): String = when (tool) {
        "list_files" -> WorkspaceStore.list(ctx).let { if (it.isEmpty()) "(no files yet)" else it.joinToString("\n") }
        "read_file" -> {
            val n = args?.optString("name").orEmpty()
            if (!WorkspaceStore.exists(ctx, n)) "ERROR: file \"$n\" doesn't exist" else WorkspaceStore.read(ctx, n).take(12000)
        }
        "write_file" -> {
            val n = args?.optString("name").orEmpty(); val c = args?.optString("content").orEmpty()
            if (n.isBlank()) "ERROR: no file name" else { WorkspaceStore.write(ctx, n, c); "OK: wrote $n (${c.length} chars)" }
        }
        "edit_file" -> {
            val n = args?.optString("name").orEmpty(); val f = args?.optString("find").orEmpty(); val r = args?.optString("replace").orEmpty()
            if (WorkspaceStore.edit(ctx, n, f, r)) "OK: edited $n" else "ERROR: couldn't find that exact text in $n (read it first)"
        }
        else -> "ERROR: unknown tool \"$tool\""
    }

    fun send() {
        val task = input.trim(); if (task.isBlank() || busy) return
        chat.add("you" to task); turns.add("user" to task); input = ""; busy = true
        scope.launch {
            var steps = 0
            while (steps < 16) {
                steps++
                val raw = withContext(Dispatchers.IO) { AgentClient.coworkTurn(turnsJson(), MemoryStore.fullProfile(ctx)) }
                turns.add("assistant" to raw)
                val obj = parse(raw)
                if (obj == null) { chat.add("agent" to raw.take(1200)); break }
                if (obj.has("done")) { chat.add("agent" to obj.optString("message").ifBlank { "Done." }); break }
                val tool = obj.optString("tool")
                val note = obj.optString("note")
                if (note.isNotBlank()) chat.add("step" to "• $note")
                else if (tool.isNotBlank()) chat.add("step" to "• $tool")
                val result = withContext(Dispatchers.IO) { execTool(tool, obj.optJSONObject("args")) }
                turns.add("user" to "TOOL RESULT ($tool):\n${result.take(8000)}")
                files = WorkspaceStore.list(ctx)
            }
            if (steps >= 16) chat.add("agent" to "Stopped after 16 steps — send another message to keep going.")
            busy = false
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, busy) { val n = chat.size + (if (busy) 1 else 0); if (n > 0) listState.animateScrollToItem(n - 1) }

    Column(modifier) {
        ScreenHeader("Cowork", onBack)
        Spacer(Modifier.height(4.dp))
        Text("A local agent that works on real files — give it a task, it does it step by step.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        // Files row
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
                Text("Try: “make a markdown packing list for a 3-day Berlin trip”, “draft a cold email to investors and save it”, “outline a blog post about edge AI and write the intro”.",
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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                Text(name, fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(8.dp))
                Text(WorkspaceStore.read(ctx, name).ifBlank { "(empty)" }, fontSize = T.caption, color = T.inkSoft,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("Delete", fontSize = T.small, color = T.danger,
                        modifier = Modifier.clickable { WorkspaceStore.delete(ctx, name); files = WorkspaceStore.list(ctx); viewing = null }.padding(end = 18.dp))
                    Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { viewing = null })
                }
            }
        }
    }
}
