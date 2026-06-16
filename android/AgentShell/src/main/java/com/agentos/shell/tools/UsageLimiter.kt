package com.agentos.shell.tools

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-day cap on expensive (Opus) actions, so you don't blow through API credits. */
object UsageLimiter {
    private const val PREF = "slyos_usage"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun key(k: String) = "${k}_${today()}"

    fun remaining(ctx: Context, k: String, cap: Int): Int =
        (cap - prefs(ctx).getInt(key(k), 0)).coerceAtLeast(0)

    /** Try to consume one use. Returns true if allowed (and records it), false if over the cap. */
    fun use(ctx: Context, k: String, cap: Int): Boolean {
        val kk = key(k); val used = prefs(ctx).getInt(kk, 0)
        if (used >= cap) return false
        prefs(ctx).edit().putInt(kk, used + 1).apply()
        return true
    }
}
