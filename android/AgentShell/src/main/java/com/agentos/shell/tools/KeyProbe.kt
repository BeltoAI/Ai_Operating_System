package com.agentos.shell.tools

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Makes the free-key flow idiot-proof. Two jobs:
 *  1) detect() — after the user copies a key from AI Studio and comes back, read it straight off the clipboard
 *     and figure out which provider it is (no manual paste, no "which box do I put this in?").
 *  2) validate() — do a tiny live request so we can show a green ✓ instead of the key silently not working.
 * Both are best-effort and never throw.
 */
object KeyProbe {

    data class Detected(val provider: String, val key: String)

    /** Sniff a model key out of arbitrary clipboard text. Returns null if nothing key-shaped is present. */
    fun detect(raw: String?): Detected? {
        val t = (raw ?: "").trim()
        if (t.isBlank() || t.length < 20 || t.contains(' ') || t.contains('\n')) return null
        return when {
            t.startsWith("AIza") -> Detected("gemini", t)                 // Google AI Studio
            t.startsWith("sk-ant-") -> Detected("anthropic", t)           // Anthropic
            t.startsWith("gsk_") -> Detected("groq", t)                   // Groq
            t.startsWith("csk-") -> Detected("cerebras", t)              // Cerebras
            t.startsWith("nvapi-") -> Detected("nvidia", t)              // NVIDIA NIM
            t.startsWith("github_pat_") || t.startsWith("ghp_") -> Detected("githubmodels", t) // GitHub Models
            t.startsWith("sk-or-") -> Detected("openrouter", t)          // OpenRouter
            t.startsWith("sk-") -> Detected("openai", t)                  // OpenAI (check last)
            else -> null
        }
    }

    /** Raw clipboard text (for a card that already knows which provider it's for). */
    fun clipboardText(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
    } catch (e: Exception) { "" }

    /** Read the system clipboard and try to detect a key. */
    fun detectFromClipboard(ctx: Context): Detected? = try {
        detect(clipboardText(ctx))
    } catch (e: Exception) { null }

    /** Live-check a key. Returns (ok, humanMessage). Runs a cheap read-only call per provider. */
    fun validate(provider: String, key: String): Pair<Boolean, String> {
        val k = key.trim()
        if (k.isBlank()) return false to "No key"
        return try {
            when (provider) {
                "gemini" -> {
                    val code = get("https://generativelanguage.googleapis.com/v1beta/models?key=$k", emptyMap())
                    if (code == 200) true to "Free Gemini key works ✓" else false to "Gemini didn't accept that key ($code)"
                }
                "openai" -> {
                    val code = get("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "OpenAI key works ✓" else false to "OpenAI didn't accept that key ($code)"
                }
                "groq" -> {
                    val code = get("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "Free Groq key works ✓" else false to "Groq didn't accept that key ($code)"
                }
                "openrouter" -> {
                    val code = get("https://openrouter.ai/api/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "OpenRouter key works ✓" else false to "OpenRouter didn't accept that key ($code)"
                }
                "cerebras" -> {
                    val code = get("https://api.cerebras.ai/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "Free Cerebras key works ✓" else false to "Cerebras didn't accept that key ($code)"
                }
                "mistral" -> {
                    val code = get("https://api.mistral.ai/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "Free Mistral key works ✓" else false to "Mistral didn't accept that key ($code)"
                }
                "nvidia" -> {
                    val code = get("https://integrate.api.nvidia.com/v1/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "Free NVIDIA key works ✓" else false to "NVIDIA didn't accept that key ($code)"
                }
                "githubmodels" -> {
                    val code = get("https://models.inference.ai.azure.com/models", mapOf("Authorization" to "Bearer $k"))
                    if (code == 200) true to "GitHub Models token works ✓"
                    else true to "Saved — GitHub Models will confirm on first use"  // GH token scopes vary; don't hard-fail
                }
                "anthropic" -> {
                    val code = get("https://api.anthropic.com/v1/models",
                        mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01"))
                    if (code == 200) true to "Claude key works ✓" else false to "Claude didn't accept that key ($code)"
                }
                else -> false to "Unknown provider"
            }
        } catch (e: Exception) { false to "Couldn't reach the internet to check" }
    }

    private fun get(url: String, headers: Map<String, String>): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }
}
