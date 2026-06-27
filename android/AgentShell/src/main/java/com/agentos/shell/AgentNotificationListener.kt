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
        // EXTRA_TEXT is often a TRUNCATED preview for long messages — prefer the full BigText /
        // TextLines so the agent sees the whole message and doesn't think it was cut off.
        val short = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }.orEmpty()
        val text = listOf(big, lines, short).maxByOrNull { it.length } ?: short
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
        // Drop engagement-bait / digests ONLY when they're non-interactive (no reply box). Real
        // DMs always carry a reply action, so they're never filtered.
        if (note.isLowValue && !note.canReply) return null
        NotificationStore.put(note)
        // Record every real human message/comment into per-conversation memory (any platform), but
        // NOT transactional/system noise (orders, banks, rides, news). Skip own echoes.
        // Emails count too (real ones, not no-reply/verification) so received mail feeds the brain.
        val capture = note.isConversational || (note.isEmail && !note.isLikelyBot)
        if (capture && title.isNotBlank()) {
            val platform = if (note.isEmail) "Email" else appLabel
            val contact = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() } ?: title
            // Modern chat notifications (MessagingStyle) carry the whole recent thread with senders —
            // capture EVERY message (both directions), not just the latest line. De-duped so repeated
            // notification updates don't double-store.
            var storedAny = false
            try {
                val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (msgs != null) {
                    for (p in msgs) {
                        val b = p as? android.os.Bundle ?: continue
                        val mt = b.getCharSequence("text")?.toString().orEmpty()
                        if (mt.isBlank()) continue
                        val sender = b.getCharSequence("sender")?.toString()
                            ?: (b.getParcelable("sender_person") as? android.app.Person)?.name?.toString()
                        val role = if (sender.isNullOrBlank()) "me" else "them"   // no sender = you
                        if (!firstTime("$contact|$role|$mt")) continue
                        com.agentos.shell.tools.ConversationStore.add(applicationContext, platform, contact, role, mt)
                        com.agentos.shell.tools.MessageStore.insertOne(applicationContext, contact, platform, sender ?: contact, role, mt)
                        storedAny = true
                    }
                }
            } catch (e: Exception) {}
            // Fallback (no MessagingStyle, e.g. email/SMS): store the single message, de-duped.
            if (!storedAny && text.isNotBlank() && !NotificationStore.isOwnEcho(note) && firstTime("$contact|them|$text")) {
                com.agentos.shell.tools.ConversationStore.add(applicationContext, platform, contact, "them", text)
                com.agentos.shell.tools.MessageStore.insertOne(applicationContext, contact, platform, contact, "them", text)
            }
        }
        return note
    }

    // Small LRU so the same message in repeated notification updates is stored only once.
    private val seenKeys = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private fun firstTime(key: String): Boolean {
        synchronized(seenKeys) {
            if (seenKeys.contains(key)) return false
            seenKeys.add(key)
            if (seenKeys.size > 800) { val iter = seenKeys.iterator(); repeat(200) { if (iter.hasNext()) { iter.next(); iter.remove() } } }
            return true
        }
    }

    /** A draft is only safe to auto-send if it's real text — not an error, placeholder, or empty. */
    private fun isSendable(draft: String): Boolean {
        val d = draft.trim()
        if (d.length < 1 || d.length > 1500) return false
        val low = d.lowercase()
        val bad = listOf("[couldn't", "err::", "agent error", "couldn't ", "no api key",
            "having trouble connecting", "no document is loaded", "(no reply)")
        return bad.none { low.startsWith(it) || low.contains("err::") }
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
                        note.text, com.agentos.shell.tools.KnowledgeStore.retrieve(applicationContext, note.text), memory
                    )
                    note.isSocial -> AgentClient.draftCommentReply(note.text,
                        com.agentos.shell.tools.ReplyContext.forSender(applicationContext, note.app, note.title)
                            .ifBlank { memory })
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
                    // FAIL-SAFE: never send an error/placeholder/empty draft to a real person.
                    if (!isSendable(draft)) {
                        Log.w("SlyOS", "auto reply skipped — draft not sendable: \"${draft.take(60)}\"")
                    } else {
                        val ok = NotificationStore.sendReply(applicationContext, note, draft)
                        Log.i("SlyOS", "auto reply sent=$ok to ${note.title}: \"$draft\"")
                        if (ok) NotificationStore.dismiss(note.key)
                    }
                }
            } catch (e: Exception) {
                Log.e("SlyOS", "auto reply failed", e)
            } finally {
                NotificationStore.pendingAuto.remove(note.key)   // always clear
            }
        }
    }
}
