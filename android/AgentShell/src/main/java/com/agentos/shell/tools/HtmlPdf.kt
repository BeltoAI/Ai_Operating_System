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
                            try { capture() } finally {
                                try { attached?.let { wm?.removeView(it) } } catch (e: Exception) {}
                                latch.countDown()
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
