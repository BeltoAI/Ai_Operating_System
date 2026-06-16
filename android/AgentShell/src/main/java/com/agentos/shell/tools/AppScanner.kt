package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Finds the messaging / social apps installed on the phone so the user can toggle each one. */
object AppScanner {
    data class AppInfo(val pkg: String, val label: String)

    // Known messaging + social packages. We only show the ones actually installed, plus any
    // other app that has produced a repliable notification this session (so nothing is missed).
    private val CANDIDATES = listOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.facebook.orca", "com.facebook.mlite",
        "com.instagram.android",
        "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thoughtcrime.securesms",                       // Signal
        "com.google.android.apps.messaging",                // Google Messages
        "com.samsung.android.messaging",                    // Samsung Messages
        "com.twitter.android", "com.x.android",
        "com.snapchat.android",
        "com.discord",
        "com.Slack",
        "com.reddit.frontpage",
        "com.linkedin.android",
        "com.zhiliaoapp.musically",                         // TikTok
        "com.tencent.mm",                                   // WeChat
        "com.viber.voip",
        "jp.naver.line.android",                            // LINE
        "com.microsoft.teams",
        "com.skype.raider",
        "com.kakao.talk",
        "com.groupme.android"
    )

    fun installed(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppInfo>()
        for (p in CANDIDATES) {
            try {
                val ai = pm.getApplicationInfo(p, 0)
                seen[p] = AppInfo(p, pm.getApplicationLabel(ai).toString())
            } catch (e: Exception) { /* not installed */ }
        }
        // Add any app that actually sent a repliable message this session.
        for (n in NotificationStore.notes) {
            if (n.pkg.isNotBlank() && n.canReply && n.pkg != "com.google.android.gm" && !seen.containsKey(n.pkg))
                seen[n.pkg] = AppInfo(n.pkg, n.app)
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** App launcher icon as a Bitmap (handles adaptive icons), or null. */
    fun icon(ctx: Context, pkg: String): Bitmap? = try {
        drawableToBitmap(ctx.packageManager.getApplicationIcon(pkg))
    } catch (e: Exception) { null }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        return bmp
    }
}
