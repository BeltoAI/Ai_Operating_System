package com.agentos.shell.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.ByteArrayOutputStream

/**
 * NATIVE POWERS — capabilities SlyOS does entirely on-device with Google's ML Kit, so the common ones need
 * NO Termux, NO server, NO setup. This is the path for non-technical people: tap once and it works. Heavier
 * repos still fall back to "connect a server", but everyday things (like removing a background) run right here.
 */
object NativeTools {
    private const val TAG = "SlyOS-Native"

    /** Remove the background from a photo entirely on-device. Returns a transparent PNG, or null on failure. */
    fun removeBackground(jpeg: ByteArray): ByteArray? = try {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (src == null) null else {
            val bmp = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
            val opts = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
            val segmenter = Segmentation.getClient(opts)
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)))
            try { segmenter.close() } catch (e: Exception) {}
            val mw = mask.width; val mh = mask.height
            val buf = mask.buffer; buf.rewind()
            val scaled = if (bmp.width == mw && bmp.height == mh) bmp else Bitmap.createScaledBitmap(bmp, mw, mh, true)
            val pixels = IntArray(mw * mh)
            scaled.getPixels(pixels, 0, mw, 0, 0, mw, mh)
            for (i in 0 until mw * mh) {
                val conf = buf.float                                   // foreground confidence 0..1
                val a = (conf * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
            }
            val out = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, mw, 0, 0, mw, mh)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
    } catch (e: Throwable) { Log.w(TAG, "removeBackground: ${e.message}"); null }
}
