package com.agentos.shell.tools

import android.content.Context

/**
 * The eight things people actually write around a meeting, and the drafts for them.
 *
 * What was there before sent a one-line message with no preview: `event_followup` fired
 * "Running a little late for X — sorry" straight at the attendees. That is fine for a text to a
 * friend and unacceptable for anything with colleagues or clients on it, which is most of a
 * calendar. Nobody should discover what their assistant said on their behalf by asking the person
 * who received it.
 *
 * So every one of these produces a DRAFT — read, edited, regenerated, attached to — and sending is
 * a separate, deliberate act.
 *
 * The purposes are not invented. They are the messages a calendar actually generates: here is the
 * agenda, here is what to read first, are you coming, I am late, it has moved, it is cancelled,
 * here is what we decided, and everything else.
 */
object MailDraft {

    enum class Purpose(val label: String, val hint: String) {
        AGENDA("Agenda", "what we'll cover"),
        MATERIALS("Send materials", "something to read first"),
        CONFIRM("Confirm attendance", "chase anyone who hasn't replied"),
        LATE("Running late", "how long, and sorry"),
        MOVED("It's moved", "the new time, and why"),
        CANCELLED("It's cancelled", "and what happens instead"),
        NOTES("Notes afterwards", "decisions and who owes what"),
        CUSTOM("Something else", "you say what")
    }

    /**
     * The instruction that produces a draft worth sending.
     *
     * Everything specific goes in — who they are, what the meeting is, when it is, where, what is
     * attached, and how the two of them normally write. A model given "write a heads-up email"
     * produces something that reads like a template because that is all it was given.
     *
     * The constraints at the end are the difference between a draft and a first draft: no subject
     * line inside the body, no placeholder brackets, no invented facts. A draft containing
     * "[insert time here]" costs more to fix than to write.
     */
    fun prompt(
        ctx: Context,
        purpose: Purpose,
        eventTitle: String,
        whenText: String,
        where: String,
        recipients: List<String>,
        attachment: String,
        extra: String
    ): String = buildString {
        append("Write the body of an email from ")
        append(try { MemoryStore.ownerName(ctx).ifBlank { "the sender" } } catch (e: Exception) { "the sender" })
        append(" to ").append(recipients.joinToString(", ").ifBlank { "the attendees" }).append(".\n\n")

        append("It is about the meeting “").append(eventTitle.ifBlank { "the meeting" }).append("”")
        if (whenText.isNotBlank()) append(", ").append(whenText)
        if (where.isNotBlank()) append(", in ").append(where)
        append(".\n")

        append(when (purpose) {
            Purpose.AGENDA -> "Set out what the meeting will cover, so they can come prepared. " +
                "If an agenda is given below, use it; otherwise keep it to what the title implies."
            Purpose.MATERIALS -> "Send something to read beforehand and say briefly why it matters " +
                "and what you'd like them to think about."
            Purpose.CONFIRM -> "Ask them to confirm they can make it. Light, not a summons — one " +
                "or two lines."
            Purpose.LATE -> "Say you are running late, by how long, apologise once and without " +
                "grovelling, and tell them whether to start without you."
            Purpose.MOVED -> "Tell them the meeting has moved, give the new time plainly, " +
                "apologise briefly for the change and ask them to say if it no longer works."
            Purpose.CANCELLED -> "Tell them it is cancelled, apologise briefly, and say what " +
                "happens instead — rescheduling, or that it is no longer needed."
            Purpose.NOTES -> "Send what was decided and who owes what, as a short list. Only what " +
                "is given below — invent no decision and no owner."
            Purpose.CUSTOM -> "Write what is asked for below."
        })
        append("\n")

        if (attachment.isNotBlank())
            append("\nMention that “").append(attachment).append("” is attached, in one clause. ")
                .append("Do not describe its contents beyond what you are told.\n")
        if (extra.isNotBlank()) append("\nWhat the sender said to include: ").append(extra).append("\n")

        // How these two actually write to each other, when that is known.
        recipients.firstOrNull()?.let { r ->
            val who = r.substringBefore('@')
            val hist = try { PersonResolver.historyFor(ctx, who, 8) } catch (e: Exception) { "" }
            if (hist.isNotBlank())
                append("\nHow they normally write to each other — match this register, not a " +
                    "template's:\n").append(hist.take(1200)).append("\n")
        }
        try {
            val voice = MemoryStore.styleProfile(ctx)
            if (voice.isNotBlank()) append("\nThe sender's own voice: ").append(voice.take(500)).append("\n")
        } catch (e: Exception) {}

        append("\nRules: return ONLY the body. No subject line, no “Subject:”, no preamble about " +
            "what you wrote. Never leave a bracketed placeholder — if you do not know something, " +
            "write around it. Invent no time, no name and no fact that is not above. Professional " +
            "and warm; short enough to read on a phone.")
    }

    /** A subject line that says what it is, without the model being asked twice. */
    fun subject(purpose: Purpose, eventTitle: String): String {
        val t = eventTitle.ifBlank { "our meeting" }
        return when (purpose) {
            Purpose.AGENDA -> "Agenda — $t"
            Purpose.MATERIALS -> "Before $t"
            Purpose.CONFIRM -> "Are you able to make $t?"
            Purpose.LATE -> "Running late — $t"
            Purpose.MOVED -> "$t has moved"
            Purpose.CANCELLED -> "$t is cancelled"
            Purpose.NOTES -> "Notes from $t"
            Purpose.CUSTOM -> t
        }
    }

    /** The revisions people actually ask for, in the order they reach for them. */
    val TWEAKS = listOf("Shorter", "Warmer", "More formal", "More direct", "Redo")

    fun tweakPrompt(current: String, how: String): String =
        "Here is an email body:\n\n$current\n\n" +
        (if (how == "Redo") "Write it again, differently, same purpose and same facts."
         else "Rewrite it $how. Keep every fact and every name exactly as they are.") +
        "\nReturn ONLY the body — no subject, no preamble, no bracketed placeholders."

    /** What to tell a document generator, when the owner describes an attachment instead of picking one. */
    fun attachmentBrief(what: String, eventTitle: String, whenText: String): String =
        buildString {
            append(what)
            if (eventTitle.isNotBlank())
                append(". It is for the meeting “").append(eventTitle).append("”")
            if (whenText.isNotBlank()) append(", ").append(whenText)
            append(". Write it as a finished document someone else will read, not as notes.")
        }
}
