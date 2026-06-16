package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Shared image helpers: load a bitmap and encode it (downscaled) to base64 JPEG. */
object ImageUtil {

    fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = try {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    fun encode(ctx: Context, uri: Uri, max: Int = 1024): String? = try {
        var bmp = loadBitmap(ctx, uri)
        if (bmp == null) null else {
            val w = bmp.width; val h = bmp.height
            if (w > max || h > max) {
                val s = max.toFloat() / maxOf(w, h)
                bmp = Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }
}
