package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The brain's safety net. Snapshots the ENTIRE on-device brain — every SQLite database (messages +
 * FTS, vectors, connections, expenses) and every SharedPreferences file (About, learned facts,
 * checklist, conversations, keys, settings…) plus the Cowork workspace files — into a single zip.
 *
 * Because it zips the whole shared_prefs/ and databases/ folders (not a hand-listed set), any store
 * we add later is backed up automatically — nothing to remember, nothing to miss.
 *
 * The snapshot is pushed to Google Drive (off-device, survives a wipe/uninstall/new phone) by
 * [DriveBackup], and a copy is also dropped in the phone's public Downloads so it survives even an
 * uninstall of the app. Restore puts everything back and relaunches so the app reads the restored data.
 */
object BrainBackup {
    private const val TAG = "SlyOS"
    const val FILE_NAME = "slyos-brain-backup.zip"
    private const val PREF = "slyos"                 // shares the main prefs
    private const val K_LAST = "backup_last"
    private const val K_AUTO = "backup_auto"
    private const val K_LAST_OK = "backup_last_ok"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Root of the app's private data (/data/data/<pkg>), parent of shared_prefs/ and databases/. */
    private fun dataDir(ctx: Context): File? = ctx.filesDir.parentFile

    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO, true)   // ON by default now
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO, on).apply()
    fun lastBackup(ctx: Context): Long = prefs(ctx).getLong(K_LAST, 0L)
    fun lastResult(ctx: Context): String = prefs(ctx).getString(K_LAST_OK, "").orEmpty()
    fun markBackedUp(ctx: Context, note: String) =
        prefs(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).putString(K_LAST_OK, note).apply()

    /**
     * Build a full snapshot zip in the cache dir and return it. Includes the shared_prefs and databases
     * folders (with any -wal/-shm files so no committed data is lost), plus the Cowork workspace files.
     */
    fun snapshot(ctx: Context): File {
        val out = File(ctx.cacheDir, FILE_NAME)
        val root = dataDir(ctx)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            if (root != null) {
                for (sub in listOf("shared_prefs", "databases")) {
                    val d = File(root, sub)
                    if (d.isDirectory) d.listFiles()?.forEach { f ->
                        if (f.isFile) addEntry(zip, "$sub/${f.name}", f)
                    }
                }
            }
            // Cowork workspace files live under files/cowork — back those up too.
            val cowork = File(ctx.filesDir, "cowork")
            if (cowork.isDirectory) cowork.listFiles()?.forEach { f ->
                if (f.isFile) addEntry(zip, "files/cowork/${f.name}", f)
            }
            // Sorted document photos (scanned/filed docs) live under files/documents/<category> — include them
            // so a restored brain has the actual pictures, not just the index.
            val docs = File(ctx.filesDir, "documents")
            if (docs.isDirectory) docs.walkTopDown().filter { it.isFile }.forEach { f ->
                try { addEntry(zip, "files/" + f.relativeTo(ctx.filesDir).path, f) } catch (e: Exception) {}
            }
            // THE SLYOS FOLDER — the actual generated deliverables (PDFs, decks, sheets, one-pagers) that
            // live in the PUBLIC Documents/SlyOS via MediaStore, OUTSIDE app-private storage. Previously the
            // backup carried only the shared_prefs index, so a restored brain "knew" about files it could no
            // longer open — every generated document was silently lost on a new device. Zip the real bytes
            // under slyfolder/<category>/<name> (same structure), plus a manifest so the drawer + summary +
            // timestamp survive the move. Cap per-file size so one huge file can't blow up the snapshot.
            addSlyFolder(ctx, zip)
        }
        return out
    }

    private const val MAX_DOC_BYTES = 25L * 1024 * 1024   // 25MB/file guard

    private fun addSlyFolder(ctx: Context, zip: ZipOutputStream) {
        try {
            val manifest = org.json.JSONArray()
            for (doc in SlyFolder.index(ctx)) {
                val bytes = SlyFolder.bytesOf(ctx, doc) ?: continue
                if (bytes.size > MAX_DOC_BYTES) continue
                val safeCat = doc.category.ifBlank { "Documents" }.replace("/", "_")
                val safeName = doc.name.replace("/", "_")
                try {
                    zip.putNextEntry(ZipEntry("slyfolder/$safeCat/$safeName"))
                    zip.write(bytes); zip.closeEntry()
                    manifest.put(org.json.JSONObject()
                        .put("name", doc.name).put("category", safeCat)
                        .put("summary", doc.summary).put("ts", doc.ts).put("path", "$safeCat/$safeName"))
                } catch (e: Exception) { Log.w(TAG, "slyfolder skip ${doc.name}: ${e.message}") }
            }
            if (manifest.length() > 0) {
                zip.putNextEntry(ZipEntry("slyfolder/_manifest.json"))
                zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            }
        } catch (e: Exception) { Log.w(TAG, "slyfolder backup failed: ${e.message}") }
    }

    private fun addEntry(zip: ZipOutputStream, name: String, file: File) {
        try {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        } catch (e: Exception) { Log.w(TAG, "backup skip $name: ${e.message}") }
    }

    /**
     * Restore a snapshot back into the app data dir. Only writes into the known subfolders and blocks
     * path traversal. Caller should relaunch the app right after so prefs/DB caches are re-read.
     */
    fun restore(ctx: Context, zip: File): Boolean {
        val root = dataDir(ctx) ?: return false
        // slyfolder/ entries can't be written into app-private storage — they belong in the PUBLIC
        // Documents/SlyOS via MediaStore. Buffer their bytes + the manifest during the pass, then
        // re-file them once we're done so a new device rebuilds the same drawers with valid uris.
        val slyBytes = HashMap<String, ByteArray>()
        var slyManifest = ""
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    when {
                        name.contains("..") -> { /* path traversal — skip */ }
                        name == "slyfolder/_manifest.json" -> slyManifest = zin.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("slyfolder/") -> slyBytes[name.removePrefix("slyfolder/")] = zin.readBytes()
                        name.startsWith("shared_prefs/") || name.startsWith("databases/") || name.startsWith("files/") -> {
                            val target = File(root, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { zin.copyTo(it) }
                        }
                    }
                    zin.closeEntry(); e = zin.nextEntry
                }
            }
            if (slyBytes.isNotEmpty()) restoreSlyFolder(ctx, slyBytes, slyManifest)
            true
        } catch (e: Exception) { Log.e(TAG, "restore failed", e); false }
    }

    /**
     * Re-file the backed-up SlyOS documents into the public folder on this device. The shared_prefs index
     * we just restored holds the OLD device's uris (now dead), so we clear it and rebuild it from the
     * manifest as each file is re-filed — leaving the drawers, names, summaries and timestamps intact.
     */
    private fun restoreSlyFolder(ctx: Context, files: Map<String, ByteArray>, manifestJson: String) {
        try {
            SlyFolder.clear(ctx)   // drop stale uris; we're about to rebuild with valid ones
            val byPath = HashMap<String, org.json.JSONObject>()
            if (manifestJson.isNotBlank()) {
                val arr = org.json.JSONArray(manifestJson)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { byPath[it.optString("path")] = it }
            }
            for ((path, bytes) in files) {
                val meta = byPath[path]
                val cat = meta?.optString("category") ?: path.substringBefore('/', "Documents")
                val nm = meta?.optString("name") ?: path.substringAfterLast('/')
                val summary = meta?.optString("summary") ?: ""
                val ts = meta?.optLong("ts") ?: 0L
                try { SlyFolder.restoreDoc(ctx, nm, cat, bytes, summary, ts) } catch (e: Exception) {}
            }
        } catch (e: Exception) { Log.w(TAG, "slyfolder restore failed: ${e.message}") }
    }

    /**
     * Drop a copy of the snapshot into the phone's public Downloads/SlyOS folder via MediaStore, so it
     * survives even uninstalling the app. Best-effort. Returns a human path or null.
     */
    fun copyToDownloads(ctx: Context, src: File): String? {
        return try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val display = "slyos-brain-$stamp.zip"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, display)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SlyOS")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
                "Downloads/SlyOS/$display"
            } else null
        } catch (e: Exception) { Log.w(TAG, "downloads copy failed: ${e.message}"); null }
    }

    /**
     * One call to snapshot everything and push it everywhere: Google Drive (primary, off-device) and a
     * local Downloads copy. Returns a short status for the UI / notification. userInitiated=false is used
     * by the auto-backup worker.
     */
    fun backupNow(ctx: Context): String {
        val zip = try { snapshot(ctx) } catch (e: Exception) { return "Backup failed: ${e.message}" }
        val sizeKb = zip.length() / 1024
        var drive = "Drive not connected"
        var tree = ""
        try {
            val r = DriveBackup.upload(ctx, zip)
            drive = if (r.ok) "Drive ✓" else "Drive: ${r.error}"
            // On top of the restore-zip, mirror the SlyOS folder to Drive as a real browsable tree so the
            // user's documents show up in the same structure on every device, not just inside a zip.
            if (r.ok) try {
                val n = DriveBackup.syncFolder(ctx)
                tree = if (n > 0) " · Files synced ($n)" else " · Files up to date"
            } catch (e: Exception) { tree = " · file sync error" }
        } catch (e: Exception) { drive = "Drive error" }
        val dl = copyToDownloads(ctx, zip)
        val note = "$drive · ${if (dl != null) "Downloads ✓" else "Downloads —"} · ${sizeKb}KB$tree"
        markBackedUp(ctx, note)
        Log.i(TAG, "brain backup: $note")
        return note
    }
}
