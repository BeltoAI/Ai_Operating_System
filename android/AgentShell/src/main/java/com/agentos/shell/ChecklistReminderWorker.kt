package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.ChecklistStore

/**
 * Gentle nudge: if the checklist has open items, remind the user every so often so nothing quietly
 * rots. Throttled to at most once per 5h and only when there's actually something to do.
 */
class ChecklistReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val open = ChecklistStore.load(ctx).filter { !it.done }
        if (open.isEmpty()) return Result.success()

        val prefs = ctx.getSharedPreferences("slyos_checklist_nudge", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < 5 * 60 * 60 * 1000L) return Result.success()
        prefs.edit().putLong("last", now).apply()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel("checklist", "Checklist reminders", NotificationManager.IMPORTANCE_DEFAULT))

        val n = open.size
        val body = if (n == 1) "1 thing on your list: ${open.first().text}"
                   else "$n things on your list — top: ${open.first().text}"
        val openPi = PendingIntent.getActivity(
            ctx, 12, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val note = Notification.Builder(ctx, "checklist")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle("Still on your checklist")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify(9912, note)
        return Result.success()
    }
}
