package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** Renders text into a real, paginated A4 PDF saved to Downloads/SlyOS. No WebView needed. */
object PdfBuilder {
    private fun safeName(s: String): String =
        s.trim().replace(Regex("[\\\\/]+"), "_").take(80).ifBlank { "document" }

    fun makePdf(ctx: Context, title: String, text: String): Uri? {
        return try {
            val doc = PdfDocument()
            val pw = 595; val ph = 842; val margin = 48f
            val body = Paint().apply { textSize = 12f; color = Color.BLACK }
            val head = Paint().apply { textSize = 20f; color = Color.BLACK; isFakeBoldText = true }
            val maxW = pw - margin * 2

            fun wrap(s: String, p: Paint): List<String> {
                val out = ArrayList<String>()
                s.replace("\r", "").split("\n").forEach { para ->
                    if (para.isBlank()) { out.add(""); return@forEach }
                    var line = StringBuilder()
                    para.split(" ").forEach { w ->
                        val test = if (line.isEmpty()) w else "$line $w"
                        if (p.measureText(test) > maxW && line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder(w) }
                        else line = StringBuilder(test)
                    }
                    if (line.isNotEmpty()) out.add(line.toString())
                }
                return out
            }

            var pageNum = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
            var canvas = page.canvas
            var y = margin + 20f
            canvas.drawText(title.take(80), margin, y, head); y += 34f
            val lh = 18f
            for (ln in wrap(text, body)) {
                if (y + lh > ph - margin) {
                    doc.finishPage(page); pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
                    canvas = page.canvas; y = margin + 8f
                }
                canvas.drawText(ln, margin, y, body); y += lh
            }
            doc.finishPage(page)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(title) + ".pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SlyOS")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            uri
        } catch (e: Exception) { null }
    }
}
