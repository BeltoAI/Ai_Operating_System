package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Reads IMAGE-based PDFs — slide decks, scans, exported presentations — that have no text layer, by rendering
 * each page to a bitmap (Android PdfRenderer) and running on-device OCR (ML Kit) on it. Free, offline. This is
 * what lets an agent like Bastardi actually READ the slide decks you feed it, not just fail silently.
 */
object PdfOcr {
    private const val TAG = "SlyOS-PdfOcr"

    fun fromUri(ctx: Context, uri: Uri, maxPages: Int = 40): String = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { extract(it, maxPages) } ?: ""
    } catch (e: Exception) { Log.w(TAG, "fromUri: ${e.message}"); "" }

    fun fromFile(file: File, maxPages: Int = 40): String = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { extract(it, maxPages) }
    } catch (e: Exception) { Log.w(TAG, "fromFile: ${e.message}"); "" }

    private fun extract(pfd: ParcelFileDescriptor, maxPages: Int): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val sb = StringBuilder()
        try {
            PdfRenderer(pfd).use { renderer ->
                val n = minOf(renderer.pageCount, maxPages)
                for (i in 0 until n) {
                    val page = renderer.openPage(i)
                    val scale = 2f
                    val w = (page.width * scale).toInt().coerceIn(1, 2200)
                    val h = (page.height * scale).toInt().coerceIn(1, 3200)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val t = try { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))).text.trim() } catch (e: Exception) { "" }
                    if (t.isNotBlank()) sb.append("— Page ${i + 1} —\n").append(t).append("\n\n")
                    bmp.recycle()
                }
            }
        } catch (e: Exception) { Log.w(TAG, "extract: ${e.message}") }
        finally { try { recognizer.close() } catch (e: Exception) {} }
        return sb.toString().trim()
    }
}
