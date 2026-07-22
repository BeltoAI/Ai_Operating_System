package com.agentos.shell.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Take photos → agent writes a post from them → themed preview → edit → post (opens the
 * platform app pre-filled). The "post from a prompt" flow.
 */
@Composable
fun ComposeScreen(
    modifier: Modifier = Modifier,
    platform: String,
    topic: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }

    fun revise() {
        if (editPrompt.isBlank() || caption.isBlank()) return
        val instr = editPrompt; generating = true; status = ""
        scope.launch {
            caption = withContext(Dispatchers.IO) { AgentClient.revisePost(caption, instr, platform, com.agentos.shell.tools.Voice.voiceFor(ctx, platform)) }
            editPrompt = ""; generating = false
        }
    }

    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let { photos = photos + it }
    }
    fun capture() {
        val file = File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", file)
        pendingUri = uri
        takePic.launch(uri)
    }
    fun generate() {
        generating = true
        scope.launch {
            val (b64s, mem) = withContext(Dispatchers.IO) {
                photos.mapNotNull { encodeImage(ctx, it) } to com.agentos.shell.tools.Voice.voiceFor(ctx, platform)
            }
            caption = withContext(Dispatchers.IO) { AgentClient.composePost(platform, topic, b64s, mem) }
            generating = false
        }
    }
    fun post() {
        val intent = if (photos.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                if (photos.isNotEmpty()) putExtra(Intent.EXTRA_STREAM, photos.first())
            }
        }
        intent.type = if (photos.isEmpty()) "text/plain" else "image/*"
        intent.putExtra(Intent.EXTRA_TEXT, caption)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Most social apps ignore pre-filled caption text when an image is attached, so copy
        // it to the clipboard — one paste in the composer.
        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("caption", caption))

        val pkg = packageFor(platform)
        if (pkg != null && appInstalled(ctx, pkg)) {
            intent.setPackage(pkg)
            status = "Caption copied. Opening $platform — long-press to paste it, then post."
        } else {
            status = "Caption copied. $platform isn't installed — opening the share sheet; " +
                "paste the caption where you post."
        }
        ctx.startActivity(Intent.createChooser(intent, "Post to $platform")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        com.agentos.shell.tools.MetricsStore.record(ctx, com.agentos.shell.tools.MetricsStore.secondsFor("social_post"))
        // Feed the brain: your published caption is searchable + grows your learned voice.
        if (caption.isNotBlank()) {
            com.agentos.shell.tools.MemoryLog.add(ctx, "response", "Posted to $platform", caption, platform)
            com.agentos.shell.tools.MessageStore.insertOne(ctx, "My $platform posts", platform, "me", "me", caption)
            com.agentos.shell.tools.MemoryStore.addVoiceSamples(ctx, listOf(caption))
        }
    }

    val accent = accentFor(platform)
    val bitmaps = remember(photos) { photos.mapNotNull { loadBitmap(ctx, it) } }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Create post", onBack)
        Spacer(Modifier.height(8.dp))
        Text("$platform · $topic", fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(14.dp))

        // Photo strip + add button
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bitmaps) { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Pill("＋ Take photo", T.accent) { capture() }

        // Live preview only once there's text.
        if (caption.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            PlatformPreview(platform, accent, bitmaps.firstOrNull(), caption)
        }

        // Caption is always editable, and Post is always available — even if generation
        // fails, you can type your own and still post.
        Spacer(Modifier.height(14.dp))
        Text("Caption — edit freely", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = caption,
            onValueChange = { caption = it },
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
                .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
            decorationBox = { inner ->
                if (caption.isEmpty())
                    Text("Take a photo and tap Generate, or type your post here…",
                        fontSize = T.small, color = T.inkFaint)
                inner()
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = editPrompt,
                onValueChange = { editPrompt = it },
                singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(T.bgElevated).padding(horizontal = 12.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    if (editPrompt.isEmpty())
                        Text("how should I change it?", fontSize = T.small, color = T.inkFaint)
                    inner()
                }
            )
            Spacer(Modifier.width(8.dp))
            Pill(if (generating) "…" else "Edit", T.ink) { revise() }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Pill("Post to $platform", accent) { post() }
            Spacer(Modifier.width(10.dp))
            if (photos.isNotEmpty())
                Pill(if (generating) "writing…" else "Generate", T.ink) { if (!generating) generate() }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = T.caption, color = T.inkSoft)
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, onClick: () -> Unit) =
    Text(label, fontSize = T.small, color = T.bgElevated,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp))

@Composable
private fun PlatformPreview(platform: String, accent: Color, bmp: Bitmap?, caption: String) {
    val ig = platform.lowercase().contains("insta")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White).padding(if (ig) 0.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(if (ig) 12.dp else 0.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(if (ig) "your_handle" else "Your Name", color = Color(0xFF1A1A1A),
                    fontSize = T.small, fontWeight = FontWeight.Medium)
                if (!ig) Text("now · 🌐", color = Color(0xFF666666), fontSize = T.caption)
            }
        }
        if (!ig) Spacer(Modifier.height(10.dp))
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
                    .height(if (ig) 300.dp else 180.dp)
                    .then(if (ig) Modifier else Modifier.clip(RoundedCornerShape(8.dp)))
            )
        }
        Text(
            caption,
            color = Color(0xFF1A1A1A), fontSize = T.small,
            modifier = Modifier.padding(if (ig) 12.dp else 0.dp)
                .padding(top = if (ig) 0.dp else 10.dp)
        )
    }
}

private fun accentFor(platform: String): Color = when {
    platform.lowercase().contains("insta") -> Color(0xFFC13584)
    platform.lowercase().contains("linkedin") -> Color(0xFF0A66C2)
    platform.lowercase().contains("x") || platform.lowercase().contains("twitter") -> Color(0xFF1DA1F2)
    else -> Color(0xFFE8642C)
}

private fun packageFor(platform: String): String? = when {
    platform.lowercase().contains("insta") -> "com.instagram.android"
    platform.lowercase().contains("linkedin") -> "com.linkedin.android"
    platform.lowercase().contains("x") || platform.lowercase().contains("twitter") -> "com.twitter.android"
    else -> null
}

private fun appInstalled(ctx: android.content.Context, pkg: String): Boolean = try {
    ctx.packageManager.getPackageInfo(pkg, 0); true
} catch (e: Exception) { false }

private fun loadBitmap(ctx: android.content.Context, uri: Uri): Bitmap? = try {
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) { null }

private fun encodeImage(ctx: android.content.Context, uri: Uri): String? = try {
    var bmp = loadBitmap(ctx, uri)
    if (bmp == null) null else {
        val max = 1024
        val w = bmp.width; val h = bmp.height
        if (w > max || h > max) {
            val s = max.toFloat() / maxOf(w, h)
            bmp = Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
} catch (e: Exception) { null }
