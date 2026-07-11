package com.agentos.shell.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.agentos.shell.theme.T
import com.agentos.shell.tools.VoiceSampleStore
import kotlinx.coroutines.delay

/**
 * "Set up my voice" — records a short read-aloud sample the on-device cloner uses to speak as the owner.
 * The recording never leaves the phone. Reading the script once (~20 s) is enough for a zero-shot clone.
 */
@Composable
fun VoiceSetupDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    var hasSample by remember { mutableStateOf(VoiceSampleStore.hasSample(ctx)) }
    var status by remember { mutableStateOf("") }
    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun stop() {
        try { recorder.value?.stop() } catch (e: Exception) {}
        try { recorder.value?.release() } catch (e: Exception) {}
        recorder.value = null
        recording = false
        hasSample = VoiceSampleStore.hasSample(ctx)
        status = if (hasSample) "Saved — that's your voice ✓" else "That was too short — try again."
    }

    fun start() {
        if (!micGranted()) { status = "Turn on Microphone permission first (Finish setup on Home)."; return }
        try {
            try { player.value?.release() } catch (e: Exception) {}
            val file = VoiceSampleStore.sampleFile(ctx)
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            recorder.value = r; recording = true; elapsed = 0; status = "Recording — read the lines below."
        } catch (e: Exception) { status = "Couldn't start recording."; recording = false }
    }

    fun play() {
        try {
            player.value?.release()
            val mp = MediaPlayer()
            mp.setDataSource(VoiceSampleStore.sampleFile(ctx).absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.prepare(); mp.start(); player.value = mp
            status = "Playing back…"
        } catch (e: Exception) { status = "Couldn't play that back." }
    }

    // Tick the timer while recording; auto-stop at 25 s.
    LaunchedEffect(recording) {
        while (recording) { delay(1000); elapsed++; if (elapsed >= 25) stop() }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { recorder.value?.release() } catch (e: Exception) {}
            try { player.value?.release() } catch (e: Exception) {}
        }
    }

    Dialog(onDismissRequest = { if (!recording) onClose() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(22.dp)).background(T.bgElevated)
                .verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("Set up my voice", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold)
            Text("Read the lines aloud once. SlyOS clones your voice on-device from this — it never leaves your phone.",
                fontSize = T.small, color = T.inkFaint)
            Spacer(Modifier.height(16.dp))

            // The mic button.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (recording) T.accent else T.accent.copy(alpha = 0.14f))
                    .clickable { if (recording) stop() else start() }.padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (recording) "● Recording  ${elapsed}s   — tap to stop" else if (hasSample) "Re-record" else "Tap to record",
                    fontSize = T.body, color = if (recording) Color.White else T.accent, fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("READ THIS ALOUD", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(VoiceSampleStore.TRAINING_SCRIPT, fontSize = T.body, color = T.ink, lineHeight = 26.sp)

            if (status.isNotBlank()) { Spacer(Modifier.height(14.dp)); Text(status, fontSize = T.small, color = T.accent) }

            if (hasSample && !recording) {
                Spacer(Modifier.height(16.dp))
                Row {
                    Text("▶ Play it back", fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.hairline).clickable { play() }.padding(horizontal = 16.dp, vertical = 9.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Delete", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable { VoiceSampleStore.clear(ctx); hasSample = false; status = "Removed." }.padding(vertical = 9.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Done", fontSize = T.small, color = T.inkSoft,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { if (!recording) onClose() })
        }
    }
}
