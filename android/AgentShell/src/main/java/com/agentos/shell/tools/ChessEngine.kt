package com.agentos.shell.tools

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-move oracle backed by a hosted Stockfish endpoint. Given a FEN and a target strength (Elo), it
 * returns the move to play. Stockfish is superhuman; we scale search depth to the Elo slider as a strength
 * knob (low Elo = shallow search = human-ish moves). Runs over HTTPS — no native engine to embed. An
 * offline on-device engine can slot in behind the same interface later.
 */
object ChessEngine {
    private const val TAG = "SlyOS"
    // Public Stockfish REST endpoint (POST {fen, depth} → {move, san, eval|mate}).
    private const val URL_API = "https://chess-api.com/v1"

    data class Move(val uci: String, val from: String, val to: String, val san: String, val evalText: String)

    // Depth ↔ strength per chess-api docs: depth 12≈2350, 18≈2750 (free-tier max). Below that, depth scales
    // down toward club level. Max 18, so the true ceiling is ~2750 — anything higher on the slider clamps here.
    private fun depthFor(elo: Int): Int = when {
        elo >= 2750 -> 18; elo >= 2600 -> 16; elo >= 2450 -> 14; elo >= 2300 -> 12
        elo >= 2100 -> 10; elo >= 1900 -> 8; elo >= 1650 -> 6; elo >= 1350 -> 4; elo >= 1000 -> 3; else -> 2
    }

    /** At lower Elo, deliberately pick a weaker candidate sometimes so the coach actually plays down. */
    private fun pickIndex(elo: Int, n: Int): Int {
        if (n <= 1) return 0
        val weakness = ((2700 - elo).toDouble().coerceIn(0.0, 2000.0)) / 2000.0   // 0 at ≥2700, 1 at ≤700
        return if (Math.random() < weakness * 0.75) 1 + (Math.random() * (n - 1)).toInt() else 0
    }

    private fun parseMove(o: JSONObject): Move? {
        val uci = o.optString("move").ifBlank { o.optString("lan").ifBlank { o.optString("from") + o.optString("to") } }
        if (uci.length < 4) return null
        val from = o.optString("from").ifBlank { uci.substring(0, 2) }
        val to = o.optString("to").ifBlank { uci.substring(2, 4) }
        val san = o.optString("san").ifBlank { uci }
        val evalText = when {
            o.has("mate") && !o.isNull("mate") && o.optInt("mate") != 0 -> "mate in ${Math.abs(o.optInt("mate"))}"
            o.has("eval") -> { val e = o.optDouble("eval", 0.0); (if (e >= 0) "+" else "") + String.format("%.1f", e) }
            else -> ""
        }
        return Move(uci, from, to, san, evalText)
    }

    fun bestMove(fen: String, elo: Int): Move? {
        return try {
            val c = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("fen", fen).put("depth", depthFor(elo)).put("variants", 5).put("maxThinkingTime", 60).toString()
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) { Log.w(TAG, "chess engine $code: ${txt.take(120)}"); return null }
            // Response is either a single move object or an array of candidate moves (best first).
            val candidates = ArrayList<Move>()
            val trimmed = txt.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { parseMove(it)?.let { m -> candidates.add(m) } }
            } else parseMove(JSONObject(trimmed))?.let { candidates.add(it) }
            if (candidates.isEmpty()) return null
            candidates[pickIndex(elo, candidates.size)]
        } catch (e: Exception) { Log.w(TAG, "chess engine fail: ${e.message}"); null }
    }
}
