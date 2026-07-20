package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ATTACHMENT RELAY for the Telegram bot — "here's a PDF, forward it to Sarah."
 *
 * Before this, a document sent to the bot was only ingested into the knowledge base; asking to pass it on
 * did nothing. Now every received file is CACHED, and a follow-up "send that to <someone>" resolves the
 * target and actually delivers it:
 *   • a Telegram person the bot has seen before (or the team group) → sendDocument
 *   • an email address, or a contact whose email we know → Gmail with the file attached
 * If the target can't be reached we say so plainly rather than pretending it went.
 */
object TgRelay {
    private const val TAG = "SlyOS-TgRelay"
    private const val PREFS = "slyos_tgrelay"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "tg_relay").apply { if (!exists()) mkdirs() }

    /** Remember who's who, so "send it to Sarah" can resolve to a real Telegram chat id. */
    fun rememberChat(ctx: Context, senderName: String, chatId: Long) {
        val n = senderName.trim(); if (n.length < 2 || chatId == 0L) return
        p(ctx).edit().putLong("chat_" + n.lowercase(), chatId).apply()
    }

    private fun chatFor(ctx: Context, name: String): Long {
        val n = name.trim().lowercase(); if (n.length < 2) return 0L
        p(ctx).getLong("chat_$n", 0L).let { if (it != 0L) return it }
        // loose match: "sarah" should find a stored "sarah chen"
        return p(ctx).all.entries.firstOrNull { (k, v) ->
            k.startsWith("chat_") && v is Long && run {
                val stored = k.removePrefix("chat_")
                stored.contains(n) || n.contains(stored)
            }
        }?.value as? Long ?: 0L
    }

    /** Cache the just-received file so a follow-up message can forward it. */
    fun remember(ctx: Context, chatId: Long, name: String, bytes: ByteArray) {
        try {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document.pdf" }
            val f = File(dir(ctx), "$chatId-$safe")
            f.writeBytes(bytes)
            p(ctx).edit().putString("last_$chatId", f.absolutePath).putString("lastname_$chatId", name).apply()
            // keep the cache small — only the few most recent files
            dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { try { it.delete() } catch (e: Exception) {} }
        } catch (e: Exception) { Log.w(TAG, "remember: ${e.message}") }
    }

    fun lastFile(ctx: Context, chatId: Long): File? =
        p(ctx).getString("last_$chatId", null)?.let { File(it) }?.takeIf { it.exists() }

    fun lastName(ctx: Context, chatId: Long): String = p(ctx).getString("lastname_$chatId", "") ?: ""

    private val FORWARD = Regex(
        "(?i)\\b(forward|send|pass|share|relay|shoot|fwd)\\b[^.]{0,30}?\\b(it|that|this|the (pdf|file|doc|document|attachment))\\b[^.]{0,20}?\\b(to|over to|along to)\\b\\s+(.+)")
    private val FORWARD_ALT = Regex(
        "(?i)\\b(forward|send|pass|share|relay|fwd)\\b\\s+(to)\\s+(.+)")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Look a person up in the local CRM by name and return their email, or "" if we don't know one. */
    private fun emailForName(ctx: Context, name: String): String {
        val n = name.trim().lowercase(); if (n.length < 2) return ""
        return try {
            LeadStore.all(ctx).firstOrNull {
                val ln = it.name.trim().lowercase()
                it.email.contains("@") && ln.isNotBlank() && (ln == n || ln.contains(n) || n.contains(ln))
            }?.email.orEmpty()
        } catch (e: Exception) { "" }
    }

    /**
     * If [text] is a request to forward the last received file, do it and return a reply.
     * Returns null when this isn't a forward request (caller continues its normal flow).
     */
    fun handle(ctx: Context, chatId: Long, text: String): String? {
        val m = FORWARD.find(text) ?: FORWARD_ALT.find(text) ?: return null
        val rawTarget = m.groupValues.last().trim().trim('.', '!', '?', ',')
        if (rawTarget.isBlank()) return null

        val file = lastFile(ctx, chatId)
            ?: return "I don't have a file from you to forward — send me the document first, then tell me who to send it to."
        val name = lastName(ctx, chatId).ifBlank { file.name }

        // 1) explicit email address, or a saved contact's email → Gmail with the attachment
        val email = EMAIL.find(rawTarget)?.value ?: emailForName(ctx, rawTarget)
        if (!email.isNullOrBlank()) {
            if (!GoogleAuth.isConnected(ctx))
                return "I can reach $rawTarget by email, but Google isn't connected yet — connect it in SlyOS → Settings and I'll send it."
            val (ok, err) = try {
                GmailClient.sendWithAttachments(ctx, email, "Forwarded: $name", "Sharing this with you.", listOf(file))
            } catch (e: Exception) { false to (e.message ?: "error") }
            return if (ok) {
                try { MessageStore.insertOne(ctx, rawTarget, "Telegram", "me", "me", "Forwarded “$name” to $email") } catch (e: Exception) {}
                "Sent “$name” to $email ✓"
            } else "Couldn't email it to $email — $err"
        }

        // 2) a Telegram person the bot has seen, or the team group
        val target = if (Regex("(?i)\\b(the )?(team|group|everyone)\\b").containsMatchIn(rawTarget))
            try { TeamChat.groupId(ctx) } catch (e: Exception) { 0L } else chatFor(ctx, rawTarget)
        if (target != 0L) {
            val ok = try { TelegramClient.sendDocument(target, file, "Forwarded: $name") } catch (e: Exception) { false }
            return if (ok) {
                try { MessageStore.insertOne(ctx, rawTarget, "Telegram", "me", "me", "Forwarded “$name” to $rawTarget") } catch (e: Exception) {}
                "Sent “$name” to $rawTarget ✓"
            } else "Couldn't deliver “$name” to $rawTarget on Telegram."
        }

        return "I have “$name” ready, but I can't reach “$rawTarget” — I can only forward to someone who has messaged this bot before, the team group, or an email address. Give me their email and I'll send it."
    }
}
