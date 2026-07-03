package com.agentos.shell.tools

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/** Renders a SlyOS-styled image and sets it as the lock-screen wallpaper. */
object WallpaperTool {
    fun setLockScreen(ctx: Context): Boolean {
        return try {
            val dark = MemoryStore.darkMode(ctx)
            // Palette follows the app's dark/light setting.
            val bg = if (dark) 0xFF12100C.toInt() else 0xFFF4EFE6.toInt()
            val title = if (dark) 0xFFF4EFE6.toInt() else 0xFF1A1714.toInt()
            val subtitle = if (dark) 0xFF8B8478.toInt() else 0xFF9A9085.toInt()
            val w = 1080; val h = 2340
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(bg)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
            p.color = title
            p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            p.textSize = 150f
            c.drawText("SlyOS", w / 2f, h * 0.40f, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 46f
            p.color = subtitle
            c.drawText("what should happen?", w / 2f, h * 0.46f, p)
            p.color = 0xFFE8642C.toInt()
            c.drawCircle(w / 2f, h * 0.51f, 11f, p)
            val wm = WallpaperManager.getInstance(ctx)
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            ctx.getSharedPreferences("slyos", Context.MODE_PRIVATE).edit().putBoolean("lock_wp_set", true).apply()
            true
        } catch (e: Exception) { false }
    }

    /** True once the user has applied the SlyOS lock-screen wallpaper — so we can re-render it (e.g. on a
     *  dark/light switch) to keep it in sync with the theme. */
    fun isSet(ctx: Context): Boolean =
        ctx.getSharedPreferences("slyos", Context.MODE_PRIVATE).getBoolean("lock_wp_set", false)
}
