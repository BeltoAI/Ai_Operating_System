package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.agentos.shell.tools.ChatStore
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Classical chatbot — regular Claude/ChatGPT/Gemini-style conversation on the user's selected model. Web
 * search, draws on the brain, rich Markdown + headline cards, saved threads. Every turn feeds the brain.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    var threads by remember { mutableStateOf(ChatStore.threads(ctx)) }
    var currentId by remember { mutableStateOf(0L) }        // 0 = thread list
    var msgs by remember { mutableStateOf(listOf<ChatStore.Msg>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun open(id: Long) { currentId = id; msgs = ChatStore.messages(ctx, id) }

    LaunchedEffect(msgs.size, busy) {
        val n = msgs.size + (if (busy) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send() {
        val q = input.trim()
        if (q.isBlank() || busy) return
        input = ""; busy = true
        if (currentId == 0L) currentId = ChatStore.create(ctx)
        val id = currentId
        msgs = ChatStore.append(ctx, id, "you", q)
        scope.launch {
            val context = withContext(Dispatchers.IO) {
                val about = MemoryStore.about(ctx)
                val brain = try { com.agentos.shell.tools.BrainContext.build(ctx, q) } catch (e: Exception) { "" }
                listOf(about, brain).filter { it.isNotBlank() }.joinToString("\n\n")
            }
            val history = ArrayList<Pair<String, String>>()
            var pendingUser: String? = null
            msgs.dropLast(1).forEach { m ->
                if (m.role == "you") pendingUser = m.text
                else if (m.role == "ai" && pendingUser != null) { history.add(pendingUser!! to m.text); pendingUser = null }
            }
            val (code, reply) = withContext(Dispatchers.IO) { AgentClient.chat(q, context, history) }
            val shown = if (code == 200 && reply.isNotBlank()) reply
                else "Couldn't reach the model — check your connection or key, then try again."
            msgs = ChatStore.append(ctx, id, "ai", shown)
            threads = ChatStore.threads(ctx)
            busy = false
            if (code == 200) withContext(Dispatchers.IO) {
                val clean = RichParse.fromTag(shown).second
                try {
                    MessageStore.insertOne(ctx, "Me", "SlyOS", "me", "me", q)
                    MessageStore.insertOne(ctx, "SlyOS", "SlyOS", "SlyOS", "them", clean)
                } catch (e: Exception) {}
                val pk = MemoryLog.add(ctx, "prompt", q, q, "Chat")
                MemoryLog.add(ctx, "response", clean, clean, "Chat reply", pk)
            }
        }
    }

    Column(modifier) {
        ScreenHeader("Chat") { if (currentId == 0L) onBack() else { threads = ChatStore.threads(ctx); currentId = 0L } }
        Spacer(Modifier.height(8.dp))

        if (currentId == 0L) {
            // Thread list — previous chats, like Cowork / Research.
            Text("New chat", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { open(ChatStore.create(ctx)) }.padding(horizontal = 16.dp, vertical = 10.dp))
            Spacer(Modifier.height(14.dp))
            if (threads.isEmpty())
                Text("No chats yet.", fontSize = T.small, color = T.inkFaint)
            LazyColumn(Modifier.weight(1f)) {
                items(threads, key = { it.id }) { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { open(t.id) }, onLongClick = {})
                        .padding(vertical = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontSize = T.body, color = T.ink)
                            Text(fmt.format(Date(t.updated)), fontSize = T.caption, color = T.inkFaint)
                        }
                        Text("✕", fontSize = T.small, color = T.inkFaint,
                            modifier = Modifier.clickable { ChatStore.delete(ctx, t.id); threads = ChatStore.threads(ctx) }
                                .padding(start = 10.dp))
                    }
                    Hairline()
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                if (msgs.isEmpty()) item {
                    Text("Ask me anything.", fontSize = T.small, color = T.inkFaint, modifier = Modifier.padding(vertical = 10.dp))
                }
                items(msgs) { m ->
                    if (m.role == "you") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.End) {
                            Text(m.text, fontSize = T.small, color = T.bgElevated,
                                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                                    .background(T.accent).padding(horizontal = 13.dp, vertical = 9.dp))
                        }
                    } else {
                        val (hero, body) = remember(m.text) { RichParse.render(m.text) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            if (hero != null) { HeroCardView(hero); Spacer(Modifier.height(10.dp)) }
                            if (body.isNotBlank()) MarkdownText(body)
                        }
                    }
                }
                if (busy) item {
                    Text("thinking…", fontSize = T.small, color = T.accent, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(12.dp))
                        .background(T.bgElevated).padding(12.dp),
                    decorationBox = { inner -> if (input.isEmpty()) Text("Message…", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (busy || input.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = !busy && input.isNotBlank()) { send() }
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}
