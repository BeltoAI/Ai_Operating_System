package com.agentos.shell.tools

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Turns captured photos into a PDF (one page per image). Returns a shareable Uri. */
object PdfTool {

    fun imagesToPdf(ctx: Context, uris: List<Uri>): Uri? {
        if (uris.isEmpty()) return null
        return try {
            val doc = PdfDocument()
            uris.forEachIndexed { i, uri ->
                val bmp = ImageUtil.loadBitmap(ctx, uri) ?: return@forEachIndexed
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, bmp.width, bmp.height), Paint())
                doc.finishPage(page)
            }
            val file = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", file)
        } catch (e: Exception) {
            Log.e("SlyOS", "pdf failed", e); null
        }
    }
}
