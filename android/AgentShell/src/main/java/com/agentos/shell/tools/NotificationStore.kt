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
        val replyAction: Notification.Action?
    ) {
        val canReply: Boolean get() = replyAction?.remoteInputs?.isNotEmpty() == true
    }

    val notes = mutableStateListOf<Note>()

    /** Keys currently scheduled for autonomous auto-reply (shown with a cancel option). */
    val pendingAuto = mutableStateListOf<String>()

    /** Set by the listener service so we can dismiss real notifications. */
    @Volatile var listener: NotificationListenerService? = null

    // Shared reply cooldown so manual AND autonomous replies don't double up on a sender.
    private val recentReplies = HashMap<String, Long>()
    private val lastSent = HashMap<String, String>()
    private val recentSent = ArrayDeque<String>()   // global echo guard, last few sent
    private fun senderKey(note: Note) = "${note.app}|${note.title}"
    fun markReplied(note: Note) { recentReplies[senderKey(note)] = System.currentTimeMillis() }
    fun recordSent(note: Note, message: String) {
        val m = message.trim()
        lastSent[senderKey(note)] = m
        recentSent.addFirst(m); while (recentSent.size > 8) recentSent.removeLast()
    }
    fun repliedWithin(note: Note, windowMs: Long): Boolean =
        System.currentTimeMillis() - (recentReplies[senderKey(note)] ?: 0L) < windowMs

    private fun echoes(t: String, s: String) =
        s.isNotBlank() && (t == s || t.contains(s) || s.contains(t))

    /** True if this notification is just an echo of something we recently sent. */
    fun isOwnEcho(note: Note): Boolean {
        val t = note.text.trim()
        if (t.isBlank()) return false
        lastSent[senderKey(note)]?.let { if (echoes(t, it)) return true }
        return recentSent.any { echoes(t, it) }
    }

    /** Dismiss the actual system notification and drop it from the list. */
    fun dismiss(key: String) {
        try { listener?.cancelNotification(key) } catch (e: Exception) { Log.w(TAG, "dismiss failed", e) }
        remove(key)
    }

    fun put(note: Note) {
        notes.removeAll { it.key == note.key }
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
            recordSent(note, message)
            MetricsStore.record(ctx, MetricsStore.secondsFor("reply"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendReply: failed", e)
            false
        }
    }

    private const val TAG = "SlyOS"
}
