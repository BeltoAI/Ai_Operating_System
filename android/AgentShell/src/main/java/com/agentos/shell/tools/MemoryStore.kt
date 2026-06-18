package com.agentos.shell.tools

import android.content.Context

/**
 * What the agent knows about the user. Persisted locally (SharedPreferences) and injected
 * into every prompt so replies and answers are personalized. The user edits this on the
 * Memory screen.
 */
object MemoryStore {
    private const val PREF = "slyos"
    private const val KEY_ABOUT = "about_you"
    private const val KEY_AUTO = "autonomous_reply"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun about(ctx: Context): String = prefs(ctx).getString(KEY_ABOUT, "") ?: ""
    fun setAbout(ctx: Context, value: String) = prefs(ctx).edit().putString(KEY_ABOUT, value).apply()

    /** A booking/scheduling link (e.g. Calendly) the agent shares when someone wants to talk live. */
    fun bookingLink(ctx: Context): String = prefs(ctx).getString("booking_link", "") ?: ""
    fun setBookingLink(ctx: Context, value: String) = prefs(ctx).edit().putString("booking_link", value.trim()).apply()

    /** When true, the agent auto-replies to incoming messages (after a short undo window). */
    fun autonomous(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)
    fun setAutonomous(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, value).apply()

    /**
     * Night schedule: when on, auto-reply is FORCED on between [autoStartHour] and [autoEndHour]
     * (defaults 20:00–06:00). Outside that window the manual [autonomous] toggle is the default.
     */
    fun nightAuto(ctx: Context): Boolean = prefs(ctx).getBoolean("night_auto", false)
    fun setNightAuto(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("night_auto", value).apply()
    fun autoStartHour(ctx: Context): Int = prefs(ctx).getInt("auto_start", 20)
    fun autoEndHour(ctx: Context): Int = prefs(ctx).getInt("auto_end", 6)
    fun setAutoWindow(ctx: Context, start: Int, end: Int) =
        prefs(ctx).edit().putInt("auto_start", start).putInt("auto_end", end).apply()

    /** Is the given hour (0–23) inside the night window? Handles windows that wrap past midnight. */
    fun inNightWindow(ctx: Context, hour: Int): Boolean {
        val s = autoStartHour(ctx); val e = autoEndHour(ctx)
        return when {
            s == e -> true                       // 24h window
            s < e  -> hour in s until e          // same-day window
            else   -> hour >= s || hour < e      // wraps midnight (e.g. 20→6)
        }
    }

    /**
     * Per-app opt-out. Auto-reply is ON for every app by default; the user can switch individual
     * apps off here. We persist only the DISABLED packages.
     */
    private fun disabledApps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("auto_disabled_apps", emptySet()) ?: emptySet()
    fun appAutoEnabled(ctx: Context, pkg: String): Boolean = !disabledApps(ctx).contains(pkg)
    fun setAppAuto(ctx: Context, pkg: String, enabled: Boolean) {
        val cur = HashSet(disabledApps(ctx))
        if (enabled) cur.remove(pkg) else cur.add(pkg)
        prefs(ctx).edit().putStringSet("auto_disabled_apps", cur).apply()
    }

    /** The effective auto-reply state right now: forced on by the night window, else the toggle. */
    fun autonomousEffective(ctx: Context): Boolean {
        if (nightAuto(ctx)) {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (inNightWindow(ctx, h)) return true
        }
        return autonomous(ctx)
    }

    /** When true, a weekly "reach out to these people" nudge is posted. */
    fun reconnectWeekly(ctx: Context): Boolean = prefs(ctx).getBoolean("reconnect_weekly", false)
    fun setReconnectWeekly(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("reconnect_weekly", value).apply()

    /** When true, a spicy take is generated and notified once each morning. */
    fun spicyDaily(ctx: Context): Boolean = prefs(ctx).getBoolean("spicy_daily", false)
    fun setSpicyDaily(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("spicy_daily", value).apply()

    /** When true, Telegram messages are auto-answered from the loaded PDF. */
    fun docTelegram(ctx: Context): Boolean = prefs(ctx).getBoolean("doc_telegram", false)
    fun setDocTelegram(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("doc_telegram", value).apply()

    /** When true, the Telegram bot service runs (reads attachments, answers, ingests PDFs). */
    fun telegramBot(ctx: Context): Boolean = prefs(ctx).getBoolean("telegram_bot", false)
    fun setTelegramBot(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("telegram_bot", value).apply()

    /**
     * When true, the Accessibility service logs on-screen text into InteractionStore for recall.
     * (The OS-level Accessibility permission must also be granted in Settings.)
     */
    fun recallEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("recall_capture", false)
    fun setRecallEnabled(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("recall_capture", value).apply()

    /** When true, a persistent lock-screen notification offers one-tap voice to SlyOS. */
    fun lockVoice(ctx: Context): Boolean = prefs(ctx).getBoolean("lock_voice", false)
    fun setLockVoice(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("lock_voice", value).apply()
}
