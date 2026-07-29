package com.agentos.shell.tools

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The statistics behind the health page. All on-device, no model call.
 *
 * The governing decision: **every comparison is against the person's own history, never against a
 * population.** A resting heart rate of 54 is unremarkable for one person and a warning for another,
 * and an app that says "that's low!" from a textbook range is worse than one that says nothing. Your
 * baseline is you, thirty days ago.
 *
 * The second decision: **projections are bands, not lines.** A single projected number is a lie
 * dressed as a fact — it hides the spread it came from. Every forecast here carries the width of its
 * own residuals, and a series too short or too scattered to project simply does not get one.
 */
object VitalsMath {

    // MARK: - Baseline and averages

    /**
     * The 30-day exponentially weighted mean — the number every delta on the page is measured from.
     *
     * Weighted rather than flat so a baseline moves with a person who is actually changing, instead
     * of anchoring them to a month ago for a month.
     */
    fun baseline(days: List<VitalsStore.Day>, halfLife: Double = 10.0): Double? {
        if (days.isEmpty()) return null
        val newest = days.last().dayStart
        var num = 0.0; var den = 0.0
        days.forEach { d ->
            val ageDays = (newest - d.dayStart) / 86_400_000.0
            val w = Math.pow(0.5, ageDays / halfLife)
            num += d.value * w; den += w
        }
        return if (den == 0.0) null else num / den
    }

    fun mean(days: List<VitalsStore.Day>): Double? =
        if (days.isEmpty()) null else days.sumOf { it.value } / days.size

    fun meanOfLast(days: List<VitalsStore.Day>, n: Int): Double? = mean(days.takeLast(n))

    fun sd(days: List<VitalsStore.Day>): Double? {
        if (days.size < 3) return null
        val m = mean(days) ?: return null
        return sqrt(days.sumOf { (it.value - m) * (it.value - m) } / (days.size - 1))
    }

    /**
     * How unusual today is, in standard deviations of this person's own spread.
     *
     * This is what lets the page say "unusual for you" with a number behind it instead of "bad".
     */
    fun z(days: List<VitalsStore.Day>): Double? {
        val today = days.lastOrNull()?.value ?: return null
        val prior = days.dropLast(1)
        val m = mean(prior) ?: return null
        val s = sd(prior) ?: return null
        if (s < 1e-9) return null
        return (today - m) / s
    }

    /** Your Mondays are not your Saturdays, and an average that mixes them hides both. */
    fun byWeekday(days: List<VitalsStore.Day>): Map<Int, Double> {
        val cal = java.util.Calendar.getInstance()
        return days.groupBy { d ->
            cal.timeInMillis = d.dayStart; cal.get(java.util.Calendar.DAY_OF_WEEK)
        }.mapValues { (_, v) -> v.sumOf { it.value } / v.size }
    }

    // MARK: - Trend and projection

    data class Trend(
        /** Change per day, in the metric's own units. */
        val slopePerDay: Double,
        /** Where it lands at [horizonDays], and how wide the band around that is. */
        val projected: Double,
        val band: Double,
        val horizonDays: Int
    )

    /**
     * Least squares over the series, extended forward, with a band from the residual spread.
     *
     * Refuses on fewer than fourteen points, because a fortnight is the least that can distinguish a
     * trend from a bad week — and a projection drawn from five days would be the most confident and
     * least true thing on the page.
     */
    fun trend(days: List<VitalsStore.Day>, horizonDays: Int = 30): Trend? {
        if (days.size < 14) return null
        val x0 = days.first().dayStart
        val xs = days.map { (it.dayStart - x0) / 86_400_000.0 }
        val ys = days.map { it.value }
        val n = xs.size
        val mx = xs.sum() / n; val my = ys.sum() / n
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        if (sxx < 1e-9) return null
        val slope = sxy / sxx
        val intercept = my - slope * mx
        // The band is the spread of what the line failed to explain — the honest width of a forecast.
        val resid = sqrt((0 until n).sumOf {
            val e = ys[it] - (intercept + slope * xs[it]); e * e
        } / (n - 2).coerceAtLeast(1))
        val at = xs.last() + horizonDays
        return Trend(slope, intercept + slope * at, resid, horizonDays)
    }

