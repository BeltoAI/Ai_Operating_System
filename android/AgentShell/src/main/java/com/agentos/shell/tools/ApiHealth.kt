package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LIVE, END-TO-END API HEALTH.
 *
 * The gap this closes: KeyValidator proves a KEY is accepted, and HealthStore counts past calls — but
 * neither catches the failure mode that actually took two brains down here. Gemini and Cerebras both had
 * a VALID key and still returned 404 on every single call, because the configured MODEL had been retired.
 * Key-valid + model-dead reads as "fine" everywhere in the UI while the brain is completely unusable.
 *
 * So each provider is checked in three stages, and the first failure is what gets reported:
 *   1. KEY      — is the credential accepted at all?
 *   2. MODEL    — is the model SlyOS is configured to send actually in the provider's live catalogue?
 *                 (if not, we heal it here via ModelResolver, BEFORE a user-facing call ever fails)
 *   3. ROUNDTRIP— send a real 1-token completion and time it. Only this proves the brain works.
 *
 * Results are persisted so `slystats.sh` can pull them and so the Settings card shows real state.
 * Every probe is cheap (a models list + a ~1 token completion) and safe to run on demand.
 */
object ApiHealth {
    private const val TAG = "SlyOS-ApiHealth"
    private const val PREFS = "slyos_apihealth"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** stage: "key" | "model" | "roundtrip" | "ok" | "nokey" */
    data class Result(val name: String, val ok: Boolean, val stage: String, val detail: String,
                      val ms: Long, val model: String = "")

    val BRAINS = listOf("anthropic", "openai", "gemini", "groq", "cerebras", "mistral", "githubmodels", "nvidia", "openrouter")

