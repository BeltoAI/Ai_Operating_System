package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * SlyOS-style lockscreen notifications from your team. When a worker finishes something worth seeing,
 * or genuinely needs you, it pings the lock screen — tap to open SlyOS. Nothing is REQUIRED (the team is
 * fully autonomous); these are just visibility + a one-tap way in.
 */
object EmployeeNotify {
    private const val CH = "team"

    fun post(ctx: Context, empId: String, title: String, body: String, needsYou: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CH, "Your AI team", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "Updates from your SlyOS agents"
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(ctx, com.agentos.shell.ShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open", "team").putExtra("emp", empId)
            val pi = PendingIntent.getActivity(ctx, empId.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CH)
                .setSmallIcon(if (needsYou) android.R.drawable.stat_sys_warning else android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(("team_$empId").hashCode(), n)
        } catch (e: Exception) {}
    }
}
