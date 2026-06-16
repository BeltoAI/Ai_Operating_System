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

    /** When true, the agent auto-replies to incoming messages (after a short undo window). */
    fun autonomous(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)
    fun setAutonomous(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, value).apply()

    /** When true, a spicy take is generated and notified once each morning. */
    fun spicyDaily(ctx: Context): Boolean = prefs(ctx).getBoolean("spicy_daily", false)
    fun setSpicyDaily(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("spicy_daily", value).apply()

    /** When true, Telegram messages are auto-answered from the loaded PDF. */
    fun docTelegram(ctx: Context): Boolean = prefs(ctx).getBoolean("doc_telegram", false)
    fun setDocTelegram(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("doc_telegram", value).apply()

    /** When true, the Telegram bot service runs (reads attachments, answers, ingests PDFs). */
    fun telegramBot(ctx: Context): Boolean = prefs(ctx).getBoolean("telegram_bot", false)
    fun setTelegramBot(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("telegram_bot", value).apply()
}
