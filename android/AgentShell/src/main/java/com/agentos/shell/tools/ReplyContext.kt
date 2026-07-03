package com.agentos.shell.tools

import android.content.Context

/**
 * Builds the memory a reply should be grounded in: who you are, plus everything SlyOS knows about
 * THIS person — from other chats and (if enabled) what's been seen on screen. This is what makes
 * per-chat replies actually informed instead of generic.
 */
object ReplyContext {
    fun forSender(ctx: Context, app: String, title: String, query: String = ""): String {
        val sb = StringBuilder()

        // Per-platform persona FIRST and loud: this is how YOU come across on THIS app (CEO on LinkedIn,
        // funny on IG, etc.). It must drive the voice of this reply, so it leads the context.
        val style = MemoryStore.styleFor(ctx, app)
        if (style.isNotBlank())
            sb.append("⚑ YOUR PERSONA ON $app — adopt this voice and register fully for THIS reply, it overrides " +
                "your general style: $style\n\n")

        val about = MemoryStore.fullProfile(ctx)   // About + facts the agent has learned on its own
        if (about.isNotBlank()) sb.append(about).append(" ")

        // Your real schedule — so the agent can answer "are you free Thursday?" instead of guessing.
        val cal = try { CalendarTool.upcoming(ctx) } catch (e: Exception) { "" }
        if (cal.isNotBlank())
            sb.append("\nYour upcoming calendar (use ONLY if they ask about timing/availability/meeting): ").append(cal).append(" ")

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
            // Try exact contact first; if the notification name doesn't exactly match how they're
            // stored (e.g. "Papa" vs "Dad Smith"), fall back to a name search so history still surfaces.
            var dbThread = MessageStore.threadFor(ctx, name, 16)
                .map { (if (it.role == "me") "you" else name) + ": " + it.body }
            if (dbThread.isEmpty())
                dbThread = MessageStore.search(ctx, name, 16)
                    .map { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
            if (dbThread.isNotEmpty())
                sb.append("\nYour history with $name: ").append(dbThread.joinToString(" · "))

            // TRUE RAG: semantic recall from the whole brain, matched by MEANING to what they just said —
            // surfaces relevant memories even when the words differ. This is what makes replies informed.
            val semQuery = query.ifBlank { name }
            if (semQuery.length > 2) {
                val sem = VectorStore.search(ctx, semQuery, 5)
                    .map { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
                    .filter { it.length in 4..400 }.take(5)
                if (sem.isNotEmpty())
                    sb.append("\nRelevant memories (semantic match — use if they help you reply): ").append(sem.joinToString(" · "))
            }

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
