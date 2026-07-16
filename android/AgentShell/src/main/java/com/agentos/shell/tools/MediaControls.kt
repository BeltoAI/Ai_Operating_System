package com.agentos.shell.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Reads and controls whatever is currently playing audio (Spotify, YouTube Music, podcasts — anything that
 * publishes a MediaSession). Uses MediaSessionManager, which requires notification-listener access — SlyOS
 * already holds it via AgentNotificationListener, so no new permission. Everything degrades gracefully to null
 * when nothing is playing, which is exactly what drives the "only show the mini-player when there's music" UI.
 */
object MediaControls {
    private const val TAG = "SlyOS-Media"
    private val LISTENER = "com.agentos.shell.AgentNotificationListener"

    data class NowPlaying(
        val title: String, val artist: String, val app: String, val pkg: String,
        val playing: Boolean, val art: Bitmap?
    )

    private fun msm(ctx: Context): MediaSessionManager? =
        try { ctx.applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager } catch (e: Exception) { null }

    private fun component(ctx: Context) = ComponentName(ctx.applicationContext, LISTENER)

    /** The most relevant active controller: prefer the one that's actually PLAYING, else the first present. */
    private fun top(ctx: Context): MediaController? {
        return try {
            val sessions = msm(ctx)?.getActiveSessions(component(ctx)) ?: return null
            if (sessions.isEmpty()) return null
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.first()
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification-listener access yet"); null
        } catch (e: Exception) { Log.w(TAG, "getActiveSessions: ${e.message}"); null }
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    /** Snapshot of what's playing right now, or null if nothing is. */
    fun nowPlaying(ctx: Context): NowPlaying? {
        val c = top(ctx) ?: return null
        return try {
            val md = c.metadata
            val state = c.playbackState?.state
            val playing = state == PlaybackState.STATE_PLAYING
            // Some apps hold a stale idle session; treat NONE/STOPPED with no title as "not playing".
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM))?.trim().orEmpty()
            if (title.isBlank() && !playing) return null
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            NowPlaying(title.ifBlank { "Playing" }, artist, appLabel(ctx, c.packageName), c.packageName, playing, art)
        } catch (e: Exception) { null }
    }

    fun isActive(ctx: Context): Boolean = nowPlaying(ctx) != null

    fun playPause(ctx: Context): String {
        val c = top(ctx) ?: return "Nothing's playing."
        return try {
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) { c.transportControls.pause(); "Paused." } else { c.transportControls.play(); "Playing." }
        } catch (e: Exception) { "I couldn't reach the player." }
    }

    fun stop(ctx: Context): String {
        val c = top(ctx) ?: return "Nothing's playing."
        return try { c.transportControls.stop(); "Stopped." }
        catch (e: Exception) { try { c.transportControls.pause() } catch (ex: Exception) {}; "Paused." }
    }

    fun next(ctx: Context): String {
        val c = top(ctx) ?: return "Nothing's playing."
        return try { c.transportControls.skipToNext(); "Skipped ahead." } catch (e: Exception) { "I couldn't skip." }
    }

    fun previous(ctx: Context): String {
        val c = top(ctx) ?: return "Nothing's playing."
        return try { c.transportControls.skipToPrevious(); "Went back." } catch (e: Exception) { "I couldn't go back." }
    }

    /** Open the player app (its own now-playing screen if it exposed one, else just launch it). */
    fun open(ctx: Context): String {
        val c = top(ctx) ?: return "Nothing's playing."
        return try {
            val pi = c.sessionActivity
            if (pi != null) { pi.send(); "Opening ${appLabel(ctx, c.packageName)}." }
            else {
                val i = ctx.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i != null) { ctx.startActivity(i); "Opening ${appLabel(ctx, c.packageName)}." } else "I couldn't open the player."
            }
        } catch (e: Exception) { "I couldn't open the player." }
    }
}
