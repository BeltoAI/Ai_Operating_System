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

    /**
     * Upload (create or update-in-place) the snapshot by STREAMING the zip straight to Drive — never loading
     * it into memory. The old path did file.readBytes() then built a multipart byte array, so a large brain
     * (tens of thousands of messages + vectors) OOM'd — and an OutOfMemoryError is an Error, not an Exception,
     * so nothing caught it and the whole launcher crashed. Streaming holds ~64KB at a time regardless of size,
     * and we catch Throwable as a final belt so a backup can never take the app down again.
     */
    fun upload(ctx: Context, file: File): Result {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return Result(false, "not connected")
        // Prefer the id we already know; otherwise look it up by name (survives a wipe).
        var id = prefs(ctx).getString(K_ID, "").orEmpty()
        if (id.isBlank()) id = findByName(token, BrainBackup.FILE_NAME)
        return try {
            if (id.isNotBlank()) {
                val (code, body) = mediaPatchStream(id, token, file)
                if (code in 200..299) { saveId(ctx, id); Result(true, id = id) }
                else if (code == 404) createMultipartStream(ctx, token, file)   // was deleted → recreate
                else Result(false, errOf(body, code))
            } else createMultipartStream(ctx, token, file)
        } catch (t: Throwable) { Result(false, t.message ?: "backup error") }
    }

    /** Stream a raw-media PATCH of the zip file (no in-memory copy). */
    private fun mediaPatchStream(id: String, token: String, file: File): Pair<Int, String> =
        reqStream("PATCH", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
            token, "application/zip", null, file, null)

    /** Stream a NEW multipart upload of the zip file (metadata head + streamed file + tail). */
    private fun createMultipartStream(ctx: Context, token: String, file: File): Result {
        val boundary = "slyosBrain" + System.currentTimeMillis()
        val meta = JSONObject().put("name", BrainBackup.FILE_NAME).toString()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val (code, resp) = reqStream("POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            token, "multipart/related; boundary=$boundary", head, file, tail)
        return if (code in 200..299) {
            val newId = try { JSONObject(resp).getString("id") } catch (e: Exception) { "" }
            if (newId.isNotBlank()) saveId(ctx, newId)
            Result(true, id = newId)
        } else Result(false, errOf(resp, code))
    }

    /**
     * HTTP request that STREAMS a file body (optional head/tail wrapper bytes) with fixed-length streaming
     * mode, so HttpURLConnection sends it in chunks instead of buffering the whole body. Catches Throwable
     * (incl. OutOfMemoryError) so an upload can never crash the process.
     */
    private fun reqStream(method: String, url: String, token: String, contentType: String,
                          head: ByteArray?, file: File, tail: ByteArray?): Pair<Int, String> {
        return try {
            val total = (head?.size ?: 0).toLong() + file.length() + (tail?.size ?: 0)
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                if (method == "PATCH") { requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH") }
                else requestMethod = method
                connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", contentType)
                doOutput = true
                setFixedLengthStreamingMode(total)   // stream — never hold the whole body in memory
            }
            c.outputStream.use { os ->
                head?.let { os.write(it) }
                file.inputStream().use { it.copyTo(os, 64 * 1024) }
                tail?.let { os.write(it) }
                os.flush()
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (t: Throwable) { -1 to (t.message ?: "network error") }
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

    private const val K_TREE = "backup_tree_map"   // "cat/name" -> "driveId:size", so we skip unchanged files

    /**
     * Mirror the SlyOS folder to Drive AS A REAL, BROWSABLE TREE — SlyOS/<Category>/<file> — in addition
     * to the single rolling zip. The zip is the machine restore path; this is so the user can open Drive
     * on any device and see their actual documents in the same structure they have on the phone.
     *
     * drive.file scope only exposes files this app created, which is exactly these, so it can find its own
     * folders again after a reinstall. Unchanged files (same name + size) are skipped to stay light on the
     * free API quota. Best-effort: returns how many files it pushed, and never throws into the caller.
     */
    fun syncFolder(ctx: Context): Int {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return 0
        return try {
            val rootId = ensureFolder(token, SlyFolder.ROOT, "root").ifBlank { return 0 }
            val map = try { JSONObject(prefs(ctx).getString(K_TREE, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            val catFolders = HashMap<String, String>()
            var pushed = 0
            for (doc in SlyFolder.index(ctx)) {
                val cat = doc.category.ifBlank { "Documents" }
                val bytes = SlyFolder.bytesOf(ctx, doc) ?: continue
                val key = "$cat/${doc.name}"
                val prev = map.optString(key, "")
                val sizeTag = "${bytes.size}"
                // Skip if we've already pushed this exact file (id present + size unchanged).
                if (prev.isNotBlank() && prev.substringAfterLast(':') == sizeTag) continue
                val catId = catFolders.getOrPut(cat) { ensureFolder(token, cat, rootId) }
                if (catId.isBlank()) continue
                val mime = SlyFolder.mimeForName(doc.name)
                val existingId = prev.substringBefore(':', "").ifBlank { findInFolder(token, doc.name, catId) }
                val newId = if (existingId.isNotBlank()) {
                    val (code, _) = mediaPatch(existingId, token, bytes)
                    if (code in 200..299) existingId else uploadInto(token, catId, doc.name, mime, bytes)
                } else uploadInto(token, catId, doc.name, mime, bytes)
                if (newId.isNotBlank()) { map.put(key, "$newId:$sizeTag"); pushed++ }
            }
            prefs(ctx).edit().putString(K_TREE, map.toString()).apply()
            pushed
        } catch (e: Exception) { Log.w(TAG, "folder sync failed: ${e.message}"); 0 }
    }

    /** Find (or create) a folder by name under [parentId]; returns its id, or "" on failure. */
    private fun ensureFolder(token: String, name: String, parentId: String): String {
        val safe = name.replace("'", "\\'")
        val q = URLEncoder.encode(
            "name='$safe' and mimeType='application/vnd.google-apps.folder' and trashed=false and '$parentId' in parents",
            "UTF-8")
        val (code, body) = req("GET",
            "https://www.googleapis.com/drive/v3/files?q=$q&pageSize=1&fields=" +
                URLEncoder.encode("files(id)", "UTF-8"), token, null, null)
        if (code in 200..299) try {
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        } catch (e: Exception) {}
        // Create it.
        val meta = JSONObject().put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", org.json.JSONArray().put(parentId))
        val (c2, b2) = req("POST", "https://www.googleapis.com/drive/v3/files?fields=id",
            token, "application/json; charset=UTF-8", meta.toString().toByteArray(Charsets.UTF_8))
        return if (c2 in 200..299) try { JSONObject(b2).getString("id") } catch (e: Exception) { "" } else ""
    }

    private fun findInFolder(token: String, name: String, parentId: String): String {
        val safe = name.replace("'", "\\'")
        val q = URLEncoder.encode("name='$safe' and trashed=false and '$parentId' in parents", "UTF-8")
        val (code, body) = req("GET",
            "https://www.googleapis.com/drive/v3/files?q=$q&pageSize=1&fields=" +
                URLEncoder.encode("files(id)", "UTF-8"), token, null, null)
        if (code !in 200..299) return ""
        return try {
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else ""
        } catch (e: Exception) { "" }
    }

    /** Create a new file with [bytes] inside [parentId]; returns its id, or "". */
    private fun uploadInto(token: String, parentId: String, name: String, mime: String, bytes: ByteArray): String {
        val boundary = "slyosDoc" + System.currentTimeMillis()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId)).toString()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val (code, resp) = req("POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            token, "multipart/related; boundary=$boundary", head + bytes + tail)
        return if (code in 200..299) try { JSONObject(resp).getString("id") } catch (e: Exception) { "" } else ""
    }

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
