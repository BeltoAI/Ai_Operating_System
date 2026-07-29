package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Targets, and an honest account of whether you are getting there.
 *
 * A goal without a projection is a wish written down. The point of having ninety days of someone's
 * own numbers is being able to say *when* — and, when the answer is "not at this rate", to say that
 * instead of drawing an encouraging line to a date that will not happen.
 *
 * Three things are reported and they are deliberately different questions:
 *  - where the current trend lands you, and when;
 *  - how often you have actually hit it lately, which is what a daily target really means;
 *  - what the daily number would have to become to reach it by a date you name.
 */
object VitalsGoals {

    private const val PREFS = "slyos_vitals_goals"
    private const val KEY = "goals"

    /**
     * @param direction +1 when you want the number higher, -1 when lower. Stored rather than
     *   inferred, because "weight 78" is a goal in either direction depending on the person and
     *   guessing it wrong turns encouragement into an insult.
     */
    data class Goal(val metric: String, val target: Double, val direction: Int, val byDate: Long = 0L)

    data class Progress(
        val goal: Goal,
        val current: Double,
        /** Days at the current trend, null when the trend never gets there. */
        val etaDays: Int?,
        val etaBand: Int,
        /** Days out of the last 14 that already met it — what a daily target actually measures. */
        val hitDays: Int,
        val ofDays: Int,
        /** What the daily average would have to become to hit [Goal.byDate]. */
        val neededAverage: Double?,
        val reached: Boolean
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Goal> = try {
        val a = JSONArray(p(ctx).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                Goal(it.optString("m"), it.optDouble("t"), it.optInt("d", 1), it.optLong("by", 0L))
            }
        }
    } catch (e: Exception) { emptyList() }

    fun set(ctx: Context, goal: Goal) {
        val kept = all(ctx).filterNot { it.metric == goal.metric } + goal
        write(ctx, kept)
    }

    fun remove(ctx: Context, metric: String) = write(ctx, all(ctx).filterNot { it.metric == metric })

    fun forMetric(ctx: Context, metric: String): Goal? = all(ctx).firstOrNull { it.metric == metric }

    private fun write(ctx: Context, goals: List<Goal>) {
        val a = JSONArray()
        goals.forEach {
            a.put(JSONObject().put("m", it.metric).put("t", it.target)
                .put("d", it.direction).put("by", it.byDate))
        }
        p(ctx).edit().putString(KEY, a.toString()).apply()
    }

    /** Where this goal actually stands, from the person's own series. */
    fun progress(ctx: Context, goal: Goal): Progress? {
        val s = VitalsStore.series(ctx, goal.metric, 120)
        if (s.isEmpty()) return null
        val current = s.last().value
        val recent = s.takeLast(14)
        val hit = recent.count {
            if (goal.direction >= 0) it.value >= goal.target else it.value <= goal.target
        }
        val reached = if (goal.direction >= 0) current >= goal.target else current <= goal.target

        val byDays = goal.byDate.takeIf { it > 0 }?.let {
            ((it - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(1)
        }
        val gp = VitalsMath.goal(s, goal.target, byDays)

        return Progress(
            goal = goal,
            current = current,
            etaDays = gp?.days,
            etaBand = gp?.band ?: 0,
            hitDays = hit,
            ofDays = recent.size,
            // Reported as the average you would need, not as a delta — "average 7h10 a night" is
            // something a person can act on; "+22 minutes per day of slope" is not.
            neededAverage = gp?.neededPerDay?.let { current + it * (byDays ?: 30) },
            reached = reached)
    }

    /** The sentence for a goal card. Says "not at this rate" where that is the truth. */
    fun sentence(ctx: Context, pr: Progress): String {
        val m = pr.goal.metric
        val t = VitalsStore.M.format(m, pr.goal.target) + VitalsStore.M.unit(m)
        return when {
            pr.reached && pr.hitDays >= pr.ofDays - 2 ->
                "At $t and holding — ${pr.hitDays} of the last ${pr.ofDays} days."
            pr.reached -> "There today, though only ${pr.hitDays} of the last ${pr.ofDays} days."
            pr.etaDays != null && pr.etaDays <= 400 -> {
                val when_ = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(java.util.Date(System.currentTimeMillis() + pr.etaDays * 86_400_000L))
                "On track for around $when_" +
                    (if (pr.etaBand in 1..120) " — give or take ${pr.etaBand} days" else "") +
                    ". ${pr.hitDays} of the last ${pr.ofDays} days already there."
            }
            pr.neededAverage != null ->
                "Not at the current rate. To reach $t by your date you'd need to average " +
                    VitalsStore.M.format(m, pr.neededAverage) + VitalsStore.M.unit(m) + " from here."
            else ->
                "Not moving toward $t at the moment — ${pr.hitDays} of the last ${pr.ofDays} days met it."
        }
    }

    /** A goal in the terms the metric is spoken in, for the setter. */
    fun suggestTarget(ctx: Context, metric: String): Double? {
        val s = VitalsStore.series(ctx, metric, 90)
        if (s.size < 5) return null
        val sorted = s.map { it.value }.sorted()
        // Their own better days, not a recommendation from a textbook about someone else.
        return when (VitalsStore.M.higherIsBetter(metric)) {
            true -> sorted[(sorted.size * 0.75).toInt().coerceAtMost(sorted.size - 1)]
            false -> sorted[(sorted.size * 0.25).toInt()]
            else -> sorted[sorted.size / 2]
        }.let { (it * 10).roundToInt() / 10.0 }
    }

    fun defaultDirection(metric: String): Int =
        when (VitalsStore.M.higherIsBetter(metric)) { true -> 1; false -> -1; else -> -1 }
}
