package com.agentos.shell.tools

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Observations worth putting on the page, and the weekly write-up that goes into the brain.
 *
 * Everything here is computed, not generated. A model asked to comment on a table of numbers will
 * eventually round one, call a figure low, or invent a streak — and this is the part of the page a
 * person reads first and trusts most. The model's job is the conversation, not the findings.
 *
 * Each observation states the figures it came from, because "your sleep is worse on Sundays" is a
 * claim and "you sleep 52 minutes less on Sundays, across 11 of them" is a fact.
 */
object VitalsReview {

    data class Note(val title: String, val detail: String, val metric: String)

    /**
     * What is actually notable right now, best first.
     *
     * Deliberately capped. A page of twelve observations is a page nobody reads, and the fourth-most
     * interesting thing about someone's month is usually not interesting.
     */
    fun notes(ctx: Context, max: Int = 5): List<Note> {
        val out = ArrayList<Pair<Double, Note>>()   // ranked by how much it deviates
        val present = VitalsStore.present(ctx)

        present.forEach { m ->
            val s = VitalsStore.series(ctx, m, 90)
            if (s.size < 7) return@forEach
            val label = VitalsStore.M.label(m)
            val unit = VitalsStore.M.unit(m)

            // ── The week against the month ──
            val w = VitalsMath.meanOfLast(s, 7)
            val month = VitalsMath.meanOfLast(s, 30)
            if (w != null && month != null && s.size >= 14) {
                val d = w - month
                val sd = VitalsMath.sd(s) ?: 0.0
                if (sd > 1e-9 && abs(d) / sd > 0.5) {
                    val up = d > 0
                    val better = VitalsStore.M.higherIsBetter(m)
                    val good = better != null && (better == up)
                    out.add((abs(d) / sd) to Note(
                        "$label is ${if (up) "up" else "down"} this week",
                        "Averaging ${VitalsStore.M.format(m, w)}$unit over seven days against " +
                        "${VitalsStore.M.format(m, month)}$unit for the month — " +
                        VitalsStore.M.formatDelta(m, d) + (if (good) ", in the direction you'd want."
                            else if (better == null) "." else ", in the direction you'd rather it didn't."),
                        m))
                }
            }

            // ── The day of the week that stands out ──
            if (s.size >= 21) {
                val byDay = VitalsMath.byWeekday(s)
                if (byDay.size >= 5) {
                    val mean = byDay.values.average()
                    val worst = byDay.entries.maxByOrNull { abs(it.value - mean) }
                    if (worst != null) {
                        val d = worst.value - mean
                        val sd = VitalsMath.sd(s) ?: 0.0
                        if (sd > 1e-9 && abs(d) / sd > 0.6) {
                            val names = listOf("", "Sundays", "Mondays", "Tuesdays", "Wednesdays",
                                "Thursdays", "Fridays", "Saturdays")
                            val n = s.count {
                                val c = java.util.Calendar.getInstance().apply { timeInMillis = it.dayStart }
                                c.get(java.util.Calendar.DAY_OF_WEEK) == worst.key
                            }
                            out.add((abs(d) / sd * 0.9) to Note(
                                "${names[worst.key]} are different",
                                "${label} averages ${VitalsStore.M.format(m, worst.value)}$unit on " +
                                "${names[worst.key].lowercase()} against ${VitalsStore.M.format(m, mean)}$unit " +
                                "on other days — across $n of them.",
                                m))
                        }
                    }
                }
            }

            // ── A run in one direction ──
            val streak = run {
                val base = VitalsMath.baseline(s.dropLast(1)) ?: return@run 0
                var n = 0
                val above = s.last().value > base
                for (d in s.reversed()) {
                    if ((d.value > base) == above) n++ else break
                }
                if (above) n else -n
            }
            if (abs(streak) >= 5) {
                val above = streak > 0
                out.add((abs(streak) / 10.0) to Note(
                    "${abs(streak)} days ${if (above) "above" else "below"} your baseline",
                    "$label has been ${if (above) "above" else "below"} " +
                    "${VitalsStore.M.format(m, VitalsMath.baseline(s) ?: 0.0)}$unit every day for " +
                    "${abs(streak)} days. Long enough that it is the new normal rather than a spell.",
                    m))
            }
        }

        // ── Two things that move together ──
        VitalsMath.links(ctx, present).take(2).forEach { l ->
            out.add(abs(l.r) to Note(
                "${VitalsStore.M.label(l.a)} and ${VitalsStore.M.label(l.b)} move together",
                (if (l.r > 0) "When one rises so does the other" else "When one rises the other falls") +
                ", across 90 days. An association — which of them leads, this can't say.",
                l.a))
        }

        return out.sortedByDescending { it.first }.map { it.second }.take(max)
    }

    /**
     * The weekly write-up, stored so a year from now "what was my worst sleep month?" is answerable.
     *
     * Written at most once a week — a review that appears every day is a log, and nobody rereads a
     * log.
     */
    fun weekly(ctx: Context, force: Boolean = false): String? {
        val prefs = ctx.getSharedPreferences("slyos_vitals_prefs", Context.MODE_PRIVATE)
        val last = prefs.getLong("weekly_at", 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 86_400_000L) return null

        val present = VitalsStore.present(ctx)
        if (present.isEmpty()) return null
        val lines = present.mapNotNull { m ->
            val s = VitalsStore.series(ctx, m, 90)
            val w = VitalsMath.meanOfLast(s, 7) ?: return@mapNotNull null
            val prior = VitalsMath.mean(s.dropLast(7).takeLast(7))
            val delta = prior?.let { VitalsStore.M.formatDelta(m, w - it) + " on the week before" }.orEmpty()
            "${VitalsStore.M.label(m)}: ${VitalsStore.M.format(m, w)}${VitalsStore.M.unit(m)} " +
                "average" + (if (delta.isBlank()) "" else ", $delta")
        }
        if (lines.isEmpty()) return null

        val week = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(java.util.Date())
        val body = "Week to $week\n" + lines.joinToString("\n") +
            notes(ctx, 3).joinToString("") { "\n${it.title} — ${it.detail}" }

        try {
            Brain.remember(ctx, "health_insight", "Health week to $week", body,
                sensitivity = Brain.Sensitivity.SENSITIVE)
        } catch (e: Exception) {}
        prefs.edit().putLong("weekly_at", System.currentTimeMillis()).apply()
        return body
    }
}
