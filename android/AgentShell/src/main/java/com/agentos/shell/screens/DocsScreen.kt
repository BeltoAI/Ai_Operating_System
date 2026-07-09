package com.agentos.shell.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.DocStore
import com.agentos.shell.tools.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Scan any document — receipt, invoice, ID, form — and SlyOS reads the key fields, then auto-files it in a
 * folder named after its category. Everything stays on the device.
 */
@Composable
fun DocsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(DocStore.byCategory(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf(0L) }

    fun process(bmp: Bitmap) {
        busy = true; msg = "Reading the document…"
        scope.launch {
            val b64 = withContext(Dispatchers.IO) { ImageUtil.encodeBitmap(bmp, 1568) }   // higher res so small print reads
            val j = if (b64 == null) null else withContext(Dispatchers.IO) { AgentClient.extractForm(b64) }
            if (j == null) { msg = "Couldn't read that — try a clearer, flatter photo."; busy = false; return@launch }
            val folder = withContext(Dispatchers.IO) {
                DocStore.add(ctx, j.optString("category", "other"), j.optString("title", "Document"),
                    j.optString("summary", ""), j.optJSONObject("fields") ?: JSONObject(), bmp)
            }
            docs = DocStore.byCategory(ctx)
            msg = "Filed in $folder ✓ — ${j.optString("title", "Document")}"
            busy = false
        }
    }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> if (bmp != null) process(bmp) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) { ImageUtil.loadBitmap(ctx, uri) }
            if (b != null) process(b) else msg = "Couldn't open that image."
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) camLauncher.launch(null) }
    fun scanCamera() {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camLauncher.launch(null)
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Documents", onBack)
        Spacer(Modifier.height(14.dp))

        Text("Scan a document", fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Snap a receipt, invoice, ID or form — SlyOS reads the key fields and files it into the right folder.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (busy) "Reading…" else "Scan with camera", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (busy) T.hairline else T.accent)
                    .clickable(enabled = !busy) { scanCamera() }.padding(horizontal = 16.dp, vertical = 10.dp))
            Text("Pick from gallery", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable(enabled = !busy) { galleryLauncher.launch("image/*") }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp)) {
                Text(msg, fontSize = T.small, color = if (msg.startsWith("Filed")) T.accent else T.ink)
            }
        }

        docs.forEach { (category, list) ->
            Spacer(Modifier.height(22.dp))
            Text("📁  ${DocStore.folderPath(category)}  ·  ${list.size}", fontSize = T.small, color = T.inkFaint, fontWeight = FontWeight.SemiBold)
            list.forEach { d ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
                    .clickable { expandedId = if (expandedId == d.id) 0L else d.id }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontSize = T.body, color = T.ink)
                            if (d.summary.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(d.summary, fontSize = T.caption, color = T.inkSoft) }
                        }
                        Text("Remove", fontSize = T.caption, color = T.danger,
                            modifier = Modifier.clickable { DocStore.remove(ctx, d.id); docs = DocStore.byCategory(ctx) }.padding(6.dp))
                    }
                    if (expandedId == d.id) {
                        Spacer(Modifier.height(10.dp))
                        val fields = try { JSONObject(d.fieldsJson) } catch (e: Exception) { JSONObject() }
                        val keys = fields.keys().asSequence().toList()
                        if (keys.isEmpty()) Text("No fields extracted.", fontSize = T.caption, color = T.inkFaint)
                        else keys.forEach { k ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(k, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.width(120.dp))
                                Text(fields.optString(k), fontSize = T.caption, color = T.ink, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
