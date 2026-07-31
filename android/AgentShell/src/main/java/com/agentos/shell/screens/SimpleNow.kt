package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One message. One reply. Three buttons.
 *
 * This is the panel that justifies the whole mode. Somebody who finds a keyboard hard, or who
 * worries about getting the words wrong, gets handed a message and a sensible answer to it — and
 * the only decisions left are the three a person can hold in their head at once: send this, change
 * it, or leave it.
 *
 * The full Now screen shows brain questions with a file picker, a briefing card, network cards,
 * grouped conversations and four different swipe gestures. Every one of those is a good idea for
 * somebody running a company from their phone and a reason to put it down for everybody else.
 *
 * One at a time is the point. A list invites you to triage, and triage is the part that makes
 * people avoid their messages. There is only ever the oldest unanswered one on screen; deal with
 * it and the next appears.
 */
@Composable
fun SimpleNow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = NotificationStore.notes

    /**
     * What "I have dealt with this one" means.
     *
     * Keyed on the message, not on `key`. Several notifications from the same app can carry the
     * SAME key — so skipping added a key that was already in the set, the set compared equal, the
     * remembered note never recomputed, and the button did nothing at all. It looked like a dead
     * control and it was really a value-equality trap.
     */
    fun idOf(n: NotificationStore.Note) = n.key + "|" + n.title + "|" + n.text.take(80).hashCode()

    var skipped by remember { mutableStateOf(setOf<String>()) }
    val note = remember(notes.size, skipped) {
        notes.firstOrNull { idOf(it) !in skipped && !it.isLikelyBot }
    }

    // Same trap, one level down: keying the draft on `key` alone means two different messages that
    // share a key keep each other's reply.
    val noteId = note?.let { idOf(it) }
    var draft by remember(noteId) { mutableStateOf("") }
    var busy by remember(noteId) { mutableStateOf(false) }
    var editing by remember(noteId) { mutableStateOf(false) }
    var said by remember(noteId) { mutableStateOf("") }

    // The reply is written before she asks for it, because "press this to write something" is a
    // step, and every step is somewhere to stop.
    LaunchedEffect(noteId) {
        val n = note ?: return@LaunchedEffect
        if (!n.canReply) return@LaunchedEffect
        busy = true
        val d = withContext(Dispatchers.IO) {
            try {
                val th = com.agentos.shell.tools.ConversationStore.thread(ctx, n.app, n.title)
                    .map { it.role to it.text }
                val m = com.agentos.shell.tools.ReplyContext.forSender(ctx, n.app, n.title, n.text)
                if (th.isNotEmpty())
                    AgentClient.draftReplyThread(n.title.ifBlank { n.app }, th, m, null, n.text, n.isGroup)
                else AgentClient.draftReply(n.title.ifBlank { n.app }, n.text, m)
            } catch (e: Exception) { "" }
        }
        if (d.isNotBlank() && !AgentClient.looksLikeError(d)) draft = d
        busy = false
    }

    Column(modifier.fillMaxSize().background(T.bg)
        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(26.dp))

        if (note == null) {
            Text("Nothing new.", fontSize = 30.sp, color = T.ink,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Text("When somebody messages you, it will be here.",
                fontSize = 19.sp, color = T.inkFaint, lineHeight = 26.sp)
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        Text(note.title.ifBlank { note.app }, fontSize = 28.sp, color = T.ink,
            fontWeight = FontWeight.Medium, lineHeight = 35.sp)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(T.bgElevated).padding(20.dp)) {
            Text(note.text, fontSize = 21.sp, color = T.ink, lineHeight = 29.sp)
        }

        if (said.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(said, fontSize = 21.sp,
                color = if (said.contains("Sent")) T.good else T.danger)
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        if (!note.canReply) {
            Spacer(Modifier.height(22.dp))
            Text("You can't answer this one from here.", fontSize = 18.sp, color = T.inkFaint)
            Spacer(Modifier.height(16.dp))
            BigBtn("Next", T.bgElevated, T.ink) { skipped = skipped + idOf(note) }
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        Spacer(Modifier.height(24.dp))
        Text("Your answer", fontSize = 17.sp, color = T.inkFaint)
        Spacer(Modifier.height(9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(T.bgElevated).padding(20.dp)) {
            if (busy) Text("writing…", fontSize = 21.sp, color = T.inkFaint)
            else if (editing) BasicTextField(draft, { draft = it },
                textStyle = TextStyle(color = T.ink, fontSize = 21.sp, lineHeight = 29.sp),
                modifier = Modifier.fillMaxWidth())
            else Text(draft.ifBlank { "…" }, fontSize = 21.sp, color = T.ink, lineHeight = 29.sp)
        }

        Spacer(Modifier.height(20.dp))
        // Three, and no more. Send, change, leave — the whole decision, at a size you can hit.
        BigBtn(if (busy) "…" else "Send this", T.accent, Color.White) {
            if (busy || draft.isBlank()) return@BigBtn
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try { NotificationStore.sendReply(ctx, note, draft) } catch (e: Exception) { false }
                }
                busy = false
                said = if (ok) "Sent." else "That didn't send. Try again in a moment."
                if (ok) skipped = skipped + idOf(note)
            }
        }
        Spacer(Modifier.height(12.dp))
        BigBtn(if (editing) "Done changing" else "Change it", T.bgElevated, T.ink) {
            editing = !editing
        }
        Spacer(Modifier.height(12.dp))
        BigBtn("Leave it for now", T.bgElevated, T.inkFaint) { skipped = skipped + idOf(note) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun BigBtn(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg)
        .clickable { onClick() }.padding(vertical = 24.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 22.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}
