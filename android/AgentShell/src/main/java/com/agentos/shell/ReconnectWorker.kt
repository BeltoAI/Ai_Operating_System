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
import com.agentos.shell.tools.ConversationStore

/** Weekly nudge: surfaces a few people you've gone quiet on; tap to open the Reconnect screen. */
class ReconnectWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "ReconnectWorker")
        val stale = ConversationStore.staleContacts(applicationContext, 7).take(3)
        if (stale.isEmpty()) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "ReconnectWorker", true).let { Result.success() }
        val names = stale.joinToString(", ") { it.title }
        notify(applicationContext, names)
        return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "ReconnectWorker", true).let { Result.success() }
    }

    private fun notify(ctx: Context, names: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("reconnect", "Reconnect nudges", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, ShellActivity::class.java)
                .putExtra("open_reconnect", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val text = "Reach out to $names — tap for ready messages."
        val n = Notification.Builder(ctx, "reconnect")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Time to reconnect")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify(1007, n)
    }
}
