package com.agentos.shell.tools

import android.content.Context

/**
 * The thing currently being worked on, so "make it shorter" edits it instead of starting over.
 *
 * History already existed — Home kept the last twelve exchanges and passed them in. What was
 * missing was an *anchor*: nothing marked "this draft is the subject", so a follow-up competed with
 * every other turn for the model's attention and usually lost. Asking for a LinkedIn post, then
 * saying "more casual", produced a fresh post written from scratch, and the way to get what you
 * wanted was to re-explain the whole brief every time.
 *
 * Deliberately a **subject, not a mode**. A mode you can get stuck in would be worse than the
 * problem: asking "what's on tomorrow?" mid-draft must answer normally and leave the draft alone.
 * Only an instruction that reads as an edit is routed to the draft.
 */
object WorkingDraft {

    private const val PREFS = "slyos_draft"

    data class Draft(val kind: String, val text: String, val brief: String, val at: Long) {
        /** "LinkedIn post", "Email to Carlos" — what the pinned card calls itself. */
        val label: String get() = kind
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): Draft? {
        val text = p(ctx).getString("text", "").orEmpty()
        if (text.isBlank()) return null
        return Draft(
            kind = p(ctx).getString("kind", "Draft").orEmpty(),
            text = text,
            brief = p(ctx).getString("brief", "").orEmpty(),
            at = p(ctx).getLong("at", 0L))
    }

    /** Pin something as the current subject. */
    fun set(ctx: Context, kind: String, text: String, brief: String) {
        if (text.isBlank()) return
        p(ctx).edit()
            .putString("kind", kind).putString("text", text).putString("brief", brief)
            .putLong("at", System.currentTimeMillis()).apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().clear().apply()

    /**
     * Whether this message is an edit to the pinned draft rather than a new request.
     *
     * Conservative on purpose. Treating a genuine new question as an edit is the failure that would
     * make this feel broken — the owner asks something unrelated and gets their post rewritten — so
     * anything that looks like its own request wins, and only short, clearly-referential
     * instructions are captured.
     */
    fun isEdit(prompt: String): Boolean {
        val p = prompt.trim().lowercase()
        if (p.length > 140) return false

        // Anything that is plainly its own request is never an edit, however it is phrased.
        val ownRequest = Regex("(?i)\\b(what'?s|what is|when|who|where|why|how much|do i|am i|" +
            "remind me|block|schedule|invite|email|send|call|open|search|find|show me|" +
            "add to|create a|make a|write a|draft a)\\b")
        if (ownRequest.containsMatchIn(p)) return false

        // Bare tone/length instructions — the commonest way people ask for a revision.
        val bare = Regex("^(make it |can you make it |now )?(much |a bit |a little |bit )?" +
            "(short(er)?|long(er)?|punchi(er|est)|casual|more casual|formal|more formal|" +
            "warm(er)?|friendl(y|ier)|funn(y|ier)|serious|simpler|clearer|tighter|softer|" +
            "bolder|less formal|less corporate|more direct|more personal)\\b")
        if (bare.containsMatchIn(p)) return true

        // Explicit references to the thing itself.
        val refers = Regex("(?i)\\b(make it|rewrite it|redo it|try again|another version|" +
            "change it|tweak it|fix it|adjust it|reword|rephrase|same but|instead of that|" +
            "take out|remove the|add a line|mention)\\b")
        return refers.containsMatchIn(p)
    }

    /** The instruction for a revision, given the draft and what the owner just said. */
    fun revisionPrompt(draft: Draft, instruction: String): String =
        "Here is the current ${draft.label.lowercase()}:\n\n${draft.text}\n\n" +
        "Revise it exactly per this instruction: \"$instruction\".\n" +
        "Keep everything that was not asked to change — this is an edit, not a rewrite from the " +
        "brief. Return ONLY the revised text, with no preamble and no explanation of what you changed."

    /** The quick edits offered as pills, in the order people actually reach for them. */
    val QUICK = listOf("Shorter", "Punchier", "Warmer", "Redo")
}
