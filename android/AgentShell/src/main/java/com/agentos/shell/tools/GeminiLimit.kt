package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Free-tier Gemini quota watchdog. When a Gemini call comes back 429 / RESOURCE_EXHAUSTED we've
 * hit the free daily (or per-minute) cap. We surface that to the user once, with a nudge to route
 * heavier work to Claude/OpenAI, instead of failing silently. Throttled to at most once per 6h.
 */
object GeminiLimit {
    private const val PREF = "slyos_gemini_limit"
    private const val KEY_LAST = "last_warn"
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L   // 6 hours

    /** Records a rate-limit hit and posts a throttled notification. */
    fun hit(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()
        notify(ctx)
    }

    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel("gemini_limit", "Free-tier limits", NotificationManager.IMPORTANCE_DEFAULT))

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(
            ctx, 7,
            Intent(ctx, Class.forName("com.agentos.shell.ShellActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags)

        val text = "You've hit the free Gemini limit. Requests will fail or slow down until it resets. " +
            "Open Brain → Settings to route heavier tasks to Claude or OpenAI, or add billing to Gemini."
        val n = Notification.Builder(ctx, "gemini_limit")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Gemini free tier — near the limit")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify(9911, n)
    }
}
