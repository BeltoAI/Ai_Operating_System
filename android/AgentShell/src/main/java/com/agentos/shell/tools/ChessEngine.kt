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

    private fun depthFor(elo: Int): Int = when {
        elo < 700 -> 1; elo < 1000 -> 3; elo < 1400 -> 5; elo < 1800 -> 7
        elo < 2200 -> 9; elo < 2700 -> 11; elo < 3200 -> 13; else -> 15
    }

    fun bestMove(fen: String, elo: Int): Move? {
        return try {
            val c = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("fen", fen).put("depth", depthFor(elo)).toString()
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) { Log.w(TAG, "chess engine $code: ${txt.take(120)}"); return null }
            val o = JSONObject(txt)
            val uci = o.optString("move").ifBlank { (o.optString("from") + o.optString("to")) }
            if (uci.length < 4) return null
            val from = o.optString("from").ifBlank { uci.substring(0, 2) }
            val to = o.optString("to").ifBlank { uci.substring(2, 4) }
            val san = o.optString("san").ifBlank { uci }
            val evalText = when {
                o.has("mate") && !o.isNull("mate") -> "mate in ${o.optInt("mate")}"
                o.has("eval") -> { val e = o.optDouble("eval", 0.0); (if (e >= 0) "+" else "") + String.format("%.1f", e) }
                else -> ""
            }
            Move(uci, from, to, san, evalText)
        } catch (e: Exception) { Log.w(TAG, "chess engine fail: ${e.message}"); null }
    }
}
