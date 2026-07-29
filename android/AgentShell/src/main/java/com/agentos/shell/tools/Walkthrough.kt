package com.agentos.shell.tools

import android.content.Context

/**
 * A guided pass through the things SlyOS does, where each step *does the thing*.
 *
 * [FirstRun] gets someone to a working state — a key, something in the brain, one question answered.
 * That is setup, and it stops the moment the app can function. What it never did was show anyone
 * what the app is FOR: a feature that took a week to build is worth nothing to someone who never
 * learns it exists, and this app has no icons on a grid to browse.
 *
 * Two rules, both learned from the setup cards:
 *
 *  - **Never modal.** A tour that traps you is a tax on people who already know the app. This is a
 *    strip you can dismiss forever with one tap, and it never blocks anything.
 *  - **Every step is a verb.** "SlyOS can record meetings" teaches nothing. Tapping a step opens the
 *    recorder, or the health page, or fills the prompt with a question drawn from the owner's own
 *    history so the answer is theirs rather than a demo.
 *
 * Steps that need something absent are skipped rather than shown greyed out — offering a health tour
 * to someone with no wearable is an advert, not a walkthrough.
 */
object Walkthrough {

    private const val PREFS = "slyos_walkthrough"

    /**
     * @param action what the host screen should do — matched to a `when` there rather than holding a
     *   lambda, so a step is data and can be persisted and reordered.
     * @param arg    a prompt to run, where the step is a question.
     */
    data class Step(
        val id: String,
        val title: String,
        val why: String,
        val cta: String,
        val action: String,
        val arg: String = ""
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun done(ctx: Context, id: String) = p(ctx).edit().putBoolean("done_$id", true).apply()
    fun isDone(ctx: Context, id: String) = p(ctx).getBoolean("done_$id", false)
    fun dismiss(ctx: Context) = p(ctx).edit().putBoolean("hidden", true).apply()
    fun hidden(ctx: Context) = p(ctx).getBoolean("hidden", false)

    /**
     * The next thing worth showing, or null when there is nothing left.
     *
     * One at a time. A list of eight is a chore; one card with a verb on it is an invitation, and
     * the next appears only once this one is done — so the walkthrough is paced by the person rather
     * than by the app.
     */
    fun next(ctx: Context): Step? {
        if (hidden(ctx)) return null
        return all(ctx).firstOrNull { !isDone(ctx, it.id) }
    }

    fun remaining(ctx: Context): Int = all(ctx).count { !isDone(ctx, it.id) }

    /**
     * Everything worth teaching, in the order it becomes useful.
     *
     * Deliberately not a feature list. Each entry is a moment someone would actually want — "ask it
     * something only you would know" before "here is a portfolio screen" — because the first
     * establishes that the app is about them, and nothing else lands until that has.
     */
    fun all(ctx: Context): List<Step> {
        val out = ArrayList<Step>()

        // 1. Prove it knows them. Drawn from their own history so the answer cannot be generic.
        val who = try { MessageStore.topContacts(ctx, 1).firstOrNull()?.first } catch (e: Exception) { null }
        out.add(Step("ask_brain", "Ask it something only you would know",
            "It has read your messages. This is the difference between SlyOS and a chatbot.",
            "Try it", "prompt",
            if (!who.isNullOrBlank()) "What do I usually talk to $who about?" else "What do you know about me?"))

        // 2. Hold to talk. The single most-used control, and the least discoverable.
        out.add(Step("hold_talk", "Hold the dot and talk",
            "Pause mid-sentence as long as you like — it waits for you to let go. Nothing is sent " +
            "until you do.", "Got it", "none"))

        // 3. A document out of a sentence.
        out.add(Step("make_doc", "Turn a sentence into a document",
            "Ask for a one-pager and you get a real file — then say who to send it to and it goes.",
            "Try it", "prompt", "make a one-page summary of what SlyOS does"))

        // 4. Meetings, which is the feature people are most surprised by.
        out.add(Step("meeting", "Record a meeting",
            "It keeps recording with the screen off, separates who said what, and puts your own " +
            "commitments on your list.", "Open it", "meetings"))

        // 5. Feed it something. The PDF path, which is the one you actually asked for.
        val brainSize = try { MessageStore.count(ctx) } catch (e: Exception) { 0 }
        out.add(Step("feed_pdf", "Give it a document to read",
            if (brainSize < 200) "A PDF, a contract, an export — it reads the whole thing and you " +
                "can ask about it afterwards."
            else "Any PDF or export. It reads the whole thing, and it stays askable.",
            "Choose a file", "pdf"))

        // 6. Health, but only if there is any.
        if (try { VitalsStore.present(ctx).isNotEmpty() } catch (e: Exception) { false }) {
            out.add(Step("health", "Your body, against your own baseline",
                "Never a population range — every number is compared to your own history.",
                "Open it", "health"))
        }

        // 7. The team, once there is one.
        if (try { EmployeeStore.all(ctx).isNotEmpty() } catch (e: Exception) { false }) {
            out.add(Step("team", "Talk to one of your agents",
                "Each keeps its own thread, so \"make it warmer\" changes the draft instead of " +
                "starting a new one.", "Open Team", "team"))
        }

        // 8. Type-ahead, last, because it is only useful once there is somewhere to go.
        out.add(Step("places", "Type two letters to go anywhere",
            "\"hea\" finds Health, \"port\" finds Portfolio. There is no app grid — this is it.",
            "Got it", "none"))

        return out
    }

    /**
     * What changed in THIS build, in the owner's terms.
     *
     * Written as what they can now do, never as what was repaired: "your whole Whoop history, in one
     * file" tells someone something; "fixed UTC day bucketing in the rollup" does not.
     */
    val WHATS_NEW = listOf(
        "Health, properly. Every metric has its own page — the chart, your own averages and median, " +
            "where it's heading, which days differ, and somewhere to ask.",
        "Bring your whole Whoop history in from one export file. It carries HRV, recovery and strain, " +
            "which the live connection can't send at all.",
        "Type two letters to go anywhere: \"hea\" for Health, \"port\" for Portfolio.",
        "Live translation. Lay the phone flat between two people — each half has its own hold-to-talk, " +
            "and the far side is upside down so they can read it.",
        "See which model is actually better on your own brain: Claude, Groq and Gemini, measured."
    )
}
