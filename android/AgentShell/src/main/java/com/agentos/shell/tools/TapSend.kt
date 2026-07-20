package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ACCESSIBILITY TAP-SEND for platforms with no inline reply (LinkedIn, IG, X).
 *
 * LinkedIn's Message/Send are UNLABELLED icons (confirmed: no text, no desc, no resource-id), so label-matching
 * can't find them — only VISION can. This does a CONTROLLED, single-shot flow:
 *   open profile → (label OR vision) tap "Message" → find the editable box → type the EXACT drafted text →
 *   (label OR vision) tap "Send" ONCE → stop.
 * It NEVER improvises the message and it CANNOT loop (that was the earlier spam bug). If any element can't be
 * located, it aborts cleanly — never re-sends, never guesses wildly. Requires SlyOS accessibility ON and a
 * vision-capable brain (Gemini/OpenAI/Claude/GitHub) for the vision fallback.
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
                .joinToString(" | ") { "${it.role}${if (it.editable) "*" else ""}:\"${it.text.take(24)}\"" }
            Log.i(TAG, "[$step] $labels")
        } catch (e: Exception) {}
    }

    private suspend fun waitForClickable(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.findIndex(nodes, l)?.let { return it }
            delay(600)
        }
        return null
    }

    private suspend fun waitForField(svc: InteractionLogService, labels: List<String>, timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val nodes = svc.readScreen()
            for (l in labels) Reflex.fieldIndex(nodes, l)?.let { return it }
            Reflex.fieldIndex(nodes, "")?.let { return it }
            delay(600)
        }
        return null
    }

    private suspend fun screenshot(svc: InteractionLogService): String? = suspendCancellableCoroutine { cont ->
        try { svc.captureScreenshot { b64 -> if (cont.isActive) cont.resume(b64) } }
        catch (e: Exception) { if (cont.isActive) cont.resume(null) }
    }

    /** Ask the vision model for the tap point of [desc] and tap it. Returns true if it tapped. */
    private suspend fun visionTap(svc: InteractionLogService, desc: String): Boolean {
        val shot = screenshot(svc) ?: return false
        val ans = try {
            AgentClient.askVision(
                "This is a phone screenshot. Find $desc. Reply with ONLY the tap point as two integers \"X Y\" on a " +
                    "0–1000 grid (0 0 = top-left, 1000 1000 = bottom-right). If it is not visible, reply exactly: none",
                listOf(shot), "", maxTokens = 20)
        } catch (e: Exception) { "" }
        if (ans.contains("none", true)) { Log.i(TAG, "vision: '$desc' not visible"); return false }
        val m = Regex("(\\d{1,4})\\s*[,x ]\\s*(\\d{1,4})").find(ans) ?: run { Log.i(TAG, "vision: no coord in '${ans.take(40)}'"); return false }
        val gx = m.groupValues[1].toInt().coerceIn(0, 1000); val gy = m.groupValues[2].toInt().coerceIn(0, 1000)
        val px = gx / 1000f * svc.screenW; val py = gy / 1000f * svc.screenH
        Log.i(TAG, "visionTap '$desc' grid=$gx,$gy px=$px,$py")
        return svc.tapAt(px, py)
    }

    /** Tap a target by label first (cheap), else by vision. */
    private suspend fun tapTarget(svc: InteractionLogService, labels: List<String>, visionDesc: String): Boolean {
        waitForClickable(svc, labels, 3000)?.let { svc.tapNode(it); return true }
        return visionTap(svc, visionDesc)
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

    /**
     * MISSION path: we only know a NAME (+ company), not a profile URL — so land on the person first.
     * Opens a LinkedIn people-search deep link, taps the result whose row actually contains the person's
     * name, then runs the exact same single-shot message flow. Aborts cleanly if no matching result.
     */
    suspend fun sendViaSearch(ctx: Context, searchUrl: String, personName: String, message: String): Pair<Boolean, String> {
        val svc = InteractionLogService.instance ?: return false to "Turn on SlyOS accessibility first."
        if (searchUrl.isBlank() || message.isBlank() || personName.isBlank())
            return false to "Missing search link, name, or message."
        return try {
            open(ctx, searchUrl)
            delay(5000)
            dump(svc, "search")
            // Find the result row for THIS person. Match on the full name first, then the surname (LinkedIn
            // often renders "Name · 2nd" or truncates), never on the first name alone (too many false hits).
            val last = personName.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            val nodes = svc.readScreen()
            var idx = nodes.indexOfFirst { it.clickable && it.text.contains(personName, true) }
            if (idx < 0 && last.length >= 3)
                idx = nodes.indexOfFirst { it.clickable && it.text.contains(last, true) }
            if (idx < 0) {
                // Vision fallback: point at the search result card for this person.
                if (!visionTap(svc, "the search-result row for the person named “$personName” (tap their name/photo to open their profile)")) {
                    dump(svc, "no-result"); return false to "Couldn't find “$personName” in LinkedIn search results."
                }
            } else svc.tapNode(idx)
            delay(4500)
            dump(svc, "profile-from-search")
            messageFromProfile(ctx, svc, message, personName)
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }

    suspend fun sendViaProfile(ctx: Context, openUrl: String, message: String, recipient: String = ""): Pair<Boolean, String> {
        val svc = InteractionLogService.instance ?: return false to "Turn on SlyOS accessibility first."
        if (openUrl.isBlank() || message.isBlank()) return false to "Missing profile link or message."
        return try {
            open(ctx, openUrl)
            delay(4000)
            dump(svc, "profile")
            messageFromProfile(ctx, svc, message, recipient)
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            Fail.log(ctx, "LinkedIn", "tap-send to $recipient", e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }

    /** Shared single-shot flow, starting from an OPEN profile: Message → verify person → type → Send once → back out. */
    private suspend fun messageFromProfile(ctx: Context, svc: InteractionLogService, message: String, recipient: String): Pair<Boolean, String> {
        return try {
            if (!tapTarget(svc, MESSAGE_LABELS, "the button that opens a direct message/chat with this person (usually a “Message” button near the top of the profile)")) {
                dump(svc, "no-message"); return false to "Couldn't find the Message button (label or vision)."
            }
            delay(2600)
            dump(svc, "chat")
            // SAFETY: confirm the open chat is actually the intended person before typing a word. This prevents
            // ever messaging the wrong person (e.g. drafting for one contact but landing in another's thread).
            val first = recipient.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (first.length >= 2) {
                val onScreen = svc.readScreen().any { it.text.contains(first, ignoreCase = true) }
                if (!onScreen) {
                    dump(svc, "wrong-chat")
                    return false to "Safety stop — the open chat doesn't look like “$recipient”. Nothing sent."
                }
            }
            val field = waitForField(svc, FIELD_LABELS, 8000)
                ?: return false to "Opened the chat but couldn't find the message box."
            svc.setText(field, message)                  // EXACT drafted text
            delay(1300)
            dump(svc, "typed")
            if (!tapTarget(svc, SEND_LABELS, "the Send button that sends the message I just typed (often a paper-plane icon or a button that says Send, usually bottom-right of the message box)")) {
                dump(svc, "no-send"); return false to "Typed it, but couldn't find the Send button."
            }
            delay(1000)
            // Leave the chat so the NEXT person's profile opens clean (LinkedIn won't navigate a fresh deep link
            // while it's already foreground on this chat). Back out to a neutral screen.
            try { svc.back(); delay(700); svc.back(); delay(500) } catch (e: Exception) {}
            HealthStore.note("tapsend", true, "sent once")
            true to "Sent ✓"
        } catch (e: Exception) {
            HealthStore.note("tapsend", false, e.message ?: "error")
            false to (e.message ?: "Tap-send error.")
        }
    }
}
