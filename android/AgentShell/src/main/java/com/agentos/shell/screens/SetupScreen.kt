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

    // Provider choice — Gemini default because it has a free tier (no paywall to start).
    var provider by remember { mutableStateOf(MemoryStore.preferredProvider(ctx).ifBlank { "gemini" }) }
    var keyAnthropic by remember { mutableStateOf(MemoryStore.anthropicKey(ctx)) }
    var keyOpenai by remember { mutableStateOf(MemoryStore.openaiKey(ctx)) }
    var keyGemini by remember { mutableStateOf(MemoryStore.geminiKey(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    // label, key hint, where to get one
    val provMeta = mapOf(
        "gemini" to Triple("Gemini · free", "AIza… (free tier)", "https://aistudio.google.com/app/apikey"),
        "anthropic" to Triple("Claude", "sk-ant-…", "https://console.anthropic.com/settings/keys"),
        "openai" to Triple("OpenAI", "sk-…", "https://platform.openai.com/api-keys")
    )
    var about by remember { mutableStateOf(MemoryStore.about(ctx)) }
    var booking by remember { mutableStateOf(MemoryStore.bookingLink(ctx)) }
    var zenodo by remember { mutableStateOf(MemoryStore.zenodoToken(ctx)) }
    var importStatus by remember { mutableStateOf("") }
    // On-device model pick (optional). Selecting one enables it; the actual download + one-tap safety test
    // happen later in Brain → Settings → On-device model, so onboarding never blocks on a big download.
    val LL = com.agentos.shell.tools.LocalLlm
    var localPick by remember { mutableStateOf(LL.selectedId(ctx)) }
    val ramGb = remember { LL.deviceRamGb(ctx) }

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

    // Runtime permissions that make the agent actually work (calendar, contacts, SMS, mic, camera, notif).
    var permsStatus by remember { mutableStateOf("") }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val g = res.values.count { it }; permsStatus = "Granted $g of ${res.size} ✓"
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
    fun openAccessibility() { try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(18.dp))
        Text("Welcome to SlyOS", fontSize = T.prompt, color = T.ink)
        Text("Step ${step + 1} of 5", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> {
                Text("Pick your brain", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("SlyOS runs on your own model key — stored only on this phone. Gemini has a free tier, " +
                    "so you can start free and switch anytime.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(14.dp))
                Row {
                    listOf("gemini", "anthropic", "openai").forEach { p ->
                        val sel = provider == p
                        Text(provMeta[p]!!.first, fontSize = T.caption,
                            color = if (sel) T.bgElevated else T.inkSoft,
                            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                                .background(if (sel) T.accent else T.hairline)
                                .clickable { provider = p }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                val curKey = when (provider) { "anthropic" -> keyAnthropic; "openai" -> keyOpenai; else -> keyGemini }
                field(curKey, { v -> when (provider) { "anthropic" -> keyAnthropic = v; "openai" -> keyOpenai = v; else -> keyGemini = v } },
                    provMeta[provider]!!.second, secret = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showKey) "Hide" else "Show", fontSize = T.caption, color = T.inkSoft,
                        modifier = Modifier.clickable { showKey = !showKey })
                    Spacer(Modifier.width(16.dp))
                    Text("Get a ${provMeta[provider]!!.first.substringBefore(" ·")} key →", fontSize = T.caption,
                        color = T.accent, modifier = Modifier.clickable { open(ctx, provMeta[provider]!!.third) })
                }
                Spacer(Modifier.height(10.dp))
                Text("Add the other providers later in Brain → settings to orchestrate — a cheap model for everyday " +
                    "replies, a powerful one for papers. SlyOS predicts your monthly cost as you go.",
                    fontSize = T.caption, color = T.inkFaint)
                // P2.3: memory recall-by-meaning runs on Gemini's free embeddings. Tell Claude/OpenAI-first
                // users so they don't end up with a silently dead semantic brain.
                if (provider != "gemini" && keyGemini.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Tip: recall-by-meaning (\"what did I say about the deal?\") is powered by Gemini's FREE " +
                        "embeddings. Add a free Gemini key too — even on Claude — or semantic memory stays off.",
                        fontSize = T.caption, color = T.accent)
                }
            }
            1 -> {
                Text("Add a free offline backup brain?", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("Optional. SlyOS can keep a small AI on your phone as an OFFLINE BACKUP. When you have internet " +
                    "it always uses your fast cloud model; this one only steps in when you're offline or no cloud key " +
                    "works — so your phone stays cool day to day. On-device models are slower, can't search the web or " +
                    "read images, and give simpler answers, so it's a safety net, not the main brain. Your phone has " +
                    "${"%.0f".format(ramGb)} GB of memory.",
                    fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(14.dp))
                LL.MODELS.forEach { m ->
                    val v = LL.canRun(ctx, m)
                    val sel = localPick == m.id
                    val vColor = when (v.fit) { com.agentos.shell.tools.LocalLlm.Fit.NO, com.agentos.shell.tools.LocalLlm.Fit.RISKY -> T.danger; else -> T.inkSoft }
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) T.accent else T.bgElevated)
                        .clickable { localPick = if (sel) "" else m.id }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontSize = T.small, color = if (sel) T.bgElevated else T.ink,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${m.fileMb} MB", fontSize = T.caption, color = if (sel) T.bgElevated else T.inkFaint)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(m.note, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkFaint)
                        Spacer(Modifier.height(3.dp))
                        Text(v.plain, fontSize = T.caption, color = if (sel) T.bgElevated else vColor)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(if (localPick.isBlank()) "None selected — you can add one later in Brain → Settings."
                    else "After setup, open Brain → Settings → On-device model to download it and run a one-tap test. " +
                        "It stays as an offline backup and won't run (or heat your phone) while your cloud model is reachable.",
                    fontSize = T.caption, color = T.accent)
            }
            2 -> {
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
            3 -> {
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
            4 -> {
                Text("Turn it on", fontSize = T.body, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("A few Android permissions so the agent can actually read & reply to messages, check your " +
                    "calendar, and text people. All revocable in Settings anytime.", fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(14.dp))
                Text("Grant core permissions", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { askPerms() }.padding(horizontal = 16.dp, vertical = 10.dp))
                Spacer(Modifier.height(6.dp))
                Text("Calendar · Contacts · SMS · Microphone · Camera · Notifications",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(16.dp))
                Text("Enable message reading →", fontSize = T.small, color = T.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                        .clickable { openNotif() }.padding(horizontal = 16.dp, vertical = 10.dp))
                Spacer(Modifier.height(6.dp))
                Text("Opens Settings → turn on SlyOS under Notification access. This is what lets it reply across " +
                    "WhatsApp, Telegram, SMS and the rest.", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(16.dp))
                Text("Screen memory (Total Recall) — optional →", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { openAccessibility() })
                if (permsStatus.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(permsStatus, fontSize = T.caption, color = T.accent) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Text("Back", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { step-- }.padding(end = 16.dp))
            }
            val curKey0 = when (provider) { "anthropic" -> keyAnthropic; "openai" -> keyOpenai; else -> keyGemini }
            val canNext = step != 0 || curKey0.trim().length > 8
            val label = if (step < 4) "Next" else "Finish"
            Text(label, fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (canNext) T.accent else T.hairline)
                    .clickable(enabled = canNext) {
                        // Persist as we go.
                        if (step == 0) {
                            MemoryStore.setAnthropicKey(ctx, keyAnthropic.trim())
                            MemoryStore.setOpenaiKey(ctx, keyOpenai.trim())
                            MemoryStore.setGeminiKey(ctx, keyGemini.trim())
                            MemoryStore.setPreferredProvider(ctx, provider)
                            AgentClient.apiKeyOverride = keyAnthropic.trim()
                        }
                        if (step == 1) {
                            // Remember the chosen on-device model + enable it. It stays UNVERIFIED until the
                            // user runs the test in Settings, so it won't be used for prompts (or crash) yet.
                            if (localPick.isNotBlank()) { LL.setSelectedId(ctx, localPick); LL.setEnabled(ctx, true) }
                            else LL.setEnabled(ctx, false)
                        }
                        if (step == 2) {
                            MemoryStore.setAbout(ctx, about.trim())
                            MemoryStore.setBookingLink(ctx, booking.trim())
                            AgentClient.bookingLink = MemoryStore.effectiveBookingLink(ctx)
                            if (zenodo.isNotBlank()) MemoryStore.setZenodoToken(ctx, zenodo.trim())
                        }
                        if (step < 4) step++ else onDone()
                    }.padding(horizontal = 22.dp, vertical = 11.dp))
            if (step in 1..3) {
                Text("Skip", fontSize = T.small, color = T.inkFaint,
                    modifier = Modifier.clickable { step++ }.padding(start = 16.dp))
            }
        }

        if (step == 4) {
            Spacer(Modifier.height(20.dp))
            Text("Make SlyOS your phone", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
            Text("SlyOS REPLACES your home screen — it's your launcher, not an app you reopen each time. " +
                "Tap below and choose SlyOS → Always, so it's what you see every time you press Home.",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            Text("Set SlyOS as my Home app", fontSize = T.small, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable {
                        try {
                            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                                (ctx as? android.app.Activity)?.startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                            } else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                        } catch (e: Exception) {
                            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) } catch (e2: Exception) {}
                        }
                    }.padding(vertical = 11.dp))
            Spacer(Modifier.height(6.dp))
            Text("If it asks again after Finish: press Home → SlyOS → Always. Change anything later in Brain → About.",
                fontSize = T.caption, color = T.inkFaint)
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
