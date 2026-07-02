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
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SlyOS")
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    data class Quote(val price: Double, val prevClose: Double, val state: String)

    /** Rich quote from Yahoo's chart endpoint: latest price (intraday when the market's open), the
     *  previous close (for day-change), and market state (REGULAR / CLOSED / PRE / POST). */
    fun quote(symbol: String): Quote? {
        val s = symbol.trim().uppercase(); if (s.isBlank()) return null
        return try {
            val body = http("https://query1.finance.yahoo.com/v8/finance/chart/$s?interval=1m&range=1d") ?: return stooqQuote(s)
            val meta = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
            val p = meta.optDouble("regularMarketPrice", 0.0)
            val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", p))
            val state = meta.optString("marketState", "").ifBlank { "CLOSED" }
            if (p > 0) Quote(p, if (prev > 0) prev else p, state) else stooqQuote(s)
        } catch (e: Exception) { stooqQuote(s) }
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
