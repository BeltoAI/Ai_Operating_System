package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A PRACTICE (paper) trading account — fake money so the agent can build and run a real portfolio and
 * we can watch how it performs before any real brokerage/licensing. Buys are always user-confirmed;
 * everything is stored locally. Growth is persisted so the website can pull it.
 */
object TradeStore {
    private const val PREF = "slyos_trade"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class Holding(val symbol: String, val name: String, val shares: Double, val avgCost: Double)
    data class Trade(val ts: Long, val symbol: String, val action: String, val shares: Double, val price: Double)

    fun started(ctx: Context): Boolean = prefs(ctx).getBoolean("started", false)
    fun risk(ctx: Context): String = prefs(ctx).getString("risk", "balanced") ?: "balanced"
    fun setRisk(ctx: Context, r: String) = prefs(ctx).edit().putString("risk", r).apply()
    fun interests(ctx: Context): String = prefs(ctx).getString("interests", "") ?: ""
    fun setInterests(ctx: Context, v: String) = prefs(ctx).edit().putString("interests", v.trim()).apply()

    fun deposited(ctx: Context): Double = prefs(ctx).getFloat("deposited", 0f).toDouble()
    fun cash(ctx: Context): Double = prefs(ctx).getFloat("cash", 0f).toDouble()

    fun deposit(ctx: Context, amount: Double) {
        prefs(ctx).edit()
            .putFloat("deposited", (deposited(ctx) + amount).toFloat())
            .putFloat("cash", (cash(ctx) + amount).toFloat())
            .putBoolean("started", true).apply()
    }

    fun holdings(ctx: Context): List<Holding> = try {
        val arr = JSONArray(prefs(ctx).getString("holdings", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Holding(o.getString("s"), o.optString("n"), o.getDouble("q"), o.getDouble("c")) }
    } catch (e: Exception) { emptyList() }

    private fun saveHoldings(ctx: Context, list: List<Holding>) {
        val arr = JSONArray()
        list.filter { it.shares > 0.0000001 }.forEach { arr.put(JSONObject().put("s", it.symbol).put("n", it.name).put("q", it.shares).put("c", it.avgCost)) }
        prefs(ctx).edit().putString("holdings", arr.toString()).apply()
    }

    private fun logTrade(ctx: Context, t: Trade) {
        val arr = try { JSONArray(prefs(ctx).getString("trades", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("ts", t.ts).put("s", t.symbol).put("a", t.action).put("q", t.shares).put("p", t.price))
        // keep last 200
        val start = maxOf(0, arr.length() - 200)
        val trimmed = JSONArray(); for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs(ctx).edit().putString("trades", trimmed.toString()).apply()
    }

    /** Buy shares (user-confirmed). Returns true if there was enough cash. */
    fun buy(ctx: Context, symbol: String, name: String, shares: Double, price: Double): Boolean {
        val cost = shares * price
        if (shares <= 0 || cost > cash(ctx) + 0.01) return false
        val h = holdings(ctx).toMutableList()
        val i = h.indexOfFirst { it.symbol.equals(symbol, true) }
        if (i >= 0) {
            val cur = h[i]; val newShares = cur.shares + shares
            val newAvg = (cur.shares * cur.avgCost + cost) / newShares
            h[i] = cur.copy(shares = newShares, avgCost = newAvg, name = cur.name.ifBlank { name })
        } else h.add(Holding(symbol.uppercase(), name, shares, price))
        saveHoldings(ctx, h)
        prefs(ctx).edit().putFloat("cash", (cash(ctx) - cost).toFloat()).apply()
        logTrade(ctx, Trade(System.currentTimeMillis(), symbol.uppercase(), "buy", shares, price))
        return true
    }

    /** Sell shares at [price]. */
    fun sell(ctx: Context, symbol: String, shares: Double, price: Double): Boolean {
        val h = holdings(ctx).toMutableList()
        val i = h.indexOfFirst { it.symbol.equals(symbol, true) }; if (i < 0) return false
        val sellQ = minOf(shares, h[i].shares)
        h[i] = h[i].copy(shares = h[i].shares - sellQ)
        saveHoldings(ctx, h)
        prefs(ctx).edit().putFloat("cash", (cash(ctx) + sellQ * price).toFloat()).apply()
        logTrade(ctx, Trade(System.currentTimeMillis(), symbol.uppercase(), "sell", sellQ, price))
        return true
    }

    /** Persist the latest total value + growth% so pull_stats.sh can surface it on the website. */
    fun saveSnapshot(ctx: Context, totalValue: Double) {
        val dep = deposited(ctx)
        val growth = if (dep > 0) (totalValue - dep) / dep * 100.0 else 0.0
        prefs(ctx).edit().putFloat("last_value", totalValue.toFloat()).putFloat("growth_pct", growth.toFloat())
            .putLong("last_value_ts", System.currentTimeMillis()).apply()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
}
