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
        "gpt-4o" to (2.5 to 10.0),
        "gemini-2.0-flash" to (0.10 to 0.40),
        "gemini-1.5-pro-latest" to (1.25 to 5.0)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_cost", Context.MODE_PRIVATE)
    private fun monthKey(): String {
        val c = Calendar.getInstance(); return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}"
    }

    private fun priceFor(provider: String, model: String): Pair<Double, Double> =
        PRICES[model] ?: when (provider) {
            "gemini" -> 0.10 to 0.40
            "openai" -> 2.5 to 10.0
            else -> 3.0 to 15.0
        }

    fun record(ctx: Context, provider: String, model: String, inTok: Int, outTok: Int) {
        val (pin, pout) = priceFor(provider, model)
        val cost = inTok / 1_000_000.0 * pin + outTok / 1_000_000.0 * pout
        val micros = (cost * 1_000_000).toLong()
        val mk = monthKey(); val p = prefs(ctx)
        p.edit()
            .putLong("calls_$mk", p.getLong("calls_$mk", 0) + 1)
            .putLong("tok_$mk", p.getLong("tok_$mk", 0) + inTok + outTok)
            .putLong("cost_$mk", p.getLong("cost_$mk", 0) + micros)
            .putLong("cost_${mk}_$provider", p.getLong("cost_${mk}_$provider", 0) + micros)
            .apply()
    }

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
