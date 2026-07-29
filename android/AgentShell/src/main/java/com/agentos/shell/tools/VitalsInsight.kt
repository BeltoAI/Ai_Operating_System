package com.agentos.shell.tools

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the numbers mean, said carefully.
 *
 * The rules are in code rather than in a prompt, because a prompt is a request and this needs to be
 * a guarantee:
 *
 *  - **Never diagnose.** No condition is ever named, no medication is ever mentioned.
 *  - **Never "bad".** Only "unusual for you", with the figure and the baseline it is unusual against.
 *  - **Always cite.** "Three nights below 6h" — not "you've been tired".
 *  - **Never fill a gap.** Missing data is said out loud, not interpolated into a confident average.
 *  - **Anything alarming ends with a person**, not with an app.
 *
 * This is not a medical device and the page says so where it can be seen.
 */
object VitalsInsight {

    data class Flag(val title: String, val detail: String, val serious: Boolean)

    /**
     * The one sentence at the top, written from the actual numbers.
     *
     * Deliberately built by hand rather than by the model. It is the first thing read every morning,
     * it has to be right, and a model given a table of numbers will eventually round one of them or
     * call a figure "low". The model's job is the conversation below it, not this line.
     */
    fun headline(ctx: Context): String {
        val parts = ArrayList<String>()

        VitalsStore.series(ctx, VitalsStore.M.SLEEP, 90).takeIf { it.size >= 3 }?.let { s ->
            val last = s.last().value
            val base = VitalsMath.baseline(s.dropLast(1))
            if (base != null) {
                val d = last - base
                parts.add(
                    if (abs(d) < 20) "You slept ${VitalsStore.M.format(VitalsStore.M.SLEEP, last)}, about your usual"
                    else "You slept ${VitalsStore.M.format(VitalsStore.M.SLEEP, last)} against a " +
                        "${VitalsStore.M.format(VitalsStore.M.SLEEP, base)} baseline")
            }
        }

        VitalsStore.series(ctx, VitalsStore.M.HRV, 90).takeIf { it.size >= 7 }?.let { s ->
            val last = s.last().value
            val base = VitalsMath.baseline(s.dropLast(1)) ?: return@let
            val d = last - base
            if (abs(d) >= 3) parts.add(
                "your HRV is ${if (d < 0) "down" else "up"} ${abs(d).roundToInt()} from your 30-day mean")
        }

        if (parts.isEmpty()) return "Not enough history yet — a few days of readings and this starts meaning something."

        val tail = VitalsMath.sleepDebt(VitalsStore.series(ctx, VitalsStore.M.SLEEP, 90))?.let { d ->
            when {
                d.minutes >= 180 && d.paybackDays != null ->
                    " You're ${d.minutes / 60}h short over the last fortnight; at the last few nights' rate that clears in about ${d.paybackDays} days."
                d.minutes >= 180 -> " You're ${d.minutes / 60}h short over the last fortnight."
                else -> ""
            }
        }.orEmpty()

        return parts.joinToString(" and ").replaceFirstChar { it.uppercase() } + "." + tail
    }

