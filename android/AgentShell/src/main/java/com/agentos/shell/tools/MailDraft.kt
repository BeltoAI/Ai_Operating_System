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
            // An "agenda" email that says "looking forward to it" is not an agenda. If one is
            // given, use it; if not, PROPOSE one — that is the whole reason someone taps Agenda
            // rather than writing two lines themselves. "Keep it to what the title implies" was an
            // instruction to produce nothing, and the model duly produced a heads-up.
            Purpose.AGENDA -> "Set out what the meeting will cover, so they can come prepared. " +
                "If an agenda is given below, use that. If none is given, PROPOSE one: three to " +
                "five concrete numbered items drawn from the meeting's title, who is attending and " +
                "whatever context you are given below, each with a rough number of minutes, ending " +
                "with next steps. Then invite them to add anything missing. Never send this " +
                "without an actual list of items in it."
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

        // THE ATTACHMENT THAT DOES NOT EXIST.
        //
        // Observed: a draft with nothing attached that read "I've attached a draft of the current
        // project snapshot for your reference." Saying nothing about attachments does not stop a
        // model inventing one — it fills the shape an agenda email usually has. The absence has to
        // be stated as plainly as the presence, because this is the failure mode that makes the
        // sender look careless to someone who then goes looking for the file.
        if (attachment.isNotBlank())
            append("\nMention that “").append(attachment).append("” is attached, in one clause. ")
                .append("Do not describe its contents beyond what you are told.\n")
        else
            append("\nNOTHING IS ATTACHED to this email. Do not write “attached”, “enclosed”, " +
                "“please find”, or refer to any document, file or link going with it.\n")
        if (extra.isNotBlank()) append("\nWhat the sender said to include: ").append(extra).append("\n")

        // WHAT THIS MEETING IS ACTUALLY ABOUT.
        //
        // An agenda proposed from a title alone is a guess dressed as preparation. Everything the
        // brain holds on this subject — the thread that led to it, the last one of these, what was
        // left unfinished — is what makes the difference between "1. Discussion 2. Next steps" and
        // an agenda worth sending.
        try {
            val recall = BrainContext.build(ctx, "$eventTitle $extra").take(1200)
            if (recall.isNotBlank())
                append("\nWhat is already known about this meeting and its subject — use it for " +
                    "the substance, and state nothing it does not support:\n")
                    .append(recall).append("\n")
        } catch (e: Exception) {}

        // WHO IS WRITING. From Settings — role, company, the details on their signature.
        //
        // This was missing entirely, which is why drafts read like they came from nobody in
        // particular. The one block describing the sender as an actual professional was the one
        // block the email prompt never received.
        try {
            val me = MemoryStore.fullProfile(ctx)
            if (me.isNotBlank())
                append("\nWho is writing, from their own profile — get their role and their " +
                    "sign-off right:\n").append(me.take(900)).append("\n")
        } catch (e: Exception) {}

        // PROFESSIONAL BY DEFAULT.
        //
        // Prior drafts opened "hey! just a heads up for wed's smooch" — because the only voice
        // signal was the SMS history with that person, and it was fed in as "match this register".
        // The register of a text message is not the register of an email; someone you send "yo" to
        // at midnight still gets a proper note about a Thursday meeting, and the reverse is
        // embarrassing in front of everyone else on the thread. So the history is used for what it
        // is actually evidence of — how close these two are, what they call each other, what
        // shorthand they share — and never for how formal to be.
        // EVERY person on it, not just the first.
        //
        // An email to four people that only knows one of them proposes things the other three have
        // already settled, or re-explains what they told you last week. The thread with each of
        // them is the difference between a note that lands and one that reads as though nobody
        // remembered the last conversation.
        val perPerson = recipients.take(4).mapNotNull { r ->
            val who = r.substringBefore('@')
            val hist = try { PersonResolver.historyFor(ctx, who, 6) } catch (e: Exception) { "" }
            if (hist.isBlank()) null else "— $who —\n${hist.take(900)}"
        }
        if (perPerson.isNotEmpty())
            append("\nWhat has already been said to each of them, and what was left open. Use it " +
                "for FACTS and for how familiar to be, NOT for how formal to be — do not copy its " +
                "texting style, greetings, abbreviations or punctuation:\n")
                .append(perPerson.joinToString("\n\n")).append("\n")

        // AND WHAT HAPPENED IN THE ROOM.
        //
        // Meetings are recorded, summarised and filed, and none of it reached the one place it is
        // most obviously useful: the email written straight after or straight before the next one.
        // An agenda that picks up the four things left unresolved last time is the whole reason to
        // have kept the notes.
        try {
            val past = MeetingStore.all(ctx).filterNot { it.running }
                .filter { m ->
                    m.summary.isNotBlank() && (
                        eventTitle.isNotBlank() && m.title.contains(eventTitle, true) ||
                        recipients.any { r -> m.transcript().contains(r.substringBefore('@'), true) })
                }
                .sortedByDescending { it.startedAt }.take(2)
            if (past.isNotEmpty()) {
                append("\nWhat was actually said when these people last met — pick up the threads " +
                    "left open rather than starting from nothing:\n")
                past.forEach { m ->
                    append("— ").append(m.title).append(" (")
                        .append(java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                            .format(java.util.Date(m.startedAt)))
                        .append("):\n").append(m.summary.take(700)).append("\n")
                }
            }
        } catch (e: Exception) {}
        // THE PERSONA THEY SET FOR EMAIL, IN SETTINGS.
        //
        // Settings has a persona per platform — email, LinkedIn, Instagram, SMS — precisely so the
        // same person can be one thing on one and another elsewhere. Every other surface honoured
        // it. This one, the most formal of them all, never read it, so the email persona was a
        // setting that changed nothing. It outranks the learned voice, because it is what the owner
        // has actually asked for rather than what they were observed doing.
        val persona = try { MemoryStore.styleFor(ctx, "gmail") } catch (e: Exception) { "" }
        if (persona.isNotBlank())
            append("\nThe voice the sender has CHOSEN for email — this outranks anything inferred " +
                "from their past messages: ").append(persona.take(400)).append("\n")

        try {
            val voice = MemoryStore.styleProfile(ctx)
            if (voice.isNotBlank())
                append("\nThe sender's own habits of phrasing, to draw on only where they do not " +
                    "conflict with the above: ").append(voice.take(500)).append("\n")
        } catch (e: Exception) {}

        append("\nRules: return ONLY the body. No subject line, no “Subject:”, no preamble about " +
            "what you wrote. Never leave a bracketed placeholder — if you do not know something, " +
            "write around it. Invent no time, no name and no fact that is not above.\n" +
            "\nRegister: a PROFESSIONAL business email, always. Proper greeting with their name " +
            "and a proper sign-off with the sender's. Full sentences, real capitalisation and real " +
            "punctuation. No “hey!”, no “yo”, no lowercase openings, no texting abbreviations, no " +
            "emoji, no exclamation marks beyond at most one. Warm is good; casual is not — this " +
            "may be forwarded to people the sender has never met. Short enough to read on a phone.\n" +
            // Gmail sends this as plain text. Markdown does not render — it arrives as literal
            // asterisks around every heading the model thought it was emboldening.
            "\nPlain text only. NO markdown whatsoever: no **bold**, no *italics*, no backticks, " +
            "no # headings. Emphasis comes from word order and short paragraphs. Numbered lists as " +
            "“1.” at the start of a line, bullets as “- ”, nothing else.")

        // TODAY, STATED.
        //
        // Without it a draft asked for a reply "by Monday the 27th" when the 27th was two days
        // gone and the meeting was that afternoon — a model with no clock will reason about dates
        // from the training data it happens to hold. Anything it decides about "by when" has to be
        // anchored to a real date it has been told.
        append("\n\nToday is ").append(
            java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date()))
            .append(". ")
            // Told the date twice and it still asked for a reply "by Monday 27 July" when the 27th
            // was two days gone. Date arithmetic is not something to keep re-asking for and hoping;
            // removing the whole class is what actually holds. A deadline nobody asked for adds
            // nothing to any of these emails anyway.
            .append("Do NOT invent a deadline. Never write “by Monday”, “by EOD”, “by the 27th” " +
                "or any other date for their reply unless one is given to you above. Ask them to " +
                "reply with no date attached.")

        // THE SIGN-OFF, WITH WHAT SETTINGS ACTUALLY HOLDS.
        //
        // The booking link is set once in Settings and belongs on the bottom of exactly this kind
        // of email — "let me know a time that works" is worth far less than a link that takes one.
        val sig = buildString {
            val n = try { MemoryStore.ownerName(ctx) } catch (e: Exception) { "" }
            if (n.isNotBlank()) append(n)
            val role = try { MemoryStore.personal(ctx, "occupation") } catch (e: Exception) { "" }
            if (role.isNotBlank()) append("\n").append(role)
            val book = try { MemoryStore.effectiveBookingLink(ctx) } catch (e: Exception) { "" }
            if (book.isNotBlank()) append("\nBook a time: ").append(book)
        }
        if (sig.isNotBlank())
            append("\n\nEnd with a sign-off (\"Best regards,\" or similar) followed by exactly " +
                "this block, verbatim, on its own lines — nothing added and nothing dropped:\n")
                .append(sig)
    }

    /**
     * Markdown out, because the email goes as plain text.
     *
     * The prompt asks for no markdown and mostly gets it, and "mostly" is not good enough for
     * something that leaves under the owner's name — one stray `**Next steps**` arrives as literal
     * asterisks in front of a client. Asking a model twice is not a fix; removing it is.
     */
    fun plain(body: String): String = body
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")          // # headings
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")               // **bold**
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")  // *italics*
        .replace(Regex("(?<![\\w_])__(.+?)__(?![\\w_])"), "$1")  // __bold__
        .replace(Regex("`([^`]+)`"), "$1")                        // `code`
        .replace(Regex("(?m)^\\s{0,3}[*+]\\s+"), "- ")           // * bullets → -
        .trim()

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

    /**
     * The revisions people actually ask for, in the order they reach for them.
     *
     * One word each, so all five fit on the row rather than the last two sitting off the edge of a
     * scroller nobody scrolls. The label is what fits; the instruction is what the model is told.
     */
    val TWEAKS = listOf(
        "Shorter" to "more concise",
        "Warmer" to "warmer and friendlier",
        "Formal" to "more formal",
        "Direct" to "more direct and to the point",
        "Again" to "")

    fun tweakPrompt(current: String, how: String): String {
        val instruction = TWEAKS.firstOrNull { it.first == how }?.second.orEmpty()
        return "Here is an email body:\n\n$current\n\n" +
            (if (instruction.isBlank())
                "Write it again, differently, same purpose and same facts."
             else "Rewrite it $instruction. Keep every fact and every name exactly as they are.") +
            // "Warmer" must not be a licence to go casual — warmth and register are different axes,
            // and a revision that quietly drops the professional floor is worse than no revision.
            "\nIt stays a professional business email whatever the instruction: proper greeting and " +
            "sign-off, full sentences, no texting style and no emoji." +
            "\nReturn ONLY the body — no subject, no preamble, no bracketed placeholders."
    }

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
