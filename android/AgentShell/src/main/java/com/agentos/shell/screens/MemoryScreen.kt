package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.KnowledgeStore
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A clear section break: a rule + a bold accent heading, so Settings reads as distinct groups
 *  instead of one endless scroll. */
@Composable
private fun SectionTitle(t: String) {
    Spacer(Modifier.height(22.dp))
    androidx.compose.material3.Divider(color = T.hairline, thickness = 1.dp)
    Spacer(Modifier.height(16.dp))
    Text(t, fontSize = T.body, color = T.accent, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
}

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
    var importStatus by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var voiceBusy by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var sampleCount by remember { mutableStateOf(MemoryStore.voiceSamples(ctx).size) }
    val chatImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importStatus = "Reading ${uris.size} file(s) & adding to your brain…"
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
                sampleCount = MemoryStore.voiceSamples(ctx).size
                importStatus = when {
                    msgs > 0 -> "Added $chats chats / $msgs messages to your brain ✓ · $sampleCount voice samples. Tap “Learn my voice” below."
                    chats > 0 -> "Already in your brain — those messages were imported before, so nothing new was added."
                    else -> "Couldn't read those. Use WhatsApp .txt, LinkedIn/Instagram/Telegram exports (zips ok)."
                }
            }
        }
    }
    val liImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importStatus = "Importing LinkedIn network…"
            scope.launch {
                var last = ""
                withContext(Dispatchers.IO) { uris.forEach { last = com.agentos.shell.tools.ConnectionStore.importLinkedIn(ctx, it) } }
                importStatus = last.ifBlank { "Imported LinkedIn connections ✓" }
            }
        }
    }
    var brainMsg by remember { mutableStateOf("") }
    val anyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            brainMsg = "Reading ${uris.size} file(s) into your brain…"
            scope.launch { val msgs = withContext(Dispatchers.IO) { uris.map { com.agentos.shell.tools.BrainData.ingestFile(ctx, it) } }; brainMsg = msgs.joinToString(" · ") }
        }
    }
    val brainImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { brainMsg = "Importing brain…"; scope.launch { brainMsg = withContext(Dispatchers.IO) { com.agentos.shell.tools.BrainData.importBrain(ctx, uri) } } }
    }
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

        // ---- You: real-world details for shopping, forms, signups, letterheads ----
        SectionTitle("You — for shopping, forms & signatures")
        Text("Saved only on this device. Used to pre-fill checkout, sign-ups and documents — never sent unless you tap send.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var pfName by remember { mutableStateOf(MemoryStore.profileName(ctx)) }
        var pfEmail by remember { mutableStateOf(MemoryStore.profileEmail(ctx)) }
        var pfPhone by remember { mutableStateOf(MemoryStore.profilePhone(ctx)) }
        var pfAddr by remember { mutableStateOf(MemoryStore.profileAddress(ctx)) }
        var headshot by remember { mutableStateOf(MemoryStore.headshotPath(ctx)) }
        val headshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                if (bytes != null && MemoryStore.saveHeadshot(ctx, bytes)) headshot = MemoryStore.headshotPath(ctx)
            }
        }
        @Composable
        fun pField(value: String, hint: String, multiline: Boolean, onChange: (String) -> Unit) {
            BasicTextField(value, onChange, singleLine = !multiline, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 48.dp else 0.dp).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                decorationBox = { inner -> if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.height(8.dp))
        }
        pField(pfName, "Full name", false) { pfName = it; MemoryStore.setProfileName(ctx, it) }
        pField(pfEmail, "Email", false) { pfEmail = it; MemoryStore.setProfileEmail(ctx, it) }
        pField(pfPhone, "Phone", false) { pfPhone = it; MemoryStore.setProfilePhone(ctx, it) }
        pField(pfAddr, "Shipping address", true) { pfAddr = it; MemoryStore.setProfileAddress(ctx, it) }
        Text(if (headshot.isNotBlank()) "Headshot saved ✓ · replace" else "Add a headshot", fontSize = T.small, color = T.accent,
            modifier = Modifier.clickable { headshotPicker.launch("image/*") }.padding(vertical = 6.dp))
        // When you leave Settings, write your profile into the searchable brain (idempotent, off-thread).
        DisposableEffect(Unit) { onDispose { Thread { MemoryStore.syncProfileToBrain(ctx) }.start() } }
        Spacer(Modifier.height(16.dp))

        // ---- Appearance ----
        SectionTitle("Appearance")
        var darkOn by remember { mutableStateOf(com.agentos.shell.tools.MemoryStore.darkMode(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clickable {
                darkOn = !darkOn
                com.agentos.shell.tools.MemoryStore.setDarkMode(ctx, darkOn)
                com.agentos.shell.theme.T.dark = darkOn
                // Keep the SlyOS lock-screen wallpaper in sync with the theme if it's been applied.
                if (com.agentos.shell.tools.WallpaperTool.isSet(ctx))
                    Thread { com.agentos.shell.tools.WallpaperTool.setLockScreen(ctx) }.start()
            }
            .padding(vertical = 6.dp)) {
            Text(if (darkOn) "Dark mode · on" else "Dark mode · off", fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
            Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(999.dp)).background(if (darkOn) T.accent else T.hairline)) {
                Box(Modifier.align(if (darkOn) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(20.dp).clip(CircleShape).background(T.bgElevated))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Live stock data (optional) ----
        SectionTitle("Market data (optional)")
        Text("Stocks not updating in Invest? Paste a free Finnhub key (finnhub.io) for reliable real-time quotes. Crypto works without one.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var finnhub by remember { mutableStateOf(MemoryStore.finnhubKey(ctx)) }
        BasicTextField(finnhub, { finnhub = it; MemoryStore.setFinnhubKey(ctx, it); com.agentos.shell.tools.QuoteClient.finnhubKey = it.trim() },
            singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (finnhub.isEmpty()) Text("Finnhub API key", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(12.dp))
        // Hands-off investing: let the AI execute practice buy/sell moves itself (else it just proposes them).
        var handsOff by remember { mutableStateOf(MemoryStore.autoTrade(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clickable { handsOff = !handsOff; MemoryStore.setAutoTrade(ctx, handsOff) }.padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Hands-off investing", fontSize = T.small, color = T.ink)
                Text(if (handsOff) "AI executes practice buy/sell moves on its own (logged + reversible)."
                     else "AI proposes moves to your Now feed for one-tap confirm (default).",
                    fontSize = T.caption, color = T.inkFaint)
            }
            Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(999.dp)).background(if (handsOff) T.accent else T.hairline)) {
                Box(Modifier.align(if (handsOff) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(20.dp).clip(CircleShape).background(T.bgElevated))
            }
        }
        Spacer(Modifier.height(16.dp))

        SectionTitle("Booking link")
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

        // ---- Agent calls (P6) ----
        SectionTitle("Talk to your agent")
        Text("FREE: hold the brain on the Home bar to have a live, hands-free voice conversation with your " +
            "agent — it uses your phone's built-in voice (generic) and can search the web, recall your brain, " +
            "and act. No cost beyond your model usage.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        Text("Optional paid add-on — cloned voice (bring your own ElevenLabs key): paste your ElevenLabs API " +
            "key + a voice ID and calls speak in THAT voice instead of the generic one. Never shipped by SlyOS; " +
            "left blank, you stay on the free generic voice.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        var elevenKey by remember { mutableStateOf(MemoryStore.elevenKey(ctx)) }
        var elevenVoice by remember { mutableStateOf(MemoryStore.elevenVoiceId(ctx)) }
        BasicTextField(elevenKey, { elevenKey = it; MemoryStore.setElevenKey(ctx, it) }, singleLine = true,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (elevenKey.isEmpty()) Text("ElevenLabs API key (optional)", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(8.dp))
        BasicTextField(elevenVoice, { elevenVoice = it; MemoryStore.setElevenVoiceId(ctx, it) }, singleLine = true,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner -> if (elevenVoice.isEmpty()) Text("ElevenLabs voice ID (optional)", fontSize = T.small, color = T.inkFaint); inner() })
        Text("Receiving real phone calls to a number (Twilio/SIP) is a separate paid add-on on the roadmap — " +
            "SlyOS will never claim to answer your carrier calls in a cloned voice for free.",
            fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 8.dp))

        // ---- Your voice (learned from real chats) ----
        SectionTitle("Your writing voice")
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
                    if (msgs == 0 && chats == 0) { voiceStatus = "Couldn't read those. Use WhatsApp .txt, LinkedIn messages.csv, or IG/Telegram .json exports."; return@launch }
                    // msgs==0 but chats>0 means already-imported — still (re)learn voice from the pool below.
                    val pool = MemoryStore.voiceSamples(ctx)
                    if (pool.isEmpty()) {
                        voiceStatus = "Imported $msgs msgs / $chats chats, but couldn't tell which are yours — add 'My name is …' to About, then re-import."
                        return@launch
                    }
                    val profile = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.learnStyle(pool) }
                    if (!com.agentos.shell.tools.AgentClient.looksLikeError(profile)) {
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
        var dbPeople by remember { mutableStateOf(com.agentos.shell.tools.MessageStore.peopleCount(ctx)) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Memory DB: $dbCount messages · $dbPeople people", fontSize = T.caption, color = T.inkFaint)
            if (dbCount > 0) {
                Spacer(Modifier.width(12.dp))
                Text("Reset DB", fontSize = T.caption, color = T.danger,
                    modifier = Modifier.clickable { com.agentos.shell.tools.MessageStore.clear(ctx); com.agentos.shell.tools.VectorStore.clear(ctx); dbCount = 0; dbPeople = 0 })
            }
        }
        // P2.3: recall-by-meaning runs on Gemini embeddings (free). If no embedding provider is set, say
        // so out loud instead of silently returning nothing — and point the user to the free fix.
        run {
            val embedOn = com.agentos.shell.tools.EmbeddingClient.provider(ctx) != null
            Spacer(Modifier.height(4.dp))
            if (embedOn) {
                val emb = com.agentos.shell.tools.VectorStore.embeddedCount(ctx)
                val pend = com.agentos.shell.tools.VectorStore.pendingCount(ctx)
                Text("Semantic memory: on · $emb indexed" + (if (pend > 0) " · $pend queued" else ""),
                    fontSize = T.caption, color = T.inkFaint)
            } else {
                Text("Semantic memory: OFF — recall-by-meaning needs a free Gemini key. Add one under Models below to turn it on.",
                    fontSize = T.caption, color = T.danger)
            }
        }
        // Brain compute: how much the models have thought, and how efficiently.
        run {
            val CS = com.agentos.shell.tools.CostStore
            val life = CS.lifetimeTokens(ctx)
            if (life > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Brain compute: ${CS.fmtTokens(life)} tokens thought · ${CS.fmtTokens(CS.lifetimeGenerated(ctx))} generated · ~${CS.avgTokensPerResponse(ctx)} tokens per response",
                    fontSize = T.caption, color = T.inkFaint)
                val byProv = CS.tokensByProvider(ctx)
                if (byProv.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("by model: " + byProv.entries.joinToString(" · ") { "${it.key} ${CS.fmtTokens(it.value)}" }, fontSize = T.caption, color = T.inkFaint)
                }
            }
        }
        // ---- Efficiency (moved here from the Home panel) ----
        run {
            val M = com.agentos.shell.tools.MetricsStore
            val score = M.efficiencyScore(ctx)
            val trend = M.trendPct(ctx)
            val hist = M.history(ctx, 14)
            val weekMin = hist.takeLast(7).sumOf { it.savedMin }
            Spacer(Modifier.height(16.dp))
            SectionTitle("Efficiency")
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$score", fontSize = 40.sp, color = T.accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("/100", fontSize = T.body, color = T.inkFaint, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                val up = trend >= 0
                Text((if (up) "▲ +" else "▼ ") + "$trend%", fontSize = T.body,
                    color = if (up) Color(0xFF4E9A5B) else T.danger, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("vs last week · ~${if (weekMin >= 60) "${weekMin / 60}h ${weekMin % 60}m" else "$weekMin min"} saved this week",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            val maxV = (hist.maxOfOrNull { it.savedMin } ?: 0).coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val n = hist.size; val gap = 6f
                val bw = (size.width - gap * (n - 1)) / n
                hist.forEachIndexed { i, d ->
                    val h = (d.savedMin.toFloat() / maxV) * (size.height - 6f)
                    drawRect(color = if (i == n - 1) T.accent else T.accent.copy(alpha = 0.35f),
                        topLeft = Offset(i * (bw + gap), size.height - h), size = Size(bw, h))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Score = your 7-day average time saved (≈1 hr/day is 100). Keep letting the agent handle things to push it up.",
                fontSize = T.caption, color = T.inkFaint)
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
        SectionTitle("Persona per platform")
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

        SectionTitle("Brain data")
        Text("Feed ANY file into your brain (PDF, txt, md, csv…), or back the whole brain up and restore it.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text("Add any file", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { anyFilePicker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp, vertical = 9.dp))
            Text("Export brain", fontSize = T.small, color = T.inkSoft,
                modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable { scope.launch { brainMsg = withContext(Dispatchers.IO) { com.agentos.shell.tools.BrainData.exportBrain(ctx) } } }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
            Text("Import brain", fontSize = T.small, color = T.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable { brainImportPicker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        if (brainMsg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(brainMsg, fontSize = T.caption, color = T.accent) }

        SectionTitle("Import data & voice")
        Text("Add chat exports anytime — they feed the memory brain (and get indexed for semantic recall). " +
            "Then learn your writing voice from them whenever you want.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("WhatsApp", "Instagram", "Telegram", "LinkedIn msgs").forEach { p ->
                Text("📥 $p", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable { chatImportPicker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            Text("🔗 LinkedIn network", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable { liImportPicker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Text("Each opens your files — pick the export for that app (WhatsApp .txt, IG/Telegram .json, LinkedIn .csv). SlyOS detects the format.",
            fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (voiceBusy) "Learning…" else "🎙 Learn my voice", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable(enabled = !voiceBusy) {
                        voiceBusy = true; voiceStatus = ""
                        scope.launch {
                            val pool = MemoryStore.voiceSamples(ctx)
                            if (pool.size < 5) { voiceStatus = "Only $sampleCount samples — import your chats first."; voiceBusy = false; return@launch }
                            val profile = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.learnStyle(pool) }
                            if (profile.isNotBlank() && !profile.startsWith("[")) {
                                MemoryStore.setStyleProfile(ctx, profile)
                                com.agentos.shell.tools.AgentClient.styleProfile = profile
                                MemoryStore.setVoiceLearnedCount(ctx, pool.size)
                                voiceStatus = "Learned your voice from ${pool.size} messages ✓"
                            } else voiceStatus = "Couldn't learn it — ${profile.take(160)}"
                            voiceBusy = false
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        val voiceProfile = MemoryStore.styleProfile(ctx)
        Text("$sampleCount voice samples collected" +
            (if (voiceProfile.isNotBlank()) " · voice profile set ✓ (tap to view)" else " · no voice profile yet"),
            fontSize = T.caption, color = if (voiceProfile.isNotBlank()) T.accent else T.inkFaint,
            modifier = Modifier.padding(top = 6.dp).clickable(enabled = voiceProfile.isNotBlank()) { showVoice = !showVoice })
        if (showVoice && voiceProfile.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp)) {
                Text("How SlyOS thinks you write:", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(voiceProfile, fontSize = T.small, color = T.ink)
            }
        }
        if (importStatus.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(importStatus, fontSize = T.caption, color = T.accent) }
        if (voiceStatus.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(voiceStatus, fontSize = T.caption, color = if (voiceStatus.startsWith("Learned")) T.accent else T.danger) }

        SectionTitle("Models & spending")
        Text("Bring a key for any provider. Gemini has a free tier. With more than one, SlyOS uses a cheap " +
            "model for everyday replies and a powerful one for papers — same memory and voice on all of them.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        var keyGemini by remember { mutableStateOf(MemoryStore.geminiKey(ctx)) }
        var keyAnthropic by remember { mutableStateOf(MemoryStore.anthropicKey(ctx)) }
        var keyOpenai by remember { mutableStateOf(MemoryStore.openaiKey(ctx)) }
        var prefProvider by remember { mutableStateOf(MemoryStore.preferredProvider(ctx)) }
        // Card-based: each provider key is its own clean card with a status pill and an in-card field.
        @Composable
        fun keyRow(label: String, value: String, onChange: (String) -> Unit) {
            val set = value.isNotBlank()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (set) androidx.compose.ui.graphics.Color(0x1429C46A) else T.bgElevated).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                    Text(if (set) "✓ set" else "not set", fontSize = T.caption,
                        color = if (set) androidx.compose.ui.graphics.Color(0xFF1FA855) else T.inkFaint,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (set) androidx.compose.ui.graphics.Color(0x2229C46A) else T.hairline).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(7.dp))
                BasicTextField(value = value, onValueChange = onChange, singleLine = true,
                    textStyle = TextStyle(color = if (set) T.inkSoft else T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(10.dp),
                    decorationBox = { inner -> if (value.isEmpty()) Text("paste key…", fontSize = T.small, color = T.inkFaint); inner() })
            }
            Spacer(Modifier.height(10.dp))
        }
        keyRow("Gemini key (free tier)", keyGemini) { keyGemini = it; MemoryStore.setGeminiKey(ctx, it) }
        keyRow("Claude / Anthropic key", keyAnthropic) {
            keyAnthropic = it; MemoryStore.setAnthropicKey(ctx, it); com.agentos.shell.tools.AgentClient.apiKeyOverride = it.trim()
        }
        keyRow("OpenAI key", keyOpenai) { keyOpenai = it; MemoryStore.setOpenaiKey(ctx, it) }
        Text("Prefer", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        Row {
            listOf("gemini" to "Gemini", "anthropic" to "Claude", "openai" to "OpenAI").forEach { (id, lbl) ->
                val sel = prefProvider == id
                Text(lbl, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (sel) T.accent else T.hairline)
                        .clickable { prefProvider = id; MemoryStore.setPreferredProvider(ctx, id) }
                        .padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        var embN by remember { mutableStateOf(0) }
        var pendN by remember { mutableStateOf(0) }
        var embBusy by remember { mutableStateOf(false) }
        var embErr by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            embN = com.agentos.shell.tools.VectorStore.embeddedCount(ctx)
            pendN = com.agentos.shell.tools.VectorStore.pendingCount(ctx)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Semantic memory", fontSize = T.body, color = T.ink)
                Text(
                    if (com.agentos.shell.tools.EmbeddingClient.provider(ctx) == null)
                        "Needs a Gemini or OpenAI key (above) to switch on — embeddings are free on Gemini."
                    else "$embN memories indexed" + (if (pendN > 0) " · $pendN to go" else " · up to date") +
                        ". Lets the brain recall by meaning, not just keywords.",
                    fontSize = T.small, color = T.inkFaint
                )
            }
            if (com.agentos.shell.tools.EmbeddingClient.provider(ctx) != null) {
                Text(if (embBusy) "…" else "Index now", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable(enabled = !embBusy) {
                            embBusy = true
                            scope.launch {
                                withContext(Dispatchers.IO) { com.agentos.shell.tools.VectorStore.backfill(ctx, 500) }
                                embN = com.agentos.shell.tools.VectorStore.embeddedCount(ctx)
                                pendN = com.agentos.shell.tools.VectorStore.pendingCount(ctx)
                                val e = com.agentos.shell.tools.EmbeddingClient.lastError
                                embErr = when {
                                    e.contains("429") || e.contains("RESOURCE_EXHAUSTED", true) ->
                                        "Free-tier rate limit hit — the index keeps filling in the background every ~15 min. Come back later."
                                    e.isNotBlank() && embN == 0 -> e
                                    else -> ""
                                }
                                embBusy = false
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (embErr.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Embedding error: $embErr", fontSize = T.caption, color = T.danger)
        }
        // Choose which provider indexes memory. Forcing OpenAI is the reliable fast path when the free
        // Gemini tier is rate-limited. Switching re-indexes from scratch (vector sizes differ).
        if (keyGemini.isNotBlank() && keyOpenai.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            var embChoice by remember { mutableStateOf(MemoryStore.embedProvider(ctx)) }
            Text("Index with", fontSize = T.caption, color = T.inkSoft)
            Spacer(Modifier.height(4.dp))
            Row {
                listOf("auto" to "Auto", "gemini" to "Gemini (free)", "openai" to "OpenAI (reliable)").forEach { (id, lbl) ->
                    val sel = embChoice == id
                    Text(lbl, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                            .background(if (sel) T.accent else T.hairline)
                            .clickable {
                                if (embChoice != id) {
                                    embChoice = id; MemoryStore.setEmbedProvider(ctx, id)
                                    embBusy = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            com.agentos.shell.tools.VectorStore.clear(ctx)   // vector dims differ per provider
                                            com.agentos.shell.tools.VectorStore.backfill(ctx, 500)
                                        }
                                        embN = com.agentos.shell.tools.VectorStore.embeddedCount(ctx)
                                        pendN = com.agentos.shell.tools.VectorStore.pendingCount(ctx)
                                        embBusy = false
                                    }
                                }
                            }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Routing — which model handles what", fontSize = T.caption, color = T.inkSoft)
        Text("Quick tasks = triage, summaries, understanding commands (high volume).  Your replies = the " +
            "messages it sends as you.  Heavy work = papers, mini-apps, spicy posts.",
            fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        val tiers = listOf(
            com.agentos.shell.tools.ModelRouter.Tier.CHEAP to "Quick tasks",
            com.agentos.shell.tools.ModelRouter.Tier.STANDARD to "Your replies",
            com.agentos.shell.tools.ModelRouter.Tier.HEAVY to "Heavy work"
        )
        val routeMap = remember {
            mutableStateMapOf<String, String>().apply { tiers.forEach { (t, _) -> put(t.name, MemoryStore.tierProvider(ctx, t)) } }
        }
        tiers.forEach { (tier, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(label, fontSize = T.caption, color = T.inkSoft, modifier = Modifier.weight(1f))
                listOf("" to "Auto", "gemini" to "Gem", "anthropic" to "Claude", "openai" to "GPT").forEach { (pid, plbl) ->
                    val sel = (routeMap[tier.name] ?: "") == pid
                    Text(plbl, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                        modifier = Modifier.padding(start = 5.dp).clip(RoundedCornerShape(999.dp))
                            .background(if (sel) T.accent else T.hairline)
                            .clickable { routeMap[tier.name] = pid; MemoryStore.setTierProvider(ctx, tier, pid) }
                            .padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
        }
        Text("Auto = use your preferred, fall back to any key. A model only runs if its key is set.",
            fontSize = T.caption, color = T.inkFaint)

        Spacer(Modifier.height(12.dp))
        run {
            val cost = com.agentos.shell.tools.CostStore.monthCostUsd(ctx)
            val proj = com.agentos.shell.tools.CostStore.projectedMonthUsd(ctx)
            val calls = com.agentos.shell.tools.CostStore.monthCalls(ctx)
            val byProv = com.agentos.shell.tools.CostStore.monthCostByProvider(ctx)
            val daily = com.agentos.shell.tools.CostStore.dailyThisMonth(ctx)
            val dim = com.agentos.shell.tools.CostStore.daysInMonth()
            val accentC = T.accent; val lineC = T.hairline; val softC = T.inkSoft
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("This month", fontSize = T.caption, color = T.inkFaint)
                        Text("$" + String.format("%.2f", cost), fontSize = 26.sp, color = T.ink)
                    }
                    Text("~$" + String.format("%.2f", proj) + " projected", fontSize = T.caption, color = T.accent)
                }
                Spacer(Modifier.height(10.dp))
                // Cumulative spend across the month: solid = so far, dashed = projection to month-end.
                Canvas(Modifier.fillMaxWidth().height(86.dp)) {
                    val w = size.width; val h = size.height
                    var acc = 0.0
                    val cum = daily.map { acc += it; acc }
                    val maxY = maxOf(proj, cum.lastOrNull() ?: 0.0, 0.000001)
                    fun px(day: Int): Float = if (dim <= 1) 0f else (day - 1).toFloat() / (dim - 1) * w
                    fun py(v: Double): Float = (h - 4f) - (v / maxY).toFloat() * (h - 10f)
                    drawLine(lineC, Offset(0f, h - 3f), Offset(w, h - 3f), strokeWidth = 1.5f)
                    if (cum.isNotEmpty()) {
                        val path = Path(); path.moveTo(px(1), py(cum[0]))
                        for (i in 1 until cum.size) path.lineTo(px(i + 1), py(cum[i]))
                        drawPath(path, accentC, style = Stroke(width = 3f))
                        val lastX = px(cum.size); val lastY = py(cum.last())
                        drawCircle(accentC, 4.5f, Offset(lastX, lastY))
                        val proj1 = Path(); proj1.moveTo(lastX, lastY); proj1.lineTo(px(dim), py(proj))
                        drawPath(proj1, softC, style = Stroke(width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))))
                        drawCircle(softC, 3.5f, Offset(px(dim), py(proj)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("$calls model calls this month", fontSize = T.caption, color = T.inkFaint)
                if (byProv.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.HorizontalDivider(color = T.hairline, thickness = 1.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Per key", fontSize = T.caption, color = T.inkSoft)
                    Spacer(Modifier.height(4.dp))
                    val labelFor = mapOf("gemini" to "Gemini", "anthropic" to "Claude", "openai" to "OpenAI")
                    byProv.entries.sortedByDescending { it.value }.forEach { (prov, amt) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(labelFor[prov] ?: prov, fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                            Text("$" + String.format("%.2f", amt), fontSize = T.small, color = T.inkSoft)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Gemini free tier is $0.00 — usage stays free until you hit the daily cap.",
                            fontSize = T.caption, color = T.inkFaint)
                    }
                }
            }
        }
        // Hard monthly budget — cross it and everything auto-switches to free Gemini.
        Spacer(Modifier.height(12.dp))
        var budget by remember { mutableStateOf(MemoryStore.monthlyBudget(ctx).let { if (it > 0) it.toString().removeSuffix(".0") else "" }) }
        Text("Monthly budget (USD)", fontSize = T.caption, color = T.inkSoft)
        Spacer(Modifier.height(4.dp))
        BasicTextField(value = budget, onValueChange = { budget = it.filter { c -> c.isDigit() || c == '.' }; MemoryStore.setMonthlyBudget(ctx, budget) },
            singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp),
            decorationBox = { inner -> if (budget.isEmpty()) Text("e.g. 30 — 0 or blank = no cap", fontSize = T.small, color = T.inkFaint); inner() })
        Text("When this month's spend hits the cap, SlyOS routes everything to free Gemini so the bill can't run away.",
            fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 4.dp))

        SectionTitle("Connections")
        var gConnected by remember { mutableStateOf(com.agentos.shell.tools.GoogleAuth.isConnected(ctx)) }
        val gAccount = com.agentos.shell.tools.GoogleAuth.account(ctx)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Google · Calendar, Meet & Gmail", fontSize = T.body, color = T.ink)
                Text(
                    when {
                        gConnected -> "Connected" + (if (gAccount.isNotBlank()) " · $gAccount" else "") +
                            " — Meet links + invites, and recent mail (with PDF attachments) read into the brain. " +
                            "If you connected before Gmail was added, tap Disconnect then Connect to grant it."
                        com.agentos.shell.tools.GoogleAuth.configured() ->
                            "Sign in with your Google account: auto-create Meet links, send invites, and let the brain read your recent mail + attachments. One tap."
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

        Spacer(Modifier.height(14.dp))
        var ghToken by remember { mutableStateOf(MemoryStore.githubToken(ctx)) }
        val ghSet = ghToken.isNotBlank()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub · one-tap push from Cowork", fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
            if (ghSet) Text("✓ set", fontSize = T.caption, color = androidx.compose.ui.graphics.Color(0xFF1FA855),
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(androidx.compose.ui.graphics.Color(0x2229C46A)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text("Paste a GitHub token once — then Cowork can create repos and push code with no login prompts. " +
            "Make one at github.com/settings/tokens (classic, scope: repo).",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        BasicTextField(value = ghToken, onValueChange = { ghToken = it; MemoryStore.setGithubToken(ctx, it) }, singleLine = true,
            textStyle = TextStyle(color = if (ghSet) T.inkSoft else T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (ghSet) androidx.compose.ui.graphics.Color(0x1429C46A) else T.bgElevated).padding(10.dp),
            decorationBox = { inner -> if (ghToken.isEmpty()) Text("ghp_… (stored only on this phone)", fontSize = T.small, color = T.inkFaint); inner() })
        Text("Then just tell Cowork: “put this on my GitHub.”", fontSize = T.caption, color = T.inkFaint,
            modifier = Modifier.padding(top = 4.dp))

        SectionTitle("Per-app responses")
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

        SectionTitle("Document Q&A")
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
        if (kbName.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(kbName, fontSize = T.small, color = T.inkSoft) }
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
        if (tgBot) {
            // The bot only talks to YOU. Pair once by sending this code to the bot from your Telegram.
            val ownerId = remember { MemoryStore.telegramOwnerId(ctx) }
            if (ownerId == 0L) {
                val code = remember { MemoryStore.telegramPairCode(ctx) }
                Spacer(Modifier.height(6.dp))
                Text("Private — not paired yet. Message your bot with this code to pair: $code",
                    fontSize = T.small, color = T.accent)
            } else {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paired to your account ✓", fontSize = T.small, color = T.inkFaint, modifier = Modifier.weight(1f))
                    Text("Unpair", fontSize = T.small, color = T.danger,
                        modifier = Modifier.clickable { MemoryStore.clearTelegramOwner(ctx) }.padding(4.dp))
                }
            }
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

        SectionTitle("Lock screen")
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
