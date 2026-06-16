package com.agentos.shell

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads real notifications and feeds Now / People / Lock, and captures each Reply action so
 * the agent can respond. When autonomous mode is on, newly-arriving messages get an
 * auto-reply after an 8-second undo window (cancellable from the Now screen).
 */
class AgentNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val undoWindowMs = 8000L
    private val cooldownMs = 90_000L

    override fun onListenerConnected() {
        NotificationStore.listener = this
        activeNotifications?.forEach { ingest(it) }   // backfill, no auto-reply
    }

    override fun onListenerDisconnected() {
        NotificationStore.listener = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val note = ingest(sbn) ?: return
        maybeAutoReply(note)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
        NotificationStore.pendingAuto.remove(sbn.key)
    }

    private fun ingest(sbn: StatusBarNotification): NotificationStore.Note? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val appLabel = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        val replyAction = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.resultKey != null } == true
        }

        val note = NotificationStore.Note(sbn.key, appLabel, title, text, replyAction)
        NotificationStore.put(note)
        return note
    }

    private fun maybeAutoReply(note: NotificationStore.Note) {
        if (!note.canReply) return
        if (!MemoryStore.autonomous(applicationContext)) return
        if (NotificationStore.pendingAuto.contains(note.key)) return
        // Don't reply to our own sent message echoed back in the notification.
        if (NotificationStore.isOwnEcho(note)) return

        // One reply per sender per cooldown — shared with manual replies — stops the
        // post-reply notification update from re-triggering an endless loop.
        if (NotificationStore.repliedWithin(note, cooldownMs)) return
        NotificationStore.markReplied(note)   // reserve immediately

        NotificationStore.pendingAuto.add(note.key)
        scope.launch {
            val memory = MemoryStore.about(applicationContext)
            val draft = AgentClient.draftReply(note.app, note.text, memory)
            delay(undoWindowMs)
            if (NotificationStore.pendingAuto.contains(note.key)) {
                val ok = NotificationStore.sendReply(applicationContext, note, draft)
                if (ok) NotificationStore.dismiss(note.key)
                NotificationStore.pendingAuto.remove(note.key)
            }
        }
    }
}
