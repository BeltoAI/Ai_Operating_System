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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.KeyProbe
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dead-simple first-run. Four screens, giant buttons, plain words, nothing that blocks a non-technical person:
 *  1) Power your brain — get a FREE key in ~60s (deep-link → they copy it → we grab it off the clipboard and
 *     live-check it), OR just "Start free" on the built-in demo. Never gated.
 *  2) Make it yours — one toggle (notification access) and the brain learns from your messages going forward.
 *  3) Turn it on — batch the phone permissions in one tap.
 *  4) Make SlyOS your home.
 * Power-user stuff (extra providers, Zenodo, AudD, on-device model) lives in Brain → Settings, not here.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    val totalSteps = 4

    // ---- Step 0: brain / free key ----
    var keyStatus by remember { mutableStateOf("") }
    var keyOk by remember { mutableStateOf(MemoryStore.anyProviderKey(ctx) && (MemoryStore.geminiKey(ctx).isNotBlank() || MemoryStore.openaiKey(ctx).isNotBlank() || MemoryStore.anthropicKey(ctx).isNotBlank())) }
    var checking by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualKey by remember { mutableStateOf("") }

    val supported = listOf("gemini", "openai", "anthropic")
    fun saveKey(provider: String, key: String) {
        when (provider) {
            "gemini" -> MemoryStore.setGeminiKey(ctx, key)
            "openai" -> MemoryStore.setOpenaiKey(ctx, key)
            "anthropic" -> { MemoryStore.setAnthropicKey(ctx, key); AgentClient.apiKeyOverride = key }
        }
        MemoryStore.setPreferredProvider(ctx, provider)
    }
    fun handle(d: KeyProbe.Detected) {
        if (d.provider !in supported) {
            keyStatus = "That looks like a valid ${d.provider} key — but SlyOS currently runs on Gemini, OpenAI or Claude. " +
                "Grab a FREE Gemini key with the button above (it takes a minute)."
            keyOk = false; return
        }
        checking = true; keyStatus = "Checking your key…"
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { KeyProbe.validate(d.provider, d.key) }
            checking = false; keyStatus = msg; keyOk = ok
            if (ok) saveKey(d.provider, d.key)
        }
    }
    fun connectFromClipboard() {
        val d = KeyProbe.detectFromClipboard(ctx)
        if (d == null) {
            keyStatus = "Couldn't find a key on your clipboard. On the page, tap your key to copy it, then come back and tap Connect."
            return
        }
        handle(d)
    }
    fun connectManual() { handle(KeyProbe.detect(manualKey) ?: KeyProbe.Detected("gemini", manualKey.trim())) }

    // ---- Data import (optional, still available) ----
    var importStatus by remember { mutableStateOf("") }
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
                } else importStatus = "Couldn't read those. WhatsApp: open a chat → Export chat → share to SlyOS."
            }
        }
    }
    val liPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var last = ""
                withContext(Dispatchers.IO) { uris.forEach { last = ConnectionStore.importLinkedIn(ctx, it) } }
                importStatus = last.ifBlank { "Imported LinkedIn CSVs ✓" }
            }
        }
    }

    // ---- Permissions ----
    var permsStatus by remember { mutableStateOf("") }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val g = res.values.count { it }; permsStatus = "Turned on $g of ${res.size} ✓"
    }
    fun askPerms() {
        val want = mutableListOf(
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) want.add("android.permission.POST_NOTIFICATIONS")
        perms.launch(want.toTypedArray())
    }
    fun openNotif() { try { ctx.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }

    @Composable
    fun bigButton(label: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, fontSize = T.small, textAlign = TextAlign.Center,
            color = if (primary) Color.White else T.ink, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (!enabled) T.hairline else if (primary) T.accent else T.bgElevated)
                .clickable(enabled = enabled) { onClick() }.padding(vertical = 14.dp))
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("Welcome to SlyOS", fontSize = T.prompt, color = T.ink)
        Text("Step ${step + 1} of $totalSteps", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(16.dp))

        when (step) {
            // ---------- 0: BRAIN ----------
            0 -> {
                Text("Power your brain", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("SlyOS thinks using an AI brain. Get one FREE in about a minute — no credit card, nothing technical. " +
                    "Or just start on the built-in demo and add a key later.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(16.dp))

                bigButton("Get my free brain  ·  60 sec") {
                    keyStatus = "1) Sign in with Google  2) Tap “Create API key”  3) Tap the key to copy it  4) Come back and tap Connect below."
                    open(ctx, "https://aistudio.google.com/app/apikey")
                }
                Spacer(Modifier.height(10.dp))
                bigButton(if (checking) "Checking…" else "I copied my key — Connect", primary = false, enabled = !checking) { connectFromClipboard() }

                if (keyStatus.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(keyStatus, fontSize = T.caption, color = if (keyOk) T.accent else T.inkSoft)
                }
                if (keyOk) {
                    Spacer(Modifier.height(8.dp)); Text("Your brain is connected ✓", fontSize = T.small, color = T.accent, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(16.dp))
                Text(if (showManual) "Hide manual paste" else "Paste a key manually", fontSize = T.caption, color = T.inkSoft,
                    modifier = Modifier.clickable { showManual = !showManual })
                if (showManual) {
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(value = manualKey, onValueChange = { manualKey = it }, singleLine = true,
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                        decorationBox = { inner -> if (manualKey.isEmpty()) Text("AIza…  /  sk-…  /  gsk_…", fontSize = T.small, color = T.inkFaint); inner() })
                    Spacer(Modifier.height(8.dp))
                    bigButton("Check & connect", primary = false, enabled = !checking && manualKey.trim().length > 12) { connectManual() }
                }

                Spacer(Modifier.height(18.dp))
                Text("Works with any free key — Gemini, Groq, OpenRouter — or a paid Claude/OpenAI key. Stored only on this phone.",
                    fontSize = T.caption, color = T.inkFaint)
            }
            // ---------- 1: MAKE IT YOURS ----------
            1 -> {
                Text("Make it yours", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("SlyOS gets smarter the more it knows you. Turn on message-reading and it quietly learns your world " +
                    "from new messages — no exports, no files. You can turn it off anytime.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(16.dp))

                bigButton("Let SlyOS learn from my messages") { openNotif() }
                Spacer(Modifier.height(6.dp))
                Text("Opens Settings → switch on SlyOS under “Notification access.” That’s what lets it understand and " +
                    "reply across WhatsApp, Telegram, SMS and more.", fontSize = T.caption, color = T.inkFaint)

                Spacer(Modifier.height(20.dp))
                Text("Have old chats to import? (optional)", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(8.dp))
                bigButton("Import chat history", primary = false) { chatPicker.launch(arrayOf("*/*")) }
                Spacer(Modifier.height(8.dp))
                bigButton("Import LinkedIn (Connections.csv)", primary = false) { liPicker.launch(arrayOf("*/*")) }
                Spacer(Modifier.height(6.dp))
                Text("WhatsApp: open a chat → Export chat → share to SlyOS. LinkedIn/Instagram: request your data export, " +
                    "then share the zip here when it arrives.", fontSize = T.caption, color = T.inkFaint)
                if (importStatus.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(importStatus, fontSize = T.caption, color = T.accent) }
            }
            // ---------- 2: TURN IT ON ----------
            2 -> {
                Text("Turn it on", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("A few phone permissions so SlyOS can actually help — check your calendar, know your contacts, " +
                    "text people, and hear you. All revocable anytime.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(16.dp))
                bigButton("Allow the essentials") { askPerms() }
                Spacer(Modifier.height(6.dp))
                Text("Calendar · Contacts · Messages · Microphone · Notifications", fontSize = T.caption, color = T.inkFaint)
                if (permsStatus.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(permsStatus, fontSize = T.caption, color = T.accent) }
            }
            // ---------- 3: HOME ----------
            3 -> {
                Text("Make SlyOS your home", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("SlyOS replaces your home screen — it’s your phone now, not an app you reopen. Tap below and pick " +
                    "SlyOS → Always.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(16.dp))
                bigButton("Set SlyOS as my home screen") {
                    try {
                        val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                            (ctx as? android.app.Activity)?.startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                        } else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                    } catch (e: Exception) {
                        try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) } catch (e2: Exception) {}
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("If it asks again after Finish: press Home → SlyOS → Always. Everything else lives in Brain → Settings.",
                    fontSize = T.caption, color = T.inkFaint)
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Text("Back", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { step-- }.padding(end = 20.dp))
            }
            val label = if (step < totalSteps - 1) "Next" else "Finish"
            Text(label, fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { if (step < totalSteps - 1) step++ else onDone() }
                    .padding(horizontal = 26.dp, vertical = 12.dp))
            // Everything is skippable — nothing here can trap a user.
            if (step < totalSteps - 1) {
                Text(if (step == 0) "Start free instead" else "Skip", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { step++ }.padding(start = 20.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun open(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {}
}
