package com.agentos.shell.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.Screen
import com.agentos.shell.theme.T
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.AgentLoop
import com.agentos.shell.tools.AppStore
import com.agentos.shell.tools.CalendarTool
import com.agentos.shell.tools.ImageUtil
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.PdfTool
import com.agentos.shell.tools.ShortcutStore
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Load a real installed-app launcher icon as a Compose bitmap (cached by the caller). */
private fun appIconBitmap(ctx: android.content.Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? = try {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val w = d.intrinsicWidth.coerceIn(1, 192); val h = d.intrinsicHeight.coerceIn(1, 192)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
    bmp.asImageBitmap()
} catch (e: Exception) { null }

/**
 * Home = the heart. "what should happen?" goes to the real agent (AgentClient -> Claude),
 * which replies and decides an action that ToolRouter executes. Send button + tap-to-talk.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    paused: Boolean,
    autoVoice: Boolean = false,
    onVoiceConsumed: () -> Unit = {},
    onOpen: (Screen) -> Unit,
    onManual: () -> Unit,
    onCompose: (String, String) -> Unit = { _, _ -> },
    onComposeEmail: (String, String) -> Unit = { _, _ -> },
    onArchitect: () -> Unit = {},
    onSpicy: (String) -> Unit = {},
    onResearch: (String) -> Unit = {},
    onJob: (String) -> Unit = {},
    onNetwork: (String) -> Unit = {},
    onSetMission: (String) -> Unit = {},
    onLook: () -> Unit = {},
    onFaces: () -> Unit = {},
    onDocs: () -> Unit = {},
    onShop: (String) -> Unit = {},
    onInvest: (String) -> Unit = {},
    onExpenses: () -> Unit = {},
    onOperate: (String) -> Unit = {},
    onOpenApp: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allApps = remember { ToolRouter.installedApps(ctx) }
    var editMode by remember { mutableStateOf(false) }   // long-press a widget to show remove badges
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    // An image the AI just made/edited, awaiting a one-tap "Save to gallery".
    var producedImage by remember { mutableStateOf<ByteArray?>(null) }
    var producedName by remember { mutableStateOf("") }
    // Long-press a file → quick, content-aware options for it.
    var quickFile by remember { mutableStateOf<Uri?>(null) }
    var quickIsPdf by remember { mutableStateOf(false) }
    var vaultPinPrompt by remember { mutableStateOf(false) }
    var vaultPin by remember { mutableStateOf("") }
    var vaultReveal by remember { mutableStateOf<List<com.agentos.shell.tools.BankVault.Item>?>(null) }
    var vaultErr by remember { mutableStateOf("") }
    var replyDragX by remember { mutableStateOf(0f) }     // swipe the answer card: left=dismiss, right=open/Google
    var lastQuery by remember { mutableStateOf("") }
    var rememberSuggestion by remember { mutableStateOf("") }
    var showChecklist by remember { mutableStateOf(false) }   // show the live checklist card under the answer
    var checklistTick by remember { mutableStateOf(0) }       // bump to re-read ChecklistStore after a change
    var calCard by remember { mutableStateOf<Pair<String, List<com.agentos.shell.tools.CalendarTool.Event>>?>(null) }
    var pendingConfirm by remember { mutableStateOf<List<com.agentos.shell.tools.AgentAction>?>(null) }
    var saved by remember { mutableStateOf(MetricsStore.savedMinutesToday(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var showAddBtn by remember { mutableStateOf(false) }   // the + reveals only after a long-press on Home
    val shortcuts = remember { mutableStateListOf<ShortcutStore.Shortcut>().apply { addAll(ShortcutStore.list(ctx)) } }
    fun refreshShortcuts() { shortcuts.clear(); shortcuts.addAll(ShortcutStore.list(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragX by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }   // any file (PDFs, docs…)
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var history by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let { photos = photos + it }
    }
    // Attach ANYTHING — an image goes to the vision path; any other file (PDF/doc) becomes an attachment
    // the AI can read, fill, send or move. One minimal paperclip, everything flows through the brain.
    // Attach one OR several files at once.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris ?: emptyList()
        if (picked.isNotEmpty()) {
            picked.forEach { uri ->
                try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                if (com.agentos.shell.tools.FileOps.isImage(ctx, uri)) photos = photos + uri
                else attachments = attachments + uri
            }
            // Keep the most recent non-image doc "open" in the brain for follow-ups.
            picked.lastOrNull { !com.agentos.shell.tools.FileOps.isImage(ctx, it) }?.let { doc ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val body = if (com.agentos.shell.tools.FileOps.isPdf(ctx, doc))
                            com.agentos.shell.tools.FileOps.pdfText(ctx, doc) else ""
                        com.agentos.shell.tools.AttachContext.set(
                            ctx, com.agentos.shell.tools.AttachContext.Open(
                                uri = doc.toString(),
                                name = com.agentos.shell.tools.FileOps.displayName(ctx, doc),
                                text = body
                            )
                        )
                    }
                }
            }
        }
    }
    // Pick photos/videos straight from the gallery (the system photo picker — no storage permission needed).
    val pickGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (!uris.isNullOrEmpty()) photos = photos + uris
    }
    // Preview any attached file in the phone's viewer.
    val preview: (Uri) -> Unit = { uri ->
        scope.launch { withContext(Dispatchers.IO) { com.agentos.shell.tools.FileOps.preview(ctx, uri) } }
    }

    // The attach sheet: browse, shoot, or grab something someone already sent you.
    var attachSheet by remember { mutableStateOf(false) }
    var incoming by remember { mutableStateOf<List<com.agentos.shell.tools.Inbox.Item>>(emptyList()) }
    var loadingIncoming by remember { mutableStateOf(false) }
    var nudges by remember { mutableStateOf<List<com.agentos.shell.tools.Inbox.Item>>(emptyList()) }

    val mediaPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — the list simply stays empty if denied */ }

    val loadIncoming: () -> Unit = {
        loadingIncoming = true
        scope.launch {
            incoming = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.recent(ctx) }
            loadingIncoming = false
        }
    }
    // Attach an incoming item (downloads an email attachment on demand) and OPEN it in the brain — text,
    // sender and subject — so every AI still knows it turns later ("reply to that email").
    val attachIncoming: (com.agentos.shell.tools.Inbox.Item) -> Unit = { item ->
        attachSheet = false
        scope.launch {
            val uri = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.resolve(ctx, item) }
            if (uri != null) {
                if (com.agentos.shell.tools.FileOps.isImage(ctx, uri)) photos = photos + uri
                else attachments = attachments + uri
                withContext(Dispatchers.IO) {
                    val body = if (com.agentos.shell.tools.FileOps.isPdf(ctx, uri))
                        com.agentos.shell.tools.FileOps.pdfText(ctx, uri) else ""
                    com.agentos.shell.tools.AttachContext.set(
                        ctx, com.agentos.shell.tools.AttachContext.Open(
                            uri = uri.toString(), name = item.name, text = body,
                            fromName = item.mail?.sender ?: item.who,
                            fromEmail = item.mail?.email ?: "",
                            subject = item.mail?.subject ?: "",
                            msgId = item.mail?.msgId ?: ""
                        )
                    )
                }
                com.agentos.shell.tools.Inbox.dismiss(ctx, item.key)
                nudges = nudges.filterNot { it.key == item.key }
            }
        }
    }
    // Long-press → quick options for a file: open the sheet, noting whether it's a PDF (drives which options show).
    val openQuick: (Uri) -> Unit = { uri ->
        quickFile = uri
        scope.launch { quickIsPdf = withContext(Dispatchers.IO) { com.agentos.shell.tools.FileOps.isPdf(ctx, uri) } }
    }
    // Long-press a "Sent to you" card → attach it AND open quick options in one gesture.
    val quickFromIncoming: (com.agentos.shell.tools.Inbox.Item) -> Unit = { item ->
        scope.launch {
            val uri = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.resolve(ctx, item) }
            if (uri != null) {
                if (com.agentos.shell.tools.FileOps.isImage(ctx, uri)) photos = photos + uri
                else attachments = attachments + uri
                com.agentos.shell.tools.Inbox.dismiss(ctx, item.key)
                nudges = nudges.filterNot { it.key == item.key }
                quickFile = uri
                quickIsPdf = withContext(Dispatchers.IO) { com.agentos.shell.tools.FileOps.isPdf(ctx, uri) }
            }
        }
    }
    // The SlyOS filing cabinet exists from first launch, so the user can see it in their file manager.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.agentos.shell.tools.SlyFolder.ensure(ctx) } }
    // Grow the photo RAG a little each launch: describe a handful of the newest un-described photos, so the
    // brain steadily learns your gallery and you can ask for pictures by meaning. Bounded → never a cost spike.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { com.agentos.shell.tools.PhotoIndex.indexRecent(ctx, 6) }
    }
    // Rehydrate the conversation so HomeAI actually remembers across restarts (feeds the model too).
    LaunchedEffect(Unit) {
        val past = withContext(Dispatchers.IO) { com.agentos.shell.tools.HomeChatStore.recentPairs(ctx, 12) }
        if (past.isNotEmpty() && history.isEmpty()) history = past
    }
    // A fresh session starts with NO open document, so a stale attachment never gets re-detected.
    LaunchedEffect(Unit) {
        if (photos.isEmpty() && attachments.isEmpty())
            withContext(Dispatchers.IO) { com.agentos.shell.tools.AttachContext.clear(ctx) }
    }
    // Recent documents people emailed you (up to 3) — shown once, then never nagged again. We mark them
    // seen the MOMENT they're fetched, so no path (recomposition, tab switch, restart) can resurface them.
    LaunchedEffect(Unit) {
        val fetched = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.nudges(ctx, 3) }
        nudges = fetched
        withContext(Dispatchers.IO) { fetched.forEach { com.agentos.shell.tools.Inbox.dismiss(ctx, it.key) } }
    }
    val capture: () -> Unit = {
        val file = File(ctx.cacheDir, "home_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", file)
        pendingUri = uri
        takePhoto.launch(uri)
    }

    // Ask once for calendar read + write so the agent can see and block your schedule.
    val calPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}
    LaunchedEffect(Unit) {
        val need = mutableListOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.SEND_SMS
        )
        if (android.os.Build.VERSION.SDK_INT >= 33)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        calPerm.launch(need.toTypedArray())
    }

    // Text-to-speech so the agent can speak its reply (on-device, free).
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(ctx) {}
        ttsRef.value = engine
        onDispose { engine.stop(); engine.shutdown() }
    }
    val speak: (String) -> Unit = { s ->
        if (s.isNotBlank()) ttsRef.value?.apply {
            language = java.util.Locale.getDefault()
            speak(s, TextToSpeech.QUEUE_FLUSH, null, "slyos")
        }
    }

    val submit: (String, Boolean) -> Unit = submit@{ raw, doSpeak ->
        val q = raw.trim()
        if (q.isEmpty() || thinking) return@submit
        // Bank/vault questions are answered LOCALLY behind the PIN — never sent to any model.
        if (com.agentos.shell.tools.BankVault.isConfigured(ctx) && com.agentos.shell.tools.BankVault.isQuery(q)) {
            vaultErr = ""; vaultPin = ""; text = ""; vaultPinPrompt = true; return@submit
        }
        thinking = true; reply = ""; rememberSuggestion = ""; text = ""; pendingConfirm = null; lastQuery = q; replyDragX = 0f; calCard = null; producedImage = null
        scope.launch {
            // ── ATTACHMENT / FILE ACTIONS via the PLANNER ────────────────────────────────────────────
            // The model turns the request into ONE structured action (send / read / fill / edit / generate /
            // convert / move / reply), then the executor runs it deterministically. This is the robust core:
            // phrasing-proof intent (the LLM's job) + reliable execution (ours). Falls through if it's not
            // about a file at all.
            var plannerConsidered = false
            run {
                val attachedList = photos + attachments
                val wantsImageGen = Regex("(?i)\\b(generate|create|make|draw|design|imagine)\\b.{0,24}\\b(image|picture|photo|logo|illustration|art|artwork|wallpaper|icon|drawing|render)\\b").containsMatchIn(q)
                val looksLikeFileAsk = attachedList.isNotEmpty() || wantsImageGen ||
                    (com.agentos.shell.tools.FileResolver.describesAFile(q) &&
                        Regex("(?i)\\b(send|share|email|forward|fill|edit|read|summari|convert|file it|attach)\\b").containsMatchIn(q))
                if (!looksLikeFileAsk) return@run
                plannerConsidered = true   // once we look at an attached file, the OLD file branches must not re-grab it

                val names = attachedList.map { com.agentos.shell.tools.FileOps.displayName(ctx, it) }
                val plan = withContext(Dispatchers.IO) { com.agentos.shell.tools.AttachmentPlanner.plan(ctx, q, names) }
                if (plan.action == com.agentos.shell.tools.AttachmentPlanner.Action.NONE) return@run

                val res = withContext(Dispatchers.IO) { com.agentos.shell.tools.ActionExecutor.run(ctx, plan, attachedList, q) }
                // Keep the file attached for actions where you'd naturally do MORE with the same file
                // (ask another question, fill then send). Clear it for actions that consume/replace it.
                val keepAttached = plan.action == com.agentos.shell.tools.AttachmentPlanner.Action.READ ||
                    plan.action == com.agentos.shell.tools.AttachmentPlanner.Action.FILL ||
                    plan.action == com.agentos.shell.tools.AttachmentPlanner.Action.REPLY
                if (!keepAttached) { photos = emptyList(); attachments = emptyList() }
                reply = res.message
                producedImage = res.producedPng
                producedName = res.producedName
                withContext(Dispatchers.IO) {
                    if (!keepAttached) com.agentos.shell.tools.AttachContext.clear(ctx)
                    com.agentos.shell.tools.MemoryLog.add(ctx, "action", q.take(48), reply, "Files")
                    com.agentos.shell.tools.HomeChatStore.add(ctx, q, reply)
                }
                if (doSpeak) speak(reply)
                thinking = false
                return@launch
            }
            // A non-image file is attached (PDF/doc): read it, fill it, send it, or move it — through the brain.
            // (Fallback only — skipped when the planner already handled/declined this attachment.)
            if (!plannerConsidered && attachments.isNotEmpty()) {
                val files = attachments; attachments = emptyList()
                val f = files.first(); val lc = q.lowercase()
                val FO = com.agentos.shell.tools.FileOps
                val many = files.size > 1
                reply = withContext(Dispatchers.IO) {
                    when {
                        Regex("(?i)\\b(fill|complete)\\b").containsMatchIn(q) && files.any { FO.isPdf(ctx, it) } -> {
                            val results = files.filter { FO.isPdf(ctx, it) }.map { FO.fillPdfForm(ctx, it).second }
                            results.joinToString("\n")
                        }
                        Regex("(?i)\\bsend\\b|\\bshare\\b|email (this|it|the|them)").containsMatchIn(q) -> {
                            val what = if (many) "${files.size} files" else "the file"
                            val channel = FO.detectChannel(q)   // whatsapp, instagram, gmail… whatever they named
                            // Who + what to say, so it lands straight in their chat, ready to send.
                            val rec = Regex("(?i)\\bto\\s+(.+?)(?:\\s+(?:on|via|through|over|using|by)\\b|\\s+(?:saying|say|tell|and\\s+say|with\\s+(?:the\\s+)?message)\\b|[.,]|$)")
                                .find(q)?.groupValues?.get(1)?.trim()?.trim('"', '.', ',')
                                ?.takeUnless { FO.detectChannel(it).isNotBlank() }   // don't mistake the channel for a name
                            val msg = Regex("(?i)(?:saying|say|tell(?:\\s+(?:her|him|them))?|with\\s+(?:the\\s+)?message|and\\s+say)\\s+(.+)$")
                                .find(q)?.groupValues?.get(1)?.trim()?.trim('"') ?: ""
                            val emailInQ = Regex("[\\w.+-]+@[\\w.-]+\\.\\w+").find(q)?.value
                            val CT = com.agentos.shell.tools.ContactsTool
                            val out = when {
                                rec.isNullOrBlank() && emailInQ == null -> {
                                    if (FO.send(ctx, files, channel))
                                        "Opened ${if (channel.isBlank()) "your share sheet" else channel} with $what attached — pick who to send to."
                                    else "I couldn't send ${if (many) "those" else "that one"}."
                                }
                                FO.isEmailChannel(channel) || (channel.isBlank() && emailInQ != null) -> {
                                    val email = emailInQ ?: (rec?.let { CT.findEmail(ctx, it) } ?: "")
                                    FO.sendToPerson(ctx, files, channel.ifBlank { "mail" }, rec ?: email, toEmail = email, message = msg, subject = "For you")
                                        ?: "I couldn't open email."
                                }
                                else -> {
                                    // Any messaging channel. WhatsApp can land in the exact chat (needs a number).
                                    val c = rec?.let { CT.findContact(ctx, it) }
                                    FO.sendToPerson(ctx, files, channel.ifBlank { "" }, rec ?: c?.name ?: "them",
                                        toNumber = c?.number ?: "", message = msg)
                                        ?: run { FO.send(ctx, files, channel); "Opened ${if (channel.isBlank()) "your share sheet" else channel} with $what attached." }
                                }
                            }
                            // Fuel the brain: it should remember you sent this, to whom, over what.
                            com.agentos.shell.tools.MemoryLog.add(ctx, "action",
                                "Sent ${if (many) "${files.size} files" else files.firstOrNull()?.let { FO.displayName(ctx, it) } ?: "a file"}" +
                                    (if (!rec.isNullOrBlank()) " to $rec" else "") + (if (channel.isNotBlank()) " over $channel" else ""),
                                out, "Files")
                            out
                        }
                        Regex("(?i)\\b(file|move|put|save (it|them)?\\s*(to|in|into))\\b").containsMatchIn(q) -> {
                            val asked = Regex("(?i)(?:in|into|to|under)\\s+(?:my\\s+|the\\s+)?([\\w -]{2,30})").find(q)
                                ?.groupValues?.get(1)?.trim()?.trimEnd('.', ' ') ?: ""
                            val cat = com.agentos.shell.tools.SlyFolder.CATEGORIES
                                .firstOrNull { it.equals(asked, true) || (asked.isNotBlank() && it.contains(asked, true)) } ?: ""
                            files.joinToString("\n") { file ->
                                val body = if (FO.isPdf(ctx, file)) FO.pdfText(ctx, file) else ""
                                com.agentos.shell.tools.SlyFolder.fileExisting(ctx, file, body, cat).second
                            }
                        }
                        FO.isPdf(ctx, f) -> {
                            val body = FO.pdfText(ctx, f)
                            if (body.isBlank()) "I opened it but couldn't pull out any text — it looks like a scan. Add the OCR power and I'll read it."
                            else {
                                com.agentos.shell.tools.AttachContext.setText(ctx, body)   // stays known for follow-ups
                                AgentClient.ask("$q\n\n--- The attached document says ---\n${body.take(12000)}",
                                    emptyList(), MemoryStore.fullProfile(ctx)).say
                            }
                        }
                        else -> "I've got ${if (many) "${files.size} files" else "\"${FO.displayName(ctx, f)}\""}. Tell me what to do — read, fill in, send, or file away."
                    }
                }
                if (doSpeak) speak(reply)
                thinking = false
                return@launch
            }
            // If photos are attached, this is an image task (vision Q&A or PDF).
            // (Fallback only — skipped when the planner already handled/declined this attachment.)
            if (!plannerConsidered && photos.isNotEmpty()) {
                val attached = photos
                photos = emptyList()
                // POWER: "remove the background" → native on-device first (zero setup), then a connected rembg.
                if (Regex("(?i)\\b(background|cut ?out|cutout|remove bg)\\b").containsMatchIn(q)) {
                    reply = withContext(Dispatchers.IO) {
                        val src = com.agentos.shell.tools.PowerDispatch.bytesOf(ctx, attached.first())
                        if (src == null) "Couldn't read that photo."
                        else {
                            var png = com.agentos.shell.tools.NativeTools.removeBackground(src)
                            if (png == null) {
                                val ep = com.agentos.shell.tools.PowerRegistry.endpointOf(ctx, "rembg").trim()
                                if (ep.isNotBlank()) png = com.agentos.shell.tools.PowerDispatch.removeBackground(ep, src)
                            }
                            if (png == null) "I couldn't cut out the background on this one — try a photo with a clearer subject."
                            else {
                                val uri = com.agentos.shell.tools.PowerDispatch.saveImage(ctx, png, "cutout_${System.currentTimeMillis()}.png")
                                if (uri != null) "Done — cut out the background and saved it to your gallery." else "Cut it out, but couldn't save it."
                            }
                        }
                    }
                    if (doSpeak) speak(reply)
                    thinking = false
                    return@launch
                }
                if (q.lowercase().contains("pdf")) {
                    val uri = withContext(Dispatchers.IO) { PdfTool.imagesToPdf(ctx, attached) }
                    if (uri != null) {
                        reply = "Made a ${attached.size}-page PDF — choose where to save it."
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/pdf")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Save PDF"
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else reply = "Couldn't make the PDF."
                } else {
                    val b64s = withContext(Dispatchers.IO) { attached.mapNotNull { ImageUtil.encode(ctx, it) } }
                    reply = withContext(Dispatchers.IO) {
                        AgentClient.askVision(q, b64s, MemoryStore.fullProfile(ctx))
                    }
                    // Remember what you looked at + what it was, so vision Q&A feeds the brain too.
                    if (reply.isNotBlank())
                        com.agentos.shell.tools.MemoryLog.add(ctx, "response",
                            "Looked at: ${q.take(40)}", reply, "Camera")
                }
                if (doSpeak) speak(reply)
                thinking = false
                return@launch
            }

            // Calendar questions ("what's on my calendar today / this week") render a real agenda card
            // instead of a plain text list — and skip the LLM call entirely.
            val calWin = CalendarQuery.parse(q)
            if (calWin != null && com.agentos.shell.tools.CalendarTool.hasPermission(ctx)) {
                val evs = withContext(Dispatchers.IO) { com.agentos.shell.tools.CalendarTool.eventsBetween(ctx, calWin.start, calWin.end) }
                calCard = calWin.label to evs
                reply = ""; thinking = false
                withContext(Dispatchers.IO) {
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "Me", "SlyOS", "me", "me", q)
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "SlyOS", "SlyOS", "SlyOS", "them",
                        "Showed calendar (${calWin.label}): ${evs.size} event" + (if (evs.size == 1) "" else "s") + (if (evs.isEmpty()) " — free" else ""))
                }
                history = (history + (q to "Showed calendar: ${calWin.label} — ${evs.size} events")).takeLast(12)
                saved = MetricsStore.savedMinutesToday(ctx)
                return@launch
            }

            // "Stop sharing my location" — turn the live share off.
            if (Regex("(?i)\\bstop\\b[^.]{0,24}\\b(shar\\w*|location)\\b").containsMatchIn(q) &&
                Regex("(?i)\\blocation\\b").containsMatchIn(q)) {
                com.agentos.shell.LiveLocationService.stop(ctx)
                reply = "Stopped sharing your location."; thinking = false
                if (doSpeak) speak(reply)
                return@launch
            }
            // "Share my location with X [on whatsapp/sms/telegram] [until I'm home]" — routed deterministically
            // so it can never be misread as plain navigation. Parses the contact, the channel, and whether the
            // user ALSO wants turn-by-turn home (home never engages unless asked).
            if (Regex("(?i)\\bshare\\b[^.]{0,40}\\blocation\\b").containsMatchIn(q) ||
                Regex("(?i)\\b(send|drop)\\b[^.]{0,24}\\blocation\\b").containsMatchIn(q)) {
                val channel = when {
                    Regex("(?i)whats\\s?app").containsMatchIn(q) -> "whatsapp"
                    Regex("(?i)\\btelegram\\b").containsMatchIn(q) -> "telegram"
                    Regex("(?i)\\b(sms|text|texts|imessage)\\b").containsMatchIn(q) -> "sms"
                    else -> ""
                }
                val name = Regex("(?i)\\bwith\\s+([\\p{L}][\\p{L} .'\\-]{0,30})").find(q)?.groupValues?.get(1)
                    ?.replace(Regex("(?i)\\b(until|till|til|and|so|then|while|on|via|through|over|using)\\b.*$"), "")?.trim().orEmpty()
                val navHome = Regex("(?i)(until[^.]*\\bhome\\b|get(?:ting)? home|navigate me home|way home|take me home|reach(?:ing)? home|head(?:ing)? home)").containsMatchIn(q)
                val arg = org.json.JSONObject().put("name", name).put("navigate", navHome)
                if (channel.isNotBlank()) arg.put("channel", channel)
                val msg = withContext(Dispatchers.IO) { ToolRouter.executeAction(ctx, "share_location", arg.toString()) }
                reply = msg.ifBlank { "Started sharing your location." }
                thinking = false
                withContext(Dispatchers.IO) {
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "Me", "SlyOS", "me", "me", q)
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "SlyOS", "SlyOS", "SlyOS", "them", reply)
                }
                if (doSpeak) speak(reply)
                return@launch
            }

            val apps = withContext(Dispatchers.IO) { ToolRouter.installedApps(ctx).map { it.label } }
            // One shared brain context (profile + calendar + memories + network + papers + tasks +
            // mission + portfolio + jobs) — the SAME builder Conversation mode uses, so nothing drifts.
            // If the user tapped the floating Brain over another app, prepend what was on that screen so
            // this question is answered in context (one-shot — cleared after use).
            val snap = com.agentos.shell.tools.ScreenSnap.take()
            val screenCtx = if (snap.first.isNotBlank()) "WHAT WAS ON SCREEN (" + snap.second + "):\n" + snap.first + "\n\n" else ""
            val context = screenCtx + withContext(Dispatchers.IO) { com.agentos.shell.tools.BrainContext.build(ctx, q) }
            val result = withContext(Dispatchers.IO) { AgentClient.ask(q, apps, context, history) }
            // Auto-grow the brain: durable facts learned in conversation are saved automatically
            // (to the separate learned-facts store, not your curated About).
            if (result.remember.isNotBlank()) MemoryStore.addLearnedFact(ctx, result.remember)
            rememberSuggestion = result.remember

            // compose_post navigates to the post composer instead of executing inline.
            val composeAct = result.actions.firstOrNull { it.type == "compose_post" }
            if (composeAct != null) {
                val o = try { org.json.JSONObject(composeAct.arg) } catch (e: Exception) { null }
                val platform = o?.optString("platform").takeUnless { it.isNullOrBlank() } ?: "LinkedIn"
                val tpc = o?.optString("topic").takeUnless { it.isNullOrBlank() } ?: q
                thinking = false
                onCompose(platform, tpc)
                return@launch
            }
            // spicy_post navigates to the X post composer.
            val spicyAct = result.actions.firstOrNull { it.type == "spicy_post" }
            if (spicyAct != null) {
                thinking = false
                onSpicy(spicyAct.arg.ifBlank { q })
                return@launch
            }
            // compose_email opens the editable email draft page (review, prompt-to-revise, then send).
            val emailAct = result.actions.firstOrNull { it.type == "compose_email" }
            if (emailAct != null) {
                val o = try { org.json.JSONObject(emailAct.arg) } catch (e: Exception) { null }
                val to = o?.optString("to").orEmpty()
                val tpc = o?.optString("topic").takeUnless { it.isNullOrBlank() } ?: emailAct.arg.ifBlank { q }
                thinking = false
                onComposeEmail(to, tpc)
                return@launch
            }
            // set_mission opens the Mission screen and starts an outreach campaign for the goal.
            val misAct = result.actions.firstOrNull { it.type == "set_mission" }
            if (misAct != null && misAct.arg.isNotBlank()) {
                thinking = false
                onSetMission(misAct.arg)
                return@launch
            }
            // network_search opens the network screen: matching people + a ready message + LinkedIn buttons.
            val netAct = result.actions.firstOrNull { it.type == "network_search" }
            if (netAct != null) {
                thinking = false
                onNetwork(netAct.arg.ifBlank { q })
                return@launch
            }
            // find_job opens the job-hunt assistant (résumé, cover letter, outreach), prefilled with the role.
            val jobAct = result.actions.firstOrNull { it.type == "find_job" }
            if (jobAct != null) {
                thinking = false
                onJob(jobAct.arg)
                return@launch
            }
            // cowork opens the Cowork workspace to actually BUILD it (files, code, tools), prefilled.
            val coworkAct = result.actions.firstOrNull { it.type == "cowork" }
            if (coworkAct != null) {
                com.agentos.shell.tools.CoworkHandoff.pending = coworkAct.arg.ifBlank { q }
                thinking = false
                onOpen(Screen.Cowork)
                return@launch
            }
            // shop opens the Shop screen and web-finds real buy options for the item.
            val shopAct = result.actions.firstOrNull { it.type == "shop" }
            if (shopAct != null) {
                thinking = false
                onShop(shopAct.arg.ifBlank { q })
                return@launch
            }
            // look opens the camera identify screen.
            val lookAct = result.actions.firstOrNull { it.type == "look" }
            if (lookAct != null) {
                thinking = false
                onLook()
                return@launch
            }
            // invest opens the practice-trading screen (build & run a paper portfolio).
            val investAct = result.actions.firstOrNull { it.type == "invest" }
            if (investAct != null) {
                thinking = false
                onInvest(investAct.arg)
                return@launch
            }
            // expenses opens the spending screen (log a receipt / see spending).
            val expenseAct = result.actions.firstOrNull { it.type == "expenses" }
            if (expenseAct != null) {
                thinking = false
                onExpenses()
                return@launch
            }
            // faces → the "Who's this?" camera recognizer (match a face against your saved people).
            if (result.actions.any { it.type == "faces" }) {
                thinking = false
                onFaces()
                return@launch
            }
            // documents → scan a form/receipt/ID; the model reads fields and auto-files it in a folder.
            if (result.actions.any { it.type == "documents" || it.type == "scan_doc" }) {
                thinking = false
                onDocs()
                return@launch
            }
            // operate → the action layer drives an app by tapping the screen (with STOP + stop-before-send).
            val operateAct = result.actions.firstOrNull { it.type == "operate" }
            if (operateAct != null) {
                thinking = false
                reply = "On it — I'll drive it on screen. Watch the STOP banner; I'll stop before anything final."
                onOperate(operateAct.arg.ifBlank { q })
                return@launch
            }
            // write_paper navigates to the Research workspace, pre-filled.
            val paperAct = result.actions.firstOrNull { it.type == "write_paper" }
            if (paperAct != null) {
                thinking = false
                onResearch(paperAct.arg.ifBlank { q })
                return@launch
            }

            // Consequential actions (send message/email, add event, remind) don't fire silently —
            // they surface as a tap-to-confirm card with the fields pre-filled. Everything else runs now.
            val actionable = result.actions.filter { it.type.isNotBlank() && it.type != "none" }
            val confirmables = actionable.filter { it.type in ActionConfirm.CONFIRM_TYPES }
            // Any checklist change, or a "show my checklist / to-do / list" query → reveal the live checklist
            // card under the answer so what SlyOS shows always matches the brain (no more phantom clears).
            val touchedChecklist = actionable.any { it.type.startsWith("checklist") }
            val asksChecklist = Regex("(?i)\\b(show|see|view|what'?s on|open|my)\\b.*\\b(check ?list|to-?do'?s?|task list|list)\\b").containsMatchIn(q)
            if (touchedChecklist || asksChecklist) { showChecklist = true; checklistTick++ } else showChecklist = false
            // Only pay for the loop when the model wants the web, OR the user is asking about spending
            // (so the review pulls REAL numbers via expense_lookup, not a guess). Simple Q&A stays one call.
            val financeQ = Regex("(?i)\\b(spen[dt]|spending|expense|expenditure|budget|spending review|where.?s my money|how much (did|have) i (spend|spent))\\b").containsMatchIn(q)
            val needsLoop = actionable.any { it.type == "web_search" } || (financeQ && confirmables.isEmpty())
            when {
                confirmables.isNotEmpty() -> {
                    // Consequential → run benign steps now, confirm the rest via card (existing UX).
                    val actionMsg = withContext(Dispatchers.IO) { ToolRouter.executeActions(ctx, actionable.filter { it.type !in ActionConfirm.CONFIRM_TYPES }) }
                    pendingConfirm = confirmables
                    reply = if (actionMsg.isNotEmpty()) actionMsg else result.say
                }
                needsLoop -> {
                    // P1/P2: pure Q&A, a live-web question, or a multi-step request → the agent loop (real
                    // web search + memory + tool chaining), instead of just opening the browser or answering blind.
                    val out = withContext(Dispatchers.IO) { AgentLoop.run(ctx, q, context, history, userInitiated = true) }
                    reply = out.answer
                    pendingConfirm = out.actions.ifEmpty { null }   // P0: consequential steps → confirm card
                }
                else -> {
                    // Concrete benign actions (open app/url, play music…) — just execute them.
                    val actionMsg = withContext(Dispatchers.IO) { ToolRouter.executeActions(ctx, actionable) }
                    pendingConfirm = null
                    reply = if (actionMsg.isNotEmpty()) actionMsg else result.say
                }
            }
            refreshShortcuts()
            // The visual card tag stays in `reply` (the card reads it) but must never be spoken or stored.
            val cleanReply = RichParse.fromTag(reply).second
            if (doSpeak) speak(cleanReply)
            history = (history + (q to cleanReply)).takeLast(12)   // P4: keep more context across turns
            withContext(Dispatchers.IO) { com.agentos.shell.tools.HomeChatStore.add(ctx, q, cleanReply) }  // survives restarts
            // Capture this exchange as connected memories.
            val pk = MemoryLog.add(ctx, "prompt", q, q, "Home prompt")
            MemoryLog.add(ctx, "response", cleanReply, cleanReply, "Agent reply", pk)
            // Persist into the real brain DB too, so daily use actually GROWS the brain (and the
            // semantic index) — not just the capped 80-entry graph log. This is what makes the
            // memory count climb over time instead of sitting flat.
            withContext(Dispatchers.IO) {
                com.agentos.shell.tools.MessageStore.insertOne(ctx, "Me", "SlyOS", "me", "me", q)
                if (cleanReply.isNotBlank())
                    com.agentos.shell.tools.MessageStore.insertOne(ctx, "SlyOS", "SlyOS", "SlyOS", "them", cleanReply)
            }
            saved = MetricsStore.savedMinutesToday(ctx)
            thinking = false
        }
    }

    val voice = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) { text = spoken; submit(spoken, true) }
        }
    }
    val startVoice: () -> Unit = {
        try {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak")
            )
        } catch (e: Exception) { reply = "No voice input available on this device." }
    }
    // Launched from the lock-screen "Speak" shortcut → open the mic immediately, ONCE, then clear
    // it so returning to Home (e.g. via the nav bar) never re-opens the mic.
    LaunchedEffect(autoVoice) { if (autoVoice) { startVoice(); onVoiceConsumed() } }

    // Live status bar: clock + battery, so Home reads like a real OS home screen (One UI hides these
    // while SlyOS is the launcher). Refreshes every 30s; battery via BatteryManager.
    var clock by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val f = java.text.SimpleDateFormat("EEE  h:mm a", java.util.Locale.getDefault())
        while (true) {
            clock = f.format(java.util.Date())
            try {
                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                charging = bm.isCharging
            } catch (e: Exception) { battery = -1 }
            kotlinx.coroutines.delay(30_000)
        }
    }

    Column(modifier.pointerInput(Unit) {
        // Long-press empty Home space → reveal the + (add apps / SlyOS tools), like a stock launcher.
        detectTapGestures(onLongPress = { showAddBtn = true })
    }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock, fontSize = T.caption, color = T.inkSoft)
            Spacer(Modifier.weight(1f))
            if (battery in 0..100)
                Text("$battery%" + if (charging) " ⚡" else "", fontSize = T.caption,
                    color = if (battery <= 15 && !charging) T.accent else T.inkSoft)
        }
        // One-tap setup: auto-asks for every missing runtime permission on launch, then a slim pill
        // to grant the rest. Vanishes once everything's granted.
        PermissionBar()
        // The + appears only after a long-press on Home; hidden otherwise for a clean panel.
        if (editMode || showAddBtn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (editMode) {
                    Text("Done", fontSize = T.small, color = T.accent,
                        modifier = Modifier.clickable { editMode = false }.padding(end = 12.dp))
                }
                if (showAddBtn) Icon(Icons.Filled.Add, contentDescription = "Add shortcut", tint = T.inkSoft,
                    modifier = Modifier.size(26.dp).clickable { showAdd = true })
            }
        }

        if (shortcuts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp)) {
                val maxX = (maxWidth.value - 56f).coerceAtLeast(0f)
                val maxY = (maxHeight.value - 70f).coerceAtLeast(0f)
                shortcuts.forEach { s ->
                    val isDragged = draggingId == s.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset(s.x.dp, s.y.dp)
                            .graphicsLayer { val sc = if (isDragged) 1.12f else 1f; scaleX = sc; scaleY = sc }
                            .pointerInput(s.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingId = s.id; editMode = true },
                                    onDragEnd = {
                                        draggingId = null
                                        ShortcutStore.savePositions(ctx, shortcuts.toList())
                                    },
                                    onDragCancel = { draggingId = null },
                                    onDrag = { ch, amt ->
                                        ch.consume()
                                        val i = shortcuts.indexOfFirst { it.id == s.id }
                                        if (i >= 0) {
                                            val nx = (shortcuts[i].x + amt.x / density.density).coerceIn(0f, maxX)
                                            val ny = (shortcuts[i].y + amt.y / density.density).coerceIn(0f, maxY)
                                            shortcuts[i] = shortcuts[i].copy(x = nx, y = ny)
                                        }
                                    }
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            val icon = if (s.kind == "app") remember(s.ref) { appIconBitmap(ctx, s.ref) } else null
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (s.kind == "app" && icon == null) T.bgElevated
                                                else if (s.kind != "app") T.accent else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable {
                                        if (editMode) editMode = false
                                        else if (s.kind == "app") ToolRouter.launchApp(ctx, s.ref)
                                        else s.ref.toLongOrNull()?.let { onOpenApp(it) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null)
                                    Image(bitmap = icon, contentDescription = s.label, contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
                                else
                                    Text(if (s.kind == "app") (s.label.firstOrNull()?.uppercase() ?: "•") else "◆",
                                        fontSize = T.body, color = if (s.kind == "app") T.ink else T.bgElevated)
                            }
                            // Remove badge: only while in edit mode (after a long-press), like a launcher.
                            if (editMode) {
                                Box(Modifier.size(18.dp).offset(6.dp, (-6).dp).clip(CircleShape).background(T.ink)
                                    .clickable { ShortcutStore.remove(ctx, s.id); refreshShortcuts() },
                                    contentAlignment = Alignment.Center) {
                                    Text("✕", fontSize = T.caption, color = T.bgElevated)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.label.take(10), fontSize = T.caption, color = T.inkSoft, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        // Personalized greeting: "what should happen, Emil?" — fades + rises in once, with a hairline accent
        // underline that draws itself in. One quiet, deliberate motion; never repeats.
        val firstName = remember {
            (com.agentos.shell.tools.MemoryStore.profileName(ctx).ifBlank { com.agentos.shell.tools.MemoryStore.ownerName(ctx) })
                .trim().substringBefore(' ').take(20)
        }
        var greetShown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { greetShown = true }
        val gAlpha by animateFloatAsState(if (greetShown) 1f else 0f, tween(650), label = "gAlpha")
        val gRise by animateFloatAsState(if (greetShown) 0f else 16f, tween(650, easing = FastOutSlowInEasing), label = "gRise")
        val gLine by animateFloatAsState(if (greetShown) 1f else 0f, tween(780, delayMillis = 240), label = "gLine")
        Column(Modifier.graphicsLayer { alpha = gAlpha; translationY = gRise }) {
            Text(if (firstName.isBlank()) "what should happen?" else "what should happen, $firstName?",
                fontSize = T.prompt, color = T.ink)
            Spacer(Modifier.height(9.dp))
            Box(Modifier.width((90 * gLine).dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(T.accent))
        }
        Spacer(Modifier.height(14.dp))

        // App-name autocomplete: as you type, surface matching installed apps to open instantly.
        val appMatches = remember(text, allApps) {
            val t = text.trim().lowercase()
            if (t.length < 2 || t.contains(" ")) emptyList()
            else allApps.filter { it.label.lowercase().startsWith(t) }
                .ifEmpty { allApps.filter { it.label.lowercase().contains(t) } }
                .take(3)
        }
        if (appMatches.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top) {
                appMatches.forEach { app ->
                    val appIcon = remember(app.pkg) { appIconBitmap(ctx, app.pkg) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 16.dp).clickable { text = ""; ToolRouter.launchApp(ctx, app.pkg) }) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(T.bgElevated), contentAlignment = Alignment.Center) {
                            if (appIcon != null) Image(bitmap = appIcon, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)))
                            else Text(app.label.firstOrNull()?.uppercase() ?: "•", fontSize = T.body, color = T.ink)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(app.label.take(10), fontSize = T.caption, color = T.inkSoft, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.body),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(text, false) }),
                modifier = Modifier
                    .weight(1f)
                    .drawBehind {
                        drawLine(T.ink, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                    }
                    .padding(vertical = 8.dp),
                decorationBox = { inner ->
                    if (text.isEmpty())
                        Text("ask me anything…", color = T.inkFaint, fontSize = T.body)
                    inner()
                }
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = "Attach a file",
                tint = T.inkSoft,
                modifier = Modifier.size(22.dp).clickable {
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        mediaPerm.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
                    else mediaPerm.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    attachSheet = true; loadIncoming()
                }   // attach anything
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "Look",
                tint = T.inkSoft,
                modifier = Modifier.size(24.dp).clickable { onLook() }   // point-and-identify (Look mode)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Send",
                fontSize = T.small,
                color = if (text.isBlank() || thinking) T.inkFaint else T.bgElevated,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (text.isBlank() || thinking) T.hairline else T.accent)
                    .clickable { submit(text, false) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }

        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            AttachChip(
                kind = "IMG",
                title = if (photos.size == 1) "1 photo" else "${photos.size} photos",
                hint = "tap to view · hold for options",
                onPreview = { preview(photos.first()) },
                onRemove = { photos = emptyList() },
                onLongPress = { openQuick(photos.first()) }
            )
        }
        // SENT TO YOU — recent documents people emailed you. Clean, swipeable cards: swipe right to open
        // (preview), left to dismiss, tap to attach. Marked seen on fetch, so they never nag twice.
        if (nudges.isNotEmpty() && photos.isEmpty() && attachments.isEmpty() && reply.isBlank() && !thinking) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("SENT TO YOU")
            Spacer(Modifier.height(8.dp))
            nudges.forEach { n ->
                val dragX = remember(n.key) { mutableStateOf(0f) }
                val kind = if (n.isPdf) "PDF" else n.name.substringAfterLast('.', "FILE").uppercase().take(4)
                val nice = n.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().take(34)
                Row(
                    Modifier.fillMaxWidth()
                        .offset { androidx.compose.ui.unit.IntOffset(dragX.value.toInt(), 0) }
                        .pointerInput(n.key) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        dragX.value < -130f -> {
                                            com.agentos.shell.tools.Inbox.dismiss(ctx, n.key)
                                            nudges = nudges.filterNot { it.key == n.key }
                                        }
                                        dragX.value > 130f -> scope.launch {
                                            val u = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.resolve(ctx, n) }
                                            if (u != null) preview(u)
                                        }
                                    }
                                    dragX.value = 0f
                                },
                                onDragCancel = { dragX.value = 0f }
                            ) { _, dx -> dragX.value = (dragX.value + dx).coerceIn(-320f, 320f) }
                        }
                        .clip(RoundedCornerShape(15.dp))
                        .background(T.bgElevated)
                        .combinedClickable(onClick = { attachIncoming(n) }, onLongClick = { quickFromIncoming(n) })
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypeTile(kind, accent = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.who, fontSize = T.small, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(nice, fontSize = 11.sp, color = T.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        attachments.forEach { file ->
            val nm = com.agentos.shell.tools.FileOps.displayName(ctx, file)
            val isPdfFile = remember(nm) { com.agentos.shell.tools.FileOps.isPdf(ctx, file) }
            Spacer(Modifier.height(8.dp))
            AttachChip(
                kind = if (isPdfFile) "PDF" else nm.substringAfterLast('.', "FILE").uppercase().take(4),
                title = nm,
                hint = "tap to view · hold for options",
                onPreview = { preview(file) },
                onRemove = {
                    attachments = attachments.filterNot { it == file }
                    if (attachments.isEmpty()) com.agentos.shell.tools.AttachContext.clear(ctx)
                },
                onLongPress = { openQuick(file) }
            )
        }

        calCard?.let { (label, evs) ->
            Spacer(Modifier.height(14.dp))
            CalendarCard(label, evs) { calCard = null }
        }

        if (thinking || reply.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            // Tinder-style: swipe the answer card LEFT to dismiss, RIGHT to open its link / Google the question.
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { androidx.compose.ui.unit.IntOffset(replyDragX.toInt(), 0) }
                    .let { m -> if (thinking) m else m.pointerInput(reply) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    replyDragX < -130f -> { reply = ""; rememberSuggestion = ""; showChecklist = false }
                                    replyDragX > 130f -> {
                                        val url = Regex("https?://\\S+").find(reply)?.value?.trimEnd('.', ')', ',')
                                            ?: "https://www.google.com/search?q=" + android.net.Uri.encode(lastQuery.ifBlank { reply.take(60) })
                                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                                    }
                                }
                                replyDragX = 0f
                            },
                            onDragCancel = { replyDragX = 0f }
                        ) { _, dx -> replyDragX = (replyDragX + dx).coerceIn(-320f, 320f) }
                    } }
                    .clip(RoundedCornerShape(16.dp))
                    .background(T.bgElevated)
                    .padding(16.dp)
            ) {
                if (thinking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SlyOrbit(30)
                        Spacer(Modifier.width(14.dp))
                        Text("thinking…", fontSize = T.body, color = T.inkFaint)
                    }
                } else {
                    // Rich Visual Output: a stylized hero card from the model's card tag (reliable) or from
                    // best-effort detection — plus the clean answer text with the tag stripped.
                    val (hero, body) = remember(reply) { RichParse.render(reply) }
                    if (hero != null) { HeroCardView(hero); Spacer(Modifier.height(12.dp)) }
                    // Full answer rendered as elegant markdown (headings, bold, bullets, steps, quotes),
                    // scrollable so long replies aren't cut to a few lines.
                    Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        MarkdownText(body)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Copy", fontSize = T.small, color = T.accent,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                            .clickable {
                                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("reply", body))
                            }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            // If the agent created something with a link (Google Doc/Sheet/Slides), offer to open it.
            val replyUrl = remember(reply) { Regex("https?://[^\\s]+").find(reply)?.value?.trimEnd('.', ')', ',') }
            if (!thinking && replyUrl != null) {
                Spacer(Modifier.height(8.dp))
                Text("↗ Open in Google", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable {
                            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(replyUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                        }.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }

        // An image the AI just made/edited — preview it, then one tap to keep it in the gallery.
        producedImage?.let { png ->
            val bmp = remember(png) {
                android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(12.dp)) {
                if (bmp != null) Image(
                    bitmap = bmp, contentDescription = "result",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Save to gallery", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable {
                                val toSave = png
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        com.agentos.shell.tools.PowerDispatch.saveImage(ctx, toSave, producedName.ifBlank { "slyos_${System.currentTimeMillis()}.png" })
                                    }
                                    reply = if (uri != null) "Saved to your gallery." else "I couldn't save that."
                                    withContext(Dispatchers.IO) { com.agentos.shell.tools.MemoryLog.add(ctx, "action", "Saved an image", producedName, "Files") }
                                    producedImage = null
                                }
                            }.padding(horizontal = 16.dp, vertical = 9.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Tweak", fontSize = T.small, color = T.accent,
                        modifier = Modifier.clickable {
                            // Re-attach the result (to cache, NOT the gallery) so the next thing you type edits IT.
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    try {
                                        val f = java.io.File(ctx.cacheDir, "tweak_${System.currentTimeMillis()}.png")
                                        f.writeBytes(png)
                                        androidx.core.content.FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", f)
                                    } catch (e: Exception) { null }
                                }
                                if (uri != null) { photos = listOf(uri); producedImage = null; reply = "" }
                            }
                        }.padding(horizontal = 10.dp, vertical = 9.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Discard", fontSize = T.small, color = T.inkFaint,
                        modifier = Modifier.clickable { producedImage = null }.padding(horizontal = 10.dp, vertical = 9.dp))
                }
            }
        }

        // Live checklist card — appears when the user touches or asks to see the checklist. It reads
        // straight from ChecklistStore (the brain's source of truth), so it can NEVER disagree with what
        // SlyOS just said. Tap a row to toggle done; tap ✕ to remove; both persist and stay in sync.
        if (showChecklist) {
            val items = remember(checklistTick) { com.agentos.shell.tools.ChecklistStore.load(ctx) }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Checklist", fontSize = T.body, fontWeight = FontWeight.SemiBold, color = T.ink, modifier = Modifier.weight(1f))
                    Text("${items.count { !it.done }} open", fontSize = T.small, color = T.inkSoft)
                }
                if (items.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing on your checklist.", fontSize = T.small, color = T.inkFaint)
                } else {
                    items.forEach { item ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (item.done) "☑" else "☐", fontSize = T.body,
                                color = if (item.done) T.accent else T.inkSoft,
                                modifier = Modifier.clickable {
                                    com.agentos.shell.tools.ChecklistStore.toggle(ctx, item.id); checklistTick++
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                item.text, fontSize = T.small,
                                color = if (item.done) T.inkFaint else T.ink,
                                modifier = Modifier.weight(1f)
                            )
                            Text("✕", fontSize = T.small, color = T.inkFaint,
                                modifier = Modifier.clickable {
                                    com.agentos.shell.tools.ChecklistStore.remove(ctx, item.id)
                                    try { com.agentos.shell.tools.MessageStore.insertOne(ctx, "Checklist", "Checklist", "system", "system", "Removed from checklist: \"${item.text}\"") } catch (e: Exception) {}
                                    checklistTick++
                                }.padding(start = 10.dp))
                        }
                    }
                }
            }
        }

        // Consequential actions await a single tap to confirm — fields pre-filled and editable.
        pendingConfirm?.let { acts ->
            Spacer(Modifier.height(10.dp))
            ConfirmActionCard(
                actions = acts,
                onResult = { msg -> pendingConfirm = null; if (msg.isNotBlank()) reply = (if (reply.isNotBlank()) reply + "\n\n" else "") + msg },
                onDismiss = { pendingConfirm = null }
            )
        }

        if (rememberSuggestion.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Already auto-saved to the brain's learned facts; offer to also pin to your About.
                Text("✓ Remembered: $rememberSuggestion", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.weight(1f))
                Text(
                    "Pin to About", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable {
                        val cur = MemoryStore.about(ctx)
                        val updated = if (cur.isBlank()) rememberSuggestion else "$cur\n$rememberSuggestion"
                        MemoryStore.setAbout(ctx, updated)
                        rememberSuggestion = ""
                    }.padding(start = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clickable { startVoice() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("●", color = T.accent)
            Spacer(Modifier.height(6.dp))
            Text("tap to talk", fontSize = T.small, color = T.inkSoft)
        }

        Spacer(Modifier.weight(1f))
        // Efficiency moved to Settings. Keep only a quiet offline warning so setup isn't missed.
        if (!AgentClient.hasKey()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("agent offline — add API key in Settings", fontSize = T.caption, color = T.danger)
            }
        }
    }

    if (showAdd) {
        val apps = remember { ToolRouter.installedApps(ctx) }
        val miniApps = remember { AppStore.load(ctx) }
        Dialog(onDismissRequest = { showAdd = false; showAddBtn = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(16.dp)
            ) {
                Text("SlyOS", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text("⌨  Manual mode", fontSize = T.small, color = T.accent,
                            modifier = Modifier.fillMaxWidth().clickable { showAdd = false; showAddBtn = false; onManual() }.padding(vertical = 10.dp))
                        Text("＋  Create a new mini-app (Opus)", fontSize = T.small, color = T.accent,
                            modifier = Modifier.fillMaxWidth().clickable { showAdd = false; showAddBtn = false; onArchitect() }.padding(vertical = 10.dp))
                        Text("ADD TO HOME", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 10.dp))
                        if (miniApps.isNotEmpty()) Text("MINI-APPS", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(miniApps, key = { "m" + it.id }) { a ->
                        Text("◆  ${a.name}", fontSize = T.body, color = T.ink,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { ShortcutStore.add(ctx, "miniapp", a.name, a.id.toString()); refreshShortcuts(); showAdd = false }
                                .padding(vertical = 10.dp))
                    }
                    item { Text("APPS", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.padding(top = 8.dp)) }
                    items(apps, key = { "a" + it.pkg }) { app ->
                        Text(app.label, fontSize = T.body, color = T.ink,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { ShortcutStore.add(ctx, "app", app.label, app.pkg); refreshShortcuts(); showAdd = false }
                                .padding(vertical = 10.dp))
                    }
                }
                Text("Close", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { showAdd = false; showAddBtn = false }.padding(top = 8.dp))
            }
        }
    }

    // QUICK OPTIONS — long-press a file. A few content-aware actions; each just runs through the normal
    // planner so behaviour stays consistent. "Read it" is first, so a hold = an instant summary.
    quickFile?.let { qf ->
        Dialog(onDismissRequest = { quickFile = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(T.bgElevated).padding(18.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(T.hairline))
                }
                Spacer(Modifier.height(14.dp))
                Text(com.agentos.shell.tools.FileOps.displayName(ctx, qf), fontSize = T.small, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                val actions: List<Pair<String, String>> = if (quickIsPdf)
                    listOf("Summarize it" to "summarize this document", "Fill it in" to "fill this in",
                        "Send it" to "send it", "File it away" to "file it")
                else
                    listOf("What's in it?" to "what's in this photo?", "Remove background" to "remove the background",
                        "Send it" to "send it", "Edit it…" to "")
                actions.forEach { (label, phrase) ->
                    Text(label, fontSize = T.body, color = T.ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable {
                                quickFile = null
                                if (phrase.isBlank()) { text = "edit this photo: " }   // let them type the edit
                                else submit(phrase, false)
                            }
                            .padding(horizontal = 6.dp, vertical = 13.dp))
                }
            }
        }
    }

    // ATTACH — one sheet: shoot it, browse for it, or grab something someone already sent you.
    if (attachSheet) {
        Dialog(onDismissRequest = { attachSheet = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(T.bgElevated)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // Grabber — reads as a sheet you can pull, not a dialog box.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(T.hairline))
                }
                Spacer(Modifier.height(16.dp))

                AttachRow("PIC", "Photos", "pick from your gallery", accent = true) {
                    attachSheet = false
                    pickGallery.launch(androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                AttachRow("FILE", "Browse files", "PDFs, docs, anything", accent = true) {
                    attachSheet = false; pickFile.launch(arrayOf("*/*"))
                }
                AttachRow("CAM", "Take a photo", "shoot it now", accent = true) {
                    attachSheet = false; capture()
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("SENT TO YOU")
                Spacer(Modifier.height(8.dp))
                if (loadingIncoming) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                        SlyOrbit(28)
                    }
                } else if (incoming.isEmpty()) {
                    Text(
                        "Nothing yet. Connect Google in Settings and whatever people email you shows up here.",
                        fontSize = 12.sp, color = T.inkFaint, modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        incoming.take(14).forEach { item ->
                            val kind = if (item.source == "email")
                                (if (item.isPdf) "PDF" else item.name.substringAfterLast('.', "FILE").uppercase())
                            else "IMG"
                            val meta = item.who +
                                (agoLabel(item.ts).let { if (it.isBlank()) "" else " · $it" })
                            AttachRow(
                                kind, item.name, meta,
                                onPreview = {
                                    scope.launch {
                                        val u = withContext(Dispatchers.IO) { com.agentos.shell.tools.Inbox.resolve(ctx, item) }
                                        if (u != null) preview(u)
                                    }
                                }
                            ) { attachIncoming(item) }
                        }
                    }
                }
            }
        }
    }

    // Bank/vault reveal — entirely on-device, behind the PIN.
    if (vaultPinPrompt) {
        Dialog(onDismissRequest = { vaultPinPrompt = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Unlock bank vault", fontSize = T.body, color = T.ink)
                Text("Enter your vault PIN to view your bank info. It stays on your phone.", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.text.BasicTextField(vaultPin, { vaultPin = it }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = T.ink, fontSize = T.body),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).padding(12.dp))
                if (vaultErr.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(vaultErr, fontSize = T.caption, color = T.danger) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unlock", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (vaultPin.isNotBlank()) T.accent else T.hairline)
                            .clickable(enabled = vaultPin.isNotBlank()) {
                                val u = com.agentos.shell.tools.BankVault.unlock(ctx, vaultPin)
                                if (u != null) { vaultReveal = u; vaultPinPrompt = false; vaultPin = "" } else vaultErr = "Wrong PIN."
                            }.padding(horizontal = 18.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cancel", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { vaultPinPrompt = false; vaultPin = "" }.padding(vertical = 10.dp))
                }
            }
        }
    }
    vaultReveal?.let { list ->
        Dialog(onDismissRequest = { vaultReveal = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Your bank vault", fontSize = T.body, color = T.ink)
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

@Composable
private fun NavIcon(icon: ImageVector, label: String, onClick: () -> Unit) =
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = T.inkSoft,
        modifier = Modifier.size(26.dp).clickable { onClick() }
    )
