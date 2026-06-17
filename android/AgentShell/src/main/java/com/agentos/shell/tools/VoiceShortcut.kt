package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agentos.shell.ShellActivity

/**
 * A persistent, lock-screen-visible notification with a "Speak to SlyOS" tap that opens the app
 * straight into voice capture. This is the most reliable way to reach voice from the lock screen
 * without replacing the system keyguard (which a normal app can't do).
 */
object VoiceShortcut {
    private const val CHANNEL = "slyos_voice"
    private const val ID = 42

    fun post(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "SlyOS voice", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(ctx, ShellActivity::class.java)
            .putExtra("start_voice", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, 1, intent, flags)

        val n = Notification.Builder(ctx, CHANNEL).let { b ->
            if (Build.VERSION.SDK_INT < 26) @Suppress("DEPRECATION") b.setPriority(Notification.PRIORITY_LOW)
            b
        }
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Speak to SlyOS")
            .setContentText("Tap to talk — ask anything")
            .setContentIntent(pi)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)   // show on the lock screen
            .build()
        nm.notify(ID, n)
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}
