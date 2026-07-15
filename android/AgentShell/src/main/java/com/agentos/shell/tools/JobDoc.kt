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

    /** Render [html] to a PDF file in the cache. Runs on the main thread; returns via [onDone]. Guaranteed
     *  to call [onDone] exactly once — even if the WebView never fires onPageFinished — so the caller can
     *  never hang. */
    fun htmlToPdf(ctx: Context, html: String, baseName: String, onDone: (File?) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(ctx)
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // draw-to-canvas needs software layer
                wv.settings.javaScriptEnabled = false
                var finished = false
                fun render() {
                    if (finished) return
                    finished = true
                    try {
                        wv.measure(
                            View.MeasureSpec.makeMeasureSpec(PAGE_W, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        wv.layout(0, 0, PAGE_W, wv.measuredHeight)
                        val contentH = wv.measuredHeight.coerceAtLeast(PAGE_H)
                        val doc = PdfDocument()
                        var y = 0; var page = 1
                        while (y < contentH && page <= 12) {
                            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, page).create()
                            val p = doc.startPage(info)
                            p.canvas.save(); p.canvas.translate(0f, -y.toFloat())
                            wv.draw(p.canvas); p.canvas.restore()
                            doc.finishPage(p)
                            y += PAGE_H; page++
                        }
                        val dir = File(ctx.cacheDir, "jobdocs").apply { mkdirs() }
                        val f = File(dir, "$baseName.pdf")
                        FileOutputStream(f).use { doc.writeTo(it) }
                        doc.close()
                        onDone(if (f.length() > 0) f else null)
                    } catch (e: Exception) { onDone(null) }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { view.postDelayed({ render() }, 400) }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                // Failsafe: onPageFinished can silently not fire on some devices — render anyway after 3.5s
                // so the flow always completes instead of hanging on "Preparing PDFs…".
                Handler(Looper.getMainLooper()).postDelayed({ render() }, 3500)
            } catch (e: Exception) { onDone(null) }
        }
    }

    /** Load a URL in a headless WebView (runs JS) and return the page's visible text. Best-effort —
     *  login-walled pages (some LinkedIn/Indeed) return little; most career pages read fine. */
    fun fetchPageText(ctx: Context, url: String, onDone: (String) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                var done = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) {
                        view.postDelayed({
                            view.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
                                if (done) return@evaluateJavascript
                                done = true
                                val text = try { org.json.JSONObject("{\"t\":$raw}").getString("t") } catch (e: Exception) { "" }
                                onDone(text.replace(Regex("\\n{3,}"), "\n\n").trim().take(12000))
                            }
                        }, 1400)
                    }
                }
                wv.loadUrl(url)
                // Safety timeout so a stuck page doesn't hang the flow.
                Handler(Looper.getMainLooper()).postDelayed({ if (!done) { done = true; onDone("") } }, 12000)
            } catch (e: Exception) { onDone("") }
        }
    }

    /** Reliable HTML→PDF via Android's print engine: shows the fully-rendered PDF and lets the user
     *  save/share it. Use this to actually VIEW the finished document. */
    fun printHtml(ctx: Context, html: String, name: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(ctx)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        try {
                            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                            val adapter = view.createPrintDocumentAdapter("SlyOS-$name")
                            pm.print("SlyOS $name", adapter, android.print.PrintAttributes.Builder()
                                .setMediaSize(android.print.PrintAttributes.MediaSize.NA_LETTER).build())
                        } catch (e: Exception) {}
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } catch (e: Exception) {}
        }
    }

    /**
     * Quick-send via Gmail with the résumé + cover-letter PDFs attached: opens Gmail's compose already
     * filled with To / Subject / body / attachments so it's a single tap to send. Falls back to the app
     * chooser if Gmail isn't installed.
     */
    fun emailWithAttachments(ctx: Context, to: String, subject: String, body: String, files: List<File>) {
        // Drafts often begin with a "Subject: …" line — lift it into the real subject field so it never
        // lands in the email BODY. Whatever's on that line wins as the subject; the rest becomes the body.
        var subj = subject
        var text = body
        Regex("(?is)^\\s*subject\\s*:\\s*(.+?)\\r?\\n+").find(text)?.let { m ->
            m.groupValues[1].trim().takeIf { it.isNotBlank() }?.let { subj = it }
            text = text.removeRange(m.range).trimStart()
        }
        val uris = ArrayList<Uri>(files.filter { it.exists() }.map { FileProvider.getUriForFile(ctx, AUTHORITY, it) })
        fun build(): Intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "application/pdf"
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subj)
            putExtra(Intent.EXTRA_TEXT, text)
            when {
                uris.size > 1 -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                uris.size == 1 -> putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Prefer Gmail directly (quick send). ActivityNotFound → fall back to the chooser.
        try { ctx.startActivity(build().setPackage("com.google.android.gm")); return } catch (e: Exception) {}
        try {
            ctx.startActivity(Intent.createChooser(build(), "Email with attachments").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }
}
