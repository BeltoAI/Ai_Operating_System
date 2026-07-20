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
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: "Reminder"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val openPi = PendingIntent.getActivity(
            ctx, 21, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(ctx, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), note)
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
