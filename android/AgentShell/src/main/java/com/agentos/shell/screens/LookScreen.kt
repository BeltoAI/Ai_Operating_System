package com.agentos.shell.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
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

private val SCRIM = Color(0xCC141210)
private val ACC = Color(0xFFE8642C)

/**
 * "Look" — a LIVE camera viewfinder. Point at anything; tap Identify (or flip on Auto) and SlyOS
 * overlays what it is with one-tap actions (Open to shop / map / search). Every hit feeds the brain.
 */
@Composable
fun LookScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var result by remember { mutableStateOf<AgentClient.LookResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }

    fun openUrl(u: String) {
        if (u.isBlank()) return
        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
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

    fun identify() {
        if (busy || !granted) return
        busy = true
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp: Bitmap? = try { image.toBitmap() } catch (e: Exception) { null }
                image.close()
                if (bmp == null) { busy = false; return }
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        val b64 = ImageUtil.encodeBitmap(bmp) ?: return@withContext null
                        AgentClient.lookAt(b64, MemoryStore.fullProfile(ctx))
                    }
                    result = r; busy = false
                    if (r != null && r.title.isNotBlank()) withContext(Dispatchers.IO) {
                        MessageStore.insertOne(ctx, "Look", "Camera", "system", "system", "Looked at: ${r.title} — ${r.detail}")
                        MetricsStore.record(ctx, 60)
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) { busy = false }
        })
    }

    // Auto mode: keep scanning every few seconds for a "smart glasses" feel (off by default = no cost).
    LaunchedEffect(auto) { while (auto) { if (!busy) identify(); delay(4500) } }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { c ->
                val pv = PreviewView(c)
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
        } else {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera access needed to identify what you point at.", color = Color.White, fontSize = T.small, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text("Allow camera", color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(ACC).clickable { permLauncher.launch(Manifest.permission.CAMERA) }.padding(horizontal = 22.dp, vertical = 12.dp))
            }
        }

        // Top bar: Back + Auto toggle.
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← Back", color = Color.White, fontSize = T.small,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SCRIM).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(if (auto) "Auto ●" else "Auto ○", color = if (auto) ACC else Color.White, fontSize = T.small,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SCRIM).clickable { auto = !auto }.padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // Bottom overlay: the result card + Identify button.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            result?.let { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SCRIM).padding(18.dp)) {
                    Text(r.title, color = Color.White, fontSize = T.prompt)
                    if (r.detail.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(r.detail, color = Color(0xFFDDD6CC), fontSize = T.small) }
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
            Text(if (busy) "Looking…" else "Identify", color = Color.White, fontSize = T.body, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (busy) Color(0x66E8642C) else ACC)
                    .clickable(enabled = !busy && granted) { identify() }.padding(vertical = 16.dp))
        }
    }
}
