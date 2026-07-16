package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Proactive nightly wake-up planner. Each evening (at a time YOU choose, default 9pm) it looks at tomorrow's
 * first commitment, subtracts your prep buffer, and offers a one-tap alarm — via a notification and the Home
 * chip. Fully configurable in plain language ("remind me at 10pm instead", "wake me 90 min before", "turn these
 * off"). Everything is a suggestion; nothing sets an alarm without your tap.
 */
object AlarmPlanner {
    private const val PREFS = "slyos_alarmplan"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ymd() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun enabled(ctx: Context) = p(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, b: Boolean) = p(ctx).edit().putBoolean("enabled", b).apply()
    fun askHmm(ctx: Context): String = p(ctx).getString("ask", "20:00") ?: "20:00"
    fun setAskHmm(ctx: Context, s: String) = p(ctx).edit().putString("ask", s).apply()
    fun bufferMin(ctx: Context) = p(ctx).getInt("buffer", 60)
    fun setBufferMin(ctx: Context, m: Int) = p(ctx).edit().putInt("buffer", m.coerceIn(5, 600)).apply()

    /** wake alarm arg (e.g. "6:30 am"), the event it's for, and that event's start. Null = nothing to suggest. */
    data class Suggestion(val arg: String, val label: String, val eventBegin: Long)

    fun suggestion(ctx: Context): Suggestion? {
        if (!CalendarTool.hasPermission(ctx)) return null
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = maxOf(c.timeInMillis, now)
        val end = c.timeInMillis + 36L * 3600 * 1000   // all of tomorrow + into the next morning
        val evs = try { CalendarTool.eventsBetween(ctx, start, end, 50) } catch (e: Exception) { emptyList() }
        val first = evs.filter { it.begin > now + 30 * 60 * 1000 }.minByOrNull { it.begin } ?: return null
        val wake = first.begin - bufferMin(ctx) * 60_000L
        if (wake <= now + 60_000) return null
        val w = Calendar.getInstance().apply { timeInMillis = wake }
        val hh = w.get(Calendar.HOUR_OF_DAY); val mm = w.get(Calendar.MINUTE)
        val ap = if (hh < 12) "am" else "pm"; val h12 = when { hh == 0 -> 12; hh > 12 -> hh - 12; else -> hh }
        return Suggestion("%d:%02d %s".format(h12, mm, ap), first.title.take(40), first.begin)
    }

    private fun nowMinutes(): Int { val c = Calendar.getInstance(); return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }

    fun shouldPromptNow(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        if (p(ctx).getString("lastPrompt", "") == ymd()) return false
        val parts = askHmm(ctx).split(":")
        val askMin = (parts.getOrNull(0)?.toIntOrNull() ?: 20) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        if (nowMinutes() < askMin) return false
        return suggestion(ctx) != null
    }

    /** Called from the background worker each cycle; posts the nightly suggestion notification when due. */
    fun tick(ctx: Context) {
        if (!shouldPromptNow(ctx)) return
        val s = suggestion(ctx) ?: return
        p(ctx).edit().putString("lastPrompt", ymd()).apply()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("alarmplan", "Wake-up planner", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 42,
                Intent(ctx, Class.forName("com.agentos.shell.ShellActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "Tomorrow you've got “${s.label}”. Want a wake-up alarm for ${s.arg.uppercase()}? Open SlyOS to set it — or say “wake me 90 min before” to adjust."
            val note = Notification.Builder(ctx, "alarmplan")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Set an alarm for tomorrow?")
                .setContentText("Wake at ${s.arg.uppercase()} for “${s.label}”")
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(42, note)
        } catch (e: Exception) {}
    }

    /**
     * Plain-language config. Returns a confirmation string if [text] was a planner command, else null.
     * Examples: "remind me at 10pm to set my alarm", "wake me 90 minutes before", "turn off alarm suggestions".
     */
    fun configure(ctx: Context, text: String): String? {
        val t = text.lowercase()
        val aboutPlanner = Regex("(alarm|wake).{0,20}(suggest|remind|plan|before|each|every|nightly|night)|(suggest|remind).{0,20}(alarm|wake)").containsMatchIn(t)
        var changed = false; val bits = ArrayList<String>()
        if (Regex("\\b(off|stop|disable|no more|quit)\\b").containsMatchIn(t) && aboutPlanner) { setEnabled(ctx, false); return "Okay — I'll stop the nightly alarm suggestions. Say “turn alarm suggestions on” to bring them back." }
        if (Regex("\\b(on|enable|resume|start)\\b").containsMatchIn(t) && aboutPlanner) { setEnabled(ctx, true); changed = true; bits.add("nightly suggestions on") }
        // nightly ask-time: "at 10pm", "at 22:00"
        Regex("at\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return@let
            val mn = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3].replace(".", "")
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && mn in 0..59) { setAskHmm(ctx, "%02d:%02d".format(h, mn)); changed = true; bits.add("I'll check in at ${"%02d:%02d".format(h, mn)}") }
        }
        // buffer: "90 min before", "2 hours before"
        Regex("(\\d{1,3})\\s*(min|minute|hour|hr)s?\\s*(before|ahead|earlier)").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val mins = if (m.groupValues[2].startsWith("h")) n * 60 else n
            setBufferMin(ctx, mins); changed = true; bits.add("waking you $mins min before")
        }
        if (!changed && !aboutPlanner) return null
        if (!changed) return "Nightly alarm suggestions are ${if (enabled(ctx)) "on" else "off"} — I check at ${askHmm(ctx)} and wake you ${bufferMin(ctx)} min before your first thing."
        return "Got it — " + bits.joinToString(", ") + "."
    }
}
