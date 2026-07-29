package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.agentos.shell.tools.MeetingStore

/**
 * Recording that outlives the screen.
 *
 * The recorder was a composable: lock the phone, take a call, or let Android reclaim memory, and the
 * meeting was gone. A meeting is the one thing people record precisely because they cannot hold it
 * in their head, so losing it that way is not a degraded feature — it is the whole failure, and it
 * happens at the exact moment someone stops looking at the screen because they are in a meeting.
 *
 * A foreground service instead. The notification is the contract: while it is there, this is
 * recording, and tapping STOP is the way out from anywhere.
 *
 * Segments are written to [MeetingStore] as they arrive, so a process kill costs the last sentence
 * rather than the hour.
 */
class MeetingService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var meetingId = 0L
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        if (running) return START_STICKY
        meetingId = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        if (meetingId == 0L) { stopSelf(); return START_NOT_STICKY }
        running = true
        live = this
        startForeground(NOTIF_ID, notification("Listening…"))
        listen()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        live = null
        release()
        super.onDestroy()
    }

    private fun stopEverything() {
        running = false
        try { MeetingStore.finish(this, meetingId) } catch (e: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    private fun release() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    /**
     * One recognition segment, restarted for as long as the meeting runs.
     *
     * Android's recogniser stops on its own after a stretch of speech or silence and there is no way
     * to ask it not to, so a two-hour meeting is stitched from however many segments that takes.
     * The restart is POSTED rather than called inline — destroying a recogniser from inside its own
     * callback loses the result that was mid-delivery, which is the same trap the hold-to-talk path
     * fell into.
     */
    private fun listen() {
        release()
        if (!running) return
        val r = SpeechRecognizer.createSpeechRecognizer(this) ?: run {
            Log.w("SlyOS", "meeting: no recogniser"); stopEverything(); return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(res: Bundle?) {
                res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { partial = it }
            }

            override fun onResults(res: Bundle?) {
                val said = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) {
                    partial = ""
                    try { MeetingStore.append(this@MeetingService, meetingId, said) } catch (e: Exception) {}
                    notifyManager().notify(NOTIF_ID, notification(said.take(40)))
                }
                if (running) main.post { if (running) listen() }
            }

            override fun onError(code: Int) {
                // Silence is what a meeting sounds like between sentences. Anything else is worth a
                // line in the log but not worth ending a recording someone is relying on.
                if (code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && code != SpeechRecognizer.ERROR_NO_MATCH)
                    Log.w("SlyOS", "meeting: recogniser error $code")
                if (running) main.postDelayed({ if (running) listen() }, 250)
            }

            override fun onEvent(t: Int, b: Bundle?) {}
        })
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000))
        } catch (e: Exception) {
            Log.w("SlyOS", "meeting: couldn't start listening", e)
            if (running) main.postDelayed({ if (running) listen() }, 500)
        }
    }

    private fun notifyManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(line: String): Notification {
        val nm = notifyManager()
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Recording a meeting", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 7,
            Intent(this, MeetingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val open = PendingIntent.getActivity(this, 8,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording a meeting")
            .setContentText(line.ifBlank { "Listening…" })
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        private const val CH = "sly_meeting"
        private const val NOTIF_ID = 993
        private const val EXTRA_ID = "meeting"
        const val ACTION_STOP = "com.agentos.shell.MEETING_STOP"

        /** The running service, for the screen to read the live level and partial off. */
        @Volatile var live: MeetingService? = null; private set
        /** Input level 0..1, for the meter. */
        @Volatile var level: Float = 0f; private set
        /** What is being said right now, before it becomes a segment. */
        @Volatile var partial: String = ""; private set

        val recording: Boolean get() = live != null

        fun start(ctx: Context, meetingId: Long) {
            val i = Intent(ctx, MeetingService::class.java).putExtra(EXTRA_ID, meetingId)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { Log.w("SlyOS", "meeting: couldn't start service", e) }
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, MeetingService::class.java).setAction(ACTION_STOP)) }
            catch (e: Exception) {}
        }
    }
}
