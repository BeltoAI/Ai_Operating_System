package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Classical chatbot — a regular Claude/ChatGPT/Gemini-style conversation on the user's selected model.
 * It can search the web, draws on the brain, renders rich Markdown + headline cards, and every turn feeds
 * back into the brain so nothing said here is lost.
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf(ChatStore.load(ctx)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Which model is answering (shown quietly under the header).
    val providerLabel = when (MemoryStore.preferredProvider(ctx)) {
        "anthropic" -> "Claude"; "openai" -> "OpenAI"; "gemini" -> "Gemini"; else -> "your model"
    }

    LaunchedEffect(msgs.size, busy) {
        val n = msgs.size + (if (busy) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send() {
        val q = input.trim()
        if (q.isBlank() || busy) return
        input = ""; busy = true
        msgs = ChatStore.append(ctx, "you", q)
        scope.launch {
            // Assemble the same brain/persona context the rest of SlyOS uses.
            val context = withContext(Dispatchers.IO) {
                val about = MemoryStore.about(ctx)
                val brain = try { com.agentos.shell.tools.BrainContext.build(ctx, q) } catch (e: Exception) { "" }
                listOf(about, brain).filter { it.isNotBlank() }.joinToString("\n\n")
            }
            // Recent turns as (you, ai) pairs for multi-turn memory.
            val history = ArrayList<Pair<String, String>>()
            var pendingUser: String? = null
            msgs.dropLast(1).forEach { m ->
                if (m.role == "you") pendingUser = m.text
                else if (m.role == "ai" && pendingUser != null) { history.add(pendingUser!! to m.text); pendingUser = null }
            }
            val (code, reply) = withContext(Dispatchers.IO) { AgentClient.chat(q, context, history) }
            val shown = if (code == 200 && reply.isNotBlank()) reply
                else "I couldn't reach the model just now — check your connection or key in Brain → settings, then try again."
            msgs = ChatStore.append(ctx, "ai", shown)
            busy = false
            // Feed the brain: store the clean reply (no card tag) so it's recallable like any other memory.
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
        ScreenHeader("Chat") { onBack() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$providerLabel · web-connected · saved to your brain", fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.weight(1f))
            if (msgs.isNotEmpty())
                Text("＋ New chat", fontSize = T.caption, color = T.inkSoft,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable { ChatStore.clear(ctx); msgs = emptyList() }
                        .padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (msgs.isEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Ask me anything", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("A regular chat — questions, writing, code, explanations, live web look-ups. I draw on your " +
                        "brain and everything we discuss is saved back into it.", fontSize = T.small, color = T.inkFaint)
                }
            }
            items(msgs) { m ->
                if (m.role == "you") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.End) {
                        Text(m.text, fontSize = T.small, color = T.bgElevated,
                            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                                .background(T.accent).padding(horizontal = 13.dp, vertical = 9.dp))
                    }
                } else {
                    // Assistant turn — full-width, rich: optional headline card + Markdown body.
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
                decorationBox = { inner -> if (input.isEmpty()) Text("Message SlyOS…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (busy || input.isBlank()) T.hairline else T.accent)
                    .clickable(enabled = !busy && input.isNotBlank()) { send() }
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
