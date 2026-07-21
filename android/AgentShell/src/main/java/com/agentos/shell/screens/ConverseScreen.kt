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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentAction
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.AgentLoop
import com.agentos.shell.tools.AgentResult
import com.agentos.shell.tools.BrainContext
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

private val ACC = Color(0xFFE8642C)

/**
 * Hold the brain → this. It's YOU talking to yourself: a hands-free, general-purpose assistant with
 * full brain access. It answers, and when you ask it to DO something consequential (text someone,
 * add an event, set a reminder) it surfaces a swipeable card — swipe right to confirm, left to cancel,
 * the same gesture used across the launcher. The brain pulses to your voice and to its own.
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
    var pendingActs by remember { mutableStateOf<List<AgentAction>?>(null) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    val apps = remember { ToolRouter.installedApps(ctx).map { it.label } }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }            // P3: TTS engine init done?
    var pendingSpeak by remember { mutableStateOf<String?>(null) } // P3: queued until the engine is ready
    val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }  // P6: cloned-voice playback
    val recog = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }

    fun startListening() {
        if (!granted || recog == null || pendingActs != null) return   // don't listen while a card awaits you
        phase = "listening"; youSaid = ""
        val i = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        try { recog.startListening(i) } catch (e: Exception) {}
    }

    // Free tier: device on-device TTS (generic voice). P3 init queue keeps the mic from getting stuck.
    fun deviceSpeak(text: String) {
        val engine = tts.value
        if (engine == null || !ttsReady) { pendingSpeak = text; return }
        engine.language = Locale.getDefault()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "conv")
    }

    fun speak(text: String) {
        phase = "speaking"
        // P6 paid add-on: if the user pasted their own ElevenLabs key + voice, speak in their CLONED voice;
        // any failure falls back to the free device voice so a call never goes silent.
        if (com.agentos.shell.tools.ElevenLabs.available(ctx)) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { com.agentos.shell.tools.ElevenLabs.synthesize(ctx, text) }
                if (f == null) { deviceSpeak(text); return@launch }
                try {
                    try { player.value?.release() } catch (e: Exception) {}
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener { try { f.delete() } catch (e: Exception) {}; scope.launch { if (pendingActs == null) startListening() } }
                    mp.setOnErrorListener { _, _, _ -> scope.launch { if (pendingActs == null) startListening() }; true }
                    mp.prepare(); mp.start(); player.value = mp
                } catch (e: Exception) { deviceSpeak(text) }
            }
            return
        }
        deviceSpeak(text)
    }

    /**
     * Strip the machine-readable hero-card tag before anything human touches the text.
     *
     * The planner prefixes replies with `[[card:price;...]]` so the Home screen can render a visual
     * card 100% reliably. Voice mode has no card UI, so the tag was never consumed — it was read
     * ALOUD ("open bracket open bracket card colon…"), shown on screen, and written into the brain as
     * part of the memory, poisoning future recall with markup.
     */
    fun clean(s: String): String = try { RichParse.fromTag(s).second } catch (e: Exception) { s }

    fun answer(prompt: String) {
        phase = "thinking"
        scope.launch {
            // P3: bound the brain build so a Gemini embedding stall can't hang the voice loop.
            val brain = (withTimeoutOrNull(6000L) { withContext(Dispatchers.IO) { BrainContext.build(ctx, prompt) } })
                ?: withContext(Dispatchers.IO) { BrainContext.profileBlock(ctx) }   // degrade to profile-only
            // P1: run the real agentic loop — it can web_search, recall memory, find contacts, and chain
            // steps. P0: any consequential step it prepared comes back as a CONFIRM card, never auto-sent.
            val out = (withTimeoutOrNull(60000L) {
                withContext(Dispatchers.IO) { AgentLoop.run(ctx, prompt, brain, history, userInitiated = true) }
            }) ?: AgentLoop.Result("That took too long — let's try again.", emptyList())
            val say = clean(out.answer).ifBlank { "Done." }
            reply = say
            history = (history + (prompt to say)).takeLast(12)   // P4: keep more context across turns
            // Every exchange feeds the brain.
            withContext(Dispatchers.IO) {
                MessageStore.insertOne(ctx, "Me", "Voice", "me", "me", prompt)
                if (say.isNotBlank()) MessageStore.insertOne(ctx, "SlyOS", "Voice", "SlyOS", "them", say)
            }
            if (out.actions.isNotEmpty()) {
                pendingActs = out.actions   // swipe-right confirms, left cancels (onDone won't auto-listen)
                speak("$say  Swipe right to confirm, left to cancel.")
            } else speak(say)
        }
    }

    // Speech recognizer callbacks.
    DisposableEffect(recog, granted) {
        recog?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (phase == "listening") level = (level * 0.72f + ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 0.28f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { if (phase == "listening") startListening() }
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

    // TTS; when it finishes speaking, listen again — unless a confirm card is waiting for you.
    DisposableEffect(Unit) {
        val engine = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) ttsReady = true }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { if (pendingActs == null) startListening() } }
            override fun onError(id: String?) { scope.launch { if (pendingActs == null) startListening() } }
        })
        tts.value = engine
        onDispose { engine.stop(); engine.shutdown(); try { player.value?.release() } catch (e: Exception) {} }
    }

    // P3: flush any queued utterance the instant the engine finishes initializing.
    LaunchedEffect(ttsReady) {
        if (ttsReady) pendingSpeak?.let { txt -> pendingSpeak = null; speak(txt) }
    }
    // P3 watchdog: if we've been "speaking" too long (e.g. TTS silently failed to start), resume the mic
    // so the conversation can never get stuck forever.
    LaunchedEffect(phase, reply) {
        if (phase == "speaking") {
            delay(20000)
            if (phase == "speaking") { if (pendingActs == null) startListening() }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) startListening() }
    LaunchedEffect(Unit) { if (granted) startListening() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    // Synthetic pulse while thinking/speaking (TTS gives no amplitude), driven by a sine.
    LaunchedEffect(phase) {
        if (phase == "speaking" || phase == "thinking") {
            var t = 0f
            while (phase == "speaking" || phase == "thinking") {
                t += 0.16f
                val target = if (phase == "speaking") (0.3f + 0.3f * ((sin(t) + 1f) / 2f)) else (0.14f + 0.08f * ((sin(t * 0.6f) + 1f) / 2f))
                level = level * 0.82f + target * 0.18f
                delay(45)
            }
        }
    }

    // Resolve a pending action card: right = do it, left = cancel. Then resume listening.
    fun resolve(confirm: Boolean) {
        val acts = pendingActs ?: return
        pendingActs = null
        scope.launch {
            if (confirm) {
                val msg = withContext(Dispatchers.IO) { ToolRouter.executeActions(ctx, acts) }
                val done = clean(msg).ifBlank { "Done." }
                reply = done
                speak(done)
            } else {
                speak("Okay, cancelled.")
            }
        }
    }

    val statusText = when (phase) {
        "listening" -> "listening…"; "thinking" -> "thinking…"; "speaking" -> "speaking…"; else -> "starting…"
    }

    Box(Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF1C1712), Color(0xFF050505)))
    )) {
        if (granted) Brain3D(Modifier.fillMaxSize(), pulse = level)

        Text("End", color = Color.White, fontSize = T.small,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp)
                .clip(RoundedCornerShape(999.dp)).background(Color(0x33FFFFFF)).clickable {
                    try { recog?.stopListening() } catch (e: Exception) {}
                    tts.value?.stop(); try { player.value?.release() } catch (e: Exception) {}; onBack()
                }.padding(horizontal = 16.dp, vertical = 8.dp))

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 40.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            // A swipeable confirm card — the same right=do / left=cancel gesture as the rest of SlyOS.
            pendingActs?.let { acts ->
                var dx by remember(acts) { mutableStateOf(0f) }
                Column(
                    Modifier.fillMaxWidth().offset { IntOffset(dx.roundToInt(), 0) }
                        .pointerInput(acts) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when { dx > 130f -> resolve(true); dx < -130f -> resolve(false) }
                                    dx = 0f
                                },
                                onDragCancel = { dx = 0f }
                            ) { _, d -> dx = (dx + d).coerceIn(-320f, 320f) }
                        }
                        .clip(RoundedCornerShape(16.dp)).background(Color(0x22FFFFFF)).padding(16.dp)
                ) {
                    acts.forEach { a ->
                        val o = ActionConfirm.parse(a)
                        val primary = ActionConfirm.fieldsFor(a.type).firstOrNull()?.let { o.optString(it.key) }.orEmpty()
                        Text(ActionConfirm.titleFor(a.type, o), color = Color.White, fontSize = T.body)
                        if (primary.isNotBlank()) Text(primary.take(120), color = Color(0xFFCFC7BB), fontSize = T.small)
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("✕ swipe left", color = T.danger, fontSize = T.caption)
                        Text("swipe right ✓", color = ACC, fontSize = T.caption)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (youSaid.isNotBlank()) { Text("“" + youSaid + "”", color = Color(0xFFDDD6CC), fontSize = T.small, textAlign = TextAlign.Center); Spacer(Modifier.height(10.dp)) }
            if (reply.isNotBlank() && phase != "listening" && pendingActs == null) { Text(reply, color = Color.White, fontSize = T.small, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp)) }
            Text(statusText, color = ACC, fontSize = T.body)
        }

        if (!granted)
            Text("Allow microphone to talk", color = Color.White, fontSize = T.small,
                modifier = Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(999.dp)).background(ACC).clickable { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}
