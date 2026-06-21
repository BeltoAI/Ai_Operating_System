package com.agentos.shell.tools

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * Holds the real notifications the listener captures. Backed by a Compose state list so the
 * Now / Lock screens recompose automatically when notifications arrive or clear.
 *
 * A note is "repliable" when its source app attached a RemoteInput reply action (WhatsApp,
 * Messages, Signal, etc. all do). We keep the Action in memory so the agent can fire it.
 */
object NotificationStore {

    data class Note(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val replyAction: Notification.Action?,
        val picture: android.graphics.Bitmap? = null,
        val pkg: String = ""
    ) {
        val canReply: Boolean get() = replyAction?.remoteInputs?.isNotEmpty() == true
        val isSocial: Boolean get() = pkg in setOf(
            "com.twitter.android", "com.reddit.frontpage", "com.instagram.android",
            "com.linkedin.android", "com.zhiliaoapp.musically"
        )
        val isEmail: Boolean get() = pkg == "com.google.android.gm"
        val isLikelyBot: Boolean get() {
            val s = "$title $text".lowercase()
            return listOf("no-reply", "noreply", "no reply", "do not reply", "donotreply",
                "notifications@", "newsletter", "mailer-daemon", "automated", "unsubscribe",
                "verification code", "verify your").any { s.contains(it) }
        }
        /**
         * Engagement-bait / digest notifications with no real person behind them
         * ("see updates you missed", "people you may know", "X is hiring", trending, etc.).
         * These are pure noise — we keep them out of the feed.
         */
        /**
         * Is this an actual human message/comment we should offer to reply to — on ANY platform?
         * True when it has a reply box, comes from a known messaging/social app, or reads like a
         * message/comment/mention. False for transactional/system noise (orders, banks, rides, news).
         */
        val isConversational: Boolean get() {
            if (isLikelyBot || isLowValue) return false
            if (canReply) return true
            if (pkg in MESSAGING_PKGS) return true
            val s = "$title $text".lowercase()
            return listOf("message", "messaged", "sent you", "sent a", "texted", "replied", "reply",
                "comment", "commented", "mentioned", "mention", "tagged", "wants to", "shared a",
                "dm", "direct message", "@you", "posted", "reacted").any { s.contains(it) }
        }
        val isLowValue: Boolean get() {
            val s = "$title $text".lowercase()
            // Only UNAMBIGUOUS digest/promo phrases — things a real person would never text you.
            val baits = listOf(
                "you may have missed", "updates you missed", "notifications you missed",
                "missed notifications", "new notifications from", "people you may know",
                "suggested for you", "recommended for you", "viewed your profile",
                "profile views", "work anniversary", "is hiring", "are hiring",
                "in your network", "daily digest", "weekly digest", "what you missed this",
                "started following you", "stories for you", "reels for you",
                "trending in your", "new connections", "people you may want to follow"
            )
            // Never treat a direct message / reply / mention / comment as low-value.
            val real = listOf("message", "messaged", "sent you", "replied", "comment", "commented",
                "mentioned", "tagged you", "wants to", "shared", "voice", "photo").any { s.contains(it) }
            return !real && baits.any { s.contains(it) }
        }
    }

