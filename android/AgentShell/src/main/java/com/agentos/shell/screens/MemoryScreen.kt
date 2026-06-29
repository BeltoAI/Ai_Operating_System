package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
    var nightAuto by remember { mutableStateOf(MemoryStore.nightAuto(ctx)) }
    var startH by remember { mutableStateOf(MemoryStore.autoStartHour(ctx)) }
    var endH by remember { mutableStateOf(MemoryStore.autoEndHour(ctx)) }
    var spicyDaily by remember { mutableStateOf(MemoryStore.spicyDaily(ctx)) }
    var kbName by remember { mutableStateOf(KnowledgeStore.name(ctx)) }
    var kbStatus by remember { mutableStateOf("") }
    var docTelegram by remember { mutableStateOf(MemoryStore.docTelegram(ctx)) }
    var tgBot by remember { mutableStateOf(MemoryStore.telegramBot(ctx)) }
    var recall by remember { mutableStateOf(MemoryStore.recallEnabled(ctx)) }
    var lockVoice by remember { mutableStateOf(MemoryStore.lockVoice(ctx)) }
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
                    com.agentos.shell.tools.AgentClient.bookingLink = MemoryStore.effectiveBookingLink(ctx)
                    saved = true
                }
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )

        Spacer(Modifier.height(18.dp))
        Text("Booking link (optional)", fontSize = T.body, color = T.ink)
        Text("Only shared if someone actually asks to schedule a call — never pushed.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var booking by remember { mutableStateOf(MemoryStore.bookingLink(ctx)) }
        BasicTextField(
            value = booking,
            onValueChange = {
                booking = it
                MemoryStore.setBookingLink(ctx, it)
                com.agentos.shell.tools.AgentClient.bookingLink = it.trim()
            },
            singleLine = true,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (booking.isEmpty()) Text("https://calendly.com/…", fontSize = T.small, color = T.inkFaint); inner() }
        )

        // ---- Your voice (learned from real chats) ----
        Spacer(Modifier.height(18.dp))
        Text("Your writing voice", fontSize = T.body, color = T.ink)
        Text("Import chat exports from any platform — WhatsApp (.txt), LinkedIn (messages.csv), " +
            "Instagram/Messenger (.json), Telegram (.json). Import as many as you like; SlyOS pools " +
            "them and learns exactly how you write, then mimics it everywhere.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var styleProfile by remember { mutableStateOf(MemoryStore.styleProfile(ctx)) }
        var voiceStatus by remember { mutableStateOf("") }
        val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                voiceStatus = "Reading ${uris.size} file(s) & learning your style…"
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
                    if (msgs == 0) { voiceStatus = "Couldn't read those. Use WhatsApp .txt, LinkedIn messages.csv, or IG/Telegram .json exports."; return@launch }
                    val pool = MemoryStore.voiceSamples(ctx)
                    if (pool.isEmpty()) {
                        voiceStatus = "Imported $msgs msgs / $chats chats, but couldn't tell which are yours — add 'My name is …' to About, then re-import."
                        return@launch
                    }
                    val profile = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.learnStyle(pool) }
                    if (profile.isNotBlank()) {
                        styleProfile = profile
                        MemoryStore.setStyleProfile(ctx, profile)
                        com.agentos.shell.tools.AgentClient.styleProfile = profile
                        voiceStatus = "Learned your voice from ${pool.size} of your messages ✓ (imported $chats chats, $msgs messages)"
                    } else voiceStatus = "Imported $chats chats, but couldn't summarize a style yet."
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Import chats / learn my voice", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { chatPicker.launch(arrayOf("text/plain", "text/csv", "application/json", "*/*")) }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
            if (styleProfile.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text("Clear", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clickable { styleProfile = ""; MemoryStore.clearVoice(ctx); com.agentos.shell.tools.AgentClient.styleProfile = "" })
            }
        }
        if (voiceStatus.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(voiceStatus, fontSize = T.caption, color = T.accent) }
        var dbCount by remember { mutableStateOf(com.agentos.shell.tools.MessageStore.count(ctx)) }
        var dbPeople by remember { mutableStateOf(com.agentos.shell.tools.MessageStore.topContacts(ctx, 100000).size) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧠 Memory DB: $dbCount messages · $dbPeople people", fontSize = T.caption, color = T.inkFaint)
            if (dbCount > 0) {
                Spacer(Modifier.width(12.dp))
                Text("Reset DB", fontSize = T.caption, color = T.danger,
                    modifier = Modifier.clickable { com.agentos.shell.tools.MessageStore.clear(ctx); dbCount = 0; dbPeople = 0 })
            }
        }
        if (styleProfile.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = styleProfile,
                onValueChange = { styleProfile = it; MemoryStore.setStyleProfile(ctx, it); com.agentos.shell.tools.AgentClient.styleProfile = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp)
            )
        }

        // ---- Per-platform persona ----
        Spacer(Modifier.height(18.dp))
        Text("Persona per platform", fontSize = T.body, color = T.ink)
        Text("How you want to come across on each app — e.g. LinkedIn: professional, warm CEO · Instagram: funny & casual.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        listOf("linkedin" to "LinkedIn", "instagram" to "Instagram", "x" to "X", "reddit" to "Reddit",
            "whatsapp" to "WhatsApp", "telegram" to "Telegram").forEach { (key, label) ->
            var v by remember { mutableStateOf(MemoryStore.styleFor(ctx, key)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(label, fontSize = T.small, color = T.inkSoft, modifier = Modifier.width(78.dp))
                BasicTextField(
                    value = v, onValueChange = { v = it; MemoryStore.setStyleFor(ctx, key, it) }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(T.bgElevated).padding(horizontal = 10.dp, vertical = 8.dp),
                    decorationBox = { inner -> if (v.isEmpty()) Text("tone for $label…", fontSize = T.small, color = T.inkFaint); inner() }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Default to full auto-send", fontSize = T.body, color = T.ink)
                Text(
                    "Sets the default for apps you haven't customized below: ON = auto-send, OFF = " +
                        "pre-draft & wait for your tap. Override any app individually under Per-app responses.",
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
                Text("Auto-reply on a schedule", fontSize = T.body, color = T.ink)
                Text(
                    "Forces auto-reply ON during the window below (e.g. overnight). Outside the " +
                        "window, the toggle above is the default.",
                    fontSize = T.small, color = T.inkFaint
                )
            }
            Switch(
                checked = nightAuto,
                onCheckedChange = { nightAuto = it; MemoryStore.setNightAuto(ctx, it) }
            )
        }
        if (nightAuto) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HourStepper("From", startH) { startH = it; MemoryStore.setAutoWindow(ctx, startH, endH) }
                Spacer(Modifier.width(16.dp))
                HourStepper("To", endH) { endH = it; MemoryStore.setAutoWindow(ctx, startH, endH) }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Connections", fontSize = T.body, color = T.ink)
        var gConnected by remember { mutableStateOf(com.agentos.shell.tools.GoogleAuth.isConnected(ctx)) }
        val gAccount = com.agentos.shell.tools.GoogleAuth.account(ctx)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Google Calendar & Meet", fontSize = T.body, color = T.ink)
                Text(
                    when {
                        gConnected -> "Connected" + (if (gAccount.isNotBlank()) " · $gAccount" else "") +
                            " — meetings get a real Meet link and email invites."
                        com.agentos.shell.tools.GoogleAuth.configured() ->
                            "Sign in with your Google account to auto-create Google Meet links and send calendar invites. One tap."
                        else -> "Not available in this build yet."
                    },
                    fontSize = T.small, color = T.inkFaint
                )
            }
            if (gConnected) {
                Text("Disconnect", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clickable { com.agentos.shell.tools.GoogleAuth.disconnect(ctx); gConnected = false }
                        .padding(8.dp))
            } else if (com.agentos.shell.tools.GoogleAuth.configured()) {
                Text("Connect", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { com.agentos.shell.tools.GoogleAuth.connect(ctx) }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Per-app responses", fontSize = T.body, color = T.ink)
        Text("Pick how each app behaves. Draft pre-writes a reply and waits on the Now screen so you " +
            "just tap Send. Auto sends it for you after an 8-second undo window.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        val apps = remember { com.agentos.shell.tools.AppScanner.installed(ctx) }
        if (apps.isEmpty()) {
            Text("No messaging or social apps detected yet. They'll appear here once installed " +
                "or after their first message.", fontSize = T.small, color = T.inkFaint)
        } else {
            val modeMap = remember {
                mutableStateMapOf<String, String>().apply {
                    apps.forEach { put(it.pkg, MemoryStore.appMode(ctx, it.pkg)) }
                }
            }
            apps.forEach { app ->
                val icon = remember(app.pkg) {
                    com.agentos.shell.tools.AppScanner.icon(ctx, app.pkg)?.asImageBitmap()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = app.label,
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                    } else {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(T.hairline),
                            contentAlignment = Alignment.Center
                        ) { Text(app.label.take(1).uppercase(), fontSize = T.small, color = T.inkSoft) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                    val cur = modeMap[app.pkg] ?: "draft"
                    Row(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline).padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("off" to "Off", "draft" to "Draft", "full" to "Auto").forEach { (id, label) ->
                            val sel = cur == id
                            Text(label, fontSize = T.caption,
                                color = if (sel) T.bgElevated else T.inkSoft,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                    .background(if (sel) T.accent else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { modeMap[app.pkg] = id; MemoryStore.setAppMode(ctx, app.pkg, id) }
                                    .padding(horizontal = 11.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        var reconnect by remember { mutableStateOf(MemoryStore.reconnectWeekly(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weekly reconnect nudge", fontSize = T.body, color = T.ink)
                Text("Each Monday, surfaces a few people you've gone quiet on (>1 week) with a message ready to send.",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(checked = reconnect, onCheckedChange = {
                reconnect = it; MemoryStore.setReconnectWeekly(ctx, it)
                com.agentos.shell.ReconnectScheduler.set(ctx, it)
            })
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

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Telegram bot brain", fontSize = T.body, color = T.ink)
                Text("Runs your Telegram bot: reads images & PDFs people send, answers from your " +
                    "document, replies as you. Needs TELEGRAM_BOT_TOKEN in apikey.properties.",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(checked = tgBot, onCheckedChange = {
                tgBot = it; MemoryStore.setTelegramBot(ctx, it)
                if (it) com.agentos.shell.TelegramService.start(ctx)
                else com.agentos.shell.TelegramService.stop(ctx)
            })
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Total recall", fontSize = T.body, color = T.ink)
                Text("Reads on-screen text across your apps into a private, searchable memory so the " +
                    "agent can recall what you saw and said. Stays on your phone. Needs Accessibility " +
                    "access in Settings (passwords are never captured).",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(checked = recall, onCheckedChange = {
                recall = it; MemoryStore.setRecallEnabled(ctx, it)
            })
        }
        if (recall) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Grant Accessibility", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable {
                            try { ctx.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) {}
                        }.padding(horizontal = 16.dp, vertical = 9.dp))
                Spacer(Modifier.width(12.dp))
                val n = com.agentos.shell.tools.InteractionStore.count(ctx)
                if (n > 0) Text("Clear ($n)", fontSize = T.small, color = T.danger,
                    modifier = Modifier.clickable { com.agentos.shell.tools.InteractionStore.clear(ctx) })
            }
            Spacer(Modifier.height(4.dp))
            Text("Find SlyOS under Settings ▸ Accessibility ▸ Installed apps and turn it on.",
                fontSize = T.caption, color = T.inkFaint)
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lock-screen voice button", fontSize = T.body, color = T.ink)
                Text("Keeps a “Speak to SlyOS” shortcut on your lock screen — tap it to talk to the " +
                    "agent (your phone may ask you to unlock first).",
                    fontSize = T.small, color = T.inkFaint)
            }
            Switch(checked = lockVoice, onCheckedChange = {
                lockVoice = it; MemoryStore.setLockVoice(ctx, it)
                if (it) com.agentos.shell.tools.VoiceShortcut.post(ctx)
                else com.agentos.shell.tools.VoiceShortcut.cancel(ctx)
            })
        }

        Spacer(Modifier.height(20.dp))
        Text("Lock screen", fontSize = T.body, color = T.ink)
        Text("Set a SlyOS-styled lock-screen wallpaper (the clock/widgets stay Samsung's).",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var wpStatus by remember { mutableStateOf("") }
        Text(if (wpStatus.isEmpty()) "Set SlyOS lock screen" else wpStatus,
            fontSize = T.small, color = T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                .clickable {
                    wpStatus = if (com.agentos.shell.tools.WallpaperTool.setLockScreen(ctx))
                        "Lock screen updated ✓" else "Couldn't set wallpaper"
                }.padding(horizontal = 18.dp, vertical = 10.dp))

        Spacer(Modifier.height(20.dp))
        Text(
            "The agent reads this on every request. Nothing here leaves your phone except as " +
                "part of a prompt you trigger.",
            fontSize = T.caption, color = T.inkFaint
        )
    }
}

/** 12-hour label for a 0–23 hour, e.g. 20 -> "8 PM", 6 -> "6 AM", 0 -> "12 AM". */
private fun hourLabel(h: Int): String {
    val hr = ((h % 24) + 24) % 24
    val ampm = if (hr < 12) "AM" else "PM"
    val twelve = when (hr % 12) { 0 -> 12; else -> hr % 12 }
    return "$twelve $ampm"
}

/** Compact −/+ stepper for picking an hour of the day. */
@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.width(8.dp))
        Text("–", fontSize = T.body, color = T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                .clickable { onChange(((hour - 1) % 24 + 24) % 24) }
                .padding(horizontal = 12.dp, vertical = 4.dp))
        Text(hourLabel(hour), fontSize = T.body, color = T.ink,
            modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 10.dp))
        Text("+", fontSize = T.body, color = T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                .clickable { onChange((hour + 1) % 24) }
                .padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
