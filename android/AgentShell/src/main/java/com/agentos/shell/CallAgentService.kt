package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.agentos.shell.tools.AgentLoop
import com.agentos.shell.tools.BrainContext
import com.agentos.shell.tools.ElevenLabs
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * THE CALL AGENT — your AI answers the phone and talks to the caller, on your behalf.
 *
 * Started by [InteractionLogService] the moment it auto-answers an incoming WhatsApp/VoIP call (and puts it
 * on speaker). It runs the same half-duplex loop as Converse, headless: greet → LISTEN (SpeechRecognizer
 * hears the caller off the speaker) → BRAIN (AgentLoop, as you) → SPEAK (your cloned ElevenLabs voice, or the
 * free device voice) out the speaker so the caller hears it. Half-duplex (never listens while speaking) to
 * curb the acoustic echo of a speaker loop. Fully on-device except the LLM.
 *
 * Honest limits (stock Android): capturing the caller depends on the mic being shareable while the call app
 * holds it — device/version dependent. TTS→caller is reliable. See VOICE_CALLS.md.
 */
class CallAgentService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var recog: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var history = listOf<Pair<String, String>>()
    private var who = "WhatsApp caller"          // brain contact this call is filed under
    @Volatile private var alive = false
    private var speaking = false

    private fun ts(): String =
        java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (alive) return START_STICKY
        alive = true; running = true
        who = intent?.getStringExtra("caller")?.trim()?.takeIf { it.isNotBlank() } ?: "WhatsApp caller"
        // Brain marker: the call itself, timestamped, so every call is on the record.
        scope.launch { withContext(Dispatchers.IO) {
            try { MessageStore.insertOne(applicationContext, who, "Calls", who, "them", "📞 Incoming call — AI answered at ${ts()}") } catch (e: Exception) {}
        } }
        try { startForeground(31, notif()) } catch (e: Exception) { Log.e(TAG, "fg", e) }
        tts = TextToSpeech(applicationContext) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { speaking = false; if (alive) listen() } }
            @Deprecated("") override fun onError(id: String?) { main.post { speaking = false; if (alive) listen() } }
        })
        recog = if (SpeechRecognizer.isRecognitionAvailable(applicationContext))
            SpeechRecognizer.createSpeechRecognizer(applicationContext) else null
        recog?.setRecognitionListener(listener)
        // Open with a natural greeting in your voice, then start listening.
        val name = MemoryStore.ownerName(applicationContext).ifBlank { MemoryStore.profileName(applicationContext) }.trim()
        speak(if (name.isBlank()) "Hey, go ahead — I'm here." else "Hey, it's $name — go ahead, I'm here.")
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false; running = false
        val label = who
        Thread { try { MessageStore.insertOne(applicationContext, label, "Calls", label, "them", "📞 Call ended at ${ts()}") } catch (e: Exception) {} }.start()
        try { recog?.destroy() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        scope.cancel()
    }

    // ── Listen ───────────────────────────────────────────────────────────────────────────────────
    private fun listen() {
        if (!alive || speaking) return
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            recog?.startListening(i)
        } catch (e: Exception) { Log.e(TAG, "listen", e) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(e: Int) { if (alive && !speaking) main.postDelayed({ listen() }, 400) }
        override fun onResults(res: Bundle?) {
            val said = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (said.isBlank()) { if (alive) listen(); return }
            answer(said)
        }
        override fun onPartialResults(res: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    // ── Brain ────────────────────────────────────────────────────────────────────────────────────
    private fun answer(caller: String) {
        scope.launch {
            val brain = (withTimeoutOrNull(6000L) { withContext(Dispatchers.IO) { BrainContext.build(applicationContext, caller) } })
                ?: withContext(Dispatchers.IO) { BrainContext.profileBlock(applicationContext) }
            val out = (withTimeoutOrNull(45000L) {
                withContext(Dispatchers.IO) {
                    // userInitiated=false: on a live call, NEVER auto-fire a consequential action from the
                    // caller's words — the AI only talks. Anything actionable is skipped, not executed.
                    AgentLoop.run(applicationContext, caller, brain, history, userInitiated = false)
                }
            }) ?: AgentLoop.Result("Sorry, could you say that again?", emptyList())
            val say = out.answer.ifBlank { "Mm-hmm, go on." }
            history = (history + (caller to say)).takeLast(12)
            // Full transcript into the brain, timestamped (insertOne stamps each row): what the caller said
            // and what the AI answered, filed under this caller.
            withContext(Dispatchers.IO) {
                MessageStore.insertOne(applicationContext, who, "Calls", who, "them", caller)
                MessageStore.insertOne(applicationContext, who, "Calls", "SlyOS", "me", say)
            }
            speak(say)
        }
    }

    // ── Speak (out the speaker so the caller hears it) ────────────────────────────────────────────
    private fun speak(text: String) {
        speaking = true
        if (ElevenLabs.available(applicationContext)) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { ElevenLabs.synthesize(applicationContext, text) }
                if (f == null) { deviceSpeak(text); return@launch }
                try {
                    try { player?.release() } catch (e: Exception) {}
                    val mp = MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener { try { f.delete() } catch (e: Exception) {}; speaking = false; if (alive) listen() }
                    mp.setOnErrorListener { _, _, _ -> speaking = false; if (alive) listen(); true }
                    mp.prepare(); mp.start(); player = mp
                } catch (e: Exception) { deviceSpeak(text) }
            }
            return
        }
        deviceSpeak(text)
    }

    private fun deviceSpeak(text: String) {
        val e = tts
        if (e == null) { speaking = false; return }
        try { e.language = Locale.getDefault() } catch (ex: Exception) {}
        e.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "call")
    }

    private fun notif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("callagent", "AI is on the call", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "callagent")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("SlyOS is answering")
            .setContentText("Your AI is talking to the caller")
            .setOngoing(true).build()
    }

    companion object {
        private const val TAG = "SlyOS-CallAgent"
        @Volatile var running = false

        fun start(ctx: Context, caller: String = "") {
            if (running) return
            val i = Intent(ctx, CallAgentService::class.java).putExtra("caller", caller)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, CallAgentService::class.java)) } catch (e: Exception) {} }
    }
}
