package com.agentos.shell.tools

import android.content.Context

/**
 * Short-term memory of which providers are currently rate-limited, so the cascade doesn't waste a round-trip
 * re-hitting a brain that just said "429" on every single call. When a provider returns a rate-limit / quota
 * error we park it for a cooldown; the router sorts parked providers to the BACK (never fully drops them — if
 * everything's parked we still try, since a daily cap may have reset). A success clears the flag immediately.
 *
 * The cooldown is deliberately short (10 min): a per-minute cap heals well within it, and a daily cap simply
 * gets re-parked after one cheap probe every 10 min instead of being hammered on every request.
 */
object ProviderLimit {
    private const val PREF = "slyos_provider_limit"
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hit(ctx: Context, provider: String) =
        p(ctx).edit().putLong("until_$provider", System.currentTimeMillis() + COOLDOWN_MS).apply()

    fun limited(ctx: Context, provider: String): Boolean =
        p(ctx).getLong("until_$provider", 0L) > System.currentTimeMillis()

    fun clear(ctx: Context, provider: String) = p(ctx).edit().remove("until_$provider").apply()

    /** True when the text/code looks like a rate-limit / quota exhaustion (any provider). */
    fun isRateLimit(code: Int, text: String): Boolean =
        code == 429 || text.contains("RESOURCE_EXHAUSTED", true) || text.contains("quota", true) ||
        text.contains("rate limit", true) || text.contains("rate_limit", true) || text.contains("too many requests", true)
}
