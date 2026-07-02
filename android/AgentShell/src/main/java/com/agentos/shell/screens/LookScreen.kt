package com.agentos.shell.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ImageUtil
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val SCRIM = Color(0xCC141210)
private val ACC = Color(0xFFE8642C)

/**
 * "Look" — a full-frame live camera. Tap anywhere to point at an object (it drops a reticle and
 * identifies THAT thing), or hit the mic to ask a spoken follow-up about what you see. One tap to
 * shop / map / search. Everything feeds the brain.
 */
@Composable
fun LookScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Text-to-speech so spoken follow-ups get spoken answers (smart-glasses feel).
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val e = TextToSpeech(ctx) {}
        tts.value = e
        onDispose { e.stop(); e.shutdown() }
    }
    fun speak(s: String) { if (s.isNotBlank()) tts.value?.apply { language = Locale.getDefault(); speak(s, TextToSpeech.QUEUE_FLUSH, null, "look") } }

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var result by remember { mutableStateOf<AgentClient.LookResult?>(null) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var tap by remember { mutableStateOf<Offset?>(null) }        // where the user pointed (px)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun openUrl(u: String) {
        if (u.isBlank()) return
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }
    fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    fun actionUrl(r: AgentClient.LookResult): String {
        val q = r.query.ifBlank { r.title }
        return when (r.category) {
            "product" -> "https://www.google.com/search?tbm=shop&q=" + enc(q)
            "place" -> "https://www.google.com/maps/search/?api=1&query=" + enc(r.place.ifBlank { q })
            "food" -> "https://www.google.com/search?q=" + enc("$q recipe")
            else -> "https://www.google.com/search?q=" + enc(q)
        }
    }
    fun actionLabel(cat: String) = when (cat) { "product" -> "Shop it"; "place" -> "Open in Maps"; "food" -> "Recipes"; else -> "Search" }

    // Grab the current frame as a Bitmap (main-thread callback).
    fun capture(cb: (Bitmap?) -> Unit) {
        if (!granted) { cb(null); return }
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val b = try { image.toBitmap() } catch (e: Exception) { null }; image.close(); cb(b)
            }
            override fun onError(exc: ImageCaptureException) { cb(null) }
        })
    }

    fun identify(focus: Offset?) {
        if (busy || !granted) return
        busy = true; answer = ""
        val fx = if (focus != null && canvasSize.width > 0) (focus.x / canvasSize.width).coerceIn(0f, 1f) else null
        val fy = if (focus != null && canvasSize.height > 0) (focus.y / canvasSize.height).coerceIn(0f, 1f) else null
        capture { bmp ->
            if (bmp == null) { busy = false; return@capture }
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    val b64 = ImageUtil.encodeBitmap(bmp) ?: return@withContext null
                    AgentClient.lookAt(b64, MemoryStore.fullProfile(ctx), fx, fy)
                }
                result = r; busy = false
                if (r != null && r.title.isNotBlank()) withContext(Dispatchers.IO) {
                    MessageStore.insertOne(ctx, "Look", "Camera", "system", "system", "Looked at: ${r.title} — ${r.detail}")
                    MetricsStore.record(ctx, 60)
                }
            }
        }
    }

    fun askAboutFrame(q: String) {
        if (busy || q.isBlank()) return
        busy = true; answer = ""
        capture { bmp ->
            if (bmp == null) { busy = false; return@capture }
            scope.launch {
                val a = withContext(Dispatchers.IO) {
                    val b64 = ImageUtil.encodeBitmap(bmp) ?: return@withContext ""
                    AgentClient.askVision(q, listOf(b64), MemoryStore.fullProfile(ctx))
                }
                answer = if (AgentClient.looksLikeError(a)) "Couldn't answer that — try again." else a
                busy = false
                if (!AgentClient.looksLikeError(a) && a.isNotBlank()) { speak(a); withContext(Dispatchers.IO) { MessageStore.insertOne(ctx, "Look", "Camera", "system", "system", "Q: $q — A: $a") } }
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) askAboutFrame(said)
    }
    fun startVoice() {
        try {
            voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about what you see"))
        } catch (e: Exception) {}
    }

    LaunchedEffect(auto) { while (auto) { if (!busy) identify(null); delay(4500) } }

    // Reticle pulse.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")

    // FULL-FRAME: ignore the passed padding modifier so the camera fills the whole screen.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { c ->
                val pv = PreviewView(c)
                pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    } catch (e: Exception) {}
                }, ContextCompat.getMainExecutor(c))
                pv
            }, modifier = Modifier.fillMaxSize())

            // Tap-to-point layer + reticle.
            Canvas(Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) { detectTapGestures { off -> tap = off; identify(off) } }
            ) {
                tap?.let { p ->
                    val r = 46f * pulse
                    drawCircle(ACC, radius = r, center = p, style = Stroke(width = 4f))
                    drawCircle(ACC.copy(alpha = 0.25f), radius = r * 1.7f, center = p, style = Stroke(width = 2f))
                    drawLine(ACC, Offset(p.x - 60f, p.y), Offset(p.x - 20f, p.y), strokeWidth = 3f)
                    drawLine(ACC, Offset(p.x + 20f, p.y), Offset(p.x + 60f, p.y), strokeWidth = 3f)
                    drawLine(ACC, Offset(p.x, p.y - 60f), Offset(p.x, p.y - 20f), strokeWidth = 3f)
                    drawLine(ACC, Offset(p.x, p.y + 20f), Offset(p.x, p.y + 60f), strokeWidth = 3f)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera access needed to identify what you point at.", color = Color.White, fontSize = T.small, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text("Allow camera", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(ACC).clickable { permLauncher.launch(Manifest.permission.CAMERA) }.padding(horizontal = 22.dp, vertical = 12.dp))
            }
        }

        // Top bar: Back + hint + Auto toggle.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← Back", color = Color.White, fontSize = T.small,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SCRIM).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(if (auto) "Auto ●" else "Auto ○", color = if (auto) ACC else Color.White, fontSize = T.small,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SCRIM).clickable { auto = !auto }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (result == null && answer.isBlank())
            Text(if (busy) "Looking…" else "Tap anything to identify it", color = Color.White, fontSize = T.small,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp).clip(RoundedCornerShape(999.dp)).background(SCRIM).padding(horizontal = 14.dp, vertical = 7.dp))

        // Bottom overlay: result / answer card + mic + Identify.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            result?.let { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SCRIM).padding(18.dp)) {
                    Text(r.title, color = Color.White, fontSize = T.prompt)
                    if (r.detail.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(r.detail, color = Color(0xFFDDD6CC), fontSize = T.small) }
                    if (answer.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(answer, color = Color.White, fontSize = T.small) }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(actionLabel(r.category) + " →", color = Color.White, fontSize = T.small, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(ACC).clickable { openUrl(actionUrl(r)) }.padding(vertical = 11.dp))
                        Text("Search", color = Color.White, fontSize = T.small, textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x33FFFFFF)).clickable { openUrl("https://www.google.com/search?q=" + enc(r.query.ifBlank { r.title })) }.padding(horizontal = 16.dp, vertical = 11.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (result == null && answer.isNotBlank()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SCRIM).padding(18.dp)) {
                    Text(answer, color = Color.White, fontSize = T.small)
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🎤", fontSize = T.prompt, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SCRIM).clickable(enabled = !busy && granted) { startVoice() }.padding(horizontal = 18.dp, vertical = 12.dp))
                Text(if (busy) "Looking…" else "Identify", color = Color.White, fontSize = T.body, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (busy) Color(0x66E8642C) else ACC)
                        .clickable(enabled = !busy && granted) { tap = null; identify(null) }.padding(vertical = 16.dp))
            }
        }
    }
}
