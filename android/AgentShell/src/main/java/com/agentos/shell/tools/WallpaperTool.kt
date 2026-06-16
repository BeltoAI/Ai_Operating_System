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
            val w = 1080; val h = 2340
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFFF4EFE6.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
            p.color = 0xFF1A1714.toInt()
            p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            p.textSize = 150f
            c.drawText("SlyOS", w / 2f, h * 0.40f, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 46f
            p.color = 0xFF9A9085.toInt()
            c.drawText("what should happen?", w / 2f, h * 0.46f, p)
            p.color = 0xFFE8642C.toInt()
            c.drawCircle(w / 2f, h * 0.51f, 11f, p)
            val wm = WallpaperManager.getInstance(ctx)
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            true
        } catch (e: Exception) { false }
    }
}
