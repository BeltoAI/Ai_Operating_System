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

    @Suppress("DEPRECATION")   // getParcelableArray/getParcelable typed overloads are 33+; minSdk is 29
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

        val note = NotificationStore.Note(sbn.key, appLabel, title, text, replyAction, picture, sbn.packageName, n.contentIntent)
        // Drop engagement-bait / digests ONLY when they're non-interactive (no reply box). Real
        // DMs always carry a reply action, so they're never filtered.
        if (note.isLowValue && !note.canReply) return null
        NotificationStore.put(note)
        // Record every real human message/comment into per-conversation memory (any platform), but
        // NOT transactional/system noise (orders, banks, rides, news). Skip own echoes.
        // Emails count too (real ones, not no-reply/verification) so received mail feeds the brain.
        val capture = note.isConversational || (note.isEmail && !note.isLikelyBot) || note.isMeetingNotes
        if (capture && title.isNotBlank()) {
            val platform = when { note.isMeetingNotes -> "Meeting notes"; note.isEmail -> "Email"; else -> appLabel }
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
            // INBOUND MEDIA → brain: if someone sent a picture, describe it once (vision) and store it,
            // so the agent knows what they sent ("what did Sam send me?") and replies can reference it.
            // Gated by the ambient-capture toggle + de-duped per notification to bound cost.
            val pic = note.picture
            if (pic != null && MemoryStore.recallEnabled(applicationContext) && firstTime("img|${note.key}")) {
                scope.launch {
                    try {
                        val b64 = com.agentos.shell.tools.ImageUtil.encodeBitmap(pic) ?: return@launch
                        val desc = AgentClient.askVision("In one short sentence, describe what this image shows.", listOf(b64), "")
                        if (!AgentClient.looksLikeError(desc) && desc.isNotBlank())
                            com.agentos.shell.tools.MessageStore.insertOne(applicationContext, contact, platform, contact, "them", "[image] $desc")
                    } catch (e: Exception) {}
                }
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

    /**
     * A human-feeling delay before sending: nobody types a paragraph in 2 seconds, and an instant
     * reply reads as a bot. Model "reading + typing" time from the reply length, with a little jitter,
     * bounded so it never feels broken. This is on TOP of the undo window.
     */
    private fun humanDelayMs(draft: String): Long {
        val chars = draft.trim().length
        // ~ read the incoming msg (2s) + type at a natural pace (~55 ms/char ≈ 220 wpm), + jitter.
        val typing = 2000L + (chars * 55L)
        val jitter = (0..4000).random().toLong()
        return (typing + jitter).coerceIn(4000L, 45_000L)
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
        val telegram = note.pkg.startsWith("org.telegram")
        val docMode = telegram && MemoryStore.docTelegram(applicationContext) &&
            com.agentos.shell.tools.KnowledgeStore.hasDoc(applicationContext)
        // Per-app automation level: off / draft / full. Telegram doc-answering always sends.
        var mode = MemoryStore.appMode(applicationContext, note.pkg)
        // Night schedule forces full-auto inside its window (unless the app is explicitly off).
        if (mode != "off" && MemoryStore.nightAuto(applicationContext)) {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (MemoryStore.inNightWindow(applicationContext, h)) mode = "full"
        }
        if (!docMode && mode == "off") return
        var autoSend = docMode || mode == "full"
        if (NotificationStore.pendingAuto.contains(note.key)) { Log.i("SlyOS", "auto skip: already pending ${note.title}"); return }
        // In draft mode, a staged draft already present means we've handled this message.
        if (!autoSend && NotificationStore.stagedDrafts.containsKey(note.key)) return
        if (NotificationStore.isOwnEcho(note)) { Log.i("SlyOS", "auto skip: own echo"); return }
        if (NotificationStore.repliedWithin(note, cooldownMs)) { Log.i("SlyOS", "auto skip: cooldown ${note.title}"); return }
        // Rate rail: only NOW (past the dedupe/echo/cooldown checks) do we count this as a real reply.
        // If this contact — or SlyOS overall — has hit the hourly auto-send cap, stage a draft instead
        // of sending, so a fast thread can't loop into a token-burning reply storm.
        if (autoSend && !com.agentos.shell.tools.AutoReplyGuard.allow(note.title)) {
            Log.i("SlyOS", "auto → draft: rate cap for ${note.title}")
            autoSend = false
        }
        NotificationStore.markReplied(note)
        if (autoSend) NotificationStore.pendingAuto.add(note.key)
        Log.i("SlyOS", "auto $mode for ${note.title}: \"${note.text}\"")
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
                if (!autoSend) {
                    // Half-automatic: stage the exact reply on the Now card; the user taps Send.
                    if (isSendable(draft)) {
                        NotificationStore.stageDraft(note.key, draft)
                        Log.i("SlyOS", "draft staged for ${note.title}: \"$draft\"")
                    }
                    return@launch
                }
                // Undo window PLUS a length-scaled human delay so replies never land creepily fast.
                delay(maxOf(undoWindowMs, humanDelayMs(draft)))
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
