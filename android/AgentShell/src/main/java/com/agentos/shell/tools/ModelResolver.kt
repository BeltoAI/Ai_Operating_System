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

    /**
     * True if this error means "we can't use that model" — it's retired, or it exists but this account
     * isn't entitled to it (Cerebras returns 402 Payment Required for its paid-only models, which a
     * catalogue listing can't tell you). Both cases mean: pick a different model, don't kill the provider.
     */
    fun isModelGone(code: Int, body: String): Boolean {
        if (code == 402) return true                       // entitlement — model exists, we can't call it
        if (code != 404 && code != 400) return false
        val b = body.lowercase()
        return b.contains("model") && (b.contains("not found") || b.contains("does not exist") ||
            b.contains("no longer available") || b.contains("deprecated") || b.contains("unsupported")) ||
            b.contains("payment required") || b.contains("does not have access")
    }

    // ── blacklist: models this key provably cannot use, so we never re-pick them ───────────────────
    private fun blKey(provider: String) = "bl_$provider"
    fun blacklist(ctx: Context, provider: String, model: String) {
        if (model.isBlank()) return
        val cur = p(ctx).getStringSet(blKey(provider), emptySet())?.toMutableSet() ?: mutableSetOf()
        if (cur.add(model.lowercase())) {
            p(ctx).edit().putStringSet(blKey(provider), cur).apply()
            HealthStore.note("model_blacklist", false, "$provider: $model unusable")
        }
    }
    fun blacklisted(ctx: Context, provider: String): Set<String> =
        p(ctx).getStringSet(blKey(provider), emptySet()) ?: emptySet()

    /** A model we've already resolved for this provider+tier, if still fresh. */
    fun cached(ctx: Context, provider: String, tier: String): String? {
        val v = p(ctx).getString("m_${provider}_$tier", null) ?: return null
        val ts = p(ctx).getLong("t_${provider}_$tier", 0L)
        return if (System.currentTimeMillis() - ts < TTL_MS) v else null
    }

    /**
     * Pin a model we PROVED works with a real call. Applied to every tier of this provider, because a
     * provider whose configured model is dead is usually dead on all tiers (Cerebras 402'd on paid models
     * across the board) — and a working model beats a configured-but-broken one at any tier.
     */
    fun pin(ctx: Context, provider: String, tier: String, model: String) {
        remember(ctx, provider, tier, model)
        listOf("CHEAP", "STANDARD", "HEAVY").filter { it != tier }.forEach { t ->
            if (cached(ctx, provider, t) == null) remember(ctx, provider, t, model)
        }
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
            // Gemini: a model being LISTED does not mean it can answer a prompt — the catalogue also
            // contains embed/vision/tuning-only models, and calling generateContent on those 404s with
            // "no longer available". Only keep models that declare generateContent support.
            o.optJSONArray("models")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = a.optJSONObject(i) ?: continue
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    if (methods != null) {
                        var canChat = false
                        for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") canChat = true
                        if (!canChat) continue
                    }
                    m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            out
        } catch (e: Exception) { Log.w(TAG, "list $provider: ${e.message}"); emptyList() }
    }

    /**
     * Pick the best currently-served model for [tier]. Prefers small/fast ids for CHEAP and large/pro ids
     * for HEAVY, and always avoids non-chat models (embeddings, vision-only, audio, image, TTS).
     */
    /**
     * After a 402 (entitlement), the biggest/newest model is exactly the WRONG next guess — paid tiers are
     * where the premium models live. This ranks SMALL/older models first, which is what a free key can use.
     * That's why Cerebras kept landing on zai-glm-4.7 and failing: version-number scoring favoured it.
     */
    fun cheapestCandidates(ctx: Context, provider: String, key: String, n: Int = 5): List<String> {
        if (key.isBlank()) return emptyList()
        val bl = blacklisted(ctx, provider)
        return available(provider, key)
            .filter { m ->
                val l = m.lowercase()
                l !in bl && !l.contains("embed") && !l.contains("tts") && !l.contains("whisper") &&
                    !l.contains("image") && !l.contains("audio") && !l.contains("guard") &&
                    !l.contains("rerank") && !l.contains("moderation")
            }
            .sortedBy { m ->
                val l = m.lowercase()
                var s = 0
                // smaller parameter counts first — these are the ones free tiers actually serve
                Regex("(\\d+)\\s*b\\b").find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { s += it }
                if (l.contains("8b") || l.contains("mini") || l.contains("small") || l.contains("lite") ||
                    l.contains("instant") || l.contains("flash")) s -= 50
                if (l.contains("70b") || l.contains("120b") || l.contains("405b") ||
                    l.contains("pro") || l.contains("max") || l.contains("opus")) s += 100
                s
            }
            .take(n)
    }

    private fun pick(models: List<String>, tier: String, exclude: Set<String> = emptySet()): String? {
        val usable = models.filter { m ->
            val l = m.lowercase()
            l !in exclude &&
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
        val chosen = pick(models, tier, blacklisted(ctx, provider)) ?: run {
            HealthStore.note("model_resolve", false, "$provider: every candidate model is unusable"); return null
        }
        remember(ctx, provider, tier, chosen)
        Log.i(TAG, "healed $provider/$tier → $chosen")
        return chosen
    }

    /** Ranked candidates for [tier], best first, skipping anything known-unusable. For call-and-verify healing. */
    fun candidates(ctx: Context, provider: String, tier: String, key: String, n: Int = 4): List<String> {
        if (key.isBlank()) return emptyList()
        val bl = blacklisted(ctx, provider)
        val models = available(provider, key)
        val out = ArrayList<String>()
        val pool = models.toMutableList()
        repeat(n) {
            val next = pick(pool, tier, bl + out.map { m -> m.lowercase() }.toSet()) ?: return@repeat
            out.add(next); pool.remove(next)
        }
        return out
    }

    /** The model to actually use: a healed/cached one if we have it, else the router's default. */
    fun effective(ctx: Context?, provider: String, tier: String, default: String): String {
        if (ctx == null) return default
        return cached(ctx, provider, tier) ?: default
    }
}
