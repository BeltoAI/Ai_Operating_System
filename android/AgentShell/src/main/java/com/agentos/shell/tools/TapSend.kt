package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay

/**
 * ACCESSIBILITY TAP-SEND for platforms with no inline reply (LinkedIn, IG, X).
 *
 * DESIGN = SAFE-BY-DEFAULT. It is deterministic: open the profile → find "Message" → open the thread → type the
 * EXACT drafted message → tap "Send" exactly ONCE → stop. It never improvises text and it CANNOT loop. If any
 * step's element isn't found in the accessibility tree, it aborts and reports the step — it never guesses, never
 * re-sends, never spams. (This is a deliberate step back from the general vision agent, which improvised text
 * and re-sent in a loop — unacceptable when messaging real people.)
 *
 * Requires the SlyOS accessibility service ON. Note: apps that render Message/Send as unlabelled icons may not
 * expose them here, in which case this returns a clean failure — the honest signal that LinkedIn automation via
 * Accessibility isn't reliable, and outreach should go through email/CRM instead.
 */
object TapSend {
    private const val TAG = "SlyOS-TapSend"

    fun available(): Boolean = InteractionLogService.instance != null

    private val MESSAGE_LABELS = listOf("message")
    private val FIELD_LABELS = listOf("message", "write a message", "type a message", "write", "compose")
    private val SEND_LABELS = listOf("send")

    private fun dump(svc: InteractionLogService, step: String) {
        try {
            val labels = svc.readScreen().filter { it.clickable || it.editable }
                .joinToString(" | ") { "${it.role}${if (it.editable) "*" else ""}:\"${it.text.take(30)}\"" }
            Log.i(TAG, "[$step] screen: $labels")
        } catch (e: Exception) {}
    }

    private suspend fun waitForClickable(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.findIndex(nodes, l)?.let { return it }
            delay(700)
        }
        return null
    }

    private suspend fun waitForField(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.fieldIndex(nodes, l)?.let { return it }
            Reflex.fieldIndex(nodes, "")?.let { return it }
            delay(700)
        }
        return null
    }

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
        packageFor(url)?.let { pkg -> try { ctx.startActivity(Intent(intent).setPackage(pkg)); return } catch (e: Exception) {} }
        try { ctx.startActivity(intent) } catch (e: Exception) {}
    }

    /** Open a profile → Message → type exact text → Send ONCE. Returns (ok, step detail). Never loops/improvises. */
    suspend fun sendViaProfile(ctx: Context, openUrl: String, message: String): Pair<Boolean, String> {
        val svc = InteractionLogService.instance ?: return false to "Turn on SlyOS accessibility first."
        if (openUrl.isBlank() || message.isBlank()) return false to "Missing profile link or message."
        return try {
            open(ctx, openUrl)
            delay(3500)
            dump(svc, "profile")
            val msgBtn = waitForClickable(svc, MESSAGE_LABELS, 10000)
                ?: run { dump(svc, "no-message"); return false to "Couldn't find a labelled “Message” button (LinkedIn likely renders it as an unlabelled icon — see logcat)." }
            svc.tapNode(msgBtn)
            delay(1800)
            var field = waitForField(svc, FIELD_LABELS, 8000)
            if (field == null) return false to "Opened the chat but couldn't find the message box."
            svc.setText(field, message)                 // EXACT drafted text, no improvisation
            delay(1200)
            val send = waitForClickable(svc, SEND_LABELS, 7000)
                ?: run { dump(svc, "no-send"); return false to "Typed it, but couldn't find “Send” (unlabelled icon)." }
            svc.tapNode(send)                            // exactly ONE send
            delay(700)
            HealthStore.note("tapsend", true, "sent once")
            true to "Sent ✓"
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }
}
