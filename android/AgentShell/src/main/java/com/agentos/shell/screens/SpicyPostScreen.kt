package com.agentos.shell.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.TwitterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpicyPostScreen(modifier: Modifier = Modifier, topic: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var platform by remember { mutableStateOf("x") }      // "x" | "reddit"
    var post by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }
    val isReddit = platform == "reddit"

    fun revise() {
        if (editPrompt.isBlank() || post.isBlank()) return
        val instr = editPrompt; working = true; status = ""
        scope.launch {
            post = withContext(Dispatchers.IO) { AgentClient.revisePost(post, instr, platform, MemoryStore.about(ctx)) }
            editPrompt = ""; working = false
        }
    }

    fun generate() {
        working = true; status = ""
        scope.launch {
            post = withContext(Dispatchers.IO) { AgentClient.spicyPost(topic, platform, MemoryStore.about(ctx)) }
            working = false
        }
    }
    LaunchedEffect(topic, platform) { generate() }

    fun shareText(pkg: String, label: String, subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { send.setPackage(pkg); ctx.startActivity(send); status = "Opening $label — tap to post." }
        catch (e: Exception) {
            try {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, subject), "Post")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); status = "$label not installed — pick where to post."
            } catch (e2: Exception) { status = "Couldn't open a share target." }
        }
    }

    fun publish() {
        if (post.isBlank()) return
        // Always copy the post so it's paste-ready even if the target app doesn't prefill (posting never fails).
        try { (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("post", post)) } catch (e: Exception) {}
        com.agentos.shell.tools.MetricsStore.record(ctx, com.agentos.shell.tools.MetricsStore.secondsFor("spicy_post"))
        // Feed the brain: your published posts are searchable + grow your learned voice.
        val plat = if (isReddit) "Reddit" else "X"
        com.agentos.shell.tools.MemoryLog.add(ctx, "response", "Posted to $plat", post, plat)
        com.agentos.shell.tools.MessageStore.insertOne(ctx, "My $plat posts", plat, "me", "me", post)
        com.agentos.shell.tools.MemoryStore.addVoiceSamples(ctx, listOf(post))
        if (isReddit) {
            val parts = post.split("\n", limit = 2)
            val title = parts[0].trim()
            val body = if (parts.size > 1) parts[1].trim() else title
            shareText("com.reddit.frontpage", "Reddit", title, body)
            return
        }
        if (TwitterClient.configured()) {
            working = true; status = "Posting to X…"
            scope.launch {
                // Small human-like pause so posts don't fire machine-instant (helps X not flag it as a
                // bot when you post a few in a row). Scales a touch with length, plus jitter.
                val wait = (1500L + post.length * 12L + (0..2500).random()).coerceAtMost(7000L)
                kotlinx.coroutines.delay(wait)
                val (_, msg) = withContext(Dispatchers.IO) { TwitterClient.postTweet(post) }
                status = msg; working = false
            }
        } else shareText("com.twitter.android", "X", post.take(80), post)
    }

    Column(modifier) {
        ScreenHeader("Spicy take", onBack)
        Spacer(Modifier.height(10.dp))
        Row {
            listOf("x" to "X", "reddit" to "Reddit").forEach { (id, label) ->
                val sel = platform == id
                Text(label, fontSize = T.small, color = if (sel) T.bgElevated else T.inkSoft,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (sel) T.accent else T.hairline)
                        .clickable { if (platform != id) platform = id }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (topic.isBlank()) "A constructive tech roast." else "On: $topic",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(16.dp)) {
            Column {
                Text(if (isReddit) "r/ · draft" else "@you · now", fontSize = T.caption, color = Color(0xFF8A8076))
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = if (working && post.isEmpty()) "writing a spicy one…" else post,
                    onValueChange = { post = it },
                    textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = T.body),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (isReddit) 220.dp else 90.dp)
                )
            }
        }
        if (!isReddit) {
            Spacer(Modifier.height(6.dp))
            Text("${post.length}/280", fontSize = T.caption,
                color = if (post.length > 280) T.danger else T.inkFaint, modifier = Modifier.align(Alignment.End))
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = editPrompt,
                onValueChange = { editPrompt = it },
                singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(T.bgElevated).padding(horizontal = 12.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    if (editPrompt.isEmpty())
                        Text("how should I change it? e.g. shorter, meaner, add a stat…",
                            fontSize = T.small, color = T.inkFaint)
                    inner()
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(if (working) "…" else "Edit", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.ink)
                    .clickable(enabled = !working && editPrompt.isNotBlank()) { revise() }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Text(if (working) "…" else "Post to ${if (isReddit) "Reddit" else "X"}",
                fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !working && post.isNotBlank()) { publish() }
                    .padding(horizontal = 20.dp, vertical = 10.dp))
            Spacer(Modifier.width(10.dp))
            Text("Regenerate", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable(enabled = !working) { generate() }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(status, fontSize = T.small, color = T.accent) }
    }
}
