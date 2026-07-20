package com.agentos.shell.tools

import android.content.Context
import java.util.Calendar

/**
 * Tracks token spend per provider per month and projects an end-of-month total — so the user always
 * knows roughly what SlyOS is costing them. Estimates: prices are best-effort USD per 1M tokens and
 * are editable as providers change pricing. Costs are stored in micro-dollars (long) to stay exact.
 */
object CostStore {
    // model -> (inputUsdPerMillion, outputUsdPerMillion)
    private val PRICES: Map<String, Pair<Double, Double>> = mapOf(
        "claude-haiku-4-5" to (1.0 to 5.0),
        "claude-sonnet-4-6" to (3.0 to 15.0),
        "claude-opus-4-8" to (15.0 to 75.0),
        "gpt-4o-mini" to (0.15 to 0.60),
        "gpt-4o" to (2.5 to 10.0)
        // Gemini deliberately NOT priced here — see priceFor: SlyOS routes it on the FREE tier.
    )

    /** Must match ModelRouter.PROVIDER_FREE. SlyOS only ever routes these on their free tiers, so billing
     *  them inflates spend and can trip the monthly cap on money that was never actually charged. */
    private val FREE_PROVIDERS = setOf("gemini", "groq", "cerebras", "mistral", "nvidia", "openrouter", "githubmodels", "local")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_cost", Context.MODE_PRIVATE)
    private fun monthKey(): String {
        val c = Calendar.getInstance(); return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}"
    }
    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}-${c.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun priceFor(provider: String, model: String): Pair<Double, Double> {
        // Free tier ALWAYS wins, even if the model name happens to match a priced entry — otherwise a
        // free-tier call gets billed and the budget cap misfires on spend that never happened.
        if (provider in FREE_PROVIDERS) return 0.0 to 0.0
        return PRICES[model] ?: when (provider) {
            "openai" -> 2.5 to 10.0
            else -> 3.0 to 15.0
        }
    }

    /** Public estimate (micro-dollars) for a single call — used by per-employee ledgers. */
    fun estimateMicros(provider: String, model: String, inTok: Int, outTok: Int): Long {
        val (pin, pout) = priceFor(provider, model)
        return ((inTok / 1_000_000.0 * pin + outTok / 1_000_000.0 * pout) * 1_000_000).toLong()
    }

    fun record(ctx: Context, provider: String, model: String, inTok: Int, outTok: Int) {
        val (pin, pout) = priceFor(provider, model)
        val cost = inTok / 1_000_000.0 * pin + outTok / 1_000_000.0 * pout
        val micros = (cost * 1_000_000).toLong()
        val mk = monthKey(); val dk = dayKey(); val p = prefs(ctx)
        p.edit()
            .putLong("calls_$mk", p.getLong("calls_$mk", 0) + 1)
            .putLong("tok_$mk", p.getLong("tok_$mk", 0) + inTok + outTok)
            .putLong("cost_$mk", p.getLong("cost_$mk", 0) + micros)
            .putLong("cost_${mk}_$provider", p.getLong("cost_${mk}_$provider", 0) + micros)
            .putLong("costd_$dk", p.getLong("costd_$dk", 0) + micros)
            // ── Lifetime token ledger (all-time), for the "brain compute" readout ──
            .putLong("life_calls", p.getLong("life_calls", 0) + 1)
            .putLong("life_tok", p.getLong("life_tok", 0) + inTok + outTok)
            .putLong("life_gen", p.getLong("life_gen", 0) + outTok)              // "generated" = output tokens
            .putLong("life_tok_$provider", p.getLong("life_tok_$provider", 0) + inTok + outTok)
            .putLong("life_gen_$provider", p.getLong("life_gen_$provider", 0) + outTok)
            .apply()
    }

    // ── Lifetime compute ledger ──────────────────────────────────────────────────────────────────
    fun lifetimeCalls(ctx: Context): Long = prefs(ctx).getLong("life_calls", 0)
    fun lifetimeTokens(ctx: Context): Long = prefs(ctx).getLong("life_tok", 0)
    fun lifetimeGenerated(ctx: Context): Long = prefs(ctx).getLong("life_gen", 0)
    /** Average total tokens burned per AI response (in + out). */
    fun avgTokensPerResponse(ctx: Context): Int {
        val c = lifetimeCalls(ctx); return if (c > 0) (lifetimeTokens(ctx) / c).toInt() else 0
    }
    /** Lifetime tokens per provider (only those that have been used). */
    fun tokensByProvider(ctx: Context): Map<String, Long> =
        ModelRouter.PROVIDERS.associateWith { prefs(ctx).getLong("life_tok_$it", 0) }.filterValues { it > 0 }
    /** Compact human string like "3.4M tokens" / "812k tokens". */
    fun fmtTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.0fk".format(n / 1_000.0)
        else -> n.toString()
    }

    /** Cost for each day so far this month (index 0 = day 1 … last = today), in USD. */
    fun dailyThisMonth(ctx: Context): List<Double> {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH); val today = c.get(Calendar.DAY_OF_MONTH)
        return (1..today).map { d -> prefs(ctx).getLong("costd_$y-$m-$d", 0) / 1_000_000.0 }
    }
    fun dayOfMonth(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    fun daysInMonth(): Int = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)

    fun monthCostUsd(ctx: Context): Double = prefs(ctx).getLong("cost_${monthKey()}", 0) / 1_000_000.0
    fun monthCalls(ctx: Context): Long = prefs(ctx).getLong("calls_${monthKey()}", 0)
    fun monthCostByProvider(ctx: Context): Map<String, Double> =
        ModelRouter.PROVIDERS.associateWith { prefs(ctx).getLong("cost_${monthKey()}_$it", 0) / 1_000_000.0 }
            .filterValues { it > 0.0 }

    /** Project the full-month total by scaling what's spent so far by how much of the month has elapsed. */
    fun projectedMonthUsd(ctx: Context): Double {
        val c = Calendar.getInstance()
        val day = c.get(Calendar.DAY_OF_MONTH); val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val sofar = monthCostUsd(ctx)
        return if (day <= 0) sofar else sofar * dim / day
    }
}