    // MARK: - Goals

    data class GoalProjection(
        /** Days from now, or null when the current trend never gets there. */
        val days: Int?,
        val band: Int,
        /** What the daily rate would have to become to hit a date the owner names. */
        val neededPerDay: Double?
    )

    /**
     * When the current trend crosses a target, and what it would take to get there sooner.
     *
     * Returns null days when the trend is flat or heading away — "never at this rate" is a real and
     * useful answer, and inventing a date for it would be the opposite.
     */
    fun goal(days: List<VitalsStore.Day>, target: Double, byDays: Int? = null): GoalProjection? {
        val t = trend(days, horizonDays = 30) ?: return null
        val current = days.last().value
        val gap = target - current
        if (abs(t.slopePerDay) < 1e-6) return GoalProjection(null, 0, byDays?.let { gap / it })
        val d = gap / t.slopePerDay
        if (d <= 0) return GoalProjection(null, 0, byDays?.let { gap / it })
        // The date's uncertainty is the value band converted back into days along the slope.
        val bandDays = (t.band / abs(t.slopePerDay)).roundToInt().coerceAtMost(365)
        return GoalProjection(d.roundToInt(), bandDays, byDays?.let { gap / it })
    }

    // MARK: - Sleep debt

    /**
     * Cumulative shortfall against the person's own need, and when it would be repaid.
     *
     * The "need" is their own better nights rather than a recommended eight hours, because the
     * recommendation is not about them.
     */
    data class SleepDebt(val minutes: Int, val needMinutes: Int, val paybackDays: Int?)

    fun sleepDebt(days: List<VitalsStore.Day>): SleepDebt? {
        if (days.size < 7) return null
        // What they sleep when they sleep well: the 75th percentile of the last 90 days.
        val need = days.map { it.value }.sorted().let { it[(it.size * 0.75).toInt().coerceAtMost(it.size - 1)] }
        val recent = days.takeLast(14)
        val debt = recent.sumOf { (need - it.value).coerceAtLeast(0.0) }
        val surplusPerDay = (mean(recent.takeLast(3)) ?: 0.0) - need
        val payback = if (surplusPerDay > 1) (debt / surplusPerDay).roundToInt() else null
        return SleepDebt(debt.roundToInt(), need.roundToInt(), payback)
    }

    // MARK: - Correlation

    data class Link(val a: String, val b: String, val r: Double)

    /**
     * Pearson across metric pairs, surfaced only when it is strong enough to be worth a sentence.
     *
     * Always phrased as association where it is shown. "Nights you're in bed before 23:30, your HRV
     * averages 7 higher" is honest; "going to bed early raises your HRV" is a claim this cannot make
     * and will not.
     */
    fun links(ctx: Context, metrics: List<String>, minAbs: Double = 0.45): List<Link> {
        val series = metrics.associateWith { VitalsStore.series(ctx, it, 90) }
            .filterValues { it.size >= 14 }
        val out = ArrayList<Link>()
        val keys = series.keys.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = series[keys[i]]!!.associate { it.dayStart to it.value }
            val b = series[keys[j]]!!.associate { it.dayStart to it.value }
            val shared = a.keys.intersect(b.keys)
            if (shared.size < 14) continue
            val xs = shared.map { a[it]!! }; val ys = shared.map { b[it]!! }
            val r = pearson(xs, ys) ?: continue
            if (abs(r) >= minAbs) out.add(Link(keys[i], keys[j], r))
        }
        return out.sortedByDescending { abs(it.r) }.take(3)
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 3) return null
        val mx = xs.sum() / n; val my = ys.sum() / n
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx < 1e-9 || syy < 1e-9) return null
        return sxy / sqrt(sxx * syy)
    }
}
