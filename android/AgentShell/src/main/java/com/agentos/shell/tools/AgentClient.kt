package com.agentos.shell.tools

import android.util.Log
import com.agentos.shell.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One step the agent wants to take. */
data class AgentAction(val type: String, val arg: String)

/** What the agent decided: something to say, an ordered list of actions, a fact to remember. */
data class AgentResult(
    val say: String,
    val actions: List<AgentAction>,
    val remember: String = ""
)

/**
 * The real brain. Talks to Claude. All calls are blocking network I/O — run them off the
 * main thread. Key comes from BuildConfig (apikey.properties), never hard-coded in source.
 */
object AgentClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    // Current small model. If your account uses a different id, change it here.
    private const val MODEL = "claude-haiku-4-5"
    // The Architect runs on the most capable model.
    private const val OPUS = "claude-opus-4-8"

    fun hasKey(): Boolean = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /** Text-only call. */
    private fun call(system: String, userContent: String): Pair<Int, String> =
        callContent(system, userContent, 400)

    /** Single-message call. content may be a String or JSONArray (for multimodal). */
    private fun callContent(system: String, content: Any, maxTokens: Int): Pair<Int, String> =
        callMessages(system, JSONArray().put(JSONObject().put("role", "user").put("content", content)), maxTokens)

    /** Low-level call with a full messages array (supports multi-turn history). */
    private fun callMessages(system: String, messages: JSONArray, maxTokens: Int, model: String = MODEL): Pair<Int, String> {
        val key = BuildConfig.ANTHROPIC_API_KEY
        if (key.isBlank()) return -1 to "No API key set."

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
            .toString()

        return try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null }
                return code to (detail ?: raw.take(160))
            }
            val content = JSONObject(raw).optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            200 to sb.toString()
        } catch (e: Exception) {
            -1 to (e.message ?: "network error")
        }
    }

    /** Main prompt → reply + structured action. History = recent (user, assistant) turns. */
    fun ask(
        prompt: String,
        apps: List<String>,
        memory: String = "",
        history: List<Pair<String, String>> = emptyList()
    ): AgentResult {
        val system = buildString {
            if (memory.isNotBlank())
                append("Personal facts (use ONLY when relevant to the request; never force them " +
                    "into unrelated answers): $memory. ")
            append("You are SlyOS, an agent living on the user's phone. ")
            append("Decide the single best thing to do for their request. ")
            append("Installed apps: ").append(apps.joinToString(", ")).append(". ")
            append("Respond with ONLY a JSON object (no prose, no markdown) with keys: ")
            append("\"say\" (one short sentence to show the user), ")
            append("\"actions\" (an ORDERED array of steps; do all the user asked. ")
            append("Each step is {\"type\":..,\"arg\":..}. ")
            append("types: open_app, web_search, dial, sms, send_sms, camera, settings, add_event, timer, alarm, compose_post, checklist_add, none. ")
            append("checklist_add arg = the item text. ")
            append("IMPORTANT: any request to add/remember something to a to-do, todo, to-dos, task list, ")
            append("checklist or list MUST use checklist_add — never open_app for that. ")
            append("If the user lists several items, emit one checklist_add action per item. ")
            append("Use compose_post when the user wants to create/take photos for a social media post; ")
            append("its arg = {\"platform\":\"LinkedIn\",\"topic\":\"...\"}. ")
            append("arg rules: open_app=app name; web_search=query; dial=number; ")
            append("send_sms={\"name\":\"Alex\",\"body\":\"on my way\"}; ")
            append("add_event={\"title\":\"Blocked\",\"start\":\"2026-06-15T17:00\",\"end\":\"2026-06-15T19:00\"} (use Current time); ")
            append("timer=seconds (e.g. 3600); alarm=\"HH:MM\" 24h. ")
            append("Empty array if nothing to do.), ")
            append("\"remember\" (a durable personal fact worth saving, e.g. name/preference/relationship — or empty).")
        }
        val messages = JSONArray()
        history.takeLast(4).forEach { (u, a) ->
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val (code, text) = callMessages(system, messages, 400)
        Log.i("SlyOS", "ask code=$code raw=${text.take(300)}")
        if (code != 200) return AgentResult("Agent error $code: $text", emptyList(), "")
        val r = parse(text)
        Log.i("SlyOS", "ask parsed: say='${r.say}' actions=${r.actions.map { "${it.type}:${it.arg.take(50)}" }}")
        return r
    }

    /** Vision Q&A: answer a question about photos. Returns plain text. */
    fun askVision(prompt: String, imagesB64: List<String>, memory: String = ""): String {
        val content = JSONArray()
        imagesB64.forEach { b64 ->
            content.put(JSONObject().put("type", "image").put(
                "source", JSONObject().put("type", "base64")
                    .put("media_type", "image/jpeg").put("data", b64)))
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val sys = (if (memory.isNotBlank()) "About the user (use if relevant): $memory. " else "") +
            "You are SlyOS. Answer the user's question about the photo concisely."
        val (code, text) = callContent(sys, content, 600)
        return if (code == 200) text.trim() else "Couldn't read the image ($code)."
    }

    /** Summarize notifications into up to 3 plain-English priorities for the lock screen. */
    fun brief(items: List<String>, memory: String = ""): List<String> {
        if (items.isEmpty()) return emptyList()
        val system = (if (memory.isNotBlank()) "About the user: $memory. " else "") +
            "You are SlyOS. From these notifications, pick up to 3 things that genuinely matter " +
            "to the user right now. Reply with ONLY up to 3 short lines, one priority per line, " +
            "plain English, no numbering, no JSON."
        val (code, text) = call(system, "Notifications:\n" + items.joinToString("\n"))
        if (code != 200) return emptyList()
        return text.trim().lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
            .take(3)
    }

    /** Look at photos and write a social post. Returns the post text. */
    fun composePost(platform: String, topic: String, imagesB64: List<String>, memory: String = ""): String {
        val content = JSONArray()
        imagesB64.forEach { b64 ->
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text",
            "Write a $platform post about: $topic. " +
                (if (imagesB64.isNotEmpty()) "Use what you see in the photo(s). " else "") +
                (if (memory.isNotBlank()) "Author context: $memory. " else "") +
                "Match $platform's tone and length, add a few fitting hashtags. " +
                "Return ONLY the post text."))
        val (code, text) = callContent(
            "You are a sharp social media copywriter.", content, 600
        )
        Log.i("SlyOS", "composePost code=$code imgs=${imagesB64.size} out=${text.take(120)}")
        return if (code == 200) text.trim() else "[couldn't write the post: $code $text]"
    }

    /** The Architect (Opus 4.8): turn a prompt into a self-contained mini-app. Returns (name, html). */
    fun architect(prompt: String): Pair<String, String> {
        val sys = "You are the SlyOS Architect. The user describes an app or tool to add to their phone OS. " +
            "Build it as ONE self-contained HTML document — inline CSS and JS only, absolutely no external URLs, " +
            "fonts, or libraries (it runs offline in a WebView). Make it genuinely functional, not a mockup. " +
            "Match SlyOS style: warm ivory background #F4EFE6, near-black text #1A1714, one orange accent #E8642C, " +
            "rounded corners, generous whitespace, Apple-like minimalism, system sans-serif. " +
            "OUTPUT FORMAT: first line = a short app name (2-4 words), then a line with only ---, then the full HTML document."
        val (code, text) = callMessages(
            sys, JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), 4000, OPUS
        )
        if (code != 200) return "Build failed" to "<body style='font-family:sans-serif;padding:20px'><h3>Couldn't build ($code)</h3><p>$text</p></body>"
        val idx = text.indexOf("---")
        val name = (if (idx > 0) text.substring(0, idx) else "New app").trim().take(40).ifBlank { "New app" }
        var html = (if (idx > 0) text.substring(idx + 3) else text).trim()
        if (html.startsWith("```")) html = html.substringAfter('\n', html).trim()
        if (html.endsWith("```")) html = html.removeSuffix("```").trim()
        Log.i("SlyOS", "architect name='$name' htmlLen=${html.length}")
        return name to html
    }

    /** Natural-language Q&A over the user's memories. Returns an answer. */
    fun askMemory(query: String, memories: List<String>): String {
        if (memories.isEmpty()) return "Your memory is empty so far — as you chat, reply, and learn, it fills up."
        val sys = "You are SlyOS memory. Answer the user's question using ONLY the memories below. " +
            "Be concise and specific; quote names/times when present. If the answer isn't in them, say you don't have it yet.\n" +
            "MEMORIES:\n" + memories.joinToString("\n")
        val (code, text) = call(sys, query)
        return if (code == 200) text.trim() else "Couldn't search memory ($code)."
    }

    /** Extract a calendar event from a message. Returns add_event JSON, or "" if none. */
    fun eventFromText(message: String, nowStr: String): String {
        val sys = "Current time: $nowStr. Extract a calendar event from the message if one is " +
            "clearly implied (a meeting, plan, appointment with a time). " +
            "Reply ONLY JSON: {\"title\":\"..\",\"start\":\"YYYY-MM-DDTHH:MM\",\"end\":\"YYYY-MM-DDTHH:MM\"} " +
            "or exactly {\"none\":true} if there is no event."
        val (code, text) = call(sys, message)
        if (code != 200) return ""
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return ""
        val json = text.substring(s, e + 1)
        return if (json.contains("\"none\"")) "" else json
    }

    /** Draft a reply to an incoming message. Returns plain reply text. */
    fun draftReply(sender: String, message: String, memory: String = ""): String {
        val system = (if (memory.isNotBlank())
            "Owner facts (use only if relevant to this message): $memory. " else "") +
            "You are replying to a message on behalf of the phone's owner. " +
            "Write ONLY the reply text — short, natural, friendly. No quotes, no preamble."
        val (code, text) = call(system, "Message from $sender: \"$message\"")
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    private fun parse(text: String): AgentResult {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start)
            return AgentResult(text.trim().ifEmpty { "(no reply)" }, emptyList(), "")
        return try {
            val o = JSONObject(text.substring(start, end + 1))
            val actions = mutableListOf<AgentAction>()
            val arr = o.optJSONArray("actions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    actions.add(AgentAction(a.optString("type", "none"), argToString(a.opt("arg"))))
                }
            } else {
                val act = o.optString("action", "")
                if (act.isNotBlank()) actions.add(AgentAction(act, argToString(o.opt("arg"))))
            }
            AgentResult(o.optString("say"), actions, o.optString("remember", ""))
        } catch (e: Exception) {
            AgentResult(text.trim(), emptyList(), "")
        }
    }

    /** arg may be a string or a nested JSON object — normalize to a string. */
    private fun argToString(v: Any?): String = when (v) {
        null -> ""
        else -> v.toString()
    }
}
