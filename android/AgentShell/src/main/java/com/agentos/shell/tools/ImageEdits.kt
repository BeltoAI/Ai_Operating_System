package com.agentos.shell.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * NATIVE IMAGE EDITS — the everyday transforms, done entirely on-device, no key, no server. The prompt-based
 * creative edits ("make the sky purple") go to ImageAI; these are the deterministic ones people ask for most.
 */
object ImageEdits {
    private const val TAG = "SlyOS-ImgEdit"

    fun apply(op: String, jpeg: ByteArray): ByteArray? = try {
        when (op.lowercase()) {
            "bg", "background" -> NativeTools.removeBackground(jpeg)
            "grayscale", "greyscale", "bw", "black and white" -> grayscale(jpeg)
            "rotate" -> rotate(jpeg, 90f)
            "compress", "shrink", "smaller" -> compress(jpeg)
            "crop" -> squareCrop(jpeg)
            else -> null
        }
    } catch (e: Throwable) { Log.w(TAG, "apply $op: ${e.message}"); null }

    private fun decode(jpeg: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let {
            if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }

    private fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun jpg(bmp: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun grayscale(jpeg: ByteArray): ByteArray? {
        val src = decode(jpeg) ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return jpg(out, 95)
    }

    private fun rotate(jpeg: ByteArray, deg: Float): ByteArray? {
        val src = decode(jpeg) ?: return null
        val m = Matrix().apply { postRotate(deg) }
        return jpg(Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true), 95)
    }

    private fun compress(jpeg: ByteArray): ByteArray? {
        val src = decode(jpeg) ?: return null
        val scale = if (maxOf(src.width, src.height) > 1600) 1600f / maxOf(src.width, src.height) else 1f
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        return jpg(scaled, 70)
    }

    private fun squareCrop(jpeg: ByteArray): ByteArray? {
        val src = decode(jpeg) ?: return null
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2; val y = (src.height - side) / 2
        return jpg(Bitmap.createBitmap(src, x, y, side, side), 95)
    }

    /** Does this instruction map to a native op? (so we skip the AI call when we don't need it). */
    fun nativeOpFor(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("\\bbackground\\b|\\bcut ?out\\b|remove bg").containsMatchIn(t) -> "bg"
            Regex("gray ?scale|grey ?scale|black.?and.?white|\\bb\\s?&?\\s?w\\b|monochrome").containsMatchIn(t) -> "grayscale"
            Regex("\\brotate\\b|turn it|sideways").containsMatchIn(t) -> "rotate"
            Regex("compress|shrink|smaller file|reduce size").containsMatchIn(t) -> "compress"
            Regex("\\bcrop\\b|square it").containsMatchIn(t) -> "crop"
            else -> null
        }
    }
}
