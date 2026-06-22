package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConversationStore
import com.agentos.shell.tools.KnowledgeStore
import com.agentos.shell.tools.MemoryLog
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that turns a Telegram bot into the agent's interface: long-polls for
 * messages, reads attachments (images via vision, PDFs ingested as knowledge), answers from
 * the loaded document or generally, and replies — all through Telegram's open Bot API.
 */
class TelegramService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running && TelegramClient.configured()) {
            running = true
            startForeground(7, notif())
            scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; scope.cancel() }

    private suspend fun loop() {
        var offset = 0L
        while (running && scope.isActive) {
            val updates = TelegramClient.getUpdates(offset)
            for (u in updates) {
                offset = u.updateId + 1
                try { handle(u) } catch (e: Exception) { Log.e("SlyOS", "tg handle failed", e) }
            }
        }
    }

    private fun handle(u: TelegramClient.Update) {
        val mem = MemoryStore.about(applicationContext)
        when {
            // PDF document → ingest as the knowledge base.
            u.isPdf -> {
                val name = u.docName.ifBlank { "document.pdf" }
                val bytes = u.docFileId?.let { TelegramClient.downloadFile(it) }
                val chars = if (bytes != null) KnowledgeStore.loadFromBytes(applicationContext, bytes, name) else 0
                TelegramClient.sendMessage(u.chatId,
                    when {
                        chars > 0 -> "Got “$name” — read it ($chars chars). Ask me anything about it."
                        bytes == null -> "Couldn't download that file (it may be too big — Telegram bots can only fetch files up to 20 MB)."
                        else -> "Downloaded “$name” but couldn't extract text — it may be a scanned/image PDF."
                    })
            }
            // Photo → describe / answer with vision.
            u.photoFileId != null -> {
                val bytes = TelegramClient.downloadFile(u.photoFileId)
                val b64 = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                val ask = u.caption.ifBlank { "What's in this image?" }
                val ans = if (b64 != null) AgentClient.askVision(ask, listOf(b64), mem) else "I couldn't open that image."
                TelegramClient.sendMessage(u.chatId, ans)
            }
            // Text → natural reply, white paper only when the question is Belto/SlyOS tech.
            u.text.isNotBlank() -> {
                val chat = u.chatId.toString()
                val who = u.senderName.ifBlank { "Telegram $chat" }   // file under a real name in the brain
                ConversationStore.add(applicationContext, "Telegram", chat, "them", u.text)
                // Into the searchable brain (so Telegram chats show in the graph + Ask + reply context).
                com.agentos.shell.tools.MessageStore.insertOne(applicationContext, who, "Telegram", who, "them", u.text)
                val thread = ConversationStore.thread(applicationContext, "Telegram", chat).map { it.role to it.text }
                // If a document is loaded, pull the excerpts most relevant to THIS message (RAG —
                // not the whole file), so questions about a PDF someone sent get answered, while
                // casual chatter still stays casual (the reply prompt decides whether to use them).
                val doc = if (KnowledgeStore.hasDoc(applicationContext))
                    KnowledgeStore.retrieve(applicationContext, u.text, 6000) else ""
                // Pull EVERYTHING the brain knows about this person (cross-platform), not just your bio.
                val ctxMem = com.agentos.shell.tools.ReplyContext.forSender(applicationContext, "Telegram", who)
                val ans = AgentClient.telegramSmartReply(thread, doc, ctxMem.ifBlank { mem })
                TelegramClient.sendMessage(u.chatId, ans)
                ConversationStore.add(applicationContext, "Telegram", chat, "me", ans)
                com.agentos.shell.tools.MessageStore.insertOne(applicationContext, who, "Telegram", who, "me", ans)
                com.agentos.shell.tools.MetricsStore.record(applicationContext,
                    com.agentos.shell.tools.MetricsStore.secondsFor(if (doc.isNotBlank()) "doc_answer" else "reply"))
                MemoryLog.add(applicationContext, "response", "Telegram: ${u.text.take(30)}", ans, "Telegram bot")
            }
            u.voiceFileId != null -> TelegramClient.sendMessage(u.chatId,
                "I got a voice note — voice transcription isn't on yet, send it as text and I'll help.")
        }
    }

    private fun notif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("tg", "SlyOS bot", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "tg")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SlyOS bot is listening")
            .setContentText("Answering Telegram messages")
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, TelegramService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TelegramService::class.java)) }
    }
}
