package com.agentos.shell.tools

import android.content.Context

/** Persists the job hunt: your base résumé, the target role, and the last posting you're applying to. */
object JobStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_job", Context.MODE_PRIVATE)

    fun resume(ctx: Context): String = prefs(ctx).getString("resume", "") ?: ""
    fun setResume(ctx: Context, v: String) = prefs(ctx).edit().putString("resume", v.trim()).apply()

    fun target(ctx: Context): String = prefs(ctx).getString("target", "") ?: ""
    fun setTarget(ctx: Context, v: String) = prefs(ctx).edit().putString("target", v.trim()).apply()

    fun posting(ctx: Context): String = prefs(ctx).getString("posting", "") ?: ""
    fun setPosting(ctx: Context, v: String) = prefs(ctx).edit().putString("posting", v.trim()).apply()
}
