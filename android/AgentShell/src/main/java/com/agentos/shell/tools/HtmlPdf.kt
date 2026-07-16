package com.agentos.shell.tools

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a full designed HTML document (one-pager or slide deck) into a real, paginated PDF. Uses an offscreen
 * WebView measured to A4 width, then draws it page-by-page onto a PdfDocument canvas (public API — no print
 * callbacks). Full CSS control = high-end output. The WebView must run on the main thread, so we post there and
 * block the caller (a background agent) on a latch.
 */
object HtmlPdf {
    private const val TAG = "SlyOS-HtmlPdf"
    private fun safe(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").take(60).ifBlank { "document" }

    fun render(ctx: Context, html: String, title: String, landscape: Boolean = false): File? {
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<File>(1)
        // A4 at ~96dpi in px.
        val pageW = if (landscape) 1123 else 794
        val pageH = if (landscape) 794 else 1123
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(app)
                wv.settings.javaScriptEnabled = true
                wv.settings.loadWithOverviewMode = true
                wv.settings.useWideViewPort = true
                wv.setInitialScale(100)
                // CRITICAL: an offscreen WebView draws BLANK to a canvas under hardware acceleration — force
                // software rendering so view.draw() actually captures the page content into the PDF.
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({
                            try {
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(pageW, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                                view.layout(0, 0, pageW, view.measuredHeight)
                                if (view.measuredHeight < 40) { Log.w(TAG, "webview rendered empty"); latch.countDown(); return@postDelayed }
                                val totalH = view.measuredHeight.coerceAtLeast(pageH)
                                val pages = Math.ceil(totalH.toDouble() / pageH).toInt().coerceIn(1, 80)
                                val doc = PdfDocument()
                                for (i in 0 until pages) {
                                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
                                    val c = page.canvas
                                    c.save(); c.translate(0f, (-i * pageH).toFloat()); view.draw(c); c.restore()
                                    doc.finishPage(page)
                                }
                                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "SlyOS").apply { mkdirs() }
                                val file = File(dir, safe(title) + ".pdf")
                                file.outputStream().use { doc.writeTo(it) }
                                doc.close()
                                result[0] = if (file.exists() && file.length() > 0) file else null
                            } catch (e: Exception) { Log.w(TAG, "draw: ${e.message}") }
                            finally { latch.countDown() }
                        }, 900)
                    }
                }
                wv.loadDataWithBaseURL("https://slyos.local/", html, "text/html", "UTF-8", null)
            } catch (e: Exception) { Log.w(TAG, "render: ${e.message}"); latch.countDown() }
        }
        return try { latch.await(45, TimeUnit.SECONDS); result[0] } catch (e: Exception) { null }
    }
}
