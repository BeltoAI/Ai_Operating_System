package com.agentos.shell.tools

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a full designed HTML document (one-pager or slide deck) into a real, paginated PDF.
 *
 * The reliable way to do this on-device is to attach the WebView to a REAL (but invisible) window via
 * WindowManager — a purely offscreen WebView often never lays out (measuredHeight stays 0), which is why the
 * PDF came out empty before. We add a fully transparent, non-touchable overlay window parked offscreen (SlyOS
 * already holds the overlay permission), let the page actually render at A4 width, then draw it page-by-page
 * onto a PdfDocument canvas via the public API. If overlays aren't available we fall back to the offscreen path.
 */
object HtmlPdf {
    private const val TAG = "SlyOS-HtmlPdf"
    private fun safe(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").take(60).ifBlank { "document" }

    private fun outFile(app: Context, title: String) =
        File(File(app.getExternalFilesDir(null) ?: app.filesDir, "SlyOS").apply { mkdirs() }, safe(title) + ".pdf")

    /**
     * Render the loaded page through Android's PRINT pipeline rather than by drawing it onto a canvas.
     *
     * Why this exists — a generated pitch deck came back six pages long with the title slide on page one and
     * five blank pages after it, every page a bitmap with no selectable text. Both faults came from the
     * canvas path: the WebView is attached to a window one page tall, so only the first viewport is ever
     * composited. Re-measuring the view to full content height afterwards changes the layout but not what
     * has been painted, so `draw()` yields slide one followed by empty background — and because it captures
     * pixels, the output has no text in it at all (`/Font 0`, `/Image 20` on the deck that shipped).
     *
     * createPrintDocumentAdapter is the API that actually paginates web content: it lays the document out
     * against real page boundaries and writes VECTOR output, so text stays selectable, searchable and sharp
     * at any zoom, and the file is a fraction of the size. Driving the adapter directly (rather than through
     * PrintManager) keeps it headless — no print dialog, nothing for the user to confirm.
     */
    private fun printToPdf(app: Context, wv: WebView, title: String, landscape: Boolean, onDone: (File?) -> Unit) {
        if (Build.VERSION.SDK_INT < 21) { onDone(null); return }
        val media = if (landscape) android.print.PrintAttributes.MediaSize.ISO_A4.asLandscape()
                    else android.print.PrintAttributes.MediaSize.ISO_A4.asPortrait()
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(media)
            .setResolution(android.print.PrintAttributes.Resolution("slyos", "slyos", 300, 300))
            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
            .build()
        val adapter = wv.createPrintDocumentAdapter(safe(title))
        val file = outFile(app, title)
        // Every callback lands on the MAIN looper — the thread already running this — so nothing here may
        // block or wait. SlyPrint reports through its callback instead; see that file for why it lives in
        // the android.print package.
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(f: File?) { if (settled.compareAndSet(false, true)) onDone(f) }
        android.print.SlyPrint.write(adapter, attrs, file) { wrote ->
            if (wrote && file.exists() && file.length() > 0) {
                Log.i(TAG, "printed ${file.name} (${file.length()} bytes, vector text)")
                finish(file)
            } else finish(null)
        }
        // Safety net: if the adapter never calls back at all, fall through to the raster path rather than
        // leaving the caller waiting out its own 50s timeout with nothing to show.
        Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 20_000)
    }

    fun render(ctx: Context, html: String, title: String, landscape: Boolean = false): File? {
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<File>(1)
        val pageW = if (landscape) 1123 else 794   // A4 @ ~96dpi
        val pageH = if (landscape) 794 else 1123
        val canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)

        Handler(Looper.getMainLooper()).post {
            var wm: WindowManager? = null
            var attached: View? = null
            try {
                val wv = WebView(app)
                wv.settings.javaScriptEnabled = true
                wv.settings.loadWithOverviewMode = false
                wv.settings.useWideViewPort = false
                wv.setInitialScale(100)
                // Offscreen draws BLANK under hardware acceleration → force software rendering so draw() captures.
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                fun capture() {
                    try {
                        wv.measure(
                            View.MeasureSpec.makeMeasureSpec(pageW, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        wv.layout(0, 0, pageW, wv.measuredHeight)
                        val contentH = maxOf(wv.measuredHeight, wv.contentHeight, 0)
                        if (contentH < 40) { Log.w(TAG, "webview rendered empty ($contentH)"); return }
                        val totalH = contentH.coerceAtLeast(pageH)
                        val pages = Math.ceil(totalH.toDouble() / pageH).toInt().coerceIn(1, 80)
                        val doc = PdfDocument()
                        for (i in 0 until pages) {
                            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
                            val c = page.canvas
                            c.save(); c.translate(0f, (-i * pageH).toFloat()); wv.draw(c); c.restore()
                            doc.finishPage(page)
                        }
                        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "SlyOS").apply { mkdirs() }
                        val file = File(dir, safe(title) + ".pdf")
                        file.outputStream().use { doc.writeTo(it) }
                        doc.close()
                        result[0] = if (file.exists() && file.length() > 0) file else null
                    } catch (e: Exception) { Log.w(TAG, "draw: ${e.message}") }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // Give layout/paint time to settle on the real window, then capture and tear down.
                        view.postDelayed({
                            fun teardown() {
                                try { attached?.let { wm?.removeView(it) } } catch (e: Exception) {}
                                latch.countDown()
                            }
                            // PREFER THE REAL PRINT PIPELINE. See printToPdf(): the canvas capture below can
                            // only ever paint the first viewport, which is why a six-page deck arrived with
                            // one slide and five blank pages after it. Raster remains the fallback.
                            try {
                                printToPdf(app, view, title, landscape) { printed ->
                                    try {
                                        if (printed != null) result[0] = printed
                                        else { Log.w(TAG, "print path unavailable — falling back to raster"); capture() }
                                    } finally { teardown() }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "print adapter: ${e.message}")
                                try { capture() } finally { teardown() }
                            }
                        }, 1200)
                    }
                }

                if (canOverlay) {
                    // Attach as a real (invisible) window so the page genuinely lays out at full A4 width.
                    wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                               else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    val lp = WindowManager.LayoutParams(
                        pageW, pageH, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT)
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = -pageW - 100   // parked well offscreen
                    lp.y = 0
                    lp.alpha = 0f          // invisible to the user
                    wv.alpha = 0f
                    try { wm.addView(wv, lp); attached = wv } catch (e: Exception) { Log.w(TAG, "addView: ${e.message}"); wm = null }
                }
                wv.loadDataWithBaseURL("https://slyos.local/", html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.w(TAG, "render: ${e.message}")
                try { attached?.let { wm?.removeView(it) } } catch (ex: Exception) {}
                latch.countDown()
            }
        }
        return try { latch.await(50, TimeUnit.SECONDS); result[0] } catch (e: Exception) { null }
    }
}
