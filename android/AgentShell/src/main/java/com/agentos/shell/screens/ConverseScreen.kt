package com.agentos.shell.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sin

private val ACC = Color(0xFFE8642C)

/**
 * Hold the brain → this. A hands-free voice conversation: the brain pulses to YOUR voice while it
 * listens, and to ITS voice while it answers. Speak → it answers aloud → listens again, until you
 * tap End.
 */
@Composable
fun ConverseScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf("idle") }          // idle | listening | thinking | speaking
    var level by remember { mutableStateOf(0.15f) }           // 0..1 pulse amplitude
    var youSaid by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    val recog = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }

    fun startListening() {
        if (!granted || recog == null) return
        phase = "listening"; youSaid = ""
        val i = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        try { recog.startListening(i) } catch (e: Exception) {}
    }

    fun speak(text: String) {
        phase = "speaking"
        tts.value?.apply {
            language = Locale.getDefault()
            speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "conv")
        }
    }

    fun answer(prompt: String) {
        phase = "thinking"
        scope.launch {
            val apps = withContext(Dispatchers.IO) { ToolRouter.installedApps(ctx).map { it.label } }
            // Bounded so it always replies instead of hanging silently.
            val res = kotlinx.coroutines.withTimeoutOrNull(45000L) {
                withContext(Dispatchers.IO) { AgentClient.ask(prompt, apps, MemoryStore.fullProfile(ctx), history) }
            }
            if (res == null) { reply = "That took too long — let's try again."; speak(reply); return@launch }
            val actionMsg = withContext(Dispatchers.IO) { ToolRouter.executeActions(ctx, res.actions) }
            val out = actionMsg.ifEmpty { res.say }.ifBlank { "I'm not sure how to help with that." }
            reply = out
            history = (history + (prompt to out)).takeLast(6)
            withContext(Dispatchers.IO) {
                MessageStore.insertOne(ctx, "Me", "Voice", "me", "me", prompt)
                MessageStore.insertOne(ctx, "SlyOS", "Voice", "SlyOS", "them", out)
            }
            speak(out)
        }
    }

    // Speech recognizer callbacks.
    DisposableEffect(recog, granted) {
        recog?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (phase == "listening") level = (level * 0.72f + ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 0.28f) }   // smoothed
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { if (phase == "listening") startListening() }   // silence/timeout → keep listening
            override fun onResults(res: Bundle?) {
                val said = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) { youSaid = said; answer(said) } else startListening()
            }
            override fun onPartialResults(res: Bundle?) {
                res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) youSaid = it }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        onDispose { try { recog?.destroy() } catch (e: Exception) {} }
    }

    // TTS; when it finishes speaking, go back to listening.
    DisposableEffect(Unit) {
        val engine = TextToSpeech(ctx) { }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { startListening() } }
            override fun onError(id: String?) { scope.launch { startListening() } }
        })
        tts.value = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) startListening() }
    LaunchedEffect(Unit) { if (granted) startListening() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    // Synthetic pulse while thinking/speaking (TTS gives no amplitude), driven by a sine.
    LaunchedEffect(phase) {
        if (phase == "speaking" || phase == "thinking") {
            var t = 0f
            while (phase == "speaking" || phase == "thinking") {
                t += 0.16f
                // Gentle breathing — smoothed so it feels natural, not strobing.
                val target = if (phase == "speaking") (0.3f + 0.3f * ((sin(t) + 1f) / 2f)) else (0.14f + 0.08f * ((sin(t * 0.6f) + 1f) / 2f))
                level = level * 0.82f + target * 0.18f
                delay(45)
            }
        }
    }

    val statusText = when (phase) {
        "listening" -> "listening…"; "thinking" -> "thinking…"; "speaking" -> "speaking…"; else -> "starting…"
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF12100C))) {
        Text("End", color = Color.White, fontSize = T.small,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp)
                .clip(RoundedCornerShape(999.dp)).background(Color(0x33FFFFFF)).clickable {
                    try { recog?.stopListening() } catch (e: Exception) {}
                    tts.value?.stop(); onBack()
                }.padding(horizontal = 16.dp, vertical = 8.dp))

        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // ── The real rotating 3D memory-brain, pulsing to your voice + its reply ──
            Brain3D(Modifier.fillMaxWidth().height(400.dp), pulse = level)
            Spacer(Modifier.height(24.dp))
            Text(statusText, color = ACC, fontSize = T.body)
            Spacer(Modifier.height(18.dp))
            if (youSaid.isNotBlank()) Text("“" + youSaid + "”", color = Color(0xFFDDD6CC), fontSize = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
            if (reply.isNotBlank() && phase != "listening") { Spacer(Modifier.height(12.dp)); Text(reply, color = Color.White, fontSize = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp)) }
        }

        if (!granted)
            Text("Allow microphone to talk", color = Color.White, fontSize = T.small,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
                    .clip(RoundedCornerShape(999.dp)).background(ACC).clickable { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}
