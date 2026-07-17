package com.agentos.shell.tools

import android.content.Context

/**
 * A real in-app timer so SlyOS can show a LIVE countdown widget on Home (a delegated system-clock timer can't be
 * read back or displayed). Stores the end time, schedules the "time's up" notification via ReminderScheduler
 * (fires through Doze), and the Home widget polls the remaining time.
 */
object TimerStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("slyos_timer", Context.MODE_PRIVATE)

    /** Start a timer for [seconds]. Returns the end time in ms. */
    fun start(ctx: Context, seconds: Int, label: String = ""): Long {
        val end = System.currentTimeMillis() + seconds * 1000L
        p(ctx).edit().putLong("end", end).putLong("total", seconds * 1000L).putString("label", label).apply()
        try { com.agentos.shell.ReminderScheduler.schedule(ctx, end, "Timer's up" + (if (label.isNotBlank()) " — $label" else "")) } catch (e: Exception) {}
        return end
    }

    fun endMs(ctx: Context): Long = p(ctx).getLong("end", 0L)
    fun totalMs(ctx: Context): Long = p(ctx).getLong("total", 0L)
    fun label(ctx: Context): String = p(ctx).getString("label", "").orEmpty()
    fun running(ctx: Context): Boolean = endMs(ctx) > System.currentTimeMillis()
    fun remainingMs(ctx: Context): Long = (endMs(ctx) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun cancel(ctx: Context) = p(ctx).edit().remove("end").remove("total").remove("label").apply()

    // Let the owner dismiss the "next alarm" line (a standing daily alarm shouldn't sit there forever). Dismissal
    // is per exact alarm time, so a NEW/changed alarm shows again.
    fun dismissAlarm(ctx: Context, triggerMs: Long) = p(ctx).edit().putLong("alarm_dismissed", triggerMs).apply()
    fun alarmDismissed(ctx: Context, triggerMs: Long): Boolean = p(ctx).getLong("alarm_dismissed", 0L) == triggerMs && triggerMs != 0L
}