    // Known messaging + social apps — anything from these is treated as conversational.
    private val MESSAGING_PKGS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.facebook.mlite",
        "com.instagram.android", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.twitter.android", "com.x.android", "com.snapchat.android", "com.discord", "com.Slack",
        "com.reddit.frontpage", "com.linkedin.android", "com.zhiliaoapp.musically", "com.tencent.mm",
        "com.viber.voip", "jp.naver.line.android", "com.microsoft.teams", "com.skype.raider",
        "com.kakao.talk", "com.groupme.android"
    )

    val notes = mutableStateListOf<Note>()

    /** Keys currently scheduled for autonomous auto-reply (shown with a cancel option). */
    val pendingAuto = mutableStateListOf<String>()

    /** Set by the listener service so we can dismiss real notifications. */
    @Volatile var listener: NotificationListenerService? = null

    // Shared reply cooldown so manual AND autonomous replies don't double up on a sender.
    private val recentReplies = HashMap<String, Long>()
    private val recentSent = ArrayDeque<Pair<String, Long>>()   // (text, time) — echo guard
    private fun senderKey(note: Note) = "${note.app}|${note.title}"
    fun markReplied(note: Note) { recentReplies[senderKey(note)] = System.currentTimeMillis() }
    fun recordSent(note: Note, message: String) {
        recentSent.addFirst(message.trim() to System.currentTimeMillis())
        while (recentSent.size > 10) recentSent.removeLast()
    }
    fun repliedWithin(note: Note, windowMs: Long): Boolean =
        System.currentTimeMillis() - (recentReplies[senderKey(note)] ?: 0L) < windowMs

    // Messages we've already replied to — so the app re-posting the same notification doesn't put
    // a handled conversation back in the inbox. Matched by content signature, time-bounded.
    private val handled = ArrayDeque<Pair<String, Long>>()
    private fun sig(note: Note) = "${note.app}|${note.title}|${note.text}".lowercase().trim()
    fun markHandled(note: Note) {
        handled.addFirst(sig(note) to System.currentTimeMillis())
        while (handled.size > 50) handled.removeLast()
    }
    fun isHandled(note: Note): Boolean {
        if (note.text.isBlank()) return false
        val s = sig(note); val now = System.currentTimeMillis()
        return handled.any { (h, t) -> now - t < 15 * 60_000L && h == s }
    }

    /**
     * True only if this notification is clearly an echo of something we JUST sent (within ~20s)
     * — strict match so normal short replies like "ok" aren't wrongly suppressed.
     */
    fun isOwnEcho(note: Note): Boolean {
        val t = note.text.trim()
        if (t.isBlank()) return false
        val now = System.currentTimeMillis()
        return recentSent.any { (s, ts) ->
            now - ts < 20_000L && (t.equals(s, true) || (s.length > 12 && t.contains(s, true)))
        }
    }

    /** Dismiss the actual system notification and drop it from the list. */
    fun dismiss(key: String) {
        try { listener?.cancelNotification(key) } catch (e: Exception) { Log.w(TAG, "dismiss failed", e) }
        remove(key)
    }

    fun put(note: Note) {
        // Don't re-show an echo of our own reply, or a message we've already handled (replied to) —
        // apps love to re-post the same notification right after you reply.
        if (isOwnEcho(note) || isHandled(note)) return
        // Drop the same notification (by key) AND any duplicate with identical sender + text.
        notes.removeAll { it.key == note.key || (it.app == note.app && it.title == note.title && it.text == note.text) }
        notes.add(0, note)
        while (notes.size > 25) notes.removeAt(notes.size - 1)
    }

    fun remove(key: String) {
        notes.removeAll { it.key == key }
    }

    /** Fire the notification's own reply action with [message] — a real reply, no root. */
    fun sendReply(ctx: Context, note: Note, message: String): Boolean {
        val action = note.replyAction
        if (action == null) { Log.w(TAG, "sendReply: no replyAction for ${note.app}"); return false }
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) { Log.w(TAG, "sendReply: no remoteInputs"); return false }
        Log.i(TAG, "sendReply: app=${note.app} keys=${remoteInputs.joinToString { it.resultKey }} " +
            "pendingIntent=${action.actionIntent} msg=\"$message\"")
        return try {
            val intent = Intent()
            val results = Bundle()
            remoteInputs.forEach { ri -> results.putCharSequence(ri.resultKey, message) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            Log.i(TAG, "sendReply: fired OK")
            markReplied(note)
            markHandled(note)          // keep this conversation out of the inbox after replying
            recordSent(note, message)
            ConversationStore.add(ctx, note.app, note.title, "me", message)
            MessageStore.insertOne(ctx, note.title, note.app, note.title, "me", message)
            remove(note.key)           // drop it from Now immediately
            MetricsStore.record(ctx, MetricsStore.secondsFor("reply"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendReply: failed", e)
            false
        }
    }

    private const val TAG = "SlyOS"
}
