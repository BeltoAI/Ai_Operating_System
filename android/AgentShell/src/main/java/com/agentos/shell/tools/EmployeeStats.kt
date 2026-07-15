package com.agentos.shell.tools

import android.content.Context

/**
 * The honest receipt for every worker: what they COST (tokens in/out → dollars) versus what they
 * DELIVERED (actions done, approvals you accepted, and an estimate of the minutes they saved you).
 * Lightweight prefs ledger — no DB migration — keyed by employee id. Team totals just sum the roster.
 */
object EmployeeStats {
    private const val PREFS = "slyos_staff_stats"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Stat(
        val inTok: Long = 0, val outTok: Long = 0, val costMicros: Long = 0,
        val actions: Long = 0, val approved: Long = 0, val valueMin: Long = 0, val shifts: Long = 0
    ) {
        val tokens: Long get() = inTok + outTok
        val costUsd: Double get() = costMicros / 1_000_000.0
    }

    /** Rough minutes-of-your-time an action type saves — the "value" side of the ledger. */
    fun valueOf(actionType: String, didWork: Boolean): Int = when (actionType) {
        "send_email" -> 8
        "add_event" -> 4
        "brief", "research", "note" -> 10
        "draft" -> 6
        else -> if (didWork) 3 else 0
    }

    /** Record one shift: token spend (→ cost via CostStore pricing) plus what it produced. */
    fun record(ctx: Context, id: String, provider: String, model: String, inTok: Int, outTok: Int, actions: Int, valueMin: Int) {
        val micros = CostStore.estimateMicros(provider, model, inTok, outTok)
        val e = p(ctx).edit()
        fun bump(k: String, by: Long) { e.putLong("${id}_$k", p(ctx).getLong("${id}_$k", 0) + by) }
        bump("intok", inTok.toLong()); bump("outtok", outTok.toLong()); bump("cost", micros)
        bump("actions", actions.toLong()); bump("valmin", valueMin.toLong()); bump("shifts", 1)
        e.apply()
    }

    /** The owner accepted something this worker proposed — the strongest signal of real value. */
    fun approve(ctx: Context, id: String) {
        p(ctx).edit().putLong("${id}_approved", p(ctx).getLong("${id}_approved", 0) + 1).apply()
    }

    fun stat(ctx: Context, id: String): Stat {
        val pr = p(ctx)
        fun g(k: String) = pr.getLong("${id}_$k", 0)
        return Stat(g("intok"), g("outtok"), g("cost"), g("actions"), g("approved"), g("valmin"), g("shifts"))
    }

    fun team(ctx: Context, ids: List<String>): Stat {
        var s = Stat()
        ids.forEach { id ->
            val x = stat(ctx, id)
            s = Stat(s.inTok + x.inTok, s.outTok + x.outTok, s.costMicros + x.costMicros,
                s.actions + x.actions, s.approved + x.approved, s.valueMin + x.valueMin, s.shifts + x.shifts)
        }
        return s
    }

    fun clear(ctx: Context, id: String) {
        val pr = p(ctx); val e = pr.edit()
        listOf("intok", "outtok", "cost", "actions", "approved", "valmin", "shifts").forEach { e.remove("${id}_$it") }
        e.apply()
    }
}