    // ── low-level HTTP ────────────────────────────────────────────────────────────────────────────
    private fun req(url: String, headers: Map<String, String>, body: String? = null, timeout: Int = 15000): Pair<Int, String> = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = timeout; readTimeout = timeout
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) { "" }
        code to text
    } catch (e: Exception) { -1 to (e.message ?: "network error") }

    /** A no-cost "is the credential live" call — the provider's model catalogue. */
    private fun keyProbe(provider: String, key: String): Pair<Int, String> = when (provider) {
        "gemini" -> req("https://generativelanguage.googleapis.com/v1beta/models?key=$key", emptyMap())
        "anthropic" -> req("https://api.anthropic.com/v1/models", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
        "openai" -> req("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key"))
        "groq" -> req("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer $key"))
        "cerebras" -> req("https://api.cerebras.ai/v1/models", mapOf("Authorization" to "Bearer $key"))
        "mistral" -> req("https://api.mistral.ai/v1/models", mapOf("Authorization" to "Bearer $key"))
        "nvidia" -> req("https://integrate.api.nvidia.com/v1/models", mapOf("Authorization" to "Bearer $key"))
        "openrouter" -> req("https://openrouter.ai/api/v1/models", mapOf("Authorization" to "Bearer $key"))
        "githubmodels" -> req("https://models.inference.ai.azure.com/models", mapOf("Authorization" to "Bearer $key"))
        else -> -1 to "unknown provider"
    }

    /** Model ids the provider currently serves (empty = couldn't tell, so we don't fail the model stage). */
    private fun catalogue(provider: String, body: String): List<String> = try {
        val o = JSONObject(body); val out = ArrayList<String>()
        o.optJSONArray("data")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(out::add) }
        o.optJSONArray("models")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { m ->
                (m.optString("name").removePrefix("models/").ifBlank { m.optString("id") }).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        out
    } catch (e: Exception) { emptyList() }

    /** The real proof: a tiny completion. Returns (ok, detail). */
    private fun roundTrip(provider: String, model: String, key: String): Pair<Boolean, String> {
        val (code, text) = when (provider) {
            "anthropic" -> req("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", 1)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))).toString())
            "gemini" -> req("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key",
                emptyMap(),
                JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", "hi"))))).toString())
            else -> {
                val base = when (provider) {
                    "openai" -> "https://api.openai.com/v1"
                    "groq" -> "https://api.groq.com/openai/v1"
                    "cerebras" -> "https://api.cerebras.ai/v1"
                    "mistral" -> "https://api.mistral.ai/v1"
                    "nvidia" -> "https://integrate.api.nvidia.com/v1"
                    "openrouter" -> "https://openrouter.ai/api/v1"
                    "githubmodels" -> "https://models.inference.ai.azure.com"
                    else -> return false to "unknown provider"
                }
                req("$base/chat/completions", mapOf("Authorization" to "Bearer $key"),
                    JSONObject().put("model", model).put("max_tokens", 1)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))).toString())
            }
        }
        if (code in 200..299) return true to "answered"
        val short = text.take(120).replace("\n", " ")
        return false to "$code: $short"
    }

    /** Full three-stage check for ONE brain. Heals a retired model id in passing. */
    fun checkBrain(ctx: Context, provider: String): Result {
        val key = try { ModelRouter.keyForPublic(ctx, provider) } catch (e: Exception) { "" }
        if (key.isBlank()) return Result(provider, false, "nokey", "no key set", 0)
        val started = System.currentTimeMillis()

        val (kc, kbody) = keyProbe(provider, key)
        if (kc == -1) return Result(provider, false, "key", "unreachable: ${kbody.take(60)}", System.currentTimeMillis() - started)
        if (kc == 401 || kc == 403) return Result(provider, false, "key", "key rejected ($kc)", System.currentTimeMillis() - started)

        var model = ModelRouter.modelFor(ctx, provider, ModelRouter.Tier.STANDARD) ?: "?"
        // Stage 2: is that model actually served? Heal BEFORE the user ever hits the 404.
        val cat = catalogue(provider, kbody)
        if (cat.isNotEmpty() && model != "?" && cat.none { it.equals(model, true) || it.endsWith("/$model") }) {
            val healed = try { ModelRouter.healModel(ctx, provider, ModelRouter.Tier.STANDARD) } catch (e: Exception) { null }
            if (healed.isNullOrBlank())
                return Result(provider, false, "model", "“$model” retired; no replacement found", System.currentTimeMillis() - started, model)
            Log.i(TAG, "$provider: $model retired → healed to $healed")
            model = healed
        }

        val (ok, detail) = roundTrip(provider, model, key)
        val ms = System.currentTimeMillis() - started
        try { HealthStore.recordLlm(ctx, provider, ok, if (ok) "" else detail) } catch (e: Exception) {}
        return Result(provider, ok, if (ok) "ok" else "roundtrip", detail, ms, model)
    }

    /** Non-LLM integrations, so "does everything work" covers the whole surface, not just brains. */
    fun checkIntegrations(ctx: Context): List<Result> {
        val out = ArrayList<Result>()
        fun probe(name: String, keyBlank: Boolean, call: () -> Pair<Int, String>) {
            if (keyBlank) { out.add(Result(name, false, "nokey", "no key set", 0)); return }
            val t = System.currentTimeMillis()
            val (c, b) = call()
            val ms = System.currentTimeMillis() - t
            out.add(if (c in 200..299) Result(name, true, "ok", "reachable", ms)
                    else Result(name, false, "key", if (c == -1) "unreachable: ${b.take(50)}" else "HTTP $c", ms))
        }
        // NOTE: these two are stored as *_token, NOT *_key — providerKey() would look up the wrong pref
        // and report a perfectly good integration as "no key set".
        val gh = try { MemoryStore.githubToken(ctx) } catch (e: Exception) { "" }
        probe("github", gh.isBlank()) { req("https://api.github.com/user", mapOf("Authorization" to "Bearer $gh", "User-Agent" to "SlyOS")) }
        val vc = try { MemoryStore.vercelToken(ctx) } catch (e: Exception) { "" }
        probe("vercel", vc.isBlank()) { req("https://api.vercel.com/v2/user", mapOf("Authorization" to "Bearer $vc")) }
        val nl = try { MemoryStore.netlifyToken(ctx) } catch (e: Exception) { "" }
        probe("netlify", nl.isBlank()) { req("https://api.netlify.com/api/v1/user", mapOf("Authorization" to "Bearer $nl")) }
        val fh = try { MemoryStore.providerKey(ctx, "finnhub") } catch (e: Exception) { "" }
        probe("finnhub", fh.isBlank()) { req("https://finnhub.io/api/v1/quote?symbol=AAPL&token=$fh", emptyMap()) }
        // Telegram's bot token is a build-config constant, not a pref — ask the client itself.
        out.add(try {
            val t = System.currentTimeMillis()
            if (!TelegramClient.configured()) Result("telegram", false, "nokey", "no bot token in this build", 0)
            else { val ok = TelegramClient.botUsername().isNotBlank()
                Result("telegram", ok, if (ok) "ok" else "key", if (ok) "reachable" else "getMe failed", System.currentTimeMillis() - t) }
        } catch (e: Exception) { Result("telegram", false, "key", e.message?.take(60) ?: "error", 0) })
        // Google is OAuth — a live token refresh is the real test.
        out.add(try {
            val t = System.currentTimeMillis()
            val connected = GoogleAuth.isConnected(ctx)
            val tok = if (connected) GoogleAuth.accessToken(ctx) else ""
            Result("google", tok.isNotBlank(), if (!connected) "nokey" else if (tok.isBlank()) "key" else "ok",
                if (!connected) "not connected" else if (tok.isBlank()) "token refresh failed" else "reachable",
                System.currentTimeMillis() - t)
        } catch (e: Exception) { Result("google", false, "key", e.message?.take(60) ?: "error", 0) })
        return out
    }

    /** Probe EVERYTHING and persist, so both the UI and the adb pull see identical live state. */
    fun checkAll(ctx: Context): List<Result> {
        val out = ArrayList<Result>()
        BRAINS.forEach { pr -> out.add(try { checkBrain(ctx, pr) } catch (e: Exception) { Result(pr, false, "key", e.message?.take(60) ?: "error", 0) }) }
        out.addAll(checkIntegrations(ctx))
        persist(ctx, out)
        return out
    }

    private fun persist(ctx: Context, results: List<Result>) {
        val e = p(ctx).edit()
        e.putLong("ran_at", System.currentTimeMillis())
        results.forEach { r ->
            val status = if (r.ok) "ok" else "fail"
            val line = status + "|" + r.stage + "|" + r.ms + "|" + r.model + "|" + r.detail.take(120)
            e.putString("r_" + r.name, line)
        }
        e.putInt("healthy", results.count { it.ok }).putInt("total", results.size)
        e.apply()
        HealthStore.note("api_sweep", results.all { it.ok || it.stage == "nokey" },
            "${results.count { it.ok }}/${results.size} healthy")
    }

    fun lastRun(ctx: Context): Long = p(ctx).getLong("ran_at", 0L)
}
