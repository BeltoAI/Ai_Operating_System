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

    /** Best-effort owner first name from the About text (for tagging imported chats). */
    fun ownerName(ctx: Context): String {
        val pats = listOf(
            Regex("(?i)\\bmy name is\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI'?m\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI am\\s+([A-Z][\\p{L}'’-]+)")
        )
        for (p in pats) p.find(about(ctx))?.groupValues?.get(1)?.let { return it.trim() }
        return ""
    }

    /** A booking/scheduling link (e.g. Calendly) the agent shares when someone wants to talk live. */
    fun bookingLink(ctx: Context): String = prefs(ctx).getString("booking_link", "") ?: ""
    fun setBookingLink(ctx: Context, value: String) = prefs(ctx).edit().putString("booking_link", value.trim()).apply()

    /** Canonical platform key from an app label (so "WhatsApp Business" → whatsapp, etc.). */
    fun platformKey(app: String): String {
        val a = app.lowercase()
        return when {
            a.contains("linkedin") -> "linkedin"
            a.contains("instagram") -> "instagram"
            a.contains("twitter") || a == "x" || a.contains("x.com") -> "x"
            a.contains("reddit") -> "reddit"
            a.contains("whatsapp") -> "whatsapp"
            a.contains("telegram") -> "telegram"
            a.contains("messeng") || a.contains("orca") -> "messenger"
            a.contains("slack") -> "slack"
            a.contains("discord") -> "discord"
            a.contains("sms") || a.contains("messag") || a.contains("mms") -> "sms"
            a.contains("gmail") || a.contains("mail") -> "email"
            else -> a.take(20)
        }
    }

    /** Per-platform persona/tone, e.g. LinkedIn = "professional, warm CEO"; Instagram = "funny". */
    fun styleFor(ctx: Context, app: String): String =
        prefs(ctx).getString("style_${platformKey(app)}", "") ?: ""
    fun setStyleFor(ctx: Context, platformKey: String, value: String) =
        prefs(ctx).edit().putString("style_$platformKey", value.trim()).apply()

    /** A learned "this is how I write" profile, distilled from your real past messages. */
    fun styleProfile(ctx: Context): String = prefs(ctx).getString("style_profile", "") ?: ""
    fun setStyleProfile(ctx: Context, v: String) = prefs(ctx).edit().putString("style_profile", v.trim()).apply()

    /** The booking link to actually use: the explicit field, else auto-detected from your About text. */
    fun effectiveBookingLink(ctx: Context): String {
        val explicit = bookingLink(ctx)
        if (explicit.isNotBlank()) return explicit
        val m = Regex("https?://(?:www\\.)?(?:calendly\\.com|cal\\.com|savvycal\\.com|app\\.usemotion\\.com|tidycal\\.com)/\\S+", RegexOption.IGNORE_CASE)
            .find(about(ctx))
        return m?.value?.trimEnd('.', ',', ')', ' ') ?: ""
    }

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
