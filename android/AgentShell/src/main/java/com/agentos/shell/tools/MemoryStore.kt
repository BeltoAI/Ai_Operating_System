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
}
