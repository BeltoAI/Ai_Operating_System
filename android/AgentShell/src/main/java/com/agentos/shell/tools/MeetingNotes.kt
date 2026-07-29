package com.agentos.shell.tools

import android.content.Context
import android.net.Uri

/**
 * What a recording becomes: a summary, decisions, who owes what — and the parts of that which are
 * yours, on your list.
 *
 * The summarising rules here were learned the hard way. Handed ten words after a long instruction,
 * the model replied *"you didn't actually paste the transcript"* — a fair reading of what it was
 * given — and that apology was stored as the meeting summary. So the transcript is fenced in
 * markers, the model is told plainly that whatever is between them IS the transcript however rough,
 * and anything too short to summarise is kept verbatim instead of being summarised into an excuse.
 */
object MeetingNotes {

    /** Below this there is nothing to summarise, and pretending otherwise produces the apology. */
    private const val MIN_CHARS = 220

    data class Result(val summary: String, val verbatim: Boolean, val tasksAdded: Int)

    /**
     * Summarise, store, and turn the owner's own commitments into tasks.
     *
     * The transcript is written to the brain FIRST and unconditionally: a summary needs a model and
     * a model can fail, but what was actually said has to survive that. Verbatim is also what makes
     * "what were his exact words" answerable months later, which no summary can do.
     */
    fun make(ctx: Context, m: MeetingStore.Meeting): Result {
        val body = m.transcript().ifBlank { m.plain() }

        // NOTHING WAS HEARD — say that, rather than presenting an empty record as a result.
        //
        // Observed: a 33-second recording in a quiet room produced "Kept it:" followed by nothing,
        // which reads as a summary that came out blank rather than as a microphone that heard
        // nothing. The two need entirely different responses from the person holding the phone.
        if (body.isBlank()) {
            val none = "I didn't hear anything — check the microphone isn't covered, and that " +
                "SlyOS has permission to use it."
            try { MeetingStore.setSummary(ctx, m.id, none) } catch (e: Exception) {}
            return Result(none, verbatim = false, tasksAdded = 0)
        }

        try {
            Brain.remember(ctx, "meeting", m.title,
                "Transcript of ${m.title} (${MeetingStore.clock(m.durationMs)}):\n\n$body",
                actors = m.names.values.toList())
        } catch (e: Exception) {}

        if (body.length < MIN_CHARS) {
            val kept = "Kept it:\n\n" + m.plain()
            try { MeetingStore.setSummary(ctx, m.id, kept) } catch (e: Exception) {}
            return Result(kept, verbatim = true, tasksAdded = 0)
        }

        val summary = try {
            AgentClient.answerWell(
                "Below, between the markers, is a transcript of a meeting the owner was part of. " +
                "It comes from live speech recognition, so the speaker labels are guesses and the " +
                "words contain mistakes — read through them rather than quoting them as fact. " +
                "However short or rough it is, it IS the transcript: never reply asking for it.\n\n" +
                (if (m.me >= 0)
                    "The owner of this phone is the speaker labelled \"You\". Write THEIR actions " +
                    "starting with \"You: \" and everyone else's with their label.\n\n"
                 else "") +
                "Write, and nothing else: two or three sentences on what it was about; then " +
                "DECISIONS (omit the heading if none); then ACTIONS with who owes what and by when " +
                "if a time was said (omit if none); then OPEN questions (omit if none). Do not " +
                "invent a decision, an action or a deadline that is not there.\n\n" +
                "---TRANSCRIPT BEGINS---\n" + body.take(20000) + "\n---TRANSCRIPT ENDS---",
                "", emptyList())
        } catch (e: Exception) { "" }

        if (summary.isBlank() || AgentClient.looksLikeError(summary)) {
            return Result("Kept the transcript — couldn't summarise it just now.", verbatim = false, tasksAdded = 0)
        }

        try { MeetingStore.setSummary(ctx, m.id, summary) } catch (e: Exception) {}
        try {
            Brain.remember(ctx, "meeting", "${m.title} — summary", summary, actors = m.names.values.toList())
        } catch (e: Exception) {}

        // YOUR commitments become tasks; other people's are listed and left alone. Putting someone
        // else's promise on your list is how a checklist stops being trusted.
        //
        // AND THIS NEEDS TO KNOW WHICH VOICE IS YOURS. Measured on a real summary: every action came
        // back as "Speaker 2: put together the one-pager and send it to Carlos by Thursday", which
        // matches no pronoun at all — so nothing reached the checklist and the feature looked as
        // though it simply did not work. Until a speaker is marked as the owner there is no honest
        // way to tell their commitments from anyone else's, so nothing is added and the caller says
        // what is missing.
        var added = 0
        if (m.me < 0) return Result(summary, verbatim = false, tasksAdded = -1)
        try {
            summary.lineSequence()
                .map { it.trim().trimStart('·', '-', '*', ' ') }
                .filter { l ->
                    l.length in 9..160 &&
                        Regex("(?i)^you\\b|\\b(i'?ll|i will|we'?ll|my )\\b").containsMatchIn(l)
                }
                .map { it.replace(Regex("(?i)^you\\s*:\\s*"), "").trim() }
                .take(6)
                .forEach {
                    ChecklistStore.add(ctx, it)
                    // Marked as a commitment, not as something done — a line in the brain saying
                    // "send Carlos the numbers" reads at recall time exactly like a receipt.
                    try {
                        Brain.remember(ctx, "meeting_action", m.title,
                            "COMMITTED, not yet done — from ${m.title}: $it")
                    } catch (e: Exception) {}
                    added++
                }
        } catch (e: Exception) {}

        return Result(summary, verbatim = false, tasksAdded = added)
    }

    /**
     * The whole record as a PDF: what it was, who was there, what was decided, then every word.
     *
     * The transcript goes last because it is the reference, not the read.
     */
    fun exportPdf(ctx: Context, m: MeetingStore.Meeting): Uri? {
        val when_ = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(m.startedAt))
        val who = m.names.values.filter { it.isNotBlank() }
        val text = buildString {
            append(m.title).append("\n")
            append(when_).append("   ·   ").append(MeetingStore.clock(m.durationMs)).append("\n")
            if (who.isNotEmpty()) append("Present: ").append(who.joinToString(", ")).append("\n")
            append("\n")
            if (m.summary.isNotBlank()) append(m.summary).append("\n\n")
            append("TRANSCRIPT\n\n")
            append(m.transcript())
        }
        return try { PdfBuilder.makePdf(ctx, m.title, text) } catch (e: Exception) { null }
    }
}
