package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a designed HTML résumé/cover letter into a real, print-ready PDF (rendered through a WebView
 * so the styling survives), and shares both as email attachments. This is what makes the job docs
 * look stunning instead of plain text.
 */
object JobDoc {
    private const val PAGE_W = 794    // A4 width  @ ~96dpi
    private const val PAGE_H = 1123   // A4 height @ ~96dpi
    private const val AUTHORITY = "com.agentos.shell.fileprovider"

    /** Render [html] to a PDF file in the cache. Runs on the main thread; returns via [onDone]. */
    fun htmlToPdf(ctx: Context, html: String, baseName: String, onDone: (File?) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(ctx)
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // draw-to-canvas needs software layer
                wv.settings.javaScriptEnabled = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({
                            try {
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(PAGE_W, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                                view.layout(0, 0, PAGE_W, view.measuredHeight)
                                val contentH = view.measuredHeight.coerceAtLeast(PAGE_H)
                                val doc = PdfDocument()
                                var y = 0; var page = 1
                                while (y < contentH && page <= 12) {
                                    val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, page).create()
                                    val p = doc.startPage(info)
                                    p.canvas.save(); p.canvas.translate(0f, -y.toFloat())
                                    view.draw(p.canvas); p.canvas.restore()
                                    doc.finishPage(p)
                                    y += PAGE_H; page++
                                }
                                val dir = File(ctx.cacheDir, "jobdocs").apply { mkdirs() }
                                val f = File(dir, "$baseName.pdf")
                                FileOutputStream(f).use { doc.writeTo(it) }
                                doc.close()
                                onDone(f)
                            } catch (e: Exception) { onDone(null) }
                        }, 400)
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } catch (e: Exception) { onDone(null) }
        }
    }

    /** Open the user's email app with the outreach text and the given PDFs attached. */
    fun emailWithAttachments(ctx: Context, to: String, subject: String, body: String, files: List<File>) {
        val uris = ArrayList<Uri>(files.filter { it.exists() }.map { FileProvider.getUriForFile(ctx, AUTHORITY, it) })
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "application/pdf"
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            when {
                uris.size > 1 -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                uris.size == 1 -> putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Email with attachments").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }
}
