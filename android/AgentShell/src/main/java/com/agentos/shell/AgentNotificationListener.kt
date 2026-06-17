package com.agentos.shell

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
    private val cooldownMs = 10_000L

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
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null   // skip the duplicate "summary"

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

        // Best-effort: some apps attach the image as EXTRA_PICTURE (BigPictureStyle).
        val picture = try {
            extras.get(android.app.Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
        } catch (e: Exception) { null }

        val note = NotificationStore.Note(sbn.key, appLabel, title, text, replyAction, picture, sbn.packageName)
        NotificationStore.put(note)
        // Record incoming messages per-conversation (only repliable = real messages).
        if (note.canReply && text.isNotBlank())
            com.agentos.shell.tools.ConversationStore.add(applicationContext, appLabel, title, "them", text)
        return note
    }

    private fun maybeAutoReply(note: NotificationStore.Note) {
        if (!note.canReply) return
        if (note.isEmail) return   // email is always human-reviewed, never autonomous
        if (!MemoryStore.appAutoEnabled(applicationContext, note.pkg)) return   // per-app opt-out
        val telegram = note.pkg.startsWith("org.telegram")
        val docMode = telegram && MemoryStore.docTelegram(applicationContext) &&
            com.agentos.shell.tools.KnowledgeStore.hasDoc(applicationContext)
        // Telegram document-answering is its own lane; otherwise require autonomous (toggle OR
        // night schedule). Covers EVERY messaging/social app that exposes a reply action.
        if (!MemoryStore.autonomousEffective(applicationContext) && !docMode) return
        if (NotificationStore.pendingAuto.contains(note.key)) { Log.i("SlyOS", "auto skip: already pending ${note.title}"); return }
        if (NotificationStore.isOwnEcho(note)) { Log.i("SlyOS", "auto skip: own echo"); return }
        if (NotificationStore.repliedWithin(note, cooldownMs)) { Log.i("SlyOS", "auto skip: cooldown ${note.title}"); return }
        NotificationStore.markReplied(note)
        NotificationStore.pendingAuto.add(note.key)
        Log.i("SlyOS", "auto reply scheduled for ${note.title}: \"${note.text}\"")
        scope.launch {
            try {
                val memory = MemoryStore.about(applicationContext)
                val img = note.picture?.let { com.agentos.shell.tools.ImageUtil.encodeBitmap(it) }
                val draft = when {
                    docMode -> AgentClient.answerFromDoc(
                        note.text, com.agentos.shell.tools.KnowledgeStore.retrieve(applicationContext, note.text)
                    )
                    note.isSocial -> AgentClient.draftCommentReply(note.text, memory)
                    else -> {
                        val thread = com.agentos.shell.tools.ConversationStore
                            .thread(applicationContext, note.app, note.title).map { it.role to it.text }
                        val ctxMem = com.agentos.shell.tools.ReplyContext
                            .forSender(applicationContext, note.app, note.title)
                        AgentClient.draftReplyThread(note.title.ifBlank { note.app }, thread, ctxMem, img)
                    }
                }
                delay(undoWindowMs)
                if (NotificationStore.pendingAuto.contains(note.key)) {
                    val ok = NotificationStore.sendReply(applicationContext, note, draft)
                    Log.i("SlyOS", "auto reply sent=$ok to ${note.title}: \"$draft\"")
                    if (ok) NotificationStore.dismiss(note.key)
                }
            } catch (e: Exception) {
                Log.e("SlyOS", "auto reply failed", e)
            } finally {
                NotificationStore.pendingAuto.remove(note.key)   // always clear
            }
        }
    }
}