    /**
     * Things worth saying out loud.
     *
     * The strain flag is the only one that reaches for a body-wide reading, and it is deliberately
     * narrow: resting heart rate up, HRV down and respiratory rate up, on two consecutive nights, is
     * a well-established pattern. It says the body is working harder than usual. It does NOT name an
     * illness, and past three days it says to talk to a doctor — because at that point the useful
     * next step is a person, not a phone.
     */
    fun flags(ctx: Context): List<Flag> {
        val out = ArrayList<Flag>()

        fun raisedFor(metric: String, nights: Int, up: Boolean): Boolean {
            val s = VitalsStore.series(ctx, metric, 90)
            if (s.size < 10) return false
            val base = VitalsMath.baseline(s.dropLast(nights)) ?: return false
            val sd = VitalsMath.sd(s.dropLast(nights)) ?: return false
            if (sd < 1e-6) return false
            return s.takeLast(nights).all {
                if (up) (it.value - base) / sd > 0.8 else (base - it.value) / sd > 0.8
            }
        }

        val rhrUp = raisedFor(VitalsStore.M.RHR, 2, up = true)
        val hrvDown = raisedFor(VitalsStore.M.HRV, 2, up = false)
        val respUp = raisedFor(VitalsStore.M.RESP, 2, up = true)
        val n = listOf(rhrUp, hrvDown, respUp).count { it }
        if (n >= 2) {
            val days = (3..7).firstOrNull { !raisedFor(VitalsStore.M.RHR, it, up = true) } ?: 7
            out.add(Flag(
                "Your body is working harder than usual",
                "Two nights of " + listOfNotNull(
                    if (rhrUp) "a higher resting heart rate" else null,
                    if (hrvDown) "lower HRV" else null,
                    if (respUp) "a higher breathing rate" else null
                ).joinToString(", ") + ". This often shows up before you feel it — a lighter day " +
                "would be sensible." + (if (days >= 3) " It's been like this three days or more; " +
                "worth mentioning to a doctor." else ""),
                serious = days >= 3))
        }

        // Missing data is stated, never interpolated. A gap silently averaged away is how a page
        // ends up confidently describing nights that were never recorded.
        val gaps = VitalsStore.present(ctx).mapNotNull { m ->
            val s = VitalsStore.series(ctx, m, 14)
            val expected = 14
            if (s.size in 1 until expected - 4) VitalsStore.M.label(m) else null
        }
        if (gaps.isNotEmpty() && gaps.size <= 3) out.add(Flag(
            "Some days are missing",
            "No readings for ${gaps.joinToString(", ")} on several days in the last fortnight — " +
            "averages here skip those days rather than guessing at them.",
            serious = false))

        return out
    }

    /**
     * One memory per day, written once, for every day there is data.
     *
     * Two bugs this replaces, both found by reading the brain rather than the code:
     *
     *  - it wrote TODAY on every visit, so four opens of the Health page left four near-identical
     *    rows for the same day, each of them embedded and each competing with the others at recall
     *    time. A month of that is hundreds of rows saying the same thing, and the cost lands on
     *    every unrelated question too, because they crowd the ranked context;
     *  - it wrote ONLY today, so an imported history — the whole point of the Whoop export — was
     *    invisible to the brain. "How did I sleep last Thursday?" had nothing to answer from.
     *
     * Raw samples are still never written. A heart-rate series is thousands of rows a day and would
     * drown recall; "Tue 14 Jan: slept 6h12, HRV 52" is what a question actually gets asked against.
     */
    fun rememberDays(ctx: Context, maxDays: Int = 120) {
        val prefs = ctx.getSharedPreferences("slyos_vitals_prefs", Context.MODE_PRIVATE)
        val metrics = VitalsStore.present(ctx)
        if (metrics.isEmpty()) return

        // Every day that has any reading at all, newest first.
        val byDay = HashMap<Long, MutableList<Pair<String, Double>>>()
        metrics.forEach { m ->
            VitalsStore.series(ctx, m, maxDays).forEach { d ->
                byDay.getOrPut(d.dayStart) { ArrayList() }.add(m to d.value)
            }
        }

        val fmt = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
        var written = 0
        byDay.entries.sortedByDescending { it.key }.forEach { (dayStart, vals) ->
            val label = fmt.format(java.util.Date(dayStart))
            val body = "$label: " + vals.sortedBy { VitalsStore.M.ORDER.indexOf(it.first) }
                .joinToString(", ") {
                    "${VitalsStore.M.label(it.first)} ${VitalsStore.M.format(it.first, it.second)}${VitalsStore.M.unit(it.first)}"
                }
            // Keyed on the CONTENT, not the day: a day whose numbers changed (a later sync filling
            // in last night's sleep) is worth rewriting, and a day whose numbers did not is not.
            val key = "wrote_" + dayStart + "_" + body.hashCode()
            if (prefs.getBoolean(key, false)) return@forEach

            val flagLine = if (dayStart == byDay.keys.maxOrNull()) flags(ctx).joinToString("; ") { it.title } else ""
            try {
                Brain.remember(ctx, "health_day", "Health $label",
                    body + (if (flagLine.isBlank()) "" else ". Flags: $flagLine"),
                    ts = dayStart,
                    sensitivity = Brain.Sensitivity.SENSITIVE)
                prefs.edit().putBoolean(key, true).apply()
                written++
            } catch (e: Exception) {}
        }
    }

    /** Kept for callers that only want today refreshed. */
    fun rememberToday(ctx: Context) = rememberDays(ctx, 2)

