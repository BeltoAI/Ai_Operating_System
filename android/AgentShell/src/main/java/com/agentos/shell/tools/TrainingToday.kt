package com.agentos.shell.tools

import android.content.Context

/**
 * What you did today, on the screen you actually look at.
 *
 * Health data lived on the Health page and nowhere else, so a workout finished at three o'clock was
 * invisible until someone went looking for it. The point of a home screen is that you do not have to
 * go looking.
 *
 * Two things are deliberately NOT here, because neither is possible and both were asked for:
 *
 *  - **Ending a workout.** Whoop exposes no API or intent to stop a session from outside its own app.
 *    A swipe that appeared to end one would be a lie about the most consequential thing on the card.
 *    Swipe-left dismisses the CARD, and says so.
 *  - **Starting a named activity by prompt.** There is no deep link for activity type. The honest
 *    answer to "start a run" is that Whoop opens and you start it there.
 *
 * And one thing is uncertain rather than assumed: whether a session appears WHILE it is in progress.
 * Every session observed on the device carried both a start and an end, which is what a completed
 * write looks like. So this reports what has finished — "trained today" — rather than claiming to be
 * live. If a mid-session read is ever proven to work, the same card can say "training now"; until
 * then it does not pretend to.
 */
object TrainingToday {

    data class Session(val minutes: Int, val startMs: Long)

    data class Day(
        val sessions: List<Session>,
        val totalMinutes: Int,
        /**
         * NOT heart rate.
         *
         * The card was going to show "avg 128 bpm". The store holds no `hr` metric at all — only
         * `rhr`, resting heart rate — so that number would have been either a silent zero or, far
         * worse, a resting figure labelled as a workout average. Calories is what is actually
         * recorded alongside a session, so calories is what it says.
         */
        val calories: Int,
        val steps: Int
    ) {
        val trained: Boolean get() = sessions.isNotEmpty()
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Today's training, from what has actually synced.
     *
     * Sessions are filtered on their START rather than their end, so a workout begun at 23:50 belongs
     * to the day it began on — the same rule a person would use, and the opposite of what a naive
     * end-time filter does at midnight.
     */
    fun today(ctx: Context): Day = try {
        val from = startOfToday()
        // Samples, not the daily roll-up: two workouts must read as two, and the roll-up has
        // already added them together.
        val sessions = VitalsStore.samplesSince(ctx, "exercise", from)
            .map { Session(it.value.toInt(), it.start) }
            .filter { it.minutes >= 3 }          // a three-minute "session" is a phone twitch
            .sortedBy { it.startMs }
        val kcal = VitalsStore.samplesSince(ctx, "calories", from).sumOf { it.value }.toInt()
        val steps = VitalsStore.samplesSince(ctx, "steps", from).sumOf { it.value }.toInt()
        Day(sessions, sessions.sumOf { it.minutes }, kcal, steps)
    } catch (e: Exception) { Day(emptyList(), 0, 0, 0) }

    /** The headline. Two sessions read as two, because that is what happened. */
    fun line(d: Day): String {
        if (!d.trained) return if (d.steps > 0) "${"%,d".format(d.steps)} steps today" else ""
        val parts = d.sessions.joinToString(" + ") { "${it.minutes} min" }
        return "Trained today — $parts"
    }

    /** The quieter second line — only what was actually recorded. */
    fun detail(d: Day): String = buildString {
        if (d.totalMinutes > 0) append("${d.totalMinutes} min total")
        if (d.calories > 0) {
            if (isNotEmpty()) append(" · "); append("${"%,d".format(d.calories)} kcal")
        }
        if (d.steps > 0) {
            if (isNotEmpty()) append(" · "); append("${"%,d".format(d.steps)} steps")
        }
    }

    /** Open Whoop — the only thing that can honestly be offered for starting or stopping anything. */
    fun openWhoop(ctx: Context): Boolean = try {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.whoop.android")
        if (i != null) {
            ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } else false
    } catch (e: Exception) { false }

    // MARK: - Dismissal

    private const val PREFS = "slyos_training_card"

    /** Dismissed for today only — tomorrow is a new day and a new card. */
    fun dismissedToday(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("dismissed", 0L) >= startOfToday()
    } catch (e: Exception) { false }

    fun dismissToday(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("dismissed", System.currentTimeMillis()).apply()
        } catch (e: Exception) {}
    }
}
