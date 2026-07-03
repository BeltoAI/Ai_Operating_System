package com.agentos.shell.tools

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Normalized result from any provider, including token usage for cost tracking. */
data class LlmResult(val code: Int, val text: String, val inTokens: Int = 0, val outTokens: Int = 0)

/**
 * One call, any provider. AgentClient builds prompts in the Anthropic shape — system string +
 * messages[{role, content}] where content is a String or an array of {type:text|image} blocks.
 * This translates that to each provider's wire format and normalizes the reply + usage back, so
 * the rest of the app (memory assembly, personas, actions) is completely provider-agnostic.
 */
object LlmProviders {

    fun call(provider: String, model: String, apiKey: String, system: String,
             messages: JSONArray, maxTokens: Int, readMs: Int, tools: JSONArray?): LlmResult =
        when (provider) {
            "openai" -> openai(model, apiKey, system, messages, maxTokens, readMs)
            // P2: pass tools through so Gemini can use Google Search grounding (free-tier web search).
            "gemini" -> gemini(model, apiKey, system, messages, maxTokens, readMs, tools)
            else -> anthropic(model, apiKey, system, messages, maxTokens, readMs, tools)
        }

    private fun post(url: String, headers: Map<String, String>, body: String, readMs: Int): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = readMs
                setRequestProperty("content-type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            code to raw
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    private fun errMsg(raw: String, code: Int): String = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.take(160)
    } catch (e: Exception) { raw.take(160) }

    // ---------- Anthropic ----------
    private fun anthropic(model: String, apiKey: String, system: String, messages: JSONArray,
                          maxTokens: Int, readMs: Int, tools: JSONArray?): LlmResult {
        val obj = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", messages)
        // Prompt caching: cache the big, stable system prompt so repeated calls (e.g. every Home ask
        // reuses the same persona + instructions) pay ~10% on those input tokens instead of full price.
        if (system.length > 2000) {
            obj.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system)
                .put("cache_control", JSONObject().put("type", "ephemeral"))))
        } else obj.put("system", system)
        if (tools != null) obj.put("tools", tools)
        val (code, raw) = post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), obj.toString(), readMs)
        if (code !in 200..299) return LlmResult(code, errMsg(raw, code))
        return try {
            val o = JSONObject(raw)
            val content = o.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val p = content.getJSONObject(i); if (p.optString("type") == "text") sb.append(p.optString("text"))
            }
            val u = o.optJSONObject("usage")
            LlmResult(200, sb.toString(), u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0)
        } catch (e: Exception) { LlmResult(-1, e.message ?: "parse error") }
    }

    // ---------- OpenAI ----------
    private fun openai(model: String, apiKey: String, system: String, messages: JSONArray,
                       maxTokens: Int, readMs: Int): LlmResult {
        val msgs = JSONArray()
        if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val content = m.opt("content")
            val outContent: Any = if (content is JSONArray) openaiBlocks(content) else content?.toString().orEmpty()
            msgs.put(JSONObject().put("role", m.optString("role")).put("content", outContent))
        }
        val obj = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", maxTokens)
        val (code, raw) = post("https://api.openai.com/v1/chat/completions",
            mapOf("authorization" to "Bearer $apiKey"), obj.toString(), readMs)
        if (code !in 200..299) return LlmResult(code, errMsg(raw, code))
        return try {
            val o = JSONObject(raw)
            val text = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            val u = o.optJSONObject("usage")
            LlmResult(200, text, u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0)
        } catch (e: Exception) { LlmResult(-1, e.message ?: "parse error") }
    }

    private fun openaiBlocks(blocks: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> out.put(JSONObject().put("type", "text").put("text", b.optString("text")))
                "image" -> {
                    val src = b.optJSONObject("source")
                    val mt = src?.optString("media_type") ?: "image/jpeg"
                    val data = src?.optString("data") ?: ""
                    out.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:$mt;base64,$data")))
                }
            }
        }
        return out
    }

    // ---------- Gemini ----------
    private fun gemini(model: String, apiKey: String, system: String, messages: JSONArray,
                       maxTokens: Int, readMs: Int, tools: JSONArray? = null): LlmResult {
        val contents = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = if (m.optString("role") == "assistant") "model" else "user"
            val parts = JSONArray()
            val content = m.opt("content")
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val b = content.getJSONObject(j)
                    when (b.optString("type")) {
                        "text" -> parts.put(JSONObject().put("text", b.optString("text")))
                        "image" -> {
                            val src = b.optJSONObject("source")
                            parts.put(JSONObject().put("inline_data", JSONObject()
                                .put("mime_type", src?.optString("media_type") ?: "image/jpeg")
                                .put("data", src?.optString("data") ?: "")))
                        }
                    }
                }
            } else parts.put(JSONObject().put("text", content?.toString().orEmpty()))
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        val obj = JSONObject().put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        if (system.isNotBlank())
            obj.put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
        // P2: if a web-search tool was requested, enable Gemini's built-in Google Search grounding
        // (free-tier). The grounded answer text comes back in the normal candidates/parts payload.
        if (tools != null)
            obj.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val (code, raw) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey",
            emptyMap(), obj.toString(), readMs)
        if (code !in 200..299) return LlmResult(code, errMsg(raw, code))
        return try {
            val o = JSONObject(raw)
            val cand = o.getJSONArray("candidates").getJSONObject(0)
            val parts = cand.getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val u = o.optJSONObject("usageMetadata")
            LlmResult(200, sb.toString(), u?.optInt("promptTokenCount") ?: 0, u?.optInt("candidatesTokenCount") ?: 0)
        } catch (e: Exception) { LlmResult(-1, e.message ?: "parse error") }
    }
}
