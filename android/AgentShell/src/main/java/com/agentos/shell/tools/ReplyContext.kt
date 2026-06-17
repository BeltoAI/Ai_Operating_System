package com.agentos.shell.tools

import android.content.Context

/**
 * Builds the memory a reply should be grounded in: who you are, plus everything SlyOS knows about
 * THIS person — from other chats and (if enabled) what's been seen on screen. This is what makes
 * per-chat replies actually informed instead of generic.
 */
object ReplyContext {
    fun forSender(ctx: Context, app: String, title: String): String {
        val sb = StringBuilder()
        val about = MemoryStore.about(ctx)
        if (about.isNotBlank()) sb.append(about).append(" ")

        val name = title.ifBlank { app }
        if (name.isNotBlank()) {
            val thisKey = ConversationStore.sKey(app, title)

            // What this person has said in OTHER conversations (cross-chat memory).
            val elsewhere = ConversationStore.all(ctx)
                .filterKeys { it != thisKey && (it.endsWith("|$title") || it.contains(name, true)) }
                .flatMap { (_, msgs) -> msgs.takeLast(4).map { (if (it.role == "me") "you: " else "$name: ") + it.text } }
                .take(6)
            if (elsewhere.isNotEmpty())
                sb.append("\nWhat you know about $name from other chats: ").append(elsewhere.joinToString(" · "))

            // What's been seen on screen about this person.
            if (MemoryStore.recallEnabled(ctx)) {
                val rec = InteractionStore.search(ctx, name, 6).map { it.text }.take(5)
                if (rec.isNotEmpty())
                    sb.append("\nSeen on your screen re $name: ").append(rec.joinToString(" · "))
            }
        }
        return sb.toString().trim()
    }
}
