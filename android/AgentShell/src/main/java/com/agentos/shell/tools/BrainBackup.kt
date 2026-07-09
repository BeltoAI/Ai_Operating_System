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
     * Build a full snapshot zip in the cache dir and return it. Includes shared_prefs/*, databases/*
     * (with -wal/-shm so no committed data is lost), and files/cowork/* .
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
        }
        return out
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
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!name.contains("..") &&
                        (name.startsWith("shared_prefs/") || name.startsWith("databases/") || name.startsWith("files/"))) {
                        val target = File(root, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { zin.copyTo(it) }
                    }
                    zin.closeEntry(); e = zin.nextEntry
                }
            }
            true
        } catch (e: Exception) { Log.e(TAG, "restore failed", e); false }
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
        try {
            val r = DriveBackup.upload(ctx, zip)
            drive = if (r.ok) "Drive ✓" else "Drive: ${r.error}"
        } catch (e: Exception) { drive = "Drive error" }
        val dl = copyToDownloads(ctx, zip)
        val note = "$drive · ${if (dl != null) "Downloads ✓" else "Downloads —"} · ${sizeKb}KB"
        markBackedUp(ctx, note)
        Log.i(TAG, "brain backup: $note")
        return note
    }
}
