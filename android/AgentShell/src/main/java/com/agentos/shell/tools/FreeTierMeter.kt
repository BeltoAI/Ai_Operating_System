package com.agentos.shell.tools

import android.content.Context
import java.util.Calendar

/**
 * "How much free tier is left today?" — counts requests per provider per day (reset at local midnight) and
 * compares against each free tier's published daily request cap. Surfaced in the routing matrix so you can
 * see at a glance which brain still has headroom and re-route before one runs dry. Requests (RPD) are the
 * limit that actually bites first on these tiers, so we track calls, not tokens.
 */
object FreeTierMeter {
    private const val PREF = "slyos_freetier"

    // Approx free DAILY request caps (verify at each console — free tiers shift). null = token-based/unknown.
    val DAILY_CAP: Map<String, Int?> = mapOf(
        "gemini" to 1500,
        "groq" to 1000,
        "cerebras" to 14400,
        "openrouter" to 50,
        "nvidia" to 1000,
        "mistral" to null,        // token-based (~1B/mo) — no clean RPD
        "githubmodels" to null    // varies by account tier
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    fun record(ctx: Context, provider: String) {
        val k = "${dayKey()}_$provider"
        p(ctx).edit().putInt(k, p(ctx).getInt(k, 0) + 1).apply()
    }

    fun usedToday(ctx: Context, provider: String): Int = p(ctx).getInt("${dayKey()}_$provider", 0)
    fun capFor(provider: String): Int? = DAILY_CAP[provider]

    /** Remaining free requests today, or null when the cap is unknown/token-based. */
    fun remaining(ctx: Context, provider: String): Int? {
        val cap = DAILY_CAP[provider] ?: return null
        return (cap - usedToday(ctx, provider)).coerceAtLeast(0)
    }

    /** Compact "37/1500" (or "37" when no cap) for the matrix UI. */
    fun label(ctx: Context, provider: String): String {
        val used = usedToday(ctx, provider)
        val cap = DAILY_CAP[provider]
        return if (cap != null) "$used/$cap" else "$used"
    }
}
