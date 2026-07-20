package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SELF-HEALING MODEL NAMES.
 *
 * Hardcoded model IDs rot: providers retire them on their own schedule (Gemini dropped gemini-2.0-flash,
 * Cerebras deprecated llama-3.3-70b / qwen-3-32b in Feb 2026). When that happens every single call to that
 * brain returns 404 forever and the provider is silently dead — which is exactly what happened here
 * (gemini 0 ok / 65 fail, cerebras 0 ok / 44 fail).
 *
 * So instead of trusting a constant, on a "model not found" we ask the provider what it ACTUALLY serves
 * (/v1/models — every provider here exposes it), pick the best match for the tier, cache it for a day, and
 * retry. The hardcoded defaults in ModelRouter stay as the fast path; this is the safety net.
 */
object ModelResolver {
    private const val TAG = "SlyOS-ModelResolver"
    private const val PREFS = "slyos_models"
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True if this error means "that model doesn't exist", as opposed to auth/rate-limit/network. */
    fun isModelGone(code: Int, body: String): Boolean {
        if (code != 404 && code != 400) return false
        val b = body.lowercase()
        return b.contains("model") && (b.contains("not found") || b.contains("does not exist") ||
            b.contains("no longer available") || b.contains("deprecated") || b.contains("unsupported"))
    }

    /** A model we've already resolved for this provider+tier, if still fresh. */
    fun cached(ctx: Context, provider: String, tier: String): String? {
        val v = p(ctx).getString("m_${provider}_$tier", null) ?: return null
        val ts = p(ctx).getLong("t_${provider}_$tier", 0L)
        return if (System.currentTimeMillis() - ts < TTL_MS) v else null
    }

    private fun remember(ctx: Context, provider: String, tier: String, model: String) {
        p(ctx).edit().putString("m_${provider}_$tier", model)
            .putLong("t_${provider}_$tier", System.currentTimeMillis()).apply()
        HealthStore.note("model_resolve", true, "$provider/$tier → $model")
    }

    private fun listUrl(provider: String, key: String): Pair<String, Map<String, String>>? = when (provider) {
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models?key=$key" to emptyMap()
        "cerebras" -> "https://api.cerebras.ai/v1/models" to mapOf("Authorization" to "Bearer $key")
        "groq" -> "https://api.groq.com/openai/v1/models" to mapOf("Authorization" to "Bearer $key")
        "mistral" -> "https://api.mistral.ai/v1/models" to mapOf("Authorization" to "Bearer $key")
        "openai" -> "https://api.openai.com/v1/models" to mapOf("Authorization" to "Bearer $key")
        else -> null
    }

    /** Every model id the provider currently serves. Empty on any failure (caller keeps its default). */
    private fun available(provider: String, key: String): List<String> {
        val (url, headers) = listUrl(provider, key) ?: return emptyList()
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (c.responseCode !in 200..299) return emptyList()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val out = ArrayList<String>()
            // OpenAI-compatible: {data:[{id:…}]}   ·   Gemini: {models:[{name:"models/…"}]}
            o.optJSONArray("data")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(out::add)
            }
            o.optJSONArray("models")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.removePrefix("models/")
                    ?.takeIf { it.isNotBlank() }?.let(out::add)
            }
            out
        } catch (e: Exception) { Log.w(TAG, "list $provider: ${e.message}"); emptyList() }
    }

    /**
     * Pick the best currently-served model for [tier]. Prefers small/fast ids for CHEAP and large/pro ids
     * for HEAVY, and always avoids non-chat models (embeddings, vision-only, audio, image, TTS).
     */
    private fun pick(models: List<String>, tier: String): String? {
        val usable = models.filter { m ->
            val l = m.lowercase()
            !l.contains("embed") && !l.contains("aqa") && !l.contains("tts") && !l.contains("whisper") &&
                !l.contains("image") && !l.contains("vision-only") && !l.contains("audio") &&
                !l.contains("guard") && !l.contains("rerank") && !l.contains("moderation")
        }
        if (usable.isEmpty()) return null
        fun score(m: String): Int {
            val l = m.lowercase()
            var s = 0
            // newer generations first — a bigger leading version number wins
            Regex("(\\d+)\\.(\\d+)").find(l)?.let { s += it.groupValues[1].toInt() * 20 + it.groupValues[2].toInt() * 2 }
            if (l.contains("latest")) s += 6
            when (tier.uppercase()) {
                "CHEAP" -> { if (l.contains("flash") || l.contains("mini") || l.contains("small") || l.contains("8b") || l.contains("lite") || l.contains("instant")) s += 30 }
                "HEAVY" -> { if (l.contains("pro") || l.contains("large") || l.contains("opus") || l.contains("70b") || l.contains("120b") || l.contains("maverick")) s += 30 }
                else -> { if (l.contains("flash") || l.contains("small") || l.contains("versatile") || l.contains("scout")) s += 20 }
            }
            if (l.contains("preview") || l.contains("exp") || l.contains("beta")) s -= 12   // prefer stable
            return s
        }
        return usable.maxByOrNull { score(it) }
    }

    /**
     * Called after a call failed with "model gone". Finds a live replacement for [provider]/[tier],
     * caches it, and returns it — or null if we couldn't reach the provider's model list.
     */
    fun heal(ctx: Context, provider: String, tier: String, key: String): String? {
        if (key.isBlank()) return null
        val models = available(provider, key)
        if (models.isEmpty()) { HealthStore.note("model_resolve", false, "$provider: couldn't list models"); return null }
        val chosen = pick(models, tier) ?: return null
        remember(ctx, provider, tier, chosen)
        Log.i(TAG, "healed $provider/$tier → $chosen")
        return chosen
    }

    /** The model to actually use: a healed/cached one if we have it, else the router's default. */
    fun effective(ctx: Context?, provider: String, tier: String, default: String): String {
        if (ctx == null) return default
        return cached(ctx, provider, tier) ?: default
    }
}
