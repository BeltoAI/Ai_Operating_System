package com.agentos.shell.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ImageUtil
import com.agentos.shell.tools.PeopleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Who's this?" — teach SlyOS your people (name + a face photo), then point the camera at someone and it
 * tells you who it thinks it is, matched against your roster by the model's vision. On-device only.
 */
@Composable
fun FaceScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roster by remember { mutableStateOf(PeopleStore.list(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf("") }
    var enrollShot by remember { mutableStateOf<Bitmap?>(null) }
    var enrollName by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }   // "recognize" | "enroll"

    val recognizeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            busy = true; resultMsg = "Looking…"
            scope.launch {
                val shot = withContext(Dispatchers.IO) { ImageUtil.encodeBitmap(bmp) }
                val ros = withContext(Dispatchers.IO) {
                    roster.mapNotNull { p -> PeopleStore.photoB64(ctx, p.id)?.let { p.name to it } }
                }
                val who = if (shot == null) "UNKNOWN"
                          else withContext(Dispatchers.IO) { AgentClient.identifyPerson(shot, ros) }
                resultMsg = when {
                    ros.isEmpty() -> "Add some people first, then I can recognize them."
                    who == "UNKNOWN" -> "New face — not in your people yet. Add them below."
                    else -> "This looks like $who."
                }
                busy = false
            }
        }
    }
    val enrollLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) { enrollShot = bmp; enrollName = "" }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (pending == "recognize") recognizeLauncher.launch(null) else enrollLauncher.launch(null) }
    }
    fun capture(which: String) {
        pending = which
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (which == "recognize") recognizeLauncher.launch(null) else enrollLauncher.launch(null)
        } else permLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Who's this?", onBack)
        Spacer(Modifier.height(14.dp))

        Text("Recognize a face", fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Point the camera at someone — SlyOS matches them against the people you've added.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(10.dp))
        Text(if (busy) "Looking…" else "📷  Who's this?", fontSize = T.body, color = T.bgElevated, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (busy) T.hairline else T.accent)
                .clickable(enabled = !busy) { capture("recognize") }.padding(vertical = 14.dp))
        if (resultMsg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                Text(resultMsg, fontSize = T.body, color = if (resultMsg.startsWith("This looks like")) T.accent else T.ink)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Add a person", fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val shot = enrollShot
        if (shot == null) {
            Text("Take a clear photo of their face, then name them.", fontSize = T.small, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            Text("＋  Add with camera", fontSize = T.small, color = T.ink,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline)
                    .clickable { capture("enroll") }.padding(horizontal = 16.dp, vertical = 10.dp))
        } else {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(bitmap = shot.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                BasicTextField(enrollName, { enrollName = it }, singleLine = true,
                    textStyle = TextStyle(color = T.ink, fontSize = T.body),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                    decorationBox = { inner -> if (enrollName.isEmpty()) Text("Their name", fontSize = T.body, color = T.inkFaint); inner() })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (enrollName.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = enrollName.isNotBlank()) {
                            PeopleStore.add(ctx, enrollName, "", shot)
                            roster = PeopleStore.list(ctx); enrollShot = null; enrollName = ""
                        }.padding(horizontal = 18.dp, vertical = 9.dp))
                Spacer(Modifier.width(12.dp))
                Text("Retake", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { capture("enroll") }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cancel", fontSize = T.small, color = T.inkSoft,
                    modifier = Modifier.clickable { enrollShot = null; enrollName = "" }.padding(8.dp))
            }
        }

        if (roster.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Your people · ${roster.size}", fontSize = T.small, color = T.inkFaint)
            Spacer(Modifier.height(8.dp))
            roster.forEach { p ->
                val bmp = remember(p.id) { PeopleStore.photoBitmap(ctx, p.id) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape))
                    else Box(Modifier.size(44.dp).clip(CircleShape).background(T.hairline))
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                    Text("Remove", fontSize = T.small, color = T.danger,
                        modifier = Modifier.clickable { PeopleStore.remove(ctx, p.id); roster = PeopleStore.list(ctx) }.padding(8.dp))
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
