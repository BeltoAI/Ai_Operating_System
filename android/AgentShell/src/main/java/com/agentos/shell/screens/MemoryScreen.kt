package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.KnowledgeStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Memory = what the agent knows about you. You write it; the agent uses it to personalize
 * every answer and every reply it drafts. Stored locally on the phone.
 */
@Composable
fun MemoryScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var about by remember { mutableStateOf(MemoryStore.about(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var autonomous by remember { mutableStateOf(MemoryStore.autonomous(ctx)) }
    var spicyDaily by remember { mutableStateOf(MemoryStore.spicyDaily(ctx)) }
    var kbName by remember { mutableStateOf(KnowledgeStore.name(ctx)) }
    var kbStatus by remember { mutableStateOf("") }
    var docTelegram by remember { mutableStateOf(MemoryStore.docTelegram(ctx)) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            kbStatus = "Reading PDF…"
            scope.launch {
                val nm = (uri.lastPathSegment ?: "document.pdf").substringAfterLast('/').substringAfterLast(':')
                val chars = withContext(Dispatchers.IO) { KnowledgeStore.load(ctx, uri, nm) }
                kbName = KnowledgeStore.name(ctx)
                kbStatus = if (chars > 0) "Loaded — $chars characters." else "Couldn't read that PDF."
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Memory", onBack)
        Spacer(Modifier.height(16.dp))

        Text("What should the agent know about you?", fontSize = T.body, color = T.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Name, how you like to be addressed, tone for replies, work, people who matter — " +
                "anything that makes its answers feel like you.",
            fontSize = T.small, color = T.inkFaint
        )
        Spacer(Modifier.height(12.dp))

        BasicTextField(
            value = about,
            onValueChange = { about = it; saved = false },
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(T.bgElevated)
                .padding(14.dp),
            decorationBox = { inner ->
                if (about.isEmpty())
                    Text(
                        "e.g. I'm Zaddy, a UCR student. Keep replies short and warm. " +
                            "My partner is Alex. I work nights, so don't assume I'm free in the evening.",
                        fontSize = T.small, color = T.inkFaint
                    )
                inner()
            }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            if (saved) "Saved ✓" else "Save",
            fontSize = T.small,
            color = if (saved) T.inkSoft else T.bgElevated,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (saved) T.hairline else T.accent)
                .clickable {
                    MemoryStore.setAbout(ctx, about.trim())
                    saved = true
                }
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-reply to messages", fontSize = T.body, color = T.ink)
                Text(
                    "Agent replies on its own after an 8-second window you can cancel from Now.",
                    fontSize = T.small, color = T.inkFaint
                )
            }
            Switch(
                checked = autonomous,
                onCheckedChange = { autonomous = it; MemoryStore.setAutonomous(ctx, it) }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily spicy take", fontSize = T.body, color = T.ink)
                Text("Each morning (~9am) SlyOS writes a spicy tech post and notifies you — tap to post.",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(
                checked = spicyDaily,
                onCheckedChange = {
                    spicyDaily = it
                    MemoryStore.setSpicyDaily(ctx, it)
                    com.agentos.shell.SpicyScheduler.set(ctx, it)
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Document Q&A", fontSize = T.body, color = T.ink)
        Text("Load a PDF; SlyOS answers Telegram messages using only that document.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Load PDF", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { pdfPicker.launch(arrayOf("application/pdf")) }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
            if (kbName.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text("Clear", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clickable { KnowledgeStore.clear(ctx); kbName = ""; kbStatus = "" })
            }
        }
        if (kbName.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("📄 $kbName", fontSize = T.small, color = T.inkSoft) }
        if (kbStatus.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(kbStatus, fontSize = T.caption, color = T.accent) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-answer Telegram from PDF", fontSize = T.body, color = T.ink)
                Text("When a Telegram message arrives, reply using only the loaded document.",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(checked = docTelegram, onCheckedChange = { docTelegram = it; MemoryStore.setDocTelegram(ctx, it) })
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "The agent reads this on every request. Nothing here leaves your phone except as " +
                "part of a prompt you trigger.",
            fontSize = T.caption, color = T.inkFaint
        )
    }
}
