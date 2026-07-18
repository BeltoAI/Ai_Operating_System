package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay

/**
 * ACCESSIBILITY TAP-SEND for platforms with no inline reply (LinkedIn, IG, X).
 *
 * It DELEGATES the actual on-screen work to ScreenAgent — the same vision-capable "operate my phone" engine
 * (with the overlay play button) the user already sees work. That matters because LinkedIn's Message/Send are
 * UNLABELLED icon buttons the accessibility tree can't name; only the vision planner can find them. So instead
 * of matching labels ourselves (which failed), we open the person's profile in the native app and hand a
 * "tap Message, type, send" goal to ScreenAgent, then wait for it to finish.
 *
 * Requires the SlyOS accessibility service ON.
 */
object TapSend {

    fun available(): Boolean = InteractionLogService.instance != null

    /** Package that owns a URL's platform, so we open the NATIVE app (which has Message) not a browser. */
    private fun packageFor(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains("linkedin.com") -> "com.linkedin.android"
            u.contains("instagram.com") -> "com.instagram.android"
            u.contains("twitter.com") || u.contains("x.com") -> "com.twitter.android"
            u.contains("facebook.com") -> "com.facebook.katana"
            else -> null
        }
    }

    private fun open(ctx: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        packageFor(url)?.let { pkg ->
            try { ctx.startActivity(Intent(intent).setPackage(pkg)); return } catch (e: Exception) {}
        }
        try { ctx.startActivity(intent) } catch (e: Exception) {}
    }

    /** Open a profile → have the vision agent tap Message, type, and Send. Returns (ok, detail). */
    suspend fun sendViaProfile(ctx: Context, openUrl: String, message: String): Pair<Boolean, String> {
        if (InteractionLogService.instance == null)
            return false to "Turn on SlyOS accessibility (Settings → Total Recall) first."
        if (openUrl.isBlank() || message.isBlank()) return false to "Missing profile link or message."
        return try {
            // Make sure the vision agent is free (finish any prior run) before we start this one.
            var waited = 0
            while (ScreenAgent.running && waited < 20) { delay(1000); waited++ }

            open(ctx, openUrl)
            delay(4000)   // let the native app load the profile

            val goal = "You are on a LinkedIn profile. Tap the “Message” button, then type this exact message " +
                "into the message box and press Send, then stop: \"" + message.take(500) + "\""
            ScreenAgent.start(ctx, goal)

            // Wait for it to spin up, then run to completion (vision + overlay), with a ceiling.
            delay(2500)
            val end = System.currentTimeMillis() + 120_000
            while (ScreenAgent.running && System.currentTimeMillis() < end) delay(1200)

            HealthStore.note("tapsend", true, "ran via ScreenAgent")
            true to "Sent via screen agent"
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }
}
