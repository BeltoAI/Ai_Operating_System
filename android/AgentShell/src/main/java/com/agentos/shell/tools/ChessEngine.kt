package com.agentos.shell.tools

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Best-move oracle backed by a hosted Stockfish endpoint (stockfish.online — a free Stockfish 16 REST API).
 * Given a FEN + target strength (Elo → search depth), returns the move to play. We only query once per
 * distinct position (one request per move), so usage stays low. If a free endpoint is ever exhausted, this
 * one interface makes it trivial to swap in another host or an on-device engine.
 */
object ChessEngine {
    private const val TAG = "SlyOS"
    private const val BASE = "https://stockfish.online/api/s/v2.php"

    @Volatile var lastError: String = ""

    data class Move(val uci: String, val from: String, val to: String, val san: String, val evalText: String)

    // stockfish.online caps depth at 15. Map Elo → depth as the strength knob (still strong even shallow).
    private fun depthFor(elo: Int): Int = when {
        elo >= 2600 -> 15; elo >= 2400 -> 14; elo >= 2200 -> 12; elo >= 2000 -> 10
        elo >= 1800 -> 8; elo >= 1550 -> 6; elo >= 1300 -> 4; elo >= 1000 -> 3; else -> 2
    }

    fun bestMove(fen: String, elo: Int): Move? {
        return try {
            val url = "$BASE?fen=" + URLEncoder.encode(fen, "UTF-8") + "&depth=" + depthFor(elo)
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 12000; readTimeout = 20000
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) { lastError = "HTTP $code ${txt.take(70)}"; return null }
            val o = JSONObject(txt)
            if (!o.optBoolean("success", false)) { lastError = (o.optString("data").ifBlank { "engine error" }).take(70); return null }
            val bm = o.optString("bestmove")                 // e.g. "bestmove e2e4 ponder d7d5"
            val uci = bm.split(" ").getOrNull(1)?.trim().orEmpty()
            if (uci.length < 4) { lastError = "no move: ${bm.take(50)}"; return null }
            val from = uci.substring(0, 2); val to = uci.substring(2, 4)
            val evalText = when {
                !o.isNull("mate") && o.optInt("mate", 0) != 0 -> "mate in ${Math.abs(o.optInt("mate"))}"
                else -> { val e = o.optDouble("evaluation", 0.0); (if (e >= 0) "+" else "") + String.format("%.1f", e) }
            }
            lastError = ""
            Move(uci, from, to, "", evalText)
        } catch (e: Exception) { lastError = e.message ?: "network error"; Log.w(TAG, "chess engine fail: ${e.message}"); null }
    }
}
