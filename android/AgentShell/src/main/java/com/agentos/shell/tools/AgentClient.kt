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
    private fun callMessages(system: String, messages: JSONArray, maxTokens: Int, model: String = MODEL, readMs: Int = 60000, tools: JSONArray? = null): Pair<Int, String> {
        val key = BuildConfig.ANTHROPIC_API_KEY
        if (key.isBlank()) return -1 to "No API key set."

        val obj = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        if (tools != null) obj.put("tools", tools)
        val payload = obj.toString()

        return try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = readMs
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
            append("types: open_app, web_search, dial, sms, send_sms, camera, settings, add_event, timer, alarm, compose_post, spicy_post, write_paper, checklist_add, none. ")
            append("Use write_paper when the user wants to write/create/draft a research paper, white paper, essay or report; arg = the topic. ")
            append("checklist_add arg = the item text. ")
            append("IMPORTANT: any request to add/remember something to a to-do, todo, to-dos, task list, ")
            append("checklist or list MUST use checklist_add — never open_app for that. ")
            append("If the user lists several items, emit one checklist_add action per item. ")
            append("Use compose_post when the user wants to create/take photos for a social media post; ")
            append("its arg = {\"platform\":\"LinkedIn\",\"topic\":\"...\"}. ")
            append("Use spicy_post when the user wants to post/tweet a spicy or witty tech take to X/Twitter; ")
            append("its arg = the topic (or empty). Add spicy_post to the action types. ")
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

    /** Generate a spicy-but-constructive tech post (Opus for sharper wit), tuned per platform. */
    fun spicyPost(topic: String, platform: String = "x", memory: String = ""): String {
        val author = if (memory.isNotBlank()) "About the author: $memory. " else ""
        val reddit = platform.lowercase().contains("reddit")
        val sys = author + if (reddit) {
            "You write savage, very funny, contrarian tech posts for Reddit. Format EXACTLY: a " +
            "punchy HEADLINE on the first line, then a blank line, then a meaty body of 4–8 " +
            "sentences across 2–3 short paragraphs that actually argues the point. Go hard at " +
            "hype, cargo-cult trends, bloated frameworks, VC buzzword soup and lazy conventional " +
            "wisdom — roast IDEAS and TRENDS, never real named people or groups; no slurs, " +
            "harassment, or protected-class jabs. Conversational Reddit voice, no hashtags. " +
            "Return ONLY the headline + body."
        } else {
            "You write savage, very funny, contrarian tech takes for X. Go hard: hot takes, " +
            "ratio-bait energy, sharp burns on hype, cargo-cult trends, bloated frameworks and " +
            "VC buzzword soup — roast IDEAS and TRENDS, never real named people; no slurs, " +
            "harassment, or protected-class jabs. There must be a genuinely sharp point under " +
            "the heat. One punchy line, under 260 characters, at most one hashtag. " +
            "Return ONLY the post text."
        }
        val user = if (topic.isBlank()) "Write a savage tech take." else "Topic: $topic"
        val (code, text) = callMessages(
            sys, JSONArray().put(JSONObject().put("role", "user").put("content", user)),
            if (reddit) 700 else 300, OPUS
        )
        return if (code == 200) text.trim().trim('"') else "[couldn't write it: $code $text]"
    }

    /** Revise an existing social post per a natural-language instruction. */
    fun revisePost(current: String, instruction: String, platform: String = "", memory: String = ""): String {
        val reddit = platform.lowercase().contains("reddit")
        val sys = (if (memory.isNotBlank()) "About the author: $memory. " else "") +
            "Revise the user's social post" + (if (platform.isNotBlank()) " for $platform" else "") +
            " exactly per their instruction. Keep it sharp and constructive — roast ideas not real " +
            "people, no slurs or harassment. " +
            (if (reddit) "Keep the format: a headline on the first line, blank line, then body. " else "") +
            "Return ONLY the revised post text."
        val (code, text) = callMessages(
            sys, JSONArray().put(JSONObject().put("role", "user")
                .put("content", "POST:\n$current\n\nINSTRUCTION: $instruction")),
            if (reddit) 700 else 300, OPUS
        )
        return if (code == 200) text.trim().trim('"') else "[couldn't revise: $code $text]"
    }

    /** Spicy-but-constructive reply to a comment/mention on a social post. */
    fun draftCommentReply(comment: String, memory: String = ""): String {
        val sys = (if (memory.isNotBlank()) "About the author: $memory. " else "") +
            "Someone commented on your spicy tech post. Reply in the same voice: witty, sharp, " +
            "a confident clapback — but constructive and about ideas, never personal attacks, " +
            "slurs, or harassment. One or two lines, under 240 characters. Return ONLY the reply."
        val (code, text) = callMessages(
            sys, JSONArray().put(JSONObject().put("role", "user").put("content", "Comment: \"$comment\"")),
            240, OPUS
        )
        return if (code == 200) text.trim().trim('"') else "[couldn't reply: $code $text]"
    }

    private fun webTool(): JSONArray =
        JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 4))

    private fun cleanHtml(s: String): String {
        var h = s.trim()
        if (h.startsWith("```")) h = h.substringAfter('\n', h).trim()
        if (h.endsWith("```")) h = h.removeSuffix("```").trim()
        return h
    }

    /** Opus call with one retry on transient 5xx errors. */
    private fun paperCall(sys: String, userContent: String, web: Boolean): Pair<Int, String> {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", userContent))
        val tools = if (web) webTool() else null
        val to = if (web) 180000 else 120000
        var (code, text) = callMessages(sys, msgs, 6000, OPUS, to, tools)
        if (code in 500..599) {
            try { Thread.sleep(1200) } catch (e: Exception) {}
            val r = callMessages(sys, msgs, 6000, OPUS, to, tools); code = r.first; text = r.second
        }
        return code to text
    }

    /** Write a research/white paper (Opus). Returns HTML, or "ERR::code::body" on failure. */
    fun writePaper(prompt: String, source: String = "", web: Boolean = false, memory: String = ""): String {
        val src = if (web) source.take(2500) else source.take(12000)
        val sys = "You are an expert research writer. Write a rigorous, well-structured white paper / " +
            "research paper IN THE USER'S NAME on their topic. Output a COMPLETE self-contained HTML " +
            "document: clean academic style (serif body, centered title, author line, abstract, numbered " +
            "sections, and a references section). Render math with MathJax — include exactly this in <head>: " +
            "<script src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\"></script> and write " +
            "math as \\( inline \\) and $$ display $$. Use ivory #F4EFE6 background, near-black text, generous margins. " +
            (if (memory.isNotBlank()) "About the author (reflect their context/voice): $memory. " else "") +
            (if (web) "Use web search to find current, real sources and CITE them with links in the references. " else "") +
            (if (src.isNotBlank()) "Also ground it in this source material:\n$src\n" else "") +
            "Return ONLY the HTML document."
        val (code, text) = paperCall(sys, prompt, web)
        return if (code == 200) cleanHtml(text) else "ERR::$code::${text.take(400)}"
    }

    /** Revise the paper HTML. Returns HTML, or "ERR::code::body" on failure. */
    fun revisePaper(currentHtml: String, instruction: String, web: Boolean = false, memory: String = ""): String {
        val sys = "Revise this HTML research paper per the instruction. Keep it a COMPLETE self-contained " +
            "HTML document with the same MathJax setup and academic styling. " +
            (if (memory.isNotBlank()) "About the author: $memory. " else "") +
            (if (web) "Use web search for any new facts/sources and cite them. " else "") +
            "Return ONLY the HTML."
        val (code, text) = paperCall(sys, "INSTRUCTION: $instruction\n\nPAPER HTML:\n${currentHtml.take(40000)}", web)
        return if (code == 200) cleanHtml(text) else "ERR::$code::${text.take(400)}"
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

    /** Conversational reply for the Telegram bot (uses the document if relevant). */
    fun telegramReply(text: String, doc: String = "", memory: String = ""): String {
        val sys = (if (memory.isNotBlank()) "About the owner you speak for: $memory. " else "") +
            "You are the owner's Telegram assistant. Reply helpfully, warm and concise, as them. " +
            (if (doc.isNotBlank()) "If relevant, use this document:\n$doc\n" else "")
        val (code, t) = call(sys, text)
        return if (code == 200) t.trim() else "Hmm, I hit an error ($code)."
    }

    /** Answer a question using ONLY the provided document excerpts. */
    fun answerFromDoc(question: String, excerpts: String): String {
        if (excerpts.isBlank()) return "No document is loaded yet."
        val sys = "Answer the question using ONLY the document excerpts below. If the answer is not " +
            "in them, say it isn't in the document — do not use outside knowledge. Be concise and clear.\n" +
            "DOCUMENT:\n" + excerpts
        val (code, text) = call(sys, question)
        return if (code == 200) text.trim() else "Couldn't check the document ($code)."
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

    /** Draft a human-sounding email reply, grounded in a document if one is provided. */
    fun draftEmailReply(sender: String, snippet: String, doc: String = "", memory: String = ""): String {
        val sys = (if (memory.isNotBlank()) "About the owner: $memory. " else "") +
            "Write a reply email on behalf of the owner. Sound genuinely human — warm, natural, " +
            "concise and professional; vary sentence length, no robotic filler, never say you're an AI. " +
            (if (doc.isNotBlank())
                "Ground any factual or technical claims ONLY in this document; if it isn't covered, " +
                "stay general and don't invent specifics:\nDOCUMENT:\n$doc\n" else "") +
            "Return ONLY the email body (greeting, body, sign-off)."
        val (code, text) = call(sys, "Email from $sender:\n$snippet")
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    /** A short, human, personalized outreach email. Returns (subject, body). */
    fun draftOutreach(recipient: String, topic: String, content: String, memory: String = ""): Pair<String, String> {
        val sys = (if (memory.isNotBlank()) "About the sender: $memory. " else "") +
            "Write a short, genuinely human, personalized outreach email. Warm, specific, respectful, " +
            "with a clear ask and a polite one-line opt-out. Not spammy, no hype. " +
            "Format: first line 'SUBJECT: ...', then a blank line, then the body."
        val user = "Recipient: $recipient\nTopic: $topic" + (if (content.isNotBlank()) "\nReference:\n$content" else "")
        val (code, text) = call(sys, user)
        if (code != 200) return "Hello" to "[couldn't draft: $code $text]"
        val subj = Regex("(?i)subject:\\s*(.*)").find(text)?.groupValues?.get(1)?.trim() ?: "Hello"
        val body = text.substringAfter("\n").substringAfter(subj).trim().ifBlank { text.trim() }
        return subj to body
    }

    /** Context-aware reply: sees the whole conversation thread with this person. */
    fun draftReplyThread(sender: String, thread: List<Pair<String, String>>, memory: String = "", imageB64: String? = null): String {
        if (thread.isEmpty()) return draftReply(sender, "", memory, imageB64)
        val system = (if (memory.isNotBlank()) "About the owner: $memory. " else "") +
            "You are replying AS the phone's owner in an ongoing conversation with $sender. " +
            "Use the earlier messages for context — stay consistent, remember what was said. " +
            "Write ONLY the next reply text, short and natural. No quotes, no preamble."
        // Normalize to alternating user/assistant turns, starting with user.
        val merged = ArrayList<Pair<String, String>>()
        thread.forEach { (role, text) ->
            val r = if (role == "me") "assistant" else "user"
            if (merged.isNotEmpty() && merged.last().first == r)
                merged[merged.size - 1] = r to (merged.last().second + "\n" + text)
            else merged.add(r to text)
        }
        while (merged.isNotEmpty() && merged.first().first == "assistant") merged.removeAt(0)
        if (merged.isEmpty()) return draftReply(sender, "", memory, imageB64)
        val arr = JSONArray()
        merged.forEachIndexed { i, (r, t) ->
            val content: Any = if (i == merged.size - 1 && r == "user" && imageB64 != null)
                JSONArray()
                    .put(JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)))
                    .put(JSONObject().put("type", "text").put("text", t))
            else t
            arr.put(JSONObject().put("role", r).put("content", content))
        }
        val (code, text) = callMessages(system, arr, 400)
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    /** Draft a reply to an incoming message (optionally seeing an attached image). */
    fun draftReply(sender: String, message: String, memory: String = "", imageB64: String? = null): String {
        val system = (if (memory.isNotBlank())
            "Owner facts (use only if relevant to this message): $memory. " else "") +
            "You are replying to a message on behalf of the phone's owner. " +
            (if (imageB64 != null) "The message includes the attached image; consider it. " else "") +
            "Write ONLY the reply text — short, natural, friendly. No quotes, no preamble."
        val userText = "Message from $sender: \"$message\""
        val (code, text) = if (imageB64 != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)))
                .put(JSONObject().put("type", "text").put("text", userText))
            callContent(system, content, 400)
        } else call(system, userText)
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
