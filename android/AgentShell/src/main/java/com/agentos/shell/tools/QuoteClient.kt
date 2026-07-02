package com.agentos.shell.tools

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free live stock quotes for the practice portfolio — no API key. Tries Yahoo Finance's public chart
 * endpoint, then falls back to Stooq's CSV. Returns null if a symbol can't be priced.
 */
object QuoteClient {
    private fun http(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    data class Quote(val price: Double, val prevClose: Double, val state: String)

    /** Rich quote from Yahoo's chart endpoint: latest price (intraday when the market's open), the
     *  previous close (for day-change), and market state (REGULAR / CLOSED / PRE / POST). */
    // Crypto → CoinGecko (free, no key, trades 24/7 so it always moves — proves the live pipe).
    private val CG = mapOf(
        "BTC-USD" to "bitcoin", "ETH-USD" to "ethereum", "SOL-USD" to "solana", "DOGE-USD" to "dogecoin",
        "XRP-USD" to "ripple", "ADA-USD" to "cardano", "BNB-USD" to "binancecoin", "LTC-USD" to "litecoin",
        "MATIC-USD" to "matic-network", "DOT-USD" to "polkadot", "AVAX-USD" to "avalanche-2", "LINK-USD" to "chainlink")
    private fun fromCoinGecko(symbol: String): Quote? = try {
        val id = CG[symbol.uppercase()] ?: return null
        val body = http("https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=usd&include_24hr_change=true") ?: return null
        val o = JSONObject(body).optJSONObject(id) ?: return null
        val p = o.optDouble("usd", 0.0); val chg = o.optDouble("usd_24h_change", 0.0)
        if (p > 0) Quote(p, p / (1 + chg / 100.0), "REGULAR") else null
    } catch (e: Exception) { null }

    fun quote(symbol: String): Quote? {
        val s = symbol.trim().uppercase(); if (s.isBlank()) return null
        if (CG.containsKey(s)) fromCoinGecko(s)?.let { return it }
        // Try both Yahoo hosts (one is often throttled while the other works).
        for (host in listOf("query1", "query2")) {
            val body = http("https://$host.finance.yahoo.com/v8/finance/chart/$s?interval=1m&range=1d") ?: continue
            try {
                val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = res.getJSONObject("meta")
                var p = meta.optDouble("regularMarketPrice", 0.0)
                val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", 0.0))
                if (p <= 0) {   // fall back to the latest non-null 1-minute close
                    val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
                    if (closes != null) for (i in closes.length() - 1 downTo 0) if (!closes.isNull(i)) { p = closes.getDouble(i); break }
                }
                val state = meta.optString("marketState", "").ifBlank { "CLOSED" }
                if (p > 0) return Quote(p, if (prev > 0) prev else p, state)
            } catch (e: Exception) { /* try next host */ }
        }
        return stooqQuote(s)
    }

    private fun stooqQuote(symbol: String): Quote? = fromStooq(symbol)?.let { Quote(it, it, "CLOSED") }

    private fun fromYahoo(symbol: String): Double? = quote(symbol)?.price

    private fun fromStooq(symbol: String): Double? = try {
        // CSV: Symbol,Date,Time,Open,High,Low,Close,Volume
        val body = http("https://stooq.com/q/l/?s=${symbol.lowercase()}.us&f=sd2t2ohlcv&h&e=csv") ?: return null
        val line = body.trim().lines().lastOrNull { it.contains(",") } ?: return null
        val cols = line.split(",")
        val close = cols.getOrNull(6)?.toDoubleOrNull()
        if (close != null && close > 0) close else null
    } catch (e: Exception) { null }

    fun price(symbol: String): Double? {
        val s = symbol.trim().uppercase(); if (s.isBlank()) return null
        return fromYahoo(s) ?: fromStooq(s)
    }

    /** Price several symbols; missing ones are omitted. */
    fun prices(symbols: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (s in symbols.distinct()) { price(s)?.let { out[s.uppercase()] = it } }
        return out
    }

    /** Rich quotes for several symbols; missing ones are omitted. */
    fun quotes(symbols: List<String>): Map<String, Quote> {
        val out = LinkedHashMap<String, Quote>()
        for (s in symbols.distinct()) { quote(s)?.let { out[s.uppercase()] = it } }
        return out
    }
}
