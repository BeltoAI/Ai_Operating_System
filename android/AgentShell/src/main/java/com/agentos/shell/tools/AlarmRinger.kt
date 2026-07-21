package com.agentos.shell.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.mutableStateOf

/**
 * THE RINGING ALARM — and, crucially, how to STOP it.
 *
 * The previous fix made reminders audible by calling Ringtone.play() directly, but kept no handle on it,
 * so nothing could ever stop the sound. A ringing alarm you cannot silence is worse than a silent one.
 *
 * This owns the ringtone + vibration for the whole process: one place that knows something is ringing,
 * what it's for, and how to stop or snooze it. It also auto-stops after a minute so a missed alarm can
 * never ring forever in your pocket.
 */
object AlarmRinger {
    /** Observable so the Home screen can show a shaking card the moment something goes off. */
    val ringing = mutableStateOf(false)
    val label = mutableStateOf("")

    @Volatile private var tone: Ringtone? = null
    @Volatile private var vib: Vibrator? = null
    @Volatile private var startedAt = 0L
    private const val AUTO_STOP_MS = 60_000L

    /** The moment it should have fired — used to snooze relative to now, not to the original time. */
    @Volatile var lastText: String = ""

    @Synchronized
    fun start(ctx: Context, text: String) {
        stop(ctx)   // never stack two ringtones
        lastText = text
        label.value = text
        ringing.value = true
        startedAt = System.currentTimeMillis()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            tone = RingtoneManager.getRingtone(ctx.applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) { Fail.log(ctx, "Reminder", "play alarm tone", e.message ?: "failed") }
        try {
            vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 600, 400)
            if (Build.VERSION.SDK_INT >= 26)
                vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))   // repeat until stopped
            else @Suppress("DEPRECATION") vib?.vibrate(pattern, 0)
        } catch (e: Exception) {}

        // Safety: never ring forever.
        Thread {
            try { Thread.sleep(AUTO_STOP_MS) } catch (e: InterruptedException) {}
            if (ringing.value && System.currentTimeMillis() - startedAt >= AUTO_STOP_MS) stop(ctx)
        }.start()
    }

    @Synchronized
    fun stop(ctx: Context) {
        try { tone?.stop() } catch (e: Exception) {}
        tone = null
        try { vib?.cancel() } catch (e: Exception) {}
        vib = null
        ringing.value = false
        label.value = ""
        // Clear the notification too, so silencing it in one place silences it everywhere.
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(REMINDER_NOTIF_ID)
        } catch (e: Exception) {}
    }

    /** Stop now, and ring again in [minutes]. */
    @Synchronized
    fun snooze(ctx: Context, minutes: Int = 5) {
        val text = lastText.ifBlank { "Reminder" }
        stop(ctx)
        val ok = try {
            com.agentos.shell.ReminderScheduler.schedule(
                ctx, System.currentTimeMillis() + minutes * 60_000L, text)
        } catch (e: Exception) { false }
        if (!ok) Fail.log(ctx, "Reminder", "snooze \"$text\"", "could not reschedule — it will NOT ring again")
        try {
            MessageStore.insertOne(ctx, "Reminders", "Reminder", "system", "system",
                "Snoozed \"$text\" for $minutes min")
        } catch (e: Exception) {}
    }

    /** Fixed id so the ringing notification can be cancelled from anywhere. */
    const val REMINDER_NOTIF_ID = 90210
}
