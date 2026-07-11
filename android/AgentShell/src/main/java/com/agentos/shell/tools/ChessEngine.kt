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

    @Volatile var lastError: String = ""   // last failure reason, surfaced in the coach pill for diagnosis

    // Depth ↔ strength per chess-api docs: depth 12≈2350, 18≈2750 (free-tier max). Below that, depth scales
    // down toward club level. Max 18, so the true ceiling is ~2750 — anything higher on the slider clamps here.
    private fun depthFor(elo: Int): Int = when {
        elo >= 2750 -> 18; elo >= 2600 -> 16; elo >= 2450 -> 14; elo >= 2300 -> 12
        elo >= 2100 -> 10; elo >= 1900 -> 8; elo >= 1650 -> 6; elo >= 1350 -> 4; elo >= 1000 -> 3; else -> 2
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
            // Minimal, proven request. (Adding `variants` makes the endpoint STREAM multiple objects, which
            // isn't valid single JSON and broke parsing — so we keep it to one clean best-move response.)
            val body = JSONObject().put("fen", fen).put("depth", depthFor(elo)).toString()
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) { lastError = "HTTP $code ${txt.take(80)}"; Log.w(TAG, "chess engine $code: ${txt.take(120)}"); return null }
            // Bulletproof: scan out EVERY top-level {…} object (handles a single object OR a streamed set),
            // parse each, and use the last valid move (the final/best one).
            var best: Move? = null
            var depthB = 0; var start = -1
            val s = txt
            for (idx in s.indices) {
                when (s[idx]) {
                    '{' -> { if (depthB == 0) start = idx; depthB++ }
                    '}' -> { depthB--; if (depthB == 0 && start >= 0) { try { parseMove(JSONObject(s.substring(start, idx + 1)))?.let { best = it } } catch (e: Exception) {}; start = -1 } }
                }
            }
            if (best == null) lastError = "no move in reply: ${txt.take(80)}" else lastError = ""
            best
        } catch (e: Exception) { lastError = e.message ?: "network error"; Log.w(TAG, "chess engine fail: ${e.message}"); null }
    }
}
