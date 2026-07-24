package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import com.agentos.shell.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FULL-BRAIN CLOUD SYNC — your ENTIRE brain (every database + prefs + documents + photo memory) saved to
 * YOUR account, so signing in on any device restores everything: settings, expenses, docs, chats, the photo
 * RAG, powers — the complete picture.
 *
 * This sits ON TOP of the granular BrainSync: BrainSync keeps live items fresh item-by-item; BrainCloud
 * carries the whole brain as one archive so a fresh device gets it all in a single restore.
 *
 * Storage: a private Supabase bucket "brains", object "<user_id>/brain.zip" (RLS so each user only touches
 * their own folder). One-time setup is in ACCOUNT_AND_SYNC.md.
 */
object BrainCloud {
    private const val TAG = "SlyOS-BrainCloud"
    private const val BUCKET = "brains"
    private const val PREF = "slyos_braincloud"

    data class Result(val ok: Boolean, val message: String)

    private fun base() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private fun anon() = BuildConfig.SUPABASE_ANON_KEY
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun lastPush(ctx: Context): Long = prefs(ctx).getLong("last_push", 0L)

    private fun objectUrl(uid: String) = base() + "/storage/v1/object/" + BUCKET + "/" + uid + "/brain.zip"

    /** Upload the whole brain to your account (overwrites the previous copy). */
    fun push(ctx: Context): Result {
        if (!AccountStore.signedIn(ctx)) return Result(false, "Sign in to sync your brain across devices.")
        if (base().isBlank() || anon().isBlank()) return Result(false, "Account backend isn't set up.")
        val token = AccountStore.freshAccessToken(ctx); val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return Result(false, "Session expired — sign in again.")
        return try {
            // STREAM the zip straight to Supabase — never load it into memory. The old path did zip.readBytes()
            // on the WHOLE brain, which OOM-crashed the app on a large brain (an OutOfMemoryError is an Error,
            // not an Exception, so nothing caught it). Fixed-length streaming holds ~64KB at a time; Throwable
            // catch means a sync can never take the app down again.
            val zip = BrainBackup.snapshot(ctx, lean = true)   // cloud sync = lean (fits Supabase's object size limit)
            val c = (URL(objectUrl(uid)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", anon())
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("x-upsert", "true")   // overwrite if it already exists
                setFixedLengthStreamingMode(zip.length())
            }
            c.outputStream.use { os -> zip.inputStream().use { it.copyTo(os, 64 * 1024) } }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                prefs(ctx).edit().putLong("last_push", System.currentTimeMillis()).apply()
                Result(true, "Your whole brain is saved to your account (${zip.length() / 1024} KB).")
            } else {
                Log.w(TAG, "push $code: ${resp.take(200)}")
                val friendly = if (code == 413 || resp.contains("maximum allowed size", true) || resp.contains("too large", true))
                    "Your brain is larger than your account's cloud limit. Raise it in Supabase → Storage → Settings (global file size limit) — meanwhile your on-device + Google Drive backups already hold everything."
                else "Couldn't upload ($code)."
                Result(false, friendly)
            }
        } catch (t: Throwable) { Log.w(TAG, "push: ${t.message}"); Result(false, "Upload failed: ${t.message}") }
    }

    /** Download the brain saved to your account and restore it. The caller should restart the app afterward. */
    fun pull(ctx: Context): Result {
        if (!AccountStore.signedIn(ctx)) return Result(false, "Sign in first.")
        if (base().isBlank() || anon().isBlank()) return Result(false, "Account backend isn't set up.")
        val token = AccountStore.freshAccessToken(ctx); val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return Result(false, "Session expired — sign in again.")
        return try {
            val c = (URL(objectUrl(uid)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", anon())
            }
            val code = c.responseCode
            if (code == 200) {
                // STREAM the download to disk (don't hold the whole brain zip in memory → no OOM).
                val f = File(ctx.cacheDir, "brain_cloud_restore.zip")
                c.inputStream.use { input -> f.outputStream().use { input.copyTo(it, 64 * 1024) } }
                val ok = BrainBackup.restore(ctx, f)
                if (ok) Result(true, "Restored your brain from your account.") else Result(false, "Downloaded it, but the restore failed.")
            } else if (code == 404 || code == 400) Result(false, "No brain saved to your account yet — back it up on your other device first.")
            else Result(false, "Couldn't download ($code).")
        } catch (t: Throwable) { Log.w(TAG, "pull: ${t.message}"); Result(false, "Download failed: ${t.message}") }
    }
}
