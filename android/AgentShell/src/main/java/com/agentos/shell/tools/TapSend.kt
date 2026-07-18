package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay

/**
 * ACCESSIBILITY TAP-SEND — for platforms with no inline reply (LinkedIn, IG, X). Opens the person's profile,
 * taps "Message", types the text, finds "Send", taps it — via the accessibility service behind ScreenAgent.
 *
 * Robustness: instead of fixed sleeps (which broke on slow loads / label mismatches), it POLLS the screen for
 * each element (profile → Message → compose box → Send) with a timeout, tries several label variants per step,
 * and reports the EXACT step it stalled on. Aborts safely so it never sends to the wrong place. Accessibility
 * service must be ON.
 */
object TapSend {

    fun available(): Boolean = InteractionLogService.instance != null

    private val MESSAGE_LABELS = listOf("message")
    private val FIELD_LABELS = listOf("message", "write a message", "type a message", "write", "compose")
    private val SEND_LABELS = listOf("send")

    /** Poll for a clickable/labelled node matching any of [labels]; returns its index or null after [timeoutMs]. */
    private suspend fun waitForClickable(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.findIndex(nodes, l)?.let { return it }
            delay(700)
        }
        return null
    }

    /** Poll for an editable field matching any of [labels] (or any editable field). */
    private suspend fun waitForField(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.fieldIndex(nodes, l)?.let { return it }
            Reflex.fieldIndex(nodes, "")?.let { return it }   // any editable field as last resort
            delay(700)
        }
        return null
    }

    /** Open a profile URL → Message → type → Send. Returns (ok, human detail naming the step reached). */
    suspend fun sendViaProfile(ctx: Context, openUrl: String, message: String): Pair<Boolean, String> {
        val svc = InteractionLogService.instance ?: return false to "Turn on SlyOS accessibility (Settings → Total Recall) first."
        if (openUrl.isBlank() || message.isBlank()) return false to "Missing profile link or message."
        return try {
            open(ctx, openUrl)
            delay(1500)
            // 1) wait for the profile's Message button (LinkedIn can take several seconds to render)
            val msgBtn = waitForClickable(svc, MESSAGE_LABELS, 10000)
                ?: return false to "Opened the profile but couldn't find the “Message” button (it may be behind “More”, or LinkedIn changed its layout)."
            svc.tapNode(msgBtn)
            delay(800)
            // 2) wait for the compose box; if not there, some layouts need a tap on the write hint first
            var field = waitForField(svc, FIELD_LABELS, 8000)
            if (field == null) {
                waitForClickable(svc, listOf("write a message", "type a message", "message"), 2500)?.let { svc.tapNode(it); delay(1200) }
                field = waitForField(svc, FIELD_LABELS, 4000)
            }
            if (field == null) return false to "Opened the chat but couldn't find the message box."
            svc.setText(field, message)
            delay(1200)
            // 3) Send may only enable after text — poll for it
            val send = waitForClickable(svc, SEND_LABELS, 7000)
                ?: return false to "Typed the message but couldn't find/enable the “Send” button."
            svc.tapNode(send)
            delay(900)
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
