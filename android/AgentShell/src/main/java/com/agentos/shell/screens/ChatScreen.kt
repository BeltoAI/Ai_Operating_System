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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.BankVault
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
/** A question about the user's OWN sensitive bank/vault info — answered locally behind the PIN, never
 *  sent to the cloud model. */
private fun isVaultQuery(q: String): Boolean =
    Regex("(?i)(my bank|bank details|bank info|bank account|account number|routing( number)?|iban|sort code|swift|card number|my card|bank vault|open (the )?vault|my (banking|account) (info|details|number))").containsMatchIn(q)

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
    val clipboard = LocalClipboardManager.current
    var renameId by remember { mutableStateOf(0L) }
    var renameText by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var attachB64 by remember { mutableStateOf<String?>(null) }   // pending image to send
    var vaultPinPrompt by remember { mutableStateOf(false) }      // asked about bank info → ask PIN
    var vaultPin by remember { mutableStateOf("") }
    var vaultReveal by remember { mutableStateOf<List<BankVault.Item>?>(null) }
    var vaultErr by remember { mutableStateOf("") }

    // Speak to the AI — the system voice recognizer fills the input box.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = (input.trim() + " " + spoken).trim()
    }
    fun startVoice() {
        try {
            voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak")
            })
        } catch (e: Exception) {}
    }
    // Attach an image — recompressed to JPEG base64 for the vision model.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val b64 = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri).use { inp ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(inp) ?: return@use null
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                } catch (e: Exception) { null }
            }
            attachB64 = b64
        }
    }

    fun open(id: Long) { currentId = id; msgs = ChatStore.messages(ctx, id) }

    LaunchedEffect(msgs.size, busy) {
        val n = msgs.size + (if (busy) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send() {
        val q = input.trim()
        val img = attachB64
        if ((q.isBlank() && img == null) || busy) return
        // Bank/vault questions are answered LOCALLY behind the PIN — never sent to the cloud model.
        if (img == null && q.isNotBlank() && BankVault.isConfigured(ctx) && isVaultQuery(q)) {
            input = ""; vaultErr = ""; vaultPin = ""; vaultPinPrompt = true; return
        }
        input = ""; attachB64 = null; busy = true
        if (currentId == 0L) currentId = ChatStore.create(ctx)
        val id = currentId
        val shownUser = if (img != null) (if (q.isBlank()) "[image]" else "$q  [image]") else q
        msgs = ChatStore.append(ctx, id, "you", shownUser)
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
            val (code, reply) = withContext(Dispatchers.IO) {
                if (img != null) {
                    val r = AgentClient.askVision(q.ifBlank { "What's in this image?" }, listOf(img), context, maxTokens = 900)
                    if (AgentClient.looksLikeError(r)) -1 to r else 200 to r
                } else AgentClient.chat(q, context, history)
            }
            val shown = if (code == 200 && reply.isNotBlank()) reply
                else "Couldn't reach the model — check your connection or key, then try again."
            msgs = ChatStore.append(ctx, id, "ai", shown)
            threads = ChatStore.threads(ctx)
            busy = false
            if (code == 200) withContext(Dispatchers.IO) {
                val clean = RichParse.fromTag(shown).second
                try {
                    MessageStore.insertOne(ctx, "Me", "SlyOS", "me", "me", shownUser)
                    MessageStore.insertOne(ctx, "SlyOS", "SlyOS", "SlyOS", "them", clean)
                } catch (e: Exception) {}
                val pk = MemoryLog.add(ctx, "prompt", shownUser, shownUser, "Chat")
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
            Spacer(Modifier.height(12.dp))
            BasicTextField(value = search, onValueChange = { search = it }, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner -> if (search.isEmpty()) Text("Search chats…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.height(12.dp))
            val q = search.trim()
            val shown = remember(threads, q) {
                if (q.isBlank()) threads
                else threads.filter { t ->
                    t.title.contains(q, true) || ChatStore.messages(ctx, t.id).any { it.text.contains(q, true) }
                }
            }
            if (threads.isEmpty())
                Text("No chats yet.", fontSize = T.small, color = T.inkFaint)
            else if (shown.isEmpty())
                Text("No chats match “$q”.", fontSize = T.small, color = T.inkFaint)
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { open(t.id) },
                            onLongClick = { renameId = t.id; renameText = t.title })
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
                    val copyDelete: @Composable (horiz: Arrangement.Horizontal) -> Unit = { horiz ->
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = horiz) {
                            Icon(Icons.Outlined.ContentCopy, "Copy", tint = T.inkFaint,
                                modifier = Modifier.size(17.dp).clickable {
                                    clipboard.setText(AnnotatedString(RichParse.fromTag(m.text).second))
                                })
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Outlined.DeleteOutline, "Delete", tint = T.inkFaint,
                                modifier = Modifier.size(18.dp).clickable {
                                    msgs = ChatStore.deleteMessage(ctx, currentId, m.ts); threads = ChatStore.threads(ctx)
                                })
                        }
                    }
                    if (m.role == "you") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(m.text, fontSize = T.small, color = T.bgElevated,
                                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                                        .background(T.accent).padding(horizontal = 13.dp, vertical = 9.dp))
                            }
                            copyDelete(Arrangement.End)
                        }
                    } else {
                        val (hero, body) = remember(m.text) { RichParse.render(m.text) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            if (hero != null) { HeroCardView(hero); Spacer(Modifier.height(10.dp)) }
                            if (body.isNotBlank()) {
                                if (hasMath(body)) MathText(body) else MarkdownText(body)
                            }
                            copyDelete(Arrangement.Start)
                        }
                    }
                }
                if (busy) item {
                    Text("thinking…", fontSize = T.small, color = T.accent, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
            if (attachB64 != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Image attached", fontSize = T.caption, color = T.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("remove", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.clickable { attachB64 = null })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, "Attach", tint = T.inkSoft,
                    modifier = Modifier.size(22.dp).clickable { pick.launch("image/*") })
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Mic, "Speak", tint = T.inkSoft,
                    modifier = Modifier.size(22.dp).clickable { startVoice() })
                Spacer(Modifier.width(10.dp))
                BasicTextField(value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(12.dp))
                        .background(T.bgElevated).padding(12.dp),
                    decorationBox = { inner -> if (input.isEmpty()) Text("Message…", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.width(8.dp))
                val canSend = !busy && (input.isNotBlank() || attachB64 != null)
                Text(if (busy) "…" else "→", fontSize = T.body, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (canSend) T.accent else T.hairline)
                        .clickable(enabled = canSend) { send() }
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }

    // Long-press a thread → rename it.
    if (renameId != 0L) {
        Dialog(onDismissRequest = { renameId = 0L }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Rename chat", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                BasicTextField(renameText, { renameText = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.body),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(12.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { ChatStore.rename(ctx, renameId, renameText); threads = ChatStore.threads(ctx); renameId = 0L }
                            .padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { renameId = 0L }.padding(vertical = 10.dp))
                }
            }
        }
    }

    // Bank query → PIN prompt (kept entirely on-device; the model never sees any of this).
    if (vaultPinPrompt) {
        Dialog(onDismissRequest = { vaultPinPrompt = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Unlock bank vault", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Text("Enter your vault PIN to view your bank info here. It stays on your phone.", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(12.dp))
                BasicTextField(vaultPin, { vaultPin = it }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = TextStyle(color = T.ink, fontSize = T.body),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(12.dp))
                if (vaultErr.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(vaultErr, fontSize = T.caption, color = T.danger) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unlock", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (vaultPin.isNotBlank()) T.accent else T.hairline)
                            .clickable(enabled = vaultPin.isNotBlank()) {
                                val u = BankVault.unlock(ctx, vaultPin)
                                if (u != null) { vaultReveal = u; vaultPinPrompt = false; vaultPin = "" } else vaultErr = "Wrong PIN."
                            }.padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { vaultPinPrompt = false; vaultPin = "" }.padding(vertical = 10.dp))
                }
            }
        }
    }

    // Reveal the decrypted bank info in a dialog only (never written to plaintext storage or the model).
    vaultReveal?.let { list ->
        Dialog(onDismissRequest = { vaultReveal = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Your bank vault", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                if (list.isEmpty()) Text("The vault is empty. Add entries in Settings → Bank vault.", fontSize = T.small, color = T.inkFaint)
                list.forEach { it2 ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(it2.label, fontSize = T.caption, color = T.inkSoft)
                        Text(it2.value, fontSize = T.body, color = T.ink)
                    }
                    Hairline()
                }
                Spacer(Modifier.height(12.dp))
                Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { vaultReveal = null }.padding(vertical = 6.dp))
            }
        }
    }

}
