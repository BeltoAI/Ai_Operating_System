package com.agentos.shell.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.Screen
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.CalendarTool
import com.agentos.shell.tools.ImageUtil
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.PdfTool
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
    onOpen: (Screen) -> Unit,
    onManual: () -> Unit,
    onCompose: (String, String) -> Unit = { _, _ -> },
    onArchitect: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var rememberSuggestion by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(MetricsStore.savedMinutesToday(ctx)) }
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
        val need = listOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.SEND_SMS
        )
        calPerm.launch(need.toTypedArray())
    }

    val submit: (String) -> Unit = submit@{ raw ->
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
                thinking = false
                return@launch
            }

            val apps = withContext(Dispatchers.IO) { ToolRouter.installedApps(ctx).map { it.label } }
            val context = withContext(Dispatchers.IO) {
                val mem = MemoryStore.about(ctx)
                val cal = CalendarTool.upcoming(ctx)
                val now = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                buildString {
                    if (mem.isNotBlank()) append(mem)
                    if (cal.isNotBlank()) append("\nUpcoming calendar:\n").append(cal)
                    append("\nCurrent time: ").append(now)
                }
            }
            val result = withContext(Dispatchers.IO) { AgentClient.ask(q, apps, context, history) }
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

            val actionMsg = withContext(Dispatchers.IO) {
                ToolRouter.executeActions(ctx, result.actions)
            }
            reply = if (actionMsg.isNotEmpty()) actionMsg else result.say
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
            if (!spoken.isNullOrBlank()) { text = spoken; submit(spoken) }
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

    Column(modifier) {
        Box(Modifier.combinedClickable(onClick = { onManual() }, onLongClick = { onArchitect() })) { Wordmark() }

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
                keyboardActions = KeyboardActions(onSend = { submit(text) }),
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
                    .clickable { submit(text) }
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
                Text("Remember: $rememberSuggestion", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.weight(1f))
                Text(
                    "Save", fontSize = T.small, color = T.accent,
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
        if (!AgentClient.hasKey())
            Text("agent offline — add API key", fontSize = T.caption, color = T.danger)
        else
            Text(
                "agent online" + if (saved > 0) " · ~$saved min saved today" else "",
                fontSize = T.caption, color = T.inkFaint
            )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NavIcon(Icons.Filled.Bolt, "Now") { onOpen(Screen.Now) }
            NavIcon(Icons.Filled.People, "People") { onOpen(Screen.People) }
            NavIcon(Icons.Filled.Memory, "Memory") { onOpen(Screen.Memory) }
            NavIcon(Icons.Filled.Apps, "Apps") { onOpen(Screen.Apps) }
            NavIcon(
                if (paused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                if (paused) "Resume" else "Manual"
            ) { onManual() }
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
