package android.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * THIS FILE IS IN `android.print` DELIBERATELY.
 *
 * `PrintDocumentAdapter.LayoutResultCallback` and `WriteResultCallback` are public abstract classes whose
 * CONSTRUCTORS are package-private, so they cannot be subclassed from application code — the compiler
 * rejects it with "constructor is package-private in android/print". Declaring this shim in the same
 * package is the supported way to reach them, and it is the only way to drive a PrintDocumentAdapter
 * headlessly (PrintManager would put a print dialog in front of the user).
 *
 * Why bother: [com.agentos.shell.tools.HtmlPdf] used to render documents by drawing the WebView onto a
 * canvas, which can only ever paint the first viewport. A generated six-page pitch deck came out with the
 * title slide on page one, five blank pages behind it, and no selectable text anywhere (`/Font 0`) because
 * the output was pixels. The print pipeline paginates web content properly and emits vector text.
 *
 * Nothing here blocks: every callback arrives on the main looper, and the result is reported through
 * [onDone], which is invoked exactly once.
 */
object SlyPrint {
    fun write(
        adapter: PrintDocumentAdapter,
        attrs: PrintAttributes,
        file: File,
        onDone: (Boolean) -> Unit
    ) {
        var settled = false
        fun done(ok: Boolean) { if (!settled) { settled = true; onDone(ok) } }
        try {
            adapter.onStart()
            adapter.onLayout(null, attrs, CancellationSignal(),
                object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                        try {
                            if (file.exists()) file.delete()
                            val pfd = ParcelFileDescriptor.open(
                                file,
                                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
                            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                                object : PrintDocumentAdapter.WriteResultCallback() {
                                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                                        try { pfd.close() } catch (e: Exception) {}
                                        try { adapter.onFinish() } catch (e: Exception) {}
                                        done(pages != null && pages.isNotEmpty())
                                    }
                                    override fun onWriteFailed(error: CharSequence?) {
                                        try { pfd.close() } catch (e: Exception) {}
                                        done(false)
                                    }
                                    override fun onWriteCancelled() {
                                        try { pfd.close() } catch (e: Exception) {}
                                        done(false)
                                    }
                                })
                        } catch (e: Exception) { done(false) }
                    }
                    override fun onLayoutFailed(error: CharSequence?) { done(false) }
                    override fun onLayoutCancelled() { done(false) }
                }, Bundle())
        } catch (e: Exception) { done(false) }
    }
}
