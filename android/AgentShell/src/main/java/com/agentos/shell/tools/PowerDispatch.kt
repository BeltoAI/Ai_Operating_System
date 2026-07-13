package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * POWER DISPATCH — the layer that actually CALLS a connected Power's running instance and pipes the result
 * back into the phone. Each repo has its own API shape, so each gets a small adapter here. This is the
 * proof-of-pattern: `rembg` (remove background) is a self-hosted HTTP server; we POST the image to
 * /api/remove and get a cut-out PNG. More adapters (Perplexica, ComfyUI, Ollama…) slot in the same way.
 */
object PowerDispatch {
    private const val TAG = "SlyOS-Dispatch"

    /** Reachability check for any connected instance (used by "Test connection"). */
    fun ping(endpoint: String): Boolean {
        val base = endpoint.trim().trimEnd('/')
        if (base.isBlank()) return false
        return try {
            val c = (URL(base).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 6000; readTimeout = 6000
            }
            val ok = c.responseCode in 200..499   // any HTTP answer means we reached it
            c.disconnect(); ok
        } catch (e: Exception) { Log.w(TAG, "ping $base: ${e.message}"); false }
    }

    /** rembg adapter: POST the image to <endpoint>/api/remove, return the transparent PNG (or null). */
    fun removeBackground(endpoint: String, image: ByteArray): ByteArray? {
        val base = endpoint.trim().trimEnd('/')
        if (base.isBlank()) return null
        Busy.start()
        return try {
            val boundary = "----slyos${System.currentTimeMillis()}"
            val c = (URL("$base/api/remove").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            c.outputStream.use { out ->
                fun str(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                str("--$boundary\r\n")
                str("Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"\r\n")
                str("Content-Type: image/jpeg\r\n\r\n")
                out.write(image)
                str("\r\n--$boundary--\r\n")
            }
            val code = c.responseCode
            val bytes = if (code in 200..299) c.inputStream.readBytes() else { c.errorStream?.readBytes(); null }
            c.disconnect()
            if (bytes == null) Log.w(TAG, "rembg HTTP $code")
            bytes
        } catch (e: Exception) { Log.w(TAG, "rembg: ${e.message}"); null } finally { Busy.end() }
    }

    /** Save a returned image into the phone's gallery (Pictures/SlyOS). */
    fun saveImage(ctx: Context, png: ByteArray, name: String): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SlyOS")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) }
        uri
    } catch (e: Exception) { Log.w(TAG, "save: ${e.message}"); null }

    /** Read the raw bytes behind a content Uri (the attached photo). */
    fun bytesOf(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }
}
