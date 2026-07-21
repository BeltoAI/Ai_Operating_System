package com.agentos.shell

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fires at a scheduled time and pops a reminder notification with the user's message. */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        /** Bumped from the old "reminders" id — channels are immutable, so a new id is the ONLY way to
         *  give existing installs sound + vibration. Bump again if these settings ever need to change. */
        const val CHANNEL = "reminders_v2_alarm"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: "Reminder"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // WHY A NEW CHANNEL ID: notification channels are IMMUTABLE once created. The old "reminders"
            // channel was created by an earlier build, so asking for IMPORTANCE_HIGH here did nothing —
            // Android kept the original (silent) settings. That is why reminders and timers never made a
            // sound. A new id is the only way to apply sound + vibration; the stale one is deleted.
            try { nm.deleteNotificationChannel("reminders") } catch (e: Exception) {}
            val ch = NotificationChannel(CHANNEL, "Reminders & timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders and timers you asked SlyOS to set"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                enableLights(true)
                setBypassDnd(true)                     // a reminder you asked for should still reach you
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // ALARM stream + the actual alarm tone, so it's audible like a real alarm rather than a blip.
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
            }
            nm.createNotificationChannel(ch)
        }
        val openPi = PendingIntent.getActivity(
            ctx, 21, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alarmTone = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val note = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)          // pre-26 devices, where channels don't exist
            .setSound(alarmTone)                              // pre-26 sound
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setFullScreenIntent(openPi, true)                // surfaces even if the screen is off
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), note)
        // BELT AND BRACES: if the device is silent/DND or the channel was muted by the user, the
        // notification can still be inaudible. Play the alarm tone on the ALARM stream directly so a
        // reminder you explicitly asked for actually makes a noise.
        try {
            val r = android.media.RingtoneManager.getRingtone(ctx, alarmTone)
            r?.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r?.play()
        } catch (e: Exception) {}
        // Log the firing into the brain so "what was I reminded about?" recalls it.
        try { com.agentos.shell.tools.MessageStore.insertOne(ctx, "Reminders", "Reminder", "system", "system", "Reminder fired: $text") } catch (e: Exception) {}
    }
}

/** Schedules reminder notifications. Uses allow-while-idle so it fires even in Doze, with no
 *  exact-alarm permission needed (approximate is fine for reminders). */
object ReminderScheduler {
    fun schedule(ctx: Context, triggerAtMs: Long, text: String): Boolean = try {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
        val req = (triggerAtMs % 1_000_000).toInt()
        val pi = PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // EXACT alarm so it fires at the right minute even in Doze (the inexact setAndAllowWhileIdle was getting
        // delayed/dropped — that's why "nothing happened"). Fall back to inexact only if exact isn't permitted.
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        true
    } catch (e: SecurityException) {
        // exact denied at runtime → still schedule inexact so something fires
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, (triggerAtMs % 1_000_000).toInt(),
                Intent(ctx, ReminderReceiver::class.java).putExtra("text", text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi); true
        } catch (e2: Exception) { false }
    } catch (e: Exception) { false }
}
