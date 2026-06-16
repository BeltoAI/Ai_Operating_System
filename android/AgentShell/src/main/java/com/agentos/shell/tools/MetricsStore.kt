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

    /** Typical seconds saved per action type — rough but consistent. */
    fun secondsFor(type: String): Int = when (type) {
        "send_sms", "reply" -> 60
        "add_event" -> 45
        "alarm", "timer" -> 25
        "web_search" -> 20
        "dial", "sms" -> 20
        "open_app", "settings", "camera" -> 10
        else -> 0
    }
}