    /**
     * The numbers, laid out for the model to answer a question against.
     *
     * Projections are labelled AS projections in the text itself. Left unmarked, a forecast written
     * into the brain reads back three months later exactly like a measurement — the same trap that
     * once had SlyOS confirm an invitation it had only ever promised to send.
     */
    fun contextFor(ctx: Context, question: String): String {
        val sb = StringBuilder("THE OWNER'S OWN HEALTH DATA (on-device, from ")
        sb.append(VitalsStore.sources(ctx).ifEmpty { listOf("their wearable") }.joinToString("/"))
        sb.append("). Every comparison is against their own baseline, never a population norm.\n")
        VitalsStore.present(ctx).forEach { m ->
            val s = VitalsStore.series(ctx, m, 90)
            if (s.isEmpty()) return@forEach
            val last = s.last().value
            val base = VitalsMath.baseline(s)
            val w = VitalsMath.meanOfLast(s, 7)
            sb.append("- ${VitalsStore.M.label(m)}: latest ${VitalsStore.M.format(m, last)}")
            if (w != null) sb.append(", 7-day mean ${VitalsStore.M.format(m, w)}")
            if (base != null) sb.append(", 30-day baseline ${VitalsStore.M.format(m, base)}")
            VitalsMath.trend(s)?.let {
                sb.append(", PROJECTION (not a measurement) in 30 days ~${VitalsStore.M.format(m, it.projected)} ±${VitalsStore.M.format(m, it.band)}")
            }
            sb.append(" (${s.size} days of data)\n")
        }
        VitalsMath.links(ctx, VitalsStore.present(ctx)).forEach {
            sb.append("- ASSOCIATION ONLY, not cause: ${VitalsStore.M.label(it.a)} and " +
                "${VitalsStore.M.label(it.b)} move together (r=${String.format("%.2f", it.r)})\n")
        }
        flags(ctx).forEach { sb.append("- FLAG: ${it.title} — ${it.detail}\n") }
        sb.append("\nRules for your answer: never name a condition or diagnose; never call a number " +
            "bad, only unusual for them and say by how much; cite the figures you used; if the data " +
            "does not answer the question, say so rather than estimating; if anything looks " +
            "concerning, suggest speaking to a doctor.")
        return sb.toString()
    }

    /**
     * Which metrics a question is actually about, for showing beside the answer.
     *
     * Named metrics first; failing that, the handful that a general "how am I doing" is about. A
     * health answer that is a paragraph of prose makes the reader hunt for the figure it describes,
     * when the figure is something the phone already has.
     */
    fun metricsFor(ctx: Context, q: String): List<String> {
        val present = VitalsStore.present(ctx)
        if (present.isEmpty()) return emptyList()
        val named = present.filter { m ->
            val words = when (m) {
                VitalsStore.M.SLEEP -> listOf("sleep", "slept", "rested", "bed", "night")
                VitalsStore.M.HRV -> listOf("hrv", "variability")
                VitalsStore.M.RHR -> listOf("resting", "heart rate", "pulse")
                VitalsStore.M.STEPS -> listOf("steps", "walked", "walking")
                VitalsStore.M.RECOVERY -> listOf("recovery", "recovered")
                VitalsStore.M.STRAIN -> listOf("strain", "exertion")
                VitalsStore.M.RESP -> listOf("breathing", "respiratory")
                VitalsStore.M.SPO2 -> listOf("oxygen", "spo2")
                VitalsStore.M.WEIGHT -> listOf("weight", "kilos", "kg")
                VitalsStore.M.EXERCISE -> listOf("exercise", "workout", "training", "train")
                else -> emptyList()
            }
            words.any { q.contains(it, ignoreCase = true) }
        }
        if (named.isNotEmpty()) return named.take(4)
        // A general question gets the ones that answer "how am I today".
        return present.filter {
            it in listOf(VitalsStore.M.RECOVERY, VitalsStore.M.SLEEP, VitalsStore.M.HRV, VitalsStore.M.RHR)
        }.take(4)
    }

    /** Whether a question is about the body rather than the calendar. */
    fun isHealthQuestion(q: String): Boolean = Regex(
        "(?i)\\b(sleep|slept|hrv|heart rate|resting heart|recovery|strain|steps|weight|" +
        "vo2|spo2|oxygen|breathing|respiratory|rested|tired|training|workout|exercise|" +
        "should i train|how am i doing physically|my health|fitness)\\b").containsMatchIn(q)
}
