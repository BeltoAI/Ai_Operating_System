package com.agentos.shell.tools

import android.util.Base64
import android.util.Log
import com.agentos.shell.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PROMPT-BASED IMAGE GENERATION + EDITING — "make the sky purple", "generate a logo of a fox".
 *
 * Claude has no image model, so this calls a dedicated one. It uses whichever key is present:
 *   • OPENAI_API_KEY → gpt-image-1 (generate + edit)
 *   • GEMINI_API_KEY → Gemini image (generate + edit)
 * Native transforms (background, crop…) live in ImageEdits and need no key at all.
 */
object ImageAI {
    private const val TAG = "SlyOS-ImageAI"

    // Set once in SlyApp so background paths work too. Prefer the USER'S entered key (Settings), else the baked
    // build key. BUG FIX: previously read only BuildConfig, so image gen was dead in keyless public builds even
    // when the user had added an OpenAI/Gemini key.
    @Volatile var appContext: android.content.Context? = null
    private val openai get() = (appContext?.let { MemoryStore.openaiKey(it) }?.takeIf { it.isNotBlank() }
        ?: BuildConfig.OPENAI_API_KEY).trim()
    private val gemini get() = (appContext?.let { MemoryStore.geminiKey(it) }?.takeIf { it.isNotBlank() }
        ?: BuildConfig.GEMINI_API_KEY).trim()

    fun available(): Boolean = openai.isNotBlank() || gemini.isNotBlank()
    fun providerName(): String = when { openai.isNotBlank() -> "OpenAI"; gemini.isNotBlank() -> "Gemini"; else -> "" }

    /** Create a brand-new image from a text prompt. Returns PNG bytes, or null. */
    fun generate(prompt: String): ByteArray? = try {
        when {
            openai.isNotBlank() -> openAiGenerate(prompt)
            gemini.isNotBlank() -> geminiImage(prompt, null)
            else -> null
        }
    } catch (e: Exception) { Log.w(TAG, "generate: ${e.message}"); null }

    /** Edit an existing image per a text instruction. Returns PNG bytes, or null. */
    fun edit(image: ByteArray, prompt: String): ByteArray? = try {
        when {
            openai.isNotBlank() -> openAiEdit(image, prompt)
            gemini.isNotBlank() -> geminiImage(prompt, image)
            else -> null
        }
    } catch (e: Exception) { Log.w(TAG, "edit: ${e.message}"); null }

    // ── OpenAI (gpt-image-1) ──────────────────────────────────────────────────────────────────────
    private fun openAiGenerate(prompt: String): ByteArray? {
        val body = JSONObject().put("model", "gpt-image-1").put("prompt", prompt).put("size", "1024x1024")
        val (code, text) = postJson("https://api.openai.com/v1/images/generations", openai, body.toString())
        if (code != 200) { Log.w(TAG, "openai gen $code: ${text.take(200)}"); return null }
        val b64 = JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
        return if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
    }

    private fun openAiEdit(image: ByteArray, prompt: String): ByteArray? {
        val boundary = "----slyos${System.currentTimeMillis()}"
        val conn = (URL("https://api.openai.com/v1/images/edits").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 90000
            setRequestProperty("Authorization", "Bearer $openai")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", "gpt-image-1"); field("prompt", prompt); field("size", "1024x1024")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"in.png\"\r\nContent-Type: image/png\r\n\r\n")
            out.write(image); out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code != 200) { Log.w(TAG, "openai edit $code: ${text.take(200)}"); return null }
        val b64 = JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
        return if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
    }

    // ── Gemini (image out) ────────────────────────────────────────────────────────────────────────
    private fun geminiImage(prompt: String, image: ByteArray?): ByteArray? {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/png").put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp-image-generation:generateContent?key=$gemini"
        val (code, text) = postJson(url, "", body.toString())
        if (code != 200) { Log.w(TAG, "gemini $code: ${text.take(200)}"); return null }
        val cand = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
        val outParts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until outParts.length()) {
            val data = outParts.optJSONObject(i)?.optJSONObject("inline_data")?.optString("data")
                ?: outParts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
            if (!data.isNullOrBlank()) return Base64.decode(data, Base64.DEFAULT)
        }
        return null
    }

    private fun postJson(url: String, bearer: String, json: String): Pair<Int, String> = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 90000
            setRequestProperty("Content-Type", "application/json")
            if (bearer.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        code to txt
    } catch (e: Exception) { -1 to (e.message ?: "network") }
}
