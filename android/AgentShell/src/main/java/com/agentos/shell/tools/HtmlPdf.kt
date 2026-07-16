package com.agentos.shell.tools

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a full designed HTML document (one-pager or slide deck) into a real, paginated A4 PDF using an
 * offscreen WebView + the system print pipeline. Full CSS control = genuinely high-end output. The WebView
 * must run on the main thread, so we post there and block the caller (a background agent) on a latch.
 */
object HtmlPdf {
    private const val TAG = "SlyOS-HtmlPdf"
    private fun safe(s: String) = s.trim().ifBlank { "document" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(60).ifBlank { "document" }

    fun render(ctx: Context, html: String, title: String): File? {
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<File>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(app)
                wv.settings.javaScriptEnabled = true
                wv.settings.loadWithOverviewMode = true
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({
                            try {
                                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "SlyOS").apply { mkdirs() }
                                val file = File(dir, safe(title) + ".pdf")
                                val adapter = view.createPrintDocumentAdapter(safe(title))
                                val attrs = PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build()
                                adapter.onLayout(null, attrs, null, object : PrintDocumentAdapter.LayoutResultCallback() {
                                    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                                        try {
                                            val pfd = ParcelFileDescriptor.open(file,
                                                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                                            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                                                object : PrintDocumentAdapter.WriteResultCallback() {
                                                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                                                        try { pfd.close() } catch (e: Exception) {}
                                                        try { adapter.onFinish() } catch (e: Exception) {}
                                                        result[0] = if (file.exists() && file.length() > 0) file else null
                                                        latch.countDown()
                                                    }
                                                    override fun onWriteFailed(error: CharSequence?) {
                                                        try { pfd.close() } catch (e: Exception) {}
                                                        Log.w(TAG, "write failed: $error"); latch.countDown()
                                                    }
                                                })
                                        } catch (e: Exception) { Log.w(TAG, "onLayoutFinished: ${e.message}"); latch.countDown() }
                                    }
                                    override fun onLayoutFailed(error: CharSequence?) { Log.w(TAG, "layout failed: $error"); latch.countDown() }
                                }, null)
                            } catch (e: Exception) { Log.w(TAG, "render inner: ${e.message}"); latch.countDown() }
                        }, 400)
                    }
                }
                wv.loadDataWithBaseURL("https://slyos.local/", html, "text/html", "UTF-8", null)
            } catch (e: Exception) { Log.w(TAG, "render: ${e.message}"); latch.countDown() }
        }
        return try { latch.await(45, TimeUnit.SECONDS); result[0] } catch (e: Exception) { null }
    }
}
