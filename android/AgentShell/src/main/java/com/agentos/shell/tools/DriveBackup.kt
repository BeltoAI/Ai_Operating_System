package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pushes the brain snapshot to the user's own Google Drive using the drive.file scope (already granted).
 * drive.file lets the app see only files IT created — which is exactly our backup — so it keeps one
 * rolling file "slyos-brain-backup.zip" updated in place, and can find + download it again even after a
 * full reinstall (the OAuth client, not the install, owns the file). This is the off-device safety net
 * so the brain is never one uninstall away from gone again.
 */
object DriveBackup {
    private const val TAG = "SlyOS"
    private const val PREF = "slyos"
    private const val K_ID = "backup_drive_id"

    data class Result(val ok: Boolean, val error: String = "", val id: String = "")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Upload (create or update-in-place) the snapshot. Returns ok + the Drive file id. */
    fun upload(ctx: Context, file: File): Result {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return Result(false, "not connected")
        val bytes = file.readBytes()
        // Prefer the id we already know; otherwise look it up by name (survives a wipe).
        var id = prefs(ctx).getString(K_ID, "").orEmpty()
        if (id.isBlank()) id = findByName(token, BrainBackup.FILE_NAME)
        return try {
            if (id.isNotBlank()) {
                val (code, body) = mediaPatch(id, token, bytes)
                if (code in 200..299) { saveId(ctx, id); Result(true, id = id) }
                else if (code == 404) createMultipart(ctx, token, bytes)   // was deleted → recreate
                else Result(false, errOf(body, code))
            } else createMultipart(ctx, token, bytes)
        } catch (e: Exception) { Result(false, e.message ?: "network error") }
    }

    /** Find the most recent backup on Drive, download it, and restore into the app. */
    fun restoreLatest(ctx: Context): Result {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return Result(false, "not connected")
        return try {
            val id = prefs(ctx).getString(K_ID, "").orEmpty().ifBlank { findByName(token, BrainBackup.FILE_NAME) }
            if (id.isBlank()) return Result(false, "no backup found on Drive")
            val tmp = File(ctx.cacheDir, "restore-" + BrainBackup.FILE_NAME)
            val ok = download(id, token, tmp)
            if (!ok) return Result(false, "download failed")
            if (!BrainBackup.restore(ctx, tmp)) return Result(false, "unpack failed")
            saveId(ctx, id)
            Result(true, id = id)
        } catch (e: Exception) { Result(false, e.message ?: "network error") }
    }

    private fun saveId(ctx: Context, id: String) = prefs(ctx).edit().putString(K_ID, id).apply()

    /** Newest file with this name that the app created. "" if none. */
    private fun findByName(token: String, name: String): String {
        val q = URLEncoder.encode("name='$name' and trashed=false", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=modifiedTime desc" +
            "&pageSize=1&fields=" + URLEncoder.encode("files(id,modifiedTime)", "UTF-8")
        val (code, body) = req("GET", url, token, null, null)
        if (code !in 200..299) return ""
        return try {
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else ""
        } catch (e: Exception) { "" }
    }

    private fun createMultipart(ctx: Context, token: String, bytes: ByteArray): Result {
        val boundary = "slyosBrain" + System.currentTimeMillis()
        val meta = JSONObject().put("name", BrainBackup.FILE_NAME).toString()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = head + bytes + tail
        val (code, resp) = req(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            token, "multipart/related; boundary=$boundary", body
        )
        return if (code in 200..299) {
            val id = try { JSONObject(resp).getString("id") } catch (e: Exception) { "" }
            if (id.isNotBlank()) saveId(ctx, id)
            Result(true, id = id)
        } else Result(false, errOf(resp, code))
    }

    private fun mediaPatch(id: String, token: String, bytes: ByteArray): Pair<Int, String> =
        req("PATCH", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
            token, "application/zip", bytes)

    private fun download(id: String, token: String, out: File): Boolean {
        return try {
            val c = (URL("https://www.googleapis.com/drive/v3/files/$id?alt=media").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (c.responseCode !in 200..299) { Log.e(TAG, "drive download ${c.responseCode}"); return false }
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            true
        } catch (e: Exception) { Log.e(TAG, "drive download failed", e); false }
    }

    /** Raw HTTP with an optional binary body. */
    private fun req(method: String, url: String, token: String, contentType: String?, body: ByteArray?): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                // HttpURLConnection rejects "PATCH"; send it as POST + the override header Google honors.
                if (method == "PATCH") { requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH") }
                else requestMethod = method
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("Content-Type", contentType)
                }
            }
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    private fun errOf(raw: String, code: Int): String =
        (try { JSONObject(raw).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { "" })
            .ifBlank { "error $code" } +
            (if (code == 403) " — reconnect Google to grant Drive." else "")
}
