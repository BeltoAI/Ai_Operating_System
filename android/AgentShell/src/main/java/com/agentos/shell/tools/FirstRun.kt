package com.agentos.shell.tools

import android.content.Context

/**
 * The three things that have to happen before SlyOS is worth anything, and what changed since last
 * time.
 *
 * There was no onboarding at all. A new install opens on "what should happen?" with an empty brain
 * and no key — so the first honest answer it can give is that it cannot help, which is a poor way
 * to meet someone. And each of these steps is a *thing to do*, not a thing to read: the card opens
 * the picker, opens the key field, runs the prompt. A tour that only describes the app leaves
 * someone exactly where they started.
 */
object FirstRun {

    private const val PREFS = "slyos_firstrun"

    /** One step of the opening sequence. */
    data class Step(
        val id: String,
        val title: String,
        val why: String,
        /** What the button says — a verb, because the card does the thing. */
        val action: String
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * What is still missing, in the order that makes the next step possible.
     *
     * A key first: without a model nothing answers, so any other step would end in an error. Then
     * something to remember, because an assistant with no history is a chatbot. Only then is there
     * any point asking it something.
     */
    fun remaining(ctx: Context): List<Step> {
        val out = ArrayList<Step>()
        if (!AgentClient.hasKey()) {
            out.add(Step(
                "key",
                "Add a key",
                "Nothing can answer without one. Gemini and Groq are free.",
                "Add it"))
        }
        val brainSize = try { MessageStore.count(ctx) } catch (e: Exception) { 0 }
        if (brainSize < 50) {
            out.add(Step(
                "feed",
                "Give it something to remember",
                "A WhatsApp export or a PDF. This is the whole product — an assistant that knows you.",
                "Choose a file"))
        }
        if (brainSize >= 50 && !p(ctx).getBoolean("asked", false)) {
            out.add(Step(
                "ask",
                "Ask it about you",
                "Something only your own history could answer.",
                "Try one"))
        }
        return out
    }

    fun markAsked(ctx: Context) = p(ctx).edit().putBoolean("asked", true).apply()

    /** Dismissed for good — the row never returns. */
    fun dismiss(ctx: Context) = p(ctx).edit().putBoolean("hidden", true).apply()
    fun hidden(ctx: Context) = p(ctx).getBoolean("hidden", false)

    /**
     * A first question that will actually work, drawn from the brain rather than invented.
     *
     * A generic example ("ask me anything!") teaches nothing and often fails on a fresh brain. The
     * most-messaged person in someone's own history is guaranteed to have an answer behind it, so
     * the first thing SlyOS ever does is prove it knows them.
     */
    fun suggestedFirstQuestion(ctx: Context): String {
        val who = try {
            MessageStore.topContacts(ctx, 1).firstOrNull()?.first
        } catch (e: Exception) { null }
        return if (!who.isNullOrBlank()) "What do I usually talk to $who about?"
               else "What do you know about me?"
    }

    // MARK: - What's new

    /**
     * Shown once per version, then never again.
     *
     * Keyed on the version name rather than a flag, so shipping a new build is the only thing that
     * brings it back — there is no way to forget to reset it, and no way for it to reappear on a
     * build someone has already seen.
     */
    fun whatsNew(ctx: Context): List<String>? {
        val current = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) { return null }
        if (current.isBlank()) return null
        if (p(ctx).getString("seenVersion", "") == current) return null
        // A first install should meet the onboarding, not a changelog.
        if (p(ctx).getString("seenVersion", "").isNullOrBlank() && remaining(ctx).isNotEmpty()) {
            p(ctx).edit().putString("seenVersion", current).apply()
            return null
        }
        return NOTES.ifEmpty { null }
    }

    fun markNewSeen(ctx: Context) {
        val current = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) { "" }
        p(ctx).edit().putString("seenVersion", current).apply()
    }

    /**
     * What changed in THIS build, in the owner's terms.
     *
     * Written as what they can now do, not as what was repaired. "Invitations now reach people"
     * tells someone something; "fixed attendee serialisation in the confirmation card" does not.
     */
    /**
     * Written once, in [Walkthrough.WHATS_NEW]. Two lists of release notes drift, and the one that
     * drifts is always the one on screen.
     */
    private val NOTES get() = Walkthrough.WHATS_NEW
}
