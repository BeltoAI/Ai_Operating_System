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
        "outreach", "email_reply" -> 360   // ~6 min for a real email
        "social_post"  -> 480    // ~8 min to craft + caption a post
        "spicy_post"   -> 300
        "doc_answer"   -> 240
        "web_search"   -> 300    // a real lookup + skim
        "reply", "send_sms" -> 90
        "add_event"    -> 60
        "alarm", "timer" -> 30
        "dial", "sms"  -> 20
        "catch_up"     -> 120
        "open_app", "settings", "camera" -> 10
        else -> 30
    }
}
