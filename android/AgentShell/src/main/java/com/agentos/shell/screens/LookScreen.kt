package com.agentos.shell.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ImageUtil
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Look" — point the camera at anything and SlyOS tells you what it is, with one tap to shop it, map
 * it, or learn more. Tap-to-identify (no battery-hungry live stream). Everything feeds the brain.
 */
@Composable
fun LookScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<AgentClient.LookResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var answerBusy by remember { mutableStateOf(false) }

    fun openUrl(u: String) {
        if (u.isBlank()) return
        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }
    fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun identify(b: Bitmap) {
        busy = true; result = null; answer = ""; asking = false; question = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val b64 = ImageUtil.encodeBitmap(b) ?: return@withContext null
                AgentClient.lookAt(b64, MemoryStore.fullProfile(ctx))
            }
            result = r
            busy = false
            if (r != null && r.title.isNotBlank()) withContext(Dispatchers.IO) {
                // Everything you look at feeds the brain (searchable: "what was that thing I saw?").
                MessageStore.insertOne(ctx, "Look", "Camera", "system", "system", "Looked at: ${r.title} — ${r.detail}")
                MetricsStore.record(ctx, 60)
            }
        }
    }

    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        if (b != null) { bmp = b; identify(b) }
    }
    LaunchedEffect(Unit) { cam.launch(null) }

    fun ask() {
        val b = bmp; val qq = question.trim()
        if (b == null || qq.isBlank() || answerBusy) return
        answerBusy = true; answer = ""
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                val b64 = ImageUtil.encodeBitmap(b) ?: return@withContext ""
                AgentClient.askVision(qq, listOf(b64), MemoryStore.fullProfile(ctx))
            }
            answer = if (AgentClient.looksLikeError(a)) "Couldn't answer that — try again." else a
            answerBusy = false
            if (!AgentClient.looksLikeError(a) && a.isNotBlank()) withContext(Dispatchers.IO) {
                MessageStore.insertOne(ctx, "Look", "Camera", "system", "system", "Q: $qq — A: $a")
            }
        }
    }

    @Composable
    fun chip(label: String, onClick: () -> Unit) {
        Text(label, fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 10.dp))
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Look") { onBack() }

        bmp?.let { b ->
            Spacer(Modifier.height(12.dp))
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)))
        }

        if (busy) { Spacer(Modifier.height(18.dp)); Text("Looking…", fontSize = T.body, color = T.accent) }

        result?.let { r ->
            Spacer(Modifier.height(16.dp))
            Text(r.title, fontSize = T.prompt, color = T.ink)
            if (r.detail.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(r.detail, fontSize = T.small, color = T.inkSoft) }
            Spacer(Modifier.height(16.dp))
            // One-tap actions, chosen by what it is.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (r.category) {
                    "product" -> {
                        chip("Shop it") { openUrl("https://www.google.com/search?tbm=shop&q=" + enc(r.query.ifBlank { r.title })) }
                        chip("Search") { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title })) }
                    }
                    "place" -> {
                        chip("Maps") { openUrl("https://www.google.com/maps/search/?api=1&query=" + enc(r.place.ifBlank { r.query.ifBlank { r.title } })) }
                        chip("Search") { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title })) }
                    }
                    "food" -> {
                        chip("Recipes") { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title } + " recipe")) }
                        chip("Search") { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title })) }
                    }
                    else -> chip("Search") { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title })) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (asking) "Ask about this ▾" else "Ask about this ▸", fontSize = T.small, color = T.accent,
                modifier = Modifier.clickable { asking = !asking }.padding(vertical = 6.dp))
            if (asking) {
                Spacer(Modifier.height(6.dp))
                BasicTextField(question, { question = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
                    decorationBox = { inner -> if (question.isEmpty()) Text("e.g. is this a good buy? how do I cook it?", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.height(8.dp))
                Text(if (answerBusy) "Thinking…" else "Ask", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (question.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = !answerBusy && question.isNotBlank()) { ask() }.padding(vertical = 12.dp))
                if (answer.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(answer, fontSize = T.small, color = T.ink) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(if (busy) "…" else (if (result == null) "Point at anything" else "Look at something else"),
            fontSize = T.body, color = T.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(T.bgElevated).clickable(enabled = !busy) { cam.launch(null) }.padding(vertical = 15.dp))
        Spacer(Modifier.height(28.dp))
    }
}
