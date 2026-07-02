package com.agentos.shell.tools

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks what the agent did for you and a rough "time saved" estimate, per day.
 * Stored locally. Used to show value on the Home screen.
 */
object MetricsStore {
    private const val PREF = "slyos_metrics"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Record one completed agent action and the seconds it likely saved you. */
    fun record(ctx: Context, savedSeconds: Int) {
        val p = prefs(ctx)
        val dayKey = "saved_${today()}"
        val cntKey = "count_${today()}"
        p.edit()
            .putInt(dayKey, p.getInt(dayKey, 0) + savedSeconds)
            .putInt(cntKey, p.getInt(cntKey, 0) + 1)
            .apply()
    }

    fun savedMinutesToday(ctx: Context): Int = prefs(ctx).getInt("saved_${today()}", 0) / 60
    fun actionsToday(ctx: Context): Int = prefs(ctx).getInt("count_${today()}", 0)

    data class DayStat(val label: String, val savedMin: Int)

    /** Minutes saved per day for the last [days] days, oldest → newest, for the trend chart. */
    fun history(ctx: Context, days: Int): List<DayStat> {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val lab = SimpleDateFormat("EEE", Locale.US)
        val out = ArrayList<DayStat>()
        for (i in days - 1 downTo 0) {
            val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val mins = prefs(ctx).getInt("saved_${df.format(c.time)}", 0) / 60
            out.add(DayStat(lab.format(c.time), mins))
        }
        return out
    }

    /** 0–100 efficiency score: 7-day average minutes saved, where ~60 min/day ≈ 100. */
    fun efficiencyScore(ctx: Context): Int {
        val avg = history(ctx, 7).map { it.savedMin }.average().let { if (it.isNaN()) 0.0 else it }
        return (avg / 60.0 * 100.0).coerceIn(0.0, 100.0).toInt()
    }

    /** % change in minutes saved, this 7 days vs the previous 7 days. */
    fun trendPct(ctx: Context): Int {
        val h = history(ctx, 14)
        val last7 = h.takeLast(7).sumOf { it.savedMin }
        val prev7 = h.take(7).sumOf { it.savedMin }
        return when {
            prev7 == 0 -> if (last7 > 0) 100 else 0
            else -> ((last7 - prev7) * 100.0 / prev7).toInt()
        }
    }

    /** Human label that scales: "~6 min", "~45 min", "~2 h 10 min". */
    fun savedLabelToday(ctx: Context): String {
        val mins = savedMinutesToday(ctx)
        return when {
            mins <= 0 -> ""
            mins < 60 -> "~$mins min saved today"
            else -> {
                val h = mins / 60; val m = mins % 60
                if (m == 0) "~$h h saved today" else "~$h h $m min saved today"
            }
        }
    }

    /**
     * Realistic minutes-worth of effort each action would have taken you by hand. Writing a research
     * paper is hours, not seconds — the estimate reflects that instead of a flat ~1 min per action.
     */
    fun secondsFor(type: String): Int = when (type) {
        "paper_write"  -> 9000   // ~2.5 h to draft a paper from scratch
        "paper_expand" -> 2400   // ~40 min to write a new chapter
        "paper_edit"   -> 900    // ~15 min to revise a section
        "cowork"       -> 1800   // ~30 min: it builds real files/tools for you
        "invest"       -> 1800   // ~30 min: researching + assembling a portfolio
        "find_job"     -> 1200   // ~20 min: résumé + cover + application
        "set_mission", "network_search" -> 900   // ~15 min: finding + drafting outreach
        "shop"         -> 600    // ~10 min of price hunting
        "write_paper"  -> 9000
        "outreach", "email_reply", "send_email", "compose_email" -> 360   // ~6 min for a real email
        "social_post", "compose_post"  -> 480    // ~8 min to craft + caption a post
        "spicy_post"   -> 300
        "create_doc", "create_sheet", "create_slides", "create_pdf" -> 420  // ~7 min to make a doc
        "doc_answer"   -> 240
        "web_search"   -> 300    // a real lookup + skim
        "look"         -> 120    // identifying + finding the link yourself
        "reply", "send_sms", "message" -> 90
        "remind", "add_event" -> 60
        "navigate", "play_music" -> 30
        "alarm", "timer" -> 30
        "dial", "sms"  -> 20
        "catch_up"     -> 120
        "open_app", "settings", "camera" -> 10
        else -> 30
    }
}
