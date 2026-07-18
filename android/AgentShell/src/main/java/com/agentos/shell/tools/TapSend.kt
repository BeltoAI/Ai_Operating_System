package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay

/**
 * ACCESSIBILITY TAP-SEND — for platforms that expose no inline reply (LinkedIn, IG, X). SlyOS opens the
 * person's profile, taps "Message", types the text, finds "Send", and taps it — using the accessibility
 * service that already powers ScreenAgent/Reflex. This is what turns "draft-only" apps into true auto-send.
 *
 * SAFETY: it aborts (returns false) the moment any expected element isn't found, so it NEVER types into or
 * sends to the wrong place. It requires the SlyOS accessibility service to be ON. Because per-app UIs vary
 * and timing matters, treat this as best-effort — test on one contact before trusting it at volume.
 */
object TapSend {

    fun available(): Boolean = InteractionLogService.instance != null

    /** Open a profile URL → Message → type → Send. Returns (ok, human detail). */
    suspend fun sendViaProfile(ctx: Context, openUrl: String, message: String): Pair<Boolean, String> {
        val svc = InteractionLogService.instance ?: return false to "Turn on SlyOS accessibility (Settings → Total Recall) first."
        if (openUrl.isBlank() || message.isBlank()) return false to "Missing profile link or message."
        return try {
            open(ctx, openUrl); delay(3800)                     // let the app + profile load
            // 1) tap "Message" to open the thread
            val msgBtn = Reflex.findIndex(svc.readScreen(), "message") ?: return false to "Couldn't find the Message button."
            svc.tapNode(msgBtn); delay(2600)
            // 2) find the compose field (tap a 'write' hint first if it needs focus)
            var field = Reflex.fieldIndex(svc.readScreen(), "message")
            if (field == null) {
                Reflex.findIndex(svc.readScreen(), "write")?.let { svc.tapNode(it); delay(1000) }
                field = Reflex.fieldIndex(svc.readScreen(), "message")
            }
            if (field == null) return false to "Couldn't find the message box."
            svc.setText(field, message); delay(900)
            // 3) find + tap Send
            val send = Reflex.findIndex(svc.readScreen(), "send") ?: return false to "Typed it, but couldn't find Send."
            svc.tapNode(send); delay(600)
            HealthStore.note("tapsend", true, "sent via tap")
            true to "Sent via tap ✓"
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }

    private fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }
}
