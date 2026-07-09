package com.agentos.shell.tools

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Confirms an API key is actually accepted by its provider — so Settings can show a real "✓ Valid" or
 * "✗ Invalid" instead of leaving you guessing whether a paste worked. Each check is a cheap, read-only
 * call (a models list or a tiny quote), never anything billable of note. Run from a background thread.
 */
object KeyValidator {
    private const val TAG = "SlyOS"

    enum class State { EMPTY, CHECKING, VALID, INVALID, ERROR }

    fun check(provider: String, key: String): State {
        if (key.isBlank()) return State.EMPTY
        return when (provider) {
            "gemini" -> gemini(key)
            "anthropic" -> anthropic(key)
            "openai" -> openai(key)
            "finnhub" -> finnhub(key)
            "github" -> github(key)
            "elevenlabs" -> elevenlabs(key)
            else -> State.ERROR
        }
    }

    private fun gemini(key: String): State =
        classify(get("https://generativelanguage.googleapis.com/v1beta/models?key=$key", emptyMap()))

    private fun openai(key: String): State =
        classify(get("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key")))

    private fun anthropic(key: String): State =
        classify(get("https://api.anthropic.com/v1/models",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")))

    private fun finnhub(key: String): State =
        classify(get("https://finnhub.io/api/v1/quote?symbol=AAPL&token=$key", emptyMap()))

    private fun github(key: String): State =
        classify(get("https://api.github.com/user",
            mapOf("Authorization" to "Bearer $key", "User-Agent" to "SlyOS", "Accept" to "application/vnd.github+json")))

    private fun elevenlabs(key: String): State =
        classify(get("https://api.elevenlabs.io/v1/user", mapOf("xi-api-key" to key)))

    /** 200s = valid; explicit auth rejections = invalid; anything else (network, rate limit) = error. */
    private fun classify(code: Int): State = when {
        code in 200..299 -> State.VALID
        code == 400 || code == 401 || code == 403 -> State.INVALID
        code < 0 -> State.ERROR
        else -> State.ERROR
    }

    private fun get(url: String, headers: Map<String, String>): Int {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = c.responseCode
            try { (if (code in 200..299) c.inputStream else c.errorStream)?.close() } catch (e: Exception) {}
            code
        } catch (e: Exception) { Log.w(TAG, "key check failed: ${e.message}"); -1 }
    }
}
