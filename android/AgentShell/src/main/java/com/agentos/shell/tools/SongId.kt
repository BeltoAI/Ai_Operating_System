package com.agentos.shell.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shazam-style song recognition. Records ~8s from the mic, uploads it to AudD (a music-recognition REST API —
 * the owner brings their own free token from audd.io, stored like the other keys), and returns the track. On a
 * hit it also hands the "Artist Title" query to ToolRouter so it opens in Spotify. Everything is best-effort and
 * returns a spoken-style string; the caller runs this off the main thread (it blocks ~8s while listening).
 */
object SongId {
    private const val TAG = "SlyOS-SongId"
    private const val RECORD_MS = 8000L

    fun hasMic(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    data class Song(val title: String, val artist: String, val query: String)

    /** Blocking: listen, recognize, and (on a hit) open Spotify. Returns what to say back to the owner. */
    fun identify(ctx: Context, openInSpotify: Boolean = true): String {
        if (!hasMic(ctx)) return "I need microphone access to listen. Enable it for SlyOS in Settings, then ask again."
        val token = MemoryStore.musicIdToken(ctx)
        if (token.isBlank())
            return "To name songs I hear, add a free AudD token in Setup → Keys (get one at audd.io). Then I can listen and open it in Spotify."
        val file = File(ctx.cacheDir, "songid.m4a")
        if (!record(ctx, file)) return "I couldn't record from the mic just now."
        val song = try { recognize(token, file) } catch (e: Exception) { Log.w(TAG, "recognize: ${e.message}"); null }
        try { file.delete() } catch (e: Exception) {}
        if (song == null) return "I listened but couldn't recognize the song — too noisy or too quiet, maybe. Try again closer to the source."
        try { MemoryLog.add(ctx, "note", "Song ID", "Heard: ${song.artist} — ${song.title}", "SlyOS") } catch (e: Exception) {}
        if (openInSpotify) { try { ToolRouter.executeAction(ctx, "play_music", song.query) } catch (e: Exception) {} }
        return "That's “${song.title}” by ${song.artist}." + (if (openInSpotify) " Opening it in your music app." else "")
    }

    private fun record(ctx: Context, out: File): Boolean {
        val rec = try { if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder() } catch (e: Exception) { return false }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128000)
            rec.setAudioSamplingRate(44100)
            rec.setOutputFile(out.absolutePath)
            rec.prepare(); rec.start()
            Thread.sleep(RECORD_MS)
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
            out.exists() && out.length() > 1000
        } catch (e: Exception) {
            Log.w(TAG, "record: ${e.message}")
            try { rec.release() } catch (ex: Exception) {}
            false
        }
    }

    /** Multipart POST the clip to AudD; parse title/artist. */
    private fun recognize(token: String, file: File): Song? {
        val boundary = "----slyos" + System.currentTimeMillis()
        val conn = (URL("https://api.audd.io/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 25000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        fun field(dos: DataOutputStream, name: String, value: String) {
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            dos.writeBytes("$value\r\n")
        }
        DataOutputStream(conn.outputStream).use { dos ->
            field(dos, "api_token", token)
            field(dos, "return", "spotify")
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\"\r\n")
            dos.writeBytes("Content-Type: audio/mp4\r\n\r\n")
            file.inputStream().use { it.copyTo(dos) }
            dos.writeBytes("\r\n--$boundary--\r\n")
            dos.flush()
        }
        val code = conn.responseCode
        val body = try { (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { "" }
        conn.disconnect()
        if (body.isBlank()) return null
        val json = JSONObject(body)
        if (json.optString("status") != "success") { Log.w(TAG, "audd: ${json.optString("error")}"); return null }
        val r = json.optJSONObject("result") ?: return null
        val title = r.optString("title").trim()
        val artist = r.optString("artist").trim()
        if (title.isBlank() && artist.isBlank()) return null
        return Song(title.ifBlank { "this track" }, artist.ifBlank { "an unknown artist" }, "$artist $title".trim())
    }
}
