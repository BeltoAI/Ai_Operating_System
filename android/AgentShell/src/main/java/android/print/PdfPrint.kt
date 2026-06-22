package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Drives a WebView's PrintDocumentAdapter to write a real, full-length, selectable-text PDF to a file
 * — without showing the system print dialog. This class lives in package `android.print` on purpose:
 * the framework's LayoutResultCallback / WriteResultCallback have package-private constructors, so only
 * a class in this package may subclass them. Result is delivered to [onDone] on the main thread.
 */
class PdfPrint(private val attributes: PrintAttributes) {

    fun print(adapter: PrintDocumentAdapter, file: File, onDone: (File?) -> Unit) {
        try {
            adapter.onLayout(null, attributes, CancellationSignal(),
                object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                        try {
                            if (file.exists()) file.delete()
                            val pfd = ParcelFileDescriptor.open(
                                file,
                                ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_TRUNCATE
                            )
                            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                                object : PrintDocumentAdapter.WriteResultCallback() {
                                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                                        try { pfd.close() } catch (e: Exception) {}
                                        onDone(if (file.exists() && file.length() > 0) file else null)
                                    }
                                    override fun onWriteFailed(error: CharSequence?) {
                                        try { pfd.close() } catch (e: Exception) {}
                                        onDone(null)
                                    }
                                })
                        } catch (e: Exception) { onDone(null) }
                    }

                    override fun onLayoutFailed(error: CharSequence?) { onDone(null) }
                })
        } catch (e: Exception) { onDone(null) }
    }
}
