package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding. Collects everything SlyOS needs to BE you, in a few steps:
 *  1) your Anthropic API key (the brain) — required
 *  2) about you + booking link + Zenodo key
 *  3) import your data — chat history (voice) and LinkedIn network
 * Everything is stored on-device. Nothing here is shipped in the APK; each person fills their own.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }

    var key by remember { mutableStateOf(MemoryStore.anthropicKey(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(MemoryStore.about(ctx)) }
    var booking by remember { mutableStateOf(MemoryStore.bookingLink(ctx)) }
    var zenodo by remember { mutableStateOf(MemoryStore.zenodoToken(ctx)) }
    var importStatus by remember { mutableStateOf("") }

    @Composable
    fun field(value: String, onChange: (String) -> Unit, hint: String, lines: Int = 1, secret: Boolean = false) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = lines == 1,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            visualTransformation = if (secret && !showKey) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = (lines * 22 + 20).dp)
                .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint); inner() }
        )
    }

    // Chat-history import (voice learning) — same engine as Brain → About.
    val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importStatus = "Reading ${uris.size} file(s) & learning your voice…"
            scope.launch {
                val owner = MemoryStore.ownerName(ctx)
                var msgs = 0; var chats = 0
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        val r = com.agentos.shell.tools.ChatImport.importAny(ctx, uri, owner)
                        msgs += r.messages; chats += r.contacts
                        MemoryStore.addVoiceSamples(ctx, r.mySamples)
                    }
                }
                val pool = MemoryStore.voiceSamples(ctx)
                if (msgs > 0 && pool.isNotEmpty()) {
                    val profile = withContext(Dispatchers.IO) { AgentClient.learnStyle(pool) }
                    if (profile.isNotBlank() && !profile.startsWith("[")) {
                        MemoryStore.setStyleProfile(ctx, profile); AgentClient.styleProfile = profile
                        MemoryStore.setVoiceLearnedCount(ctx, pool.size)
                    }
                    importStatus = "Imported $chats chats / $msgs messages & learned your voice ✓"
                } else importStatus = "Couldn't read those. Use WhatsApp .txt, LinkedIn messages.csv, or IG/Telegram .json."
            }
        }
    }
    // LinkedIn network import — same engine as Now → Reconnect.
    val liPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var last = ""
                withContext(Dispatchers.IO) { uris.forEach { last = ConnectionStore.importLinkedIn(ctx, it) } }
                importStatus = last.ifBlank { "Imported LinkedIn CSVs ✓" }
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(18.dp))
        Text("Welcome to SlyOS", fontSize = T.prompt, color = T.ink)
        Text("Step ${step + 1} of 3", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> {
                Text("Your Anthropic API key", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("This is the brain. Stored only on this phone, never shared. Each person uses their own key.",
                    fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(14.dp))
                field(key, { key = it }, "sk-ant-…", secret = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showKey) "Hide" else "Show", fontSize = T.caption, color = T.inkSoft,
                        modifier = Modifier.clickable { showKey = !showKey })
                    Spacer(Modifier.width(16.dp))
                    Text("Get a key →", fontSize = T.caption, color = T.accent, modifier = Modifier.clickable {
                        open(ctx, "https://console.anthropic.com/settings/keys") })
                }
            }
            1 -> {
                Text("About you", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("A few lines: your name, what you do, how you text. The more it knows, the more it sounds like you.",
                    fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(10.dp))
                field(about, { about = it }, "My name is … I run … I write casually, lots of …", lines = 5)
                Spacer(Modifier.height(14.dp))
                Text("Booking link (Calendly) — optional", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                field(booking, { booking = it }, "https://calendly.com/…")
                Spacer(Modifier.height(14.dp))
                Text("Zenodo token — optional, for publishing papers", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                field(zenodo, { zenodo = it }, "Zenodo personal access token", secret = true)
            }
            2 -> {
                Text("Bring in your data", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("Optional but powerful — this is what makes the brain really you. You can also do this later " +
                    "in Brain → About and Now → Reconnect.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(14.dp))
                Text("📥 Import chat history (learns your voice)", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { chatPicker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 10.dp))
                Spacer(Modifier.height(6.dp))
                Text("WhatsApp .txt · LinkedIn messages.csv · Instagram/Telegram .json (zips ok)",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(14.dp))
                Text("🔗 Import LinkedIn network (Connections.csv)", fontSize = T.small, color = T.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable { liPicker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 10.dp))
                if (importStatus.isNotBlank()) {
                    Spacer(Modifier.height(12.dp)); Text(importStatus, fontSize = T.caption, color = T.accent)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Text("Back", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { step-- }.padding(end = 16.dp))
            }
            val canNext = step != 0 || key.trim().startsWith("sk-")
            val label = if (step < 2) "Next" else "Finish"
            Text(label, fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (canNext) T.accent else T.hairline)
                    .clickable(enabled = canNext) {
                        // Persist as we go.
                        if (step == 0) { val k = key.trim(); MemoryStore.setAnthropicKey(ctx, k); AgentClient.apiKeyOverride = k }
                        if (step == 1) {
                            MemoryStore.setAbout(ctx, about.trim())
                            MemoryStore.setBookingLink(ctx, booking.trim())
                            AgentClient.bookingLink = MemoryStore.effectiveBookingLink(ctx)
                            if (zenodo.isNotBlank()) MemoryStore.setZenodoToken(ctx, zenodo.trim())
                        }
                        if (step < 2) step++ else onDone()
                    }.padding(horizontal = 22.dp, vertical = 11.dp))
            if (step in 1..2) {
                Text("Skip", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { if (step < 2) step++ else onDone() }.padding(start = 16.dp))
            }
        }

        if (step == 2) {
            Spacer(Modifier.height(20.dp))
            Text("After Finish: press Home → choose SlyOS → Always to make it your launcher. " +
                "Change anything later in Brain → About.", fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun open(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {}
}
