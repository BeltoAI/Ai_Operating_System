package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generates a spicy take daily and posts a notification with a one-tap "Post to X" action. */
class SpicyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!AgentClient.hasKey()) return Result.success()
        val post = withContext(Dispatchers.IO) { AgentClient.spicyPost("", MemoryStore.about(applicationContext)) }
        if (post.isBlank() || post.startsWith("[")) return Result.retry()
        notify(applicationContext, post)
        return Result.success()
    }

    private fun notify(ctx: Context, post: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("spicy", "Spicy takes", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, post)
        val postPi = PendingIntent.getActivity(
            ctx, 1, Intent.createChooser(share, "Post to X").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val redditShare = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, post).putExtra(Intent.EXTRA_SUBJECT, post.take(280))
        val redditPi = PendingIntent.getActivity(
            ctx, 3, Intent.createChooser(redditShare, "Post to Reddit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val openPi = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val builder = Notification.Builder(ctx, "spicy")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Today's spicy take")
            .setContentText(post)
            .setStyle(Notification.BigTextStyle().bigText(post))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_send), "Post to X", postPi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_send), "Post to Reddit", redditPi
                ).build()
            )
        nm.notify(1001, builder.build())
    }
}
