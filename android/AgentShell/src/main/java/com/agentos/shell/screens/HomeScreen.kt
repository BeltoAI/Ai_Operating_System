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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.Screen
import com.agentos.shell.theme.T
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import com.agentos.shell.tools.AgentClient
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
    onArchitect: () -> Unit = {},
    onSpicy: (String) -> Unit = {},
    onResearch: (String) -> Unit = {},
    onOpenApp: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var rememberSuggestion by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(MetricsStore.savedMinutesToday(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var showEff by remember { mutableStateOf(false) }
    val shortcuts = remember { mutableStateListOf<ShortcutStore.Shortcut>().apply { addAll(ShortcutStore.list(ctx)) } }
    fun refreshShortcuts() { shortcuts.clear(); shortcuts.addAll(ShortcutStore.list(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragX by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var history by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let { photos = photos + it }
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
        thinking = true; reply = ""; rememberSuggestion = ""; text = ""
        scope.launch {
            // If photos are attached, this is an image task (vision Q&A or PDF).
            if (photos.isNotEmpty()) {
                val attached = photos
                photos = emptyList()
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
                        AgentClient.askVision(q, b64s, MemoryStore.about(ctx))
                    }
                }
                if (doSpeak) speak(reply)
                thinking = false
                return@launch
            }

            val apps = withContext(Dispatchers.IO) { ToolRouter.installedApps(ctx).map { it.label } }
            val context = withContext(Dispatchers.IO) {
                val mem = MemoryStore.fullProfile(ctx)
                val cal = CalendarTool.upcoming(ctx)
                val now = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val recall = if (MemoryStore.recallEnabled(ctx))
                    com.agentos.shell.tools.InteractionStore.retrieve(ctx, q, 20) else ""
                // Tap the brain directly so the Home agent can answer about people, chats and your
                // network — not just your bio. (Cheap SQLite lookups; empty for plain commands.)
                val chats = com.agentos.shell.tools.MessageStore.search(ctx, q, 10)
                    .joinToString(" · ") { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
                    .take(2200)
                val net = com.agentos.shell.tools.ConnectionStore.search(ctx, q, 10)
                    .joinToString(" · ") { it.name + (if (it.role.isNotBlank()) " (${it.role})" else "") + (if (it.company.isNotBlank()) " @ ${it.company}" else "") }
                    .take(1200)
                val papers = com.agentos.shell.tools.PaperStore.libraryContext(ctx, 0L, q, 1600)
                val docText = if (com.agentos.shell.tools.KnowledgeStore.hasDoc(ctx))
                    com.agentos.shell.tools.KnowledgeStore.retrieve(ctx, q, 1600) else ""
                buildString {
                    if (mem.isNotBlank()) append(mem)
                    if (cal.isNotBlank()) append("\nUpcoming calendar:\n").append(cal)
                    if (chats.isNotBlank())
                        append("\nFrom your message history (use ONLY if relevant):\n").append(chats)
                    if (net.isNotBlank())
                        append("\nFrom your contacts/network (use ONLY if relevant):\n").append(net)
                    if (papers.isNotBlank())
                        append("\nFrom your own research papers (use ONLY if relevant):\n").append(papers)
                    if (docText.isNotBlank())
                        append("\nFrom your loaded document (use ONLY if relevant):\n").append(docText)
                    if (recall.isNotBlank())
                        append("\nFrom what I've seen on your screen (use ONLY if relevant to the request):\n").append(recall)
                    append("\nCurrent time: ").append(now)
                }
            }
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
            // write_paper navigates to the Research workspace, pre-filled.
            val paperAct = result.actions.firstOrNull { it.type == "write_paper" }
            if (paperAct != null) {
                thinking = false
                onResearch(paperAct.arg.ifBlank { q })
                return@launch
            }

            val actionMsg = withContext(Dispatchers.IO) {
                ToolRouter.executeActions(ctx, result.actions)
            }
            reply = if (actionMsg.isNotEmpty()) actionMsg else result.say
            refreshShortcuts()
            if (doSpeak) speak(reply)
            history = (history + (q to reply)).takeLast(6)
            // Capture this exchange as connected memories.
            val pk = MemoryLog.add(ctx, "prompt", q, q, "Home prompt")
            MemoryLog.add(ctx, "response", reply, reply, "Agent reply", pk)
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

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.combinedClickable(onClick = { onManual() }, onLongClick = { onArchitect() })) { Wordmark() }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Add, contentDescription = "Add shortcut", tint = T.inkSoft,
                modifier = Modifier.size(26.dp).clickable { showAdd = true })
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
                                    onDragStart = { draggingId = s.id },
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
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (s.kind == "app") T.bgElevated else T.accent)
                                    .clickable {
                                        if (s.kind == "app") ToolRouter.launchApp(ctx, s.ref)
                                        else s.ref.toLongOrNull()?.let { onOpenApp(it) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (s.kind == "app") (s.label.firstOrNull()?.uppercase() ?: "•") else "◆",
                                    fontSize = T.body, color = if (s.kind == "app") T.ink else T.bgElevated)
                            }
                            Box(Modifier.size(17.dp).clip(CircleShape).background(T.hairline)
                                .clickable { ShortcutStore.remove(ctx, s.id); refreshShortcuts() },
                                contentAlignment = Alignment.Center) {
                                Text("✕", fontSize = T.caption, color = T.inkSoft)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.label.take(10), fontSize = T.caption, color = T.inkSoft, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("what should happen?", fontSize = T.prompt, color = T.ink)
        Spacer(Modifier.height(14.dp))

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
                Icons.Filled.PhotoCamera,
                contentDescription = "Take photo",
                tint = if (photos.isEmpty()) T.inkSoft else T.accent,
                modifier = Modifier.size(24.dp).clickable { capture() }
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
            Spacer(Modifier.height(8.dp))
            Text(
                "📷 ${photos.size} photo${if (photos.size > 1) "s" else ""} attached · ask about it, or say \"save as PDF\"",
                fontSize = T.caption, color = T.accent
            )
        }

        if (thinking || reply.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(T.bgElevated)
                    .padding(14.dp)
            ) {
                Text("●", color = T.accent, fontSize = T.small)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (thinking) "thinking…" else reply,
                    fontSize = T.body,
                    color = if (thinking) T.inkFaint else T.ink
                )
            }
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
        val online = AgentClient.hasKey()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
                    .clickable { showEff = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(if (online) Color(0xFF4E9A5B) else T.danger))
                Spacer(Modifier.width(7.dp))
                Text(
                    if (!online) "agent offline — add API key"
                    else "online" + (com.agentos.shell.tools.MetricsStore.savedLabelToday(ctx).let { if (it.isNotEmpty()) "  ·  $it" else "" }),
                    fontSize = T.caption, color = T.inkSoft
                )
            }
        }
    }

    if (showEff) {
        val score = remember { com.agentos.shell.tools.MetricsStore.efficiencyScore(ctx) }
        val trend = remember { com.agentos.shell.tools.MetricsStore.trendPct(ctx) }
        val hist = remember { com.agentos.shell.tools.MetricsStore.history(ctx, 14) }
        val weekMin = remember { hist.takeLast(7).sumOf { it.savedMin } }
        Dialog(onDismissRequest = { showEff = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)
            ) {
                Text("Efficiency", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$score", fontSize = 46.sp, color = T.accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text("/100", fontSize = T.body, color = T.inkFaint, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.weight(1f))
                    val up = trend >= 0
                    Text((if (up) "▲ +" else "▼ ") + "$trend%",
                        fontSize = T.body, color = if (up) Color(0xFF4E9A5B) else T.danger,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("vs last week · ~${if (weekMin >= 60) "${weekMin / 60}h ${weekMin % 60}m" else "$weekMin min"} saved this week",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(16.dp))

                // 14-day minutes-saved bar chart.
                val maxV = (hist.maxOfOrNull { it.savedMin } ?: 0).coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val n = hist.size
                    val gap = 6f
                    val bw = (size.width - gap * (n - 1)) / n
                    hist.forEachIndexed { i, d ->
                        val h = (d.savedMin.toFloat() / maxV) * (size.height - 6f)
                        val x = i * (bw + gap)
                        val today = i == n - 1
                        drawRect(
                            color = if (today) T.accent else T.accent.copy(alpha = 0.35f),
                            topLeft = Offset(x, size.height - h),
                            size = Size(bw, h)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(hist.firstOrNull()?.label ?: "", fontSize = T.caption, color = T.inkFaint)
                    Text("14 days", fontSize = T.caption, color = T.inkFaint)
                    Text("today", fontSize = T.caption, color = T.inkSoft)
                }
                Spacer(Modifier.height(14.dp))
                Text("Score = your 7-day average time saved (≈1 hr/day is 100). Keep letting the agent handle things to push it up.",
                    fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(12.dp))
                Text("Close", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable { showEff = false })
            }
        }
    }

    if (showAdd) {
        val apps = remember { ToolRouter.installedApps(ctx) }
        val miniApps = remember { AppStore.load(ctx) }
        Dialog(onDismissRequest = { showAdd = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(16.dp)
            ) {
                Text("Add to Home", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text("＋ Create a new mini-app (Opus)", fontSize = T.small, color = T.accent,
                            modifier = Modifier.fillMaxWidth().clickable { showAdd = false; onArchitect() }.padding(vertical = 10.dp))
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
                    modifier = Modifier.clickable { showAdd = false }.padding(top = 8.dp))
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
