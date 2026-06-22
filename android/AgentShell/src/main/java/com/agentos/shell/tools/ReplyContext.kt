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

        // Your real schedule — so the agent can answer "are you free Thursday?" instead of guessing.
        val cal = try { CalendarTool.upcoming(ctx) } catch (e: Exception) { "" }
        if (cal.isNotBlank())
            sb.append("\nYour upcoming calendar (use ONLY if they ask about timing/availability/meeting): ").append(cal).append(" ")

        // Per-platform persona: how you want to come across on THIS app (e.g. CEO on LinkedIn, funny on IG).
        val style = MemoryStore.styleFor(ctx, app)
        if (style.isNotBlank())
            sb.append("\nYour persona/tone on $app (adopt it): $style ")

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

            // Deep history for this person straight from the message DB (imported + live).
            val dbThread = MessageStore.threadFor(ctx, name, 16)
                .map { (if (it.role == "me") "you" else name) + ": " + it.body }
            if (dbThread.isNotEmpty())
                sb.append("\nYour history with $name: ").append(dbThread.joinToString(" · "))

            // The REAL chat history we otherwise can't see: the conversation currently/recently on
            // screen in this app (captured by Total Recall). This is the biggest context win.
            if (MemoryStore.recallEnabled(ctx)) {
                val live = InteractionStore.recentForApp(ctx, app, 12).map { it.text }
                if (live.isNotEmpty())
                    sb.append("\nThe recent on-screen conversation in $app (use this as the live thread): ")
                        .append(live.joinToString(" · "))
                val rec = InteractionStore.search(ctx, name, 6).map { it.text }.take(5)
                if (rec.isNotEmpty())
                    sb.append("\nOther things seen on screen about $name: ").append(rec.joinToString(" · "))
            }
        }
        // Your OWN research: if what you're discussing relates to a paper you've written, pull the
        // relevant excerpts so replies can speak to your work (outward flow of papers into the brain).
        val topic = sb.toString().takeLast(1200)
        if (topic.length > 20) {
            val papers = PaperStore.libraryContext(ctx, 0L, topic, 1400)
            if (papers.isNotBlank())
                sb.append("\nFrom your own research papers (cite/use ONLY if relevant): ").append(papers)
        }
        return sb.toString().trim()
    }
}
