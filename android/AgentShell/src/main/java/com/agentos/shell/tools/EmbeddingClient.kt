package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns text into vectors so the brain can retrieve by MEANING, not just shared keywords ("the deal"
 * finds "the acquisition"). Uses the user's existing key — Gemini's text-embedding-004 (free tier)
 * preferred, else OpenAI. Anthropic has no first-party embeddings, so Gemini/OpenAI handle this even
 * for a Claude-primary user. Returns null on any failure so the keyword path always still works.
 */
object EmbeddingClient {
    private const val TAG = "SlyOS"

    /** Last embedding error (surfaced in settings so failures aren't silent). */
    @Volatile var lastError: String = ""

    /** Which provider+model will embed, given the user's keys. null = none available. */
    fun provider(ctx: Context): String? = when {
        MemoryStore.geminiKey(ctx).isNotBlank() -> "gemini"
        MemoryStore.openaiKey(ctx).isNotBlank() -> "openai"
        else -> null
    }
    private const val GEMINI_MODEL = "gemini-embedding-001"
    fun model(provider: String): String = if (provider == "openai") "text-embedding-3-small" else GEMINI_MODEL

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("content-type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to raw
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    /** Embed a batch of texts. taskType is RETRIEVAL_DOCUMENT for stored memories, RETRIEVAL_QUERY for
     *  a search query (Gemini tunes the vectors so the two compare well). Null on failure. */
    fun embed(ctx: Context, texts: List<String>, taskType: String = "RETRIEVAL_DOCUMENT"): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val p = provider(ctx) ?: return null
        return if (p == "openai") embedOpenAi(ctx, texts) else embedGemini(ctx, texts, taskType)
    }

    private fun embedGemini(ctx: Context, texts: List<String>, taskType: String): List<FloatArray>? {
        val key = MemoryStore.geminiKey(ctx)
        val reqs = JSONArray()
        texts.forEach { t ->
            reqs.put(JSONObject().put("model", "models/$GEMINI_MODEL")
                .put("taskType", taskType)
                .put("outputDimensionality", 768)
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t.take(8000))))))
        }
        val body = JSONObject().put("requests", reqs).toString()
        val (code, raw) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:batchEmbedContents?key=$key",
            emptyMap(), body)
        if (code !in 200..299) { lastError = "Gemini embed $code: ${raw.take(180)}"; Log.w(TAG, lastError); return null }
        return try {
            val arr = JSONObject(raw).getJSONArray("embeddings")
            val out = (0 until arr.length()).map { i ->
                val v = arr.getJSONObject(i).getJSONArray("values")
                FloatArray(v.length()) { v.getDouble(it).toFloat() }
            }
            lastError = ""; out
        } catch (e: Exception) { lastError = "Gemini parse: ${e.message}"; null }
    }

    private fun embedOpenAi(ctx: Context, texts: List<String>): List<FloatArray>? {
        val key = MemoryStore.openaiKey(ctx)
        val input = JSONArray(); texts.forEach { input.put(it.take(8000)) }
        val body = JSONObject().put("model", "text-embedding-3-small").put("input", input).toString()
        val (code, raw) = post("https://api.openai.com/v1/embeddings", mapOf("authorization" to "Bearer $key"), body)
        if (code !in 200..299) { lastError = "OpenAI embed $code: ${raw.take(180)}"; Log.w(TAG, lastError); return null }
        return try {
            val arr = JSONObject(raw).getJSONArray("data")
            val out = (0 until arr.length()).map { i ->
                val v = arr.getJSONObject(i).getJSONArray("embedding")
                FloatArray(v.length()) { v.getDouble(it).toFloat() }
            }
            lastError = ""; out
        } catch (e: Exception) { lastError = "OpenAI parse: ${e.message}"; null }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = Math.sqrt((na * nb).toDouble()).toFloat()
        return if (d == 0f) -1f else dot / d
    }
}
