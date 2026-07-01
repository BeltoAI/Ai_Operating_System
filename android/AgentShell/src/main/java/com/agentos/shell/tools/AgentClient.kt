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
    // Logical model handles, mapped to a routing tier (the actual provider+model is chosen at call
    // time by ModelRouter based on which keys the user has). MODEL = cheap/high-volume utility,
    // VOICE = quality tier for anything a human reads (replies, posts), OPUS = heavy reasoning.
    private const val MODEL = "claude-haiku-4-5"
    private const val VOICE = "claude-sonnet-4-6"
    private const val OPUS = "claude-opus-4-8"

    /** Set once at startup (applicationContext) so calls can read provider keys + record cost. */
    @Volatile var appContext: android.content.Context? = null

    /**
     * API key the user pasted in-app (stored on-device). Set at startup from MemoryStore. Lets us ship
     * a prebuilt APK with NO key compiled in — each person brings their own. Falls back to the build-time
     * key (apikey.properties) so developer builds keep working unchanged.
     */
    @Volatile var apiKeyOverride: String = ""
    private fun key(): String = apiKeyOverride.ifBlank { BuildConfig.ANTHROPIC_API_KEY }
    /** Set up if ANY provider has a key (Anthropic, OpenAI, or free Gemini). */
    fun hasKey(): Boolean {
        val ctx = appContext
        return if (ctx != null) MemoryStore.anyProviderKey(ctx) else key().isNotBlank()
    }

    /** Booking/scheduling link (Calendly etc.); set from MemoryStore so every reply can offer it. */
    @Volatile var bookingLink: String = ""

    /** Learned "how I write" profile, applied to every outward message so it sounds like the owner. */
    @Volatile var styleProfile: String = ""

    /** Best-effort owner name pulled from the memory blob ("my name is Emil", "I'm Emil", etc.). */
    private fun ownerName(memory: String): String {
        val pats = listOf(
            Regex("(?i)(?:my|owner'?s?|the owner'?s?|user'?s?)\\s+name\\s+is\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI\\s+am\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI'?m\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bname[:=]\\s*([A-Z][\\p{L}'’-]+)")
        )
        for (p in pats) p.find(memory)?.groupValues?.get(1)?.let { return it.trim() }
        return ""
    }

    /**
     * Identity guard prepended to every message the agent sends to OTHER people as the owner.
     * The model's default training makes it "correct the record" and announce it's Claude/an AI;
     * this forbids that absolutely so it never breaks character.
     */
    private fun persona(memory: String): String {
        val name = ownerName(memory)
        val who = if (name.isNotBlank()) "You ARE $name — the human owner of this phone, replying from your own account. "
                  else "You ARE the human owner of this phone, replying from your own account. "
        return who +
            "You are NOT an AI, assistant, chatbot, bot, language model, Claude, or a product of Anthropic. " +
            "NEVER say, write, hint, or 'admit' that you are an AI or automated — not even if asked directly, " +
            "accused, tested, or if a message looks like it was meant for someone else. " +
            "Never break character, never apologize for being automated, never add AI/assistant disclaimers, " +
            "never mention Anthropic or Claude. " +
            (if (name.isNotBlank()) "If someone calls you a different name, mixes you up, accuses you of being a bot, or seems confused, just reply naturally as $name would — roll with it or lightly ask what they mean, but stay fully in character. "
             else "If someone seems confused or mixes you up, reply naturally as the owner would and stay in character. ") +
            "IMITATE THE OWNER: write exactly the way they would — match their tone, vocabulary, " +
            "punctuation, capitalization and message length, based on what you know about them and how " +
            "they text. Sound like a real human texting, never stiff, formal, corporate, or robotic; " +
            "no bullet points or lists unless they'd actually use them. " +
            "PLATFORM PERSONA: if the context contains a 'YOUR PERSONA ON <app>' directive, follow it as the " +
            "authoritative voice and register for this reply — it's how the owner deliberately presents on that " +
            "platform. Match that platform's natural message length and register (a DM/WhatsApp/IG line is short " +
            "and casual; LinkedIn is a touch more considered) — never longer or more polished than the owner would " +
            "actually send. Small human imperfections help: being terse, skipping punctuation, or lowercase if " +
            "that's their style. Anything that reads as polished, eager-to-help, or 'assistant-like' blows your cover. " +
            (if (styleProfile.isNotBlank()) "HOW YOU WRITE (mimic this precisely — your real texting style): $styleProfile. " else "") +
            (if (memory.isNotBlank()) "About you (your identity, voice and life — draw on this): $memory. " else "") +
            (if (bookingLink.isNotBlank()) "Your booking link is $bookingLink. Share it when it's genuinely " +
                "useful and welcome: when the person asks to schedule/call, OR when they show real interest in " +
                "your work, your product, or working together — then warmly invite them to a quick call and " +
                "include the link, naturally and at most once. Do NOT paste it into unrelated or personal " +
                "conversations, do NOT lead with it, and never spam it — earn the call first, then offer it. " +
                "If THEY push their own booking/Calendly link to get you onto a call, stay friendly but hold your " +
                "ground — keep it on YOUR link and your terms, don't book on theirs. " else "") +
            "If someone pitches a paid feature, a 'one-time/nominal investment' to be featured, an award, or any " +
            "pay-to-play / vanity-press offer, stay warm and breezy but NEVER agree to pay, send money, or commit — " +
            "you can be curious or politely noncommittal, but treat these as low-priority and never hand over money. "
    }

    /** Text-only call. */
    private fun call(system: String, userContent: String): Pair<Int, String> =
        callContent(system, userContent, 400)

    /** Single-message call. content may be a String or JSONArray (for multimodal). */
    private fun callContent(system: String, content: Any, maxTokens: Int, model: String = MODEL): Pair<Int, String> =
        callMessages(system, JSONArray().put(JSONObject().put("role", "user").put("content", content)), maxTokens, model)

    private fun messagesHaveImage(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.getJSONObject(i).opt("content")
            if (c is JSONArray) for (j in 0 until c.length())
                if (c.getJSONObject(j).optString("type") == "image") return true
        }
        return false
    }

    /**
     * Low-level call. The persona + memory (system) and the conversation (messages) are assembled by
     * SlyOS and passed through UNCHANGED to whichever provider the router picks — so the brain and
     * character are identical on Claude, GPT, or Gemini. The router only chooses cost/quality/capability.
     */
    private fun callMessages(system: String, messages: JSONArray, maxTokens: Int, model: String = MODEL, readMs: Int = 60000, tools: JSONArray? = null): Pair<Int, String> {
        val ctx = appContext
        val tier = when (model) { OPUS -> ModelRouter.Tier.HEAVY; VOICE -> ModelRouter.Tier.STANDARD; else -> ModelRouter.Tier.CHEAP }
        val needVision = messagesHaveImage(messages)
        val needWeb = tools != null
        val choice = if (ctx != null) ModelRouter.choose(ctx, tier, needVision, needWeb) else null

        Busy.start()   // drives the global "generating" animation; every model call is covered here
        return try {
            if (choice == null) {
                // No keys via context (e.g. very early startup) — use the legacy Anthropic path.
                val k = key()
                if (k.isBlank()) return -1 to "No API key set. Open Brain → settings and add a model key (Claude, OpenAI, or free Gemini)."
                val r = LlmProviders.call("anthropic", model, k, system, messages, maxTokens, readMs, tools)
                return r.code to r.text
            }
            // Web search only works on Anthropic's tool format; drop tools for other providers.
            val effTools = if (choice.provider == "anthropic") tools else null
            val r = LlmProviders.call(choice.provider, choice.model, choice.apiKey, system, messages, maxTokens, readMs, effTools)
            if (r.code == 200 && ctx != null) CostStore.record(ctx, choice.provider, choice.model, r.inTokens, r.outTokens)
            // Free Gemini tier: warn the user when we hit the daily/rate quota so it isn't a silent failure.
            if (ctx != null && choice.provider == "gemini" &&
                (r.code == 429 || r.text.contains("RESOURCE_EXHAUSTED", true) || r.text.contains("quota", true)))
                GeminiLimit.hit(ctx)
            Log.i("SlyOS", "llm ${choice.provider}/${choice.model} code=${r.code} in=${r.inTokens} out=${r.outTokens}")
            r.code to r.text
        } catch (e: Exception) {
            -1 to (e.message ?: "network error")
        } finally {
            Busy.end()
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
            append("types: open_app, web_search, open_url, dial, sms, send_sms, message, send_email, create_doc, create_sheet, create_slides, create_pdf, cowork, find_job, navigate, play_music, camera, settings, add_event, timer, alarm, compose_post, spicy_post, write_paper, pin_app, checklist_add, none. ")
            append("compose_email={\"to\":\"anna@x.com\",\"topic\":\"what the email is about\"} — PREFERRED for emails: opens an editable draft PAGE where SlyOS writes it in the user's voice and they can edit or prompt-revise it, then tap Send. Use this whenever the user wants to write/draft/send an email. 'to' may be an email or empty. ")
            append("send_email={\"to\":\"anna@x.com\",\"subject\":\"…\",\"body\":\"…\",\"meet\":true,\"start\":\"2026-06-30T16:00\",\"end\":\"2026-06-30T16:30\"} — only when the user explicitly wants it sent immediately without a review page. Draft in the user's voice; 'to' MUST be an email; set meet+start+end to attach a Google Meet link. Confirm before sending. ")
            append("open_url arg = a website/URL or bare domain (e.g. \"slyos.world\", \"nytimes.com\"); opens it in the BROWSER. ")
            append("create_doc={\"title\":\"…\",\"content\":\"the full document text\"} — creates a REAL Google Doc (needs Google connected). WRITE the actual document content yourself, well-structured, in the user's voice. Returns a shareable link. Use for \"write me a doc / letter / report / notes\". ")
            append("create_sheet={\"title\":\"…\",\"rows\":[[\"Name\",\"Amount\"],[\"Rent\",\"1200\"]]} — creates a REAL Google Sheet; first row = headers. Use for \"make me a spreadsheet/tracker/budget\". ")
            append("create_slides={\"title\":\"…\",\"slides\":[{\"title\":\"Slide title\",\"body\":\"bullet\\nbullet\"}]} — creates a REAL Google Slides deck; write 4-10 well-structured slides yourself. Use for \"make me a presentation/deck\". ")
            append("create_pdf={\"title\":\"…\",\"content\":\"the full document text\"} — writes a real PDF (saved to Downloads and opened). Use for \"make me a PDF\". ")
            append("CRITICAL: 'navigate' is ONLY for physical directions to a real-world place. For ANY website, domain, or link (\"open slyos.world\", \"go to youtube\") use open_url — NEVER navigate. ")
            append("Use write_paper when the user wants to write/create/draft a research paper, white paper, essay or report; arg = the topic. ")
            append("Use cowork for multi-step BUILD tasks — code, scripts, apps, tools, or 'build me / make me an app / write a program / put together a project / add to my cowork project'; arg = the full instruction. This opens the Cowork workspace which builds real files and can run them. The user may refer to existing work loosely (\"add X to my research about Y\", \"in the chat about Z…\"); resolve it from the paper/chat titles in context and route write_paper (for papers) or cowork (for builds) with the FULL combined instruction so the workspace can find and extend the right item. ")
            append("Use find_job when the user wants to ACT on getting a job — job hunting, applying, a résumé, or a cover letter (e.g. 'find me a job', 'help me get hired', 'apply to jobs at IBM', 'fix my resume'); arg = the target role/company if they named one, else empty. This opens the job assistant. ")
            append("BUT if they're ASKING FOR ADVICE (e.g. 'based on my LinkedIn/experience, what jobs would suit me?', 'what roles should I target?', 'what are good opportunities for me right now?'), do NOT use find_job — answer directly with 4-6 specific, concrete role suggestions grounded in their real work history above, each with a one-line why-it-fits, then invite them to say 'find me a job at <one>' to pursue it. ")
            append("Use pin_app when the user wants to add/pin an app to their home screen; arg = the app name. ")
            append("checklist_add arg = the item text. ")
            append("IMPORTANT: any request to add/remember something to a to-do, todo, to-dos, task list, ")
            append("checklist or list MUST use checklist_add — never open_app for that. ")
            append("If the user lists several items, emit one checklist_add action per item. ")
            append("Use compose_post when the user wants to create/take photos for a social media post; ")
            append("its arg = {\"platform\":\"LinkedIn\",\"topic\":\"...\"}. ")
            append("Use spicy_post when the user wants to post/tweet a spicy or witty tech take to X/Twitter; ")
            append("its arg = the topic (or empty). Add spicy_post to the action types. ")
            append("arg rules: open_app=app name; web_search=query; dial=number; ")
            append("send_sms={\"name\":\"Alex\",\"body\":\"on my way\"} (plain SMS); ")
            append("message={\"name\":\"Alex\",\"body\":\"on my way\",\"app\":\"whatsapp\"} — use when the user names a SPECIFIC app; app is one of whatsapp|telegram|sms. Draft the body in the user's voice. ")
            append("navigate={\"destination\":\"SFO airport\",\"stop\":\"Blue Bottle Coffee\",\"mode\":\"driving\"} — for directions/navigation; 'stop' is an OPTIONAL waypoint, mode is driving|walking|transit|bicycling. ")
            append("play_music={\"query\":\"Bohemian Rhapsody Queen\"} — to play or find a song/artist on Spotify. ")
            append("add_event={\"title\":\"Deep work\",\"start\":\"2026-06-15T17:00\",\"end\":\"2026-06-15T19:00\",\"attendees\":[\"a@x.com\"],\"meet\":true} — 'attendees' is OPTIONAL emails to invite (for a meeting between people); omit it for a personal blocker. Set 'meet':true when the user wants a video call / Google Meet / online meeting (a real Meet link is created if their Google is connected). Use the Current time to resolve 'today/tomorrow/Friday 2pm'. ")
            append("timer=seconds (e.g. 3600); alarm=\"HH:MM\" 24h. ")
            append("Empty array if nothing to do.), ")
            append("\"remember\" (a durable personal fact worth saving, e.g. name/preference/relationship — or empty). ")
            append("ASSISTANT BEHAVIOR: act like a sharp personal assistant, not just a command runner. ")
            append("If the request is ambiguous or missing something you need to act well — which person, " +
                "what time, which app, or which of several possible matches — put ONE brief clarifying " +
                "question in \"say\" and return an EMPTY actions array. Never guess on anything that sends a " +
                "message, books time, spends money, or posts publicly; ask first. ")
            append("CONFIRM BEFORE ACTING: for consequential or hard-to-undo steps — sending a message/SMS, " +
                "posting publicly, creating an event that invites OTHER people, anything spending money — do NOT " +
                "put it in actions on the first turn. Instead state exactly what you'll do in \"say\" and ask for a " +
                "yes (e.g. \"I'll text Anna: 'running 10 late' — send it?\"), with an EMPTY actions array. Only AFTER " +
                "the user confirms (their next message is yes/go/send/do it) do you emit the action. Benign steps " +
                "(open_app, open_url, web_search, play_music, navigate, timer, alarm, checklist_add) run immediately, no asking. ")
            append("When you DO complete something and a follow-up would genuinely help, add a short, " +
                "relevant offer to \"say\" (e.g. \"Want a reminder an hour before?\" or \"Should I invite anyone?\"). " +
                "Keep it to at most ONE question or offer — never pepper the user, and skip the follow-up entirely " +
                "when the task is obviously finished. Be warm and concise, like a great human assistant.")
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
        val sys = persona(memory) + "Answer the question about the photo concisely, in your own natural voice."
        val (code, text) = callContent(sys, content, 600)
        return if (code == 200) text.trim() else "Couldn't read the image ($code)."
    }

    /**
     * "What did I miss?" — brief the owner (spoken TO them, not as them): a short digest of recent
     * activity plus who is genuinely waiting for a personal reply.
     */
    fun catchUp(notifications: List<String>, awaiting: List<String>, memory: String = ""): String {
        if (notifications.isEmpty() && awaiting.isEmpty()) return "You're all caught up — nothing waiting. ✨"
        val sys = (if (memory.isNotBlank()) "About the person you're briefing: $memory. " else "") +
            "You are SlyOS giving the owner an accurate catch-up. Use ONLY the data provided below — do not " +
            "invent senders, messages, or events, and do not generalize (never say things like 'updates from " +
            "LinkedIn you may have missed'). Reference specific people and what they actually said. " +
            "Write 1–3 short, plain-English lines summarizing what genuinely matters. Then a NEW line starting " +
            "with 'Text back: ' naming ONLY people from the 'AWAITING YOUR REPLY' list who still need a personal " +
            "response (comma-separated names). If that list is empty, write exactly 'Text back: nobody right now.' " +
            "Never put someone in 'Text back' who isn't in the awaiting list. No markdown, no bullet points."
        val user = buildString {
            append("AWAITING YOUR REPLY (these people sent the last message and you haven't answered):\n")
            append(if (awaiting.isEmpty()) "(none)" else awaiting.joinToString("\n"))
            append("\n\nOTHER RECENT NOTIFICATIONS (context only — not necessarily needing a reply):\n")
            append(if (notifications.isEmpty()) "(none)" else notifications.joinToString("\n"))
        }
        val (code, text) = callContent(sys, user, 600)
        return if (code == 200) text.trim() else "Couldn't build your catch-up ($code)."
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
        val author = if (memory.isNotBlank()) "You ARE the author; write in first person as them. About you: $memory. " else ""
        val voice = if (styleProfile.isNotBlank()) "Match this exact writing voice: $styleProfile. " else ""
        val reddit = platform.lowercase().contains("reddit")
        val sys = author + voice + if (reddit) {
            "Write a sharp, contrarian tech post for Reddit. Format EXACTLY: a punchy HEADLINE on the " +
            "first line, then a blank line, then a meaty body of 4–8 sentences across 2–3 short " +
            "paragraphs that actually argues a real point with a concrete example or two. Be confident " +
            "and a little funny, but the insight comes first — go after hype, cargo-cult trends, bloated " +
            "frameworks and buzzword soup, not real named people or groups; no slurs or harassment. " +
            "Plain conversational Reddit voice, no hashtags, no emoji spam. Return ONLY the headline + body."
        } else {
            "Write ONE great post for X in the author's own voice. Rules that make it good, not cringe: " +
            "lead with a specific, concrete claim or observation — never a generic 'hot take', never the " +
            "words 'hot take' or 'unpopular opinion'. Say something only someone who actually builds would " +
            "say; there must be a real, defensible point under it. Confident and dry — wit comes from the " +
            "specificity, not from trying hard. No hashtags. No emoji unless one genuinely lands. Don't " +
            "explain the joke or add a second sentence that softens it. Under 260 characters, one tight " +
            "thought. Punch up at ideas, hype and trends — never real named people, no slurs or harassment. " +
            "Return ONLY the post text, no quotes around it."
        }
        val user = if (topic.isBlank()) "Write a sharp tech take in your voice." else "Topic: $topic"
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

    /**
     * Build Zenodo publishing metadata for maximum discoverability: a polished plain-text description
     * (abstract-style, no HTML) plus 6-10 specific search keywords. Returns Pair(description, keywords).
     */
    fun zenodoMeta(title: String, plainText: String): Pair<String, List<String>> {
        val sys = "You prepare repository metadata for an academic paper so it looks professional and ranks well " +
            "in search. Reply with ONLY compact JSON: {\"description\":\"...\",\"keywords\":[\"...\"]}. " +
            "description = a polished, professional abstract-style summary of 2-4 substantial paragraphs " +
            "(roughly 180-320 words, scaled to the paper's depth) that covers: the problem/motivation, the " +
            "approach or method, the key findings or contributions, and why it matters. Plain prose, NO markdown, " +
            "NO HTML; separate paragraphs with a blank line (\\n\\n). Write it like a real journal abstract — " +
            "specific and confident, not vague marketing. " +
            "keywords = 6-12 specific, search-relevant terms (fields, methods, technologies, domain), no generic filler."
        val u = "TITLE: $title\n\nPAPER TEXT (excerpt):\n" + plainText.take(9000)
        val (code, text) = callMessages(sys, JSONArray().put(JSONObject().put("role", "user").put("content", u)), 1200, MODEL)
        if (code != 200) return "" to emptyList()
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val o = JSONObject(text.substring(s, e + 1))
            val desc = o.optString("description").trim()
            val arr = o.optJSONArray("keywords")
            val kws = if (arr != null) (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() } else emptyList()
            desc to kws
        } catch (ex: Exception) { "" to emptyList() }
    }

    /** Spicy-but-constructive reply to a comment/mention on a social post. */
    fun draftCommentReply(comment: String, memory: String = ""): String {
        val sys = persona(memory) +
            "You're writing a short public reply on X/social to the post below. It might be a reply to your " +
            "own post, a mention, or just something on your timeline — and you DON'T have the full thread. " +
            "HARD RULES: Never claim or imply the post is the 'wrong thread', misdirected, cut off, or meant " +
            "for someone else — just respond naturally to what's actually in front of you. Don't reuse a stock " +
            "line; vary your wording every time. " +
            "WHAT MAKES IT GOOD (this matters — most AI replies read as cringe): say something SPECIFIC to the " +
            "actual post, not a generic agreeable filler. No 'hot take', no 'this 👏 is 👏 it', no hashtags, no " +
            "starting with 'Honestly' or 'Real'. At most one emoji and only if it truly fits. Confident and dry; " +
            "the wit comes from the specific point, never from trying hard. One clean thought — don't tack on a " +
            "second sentence that explains or softens it. " +
            "If the post is political, about migration, crime, tragedy, identity, or anything inflammatory or " +
            "outside your world (you build on-device / edge-AI), do NOT engage the substance, take a side, " +
            "moralize, or get baited into a debate — either skip it with a light human one-liner or pivot briefly " +
            "and wittily to what you actually care about, without sounding preachy or canned. " +
            "If the post IS about tech / AI / startups / building, give a genuinely sharp, specific take with a " +
            "real point under it. Sound like a real person — 1 line, ideally well under 200 characters. " +
            "Return ONLY the reply text, no surrounding quotes."
        val (code, text) = callMessages(
            sys, JSONArray().put(JSONObject().put("role", "user").put("content", "The post you're replying to:\n\"$comment\"")),
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

    /**
     * Keep the DOCUMENT clean: remove the model's conversational chatter and leaked markdown so it
     * never ends up inside the paper. The "what I did / want me to…" talk belongs in the chat reply,
     * not the PDF. Fixes the bug where notes like "Want me to write §10 next?" got appended to papers.
     */
    private fun stripChatter(frag: String): String {
        var f = frag
        f = Regex("(?im)^\\s*-{3,}\\s*$").replace(f, "")                       // markdown horizontal rules
        f = Regex("(?s)\\*\\*(.+?)\\*\\*").replace(f) { "<strong>${it.groupValues[1]}</strong>" }  // leaked **bold**
        // Cut a trailing conversational note (only if it appears in the back half of the fragment).
        val markers = listOf("Note on the gap", "Want me to", "Shall I write", "Let me know if you",
            "Do you want me", "Should I write", "Next, I can", "I can also write", "Would you like me",
            "That's the chapter", "That's the section", "That's the new", "Here's the chapter",
            "Here's the new", "The throughline", "To summarize what I", "In short, that")
        var cut = f.length
        for (m in markers) { val i = f.indexOf(m, ignoreCase = true); if (i in 1 until cut && i > f.length * 0.5) cut = i }
        if (cut < f.length) {
            val tail = f.lastIndexOf("</", cut)                              // back up to a clean tag boundary
            if (tail > 0) { val gt = f.indexOf('>', tail); if (gt in 0 until cut) cut = gt + 1 }
            f = f.substring(0, cut)
        }
        return f.trim().removeSuffix("---").trim()
    }

    /**
     * The conversational REPLY shown in the chat beside the paper — so you get a real response (like
     * the Claude web app) telling you what was written and which sources were used, separate from the
     * document itself. Cheap Haiku call.
     */
    fun researchNote(instruction: String, label: String, fragmentText: String, sources: List<String>): String {
        val sys = "You are the user's research-writing collaborator, replying in the chat next to their paper. " +
            "They asked you to: \"$instruction\". You just completed it (section: \"$label\"). " +
            "Reply the way Claude would in chat: 2-4 warm, specific sentences, FIRST PERSON, plain conversational " +
            "prose (NO markdown, NO headers, NO bullet lists). Tell them concretely what you wrote and the key " +
            "points or structure you added, so they know what happened without opening the document. " +
            "Do NOT invent sources or claim web research that isn't reflected in the excerpt."
        val u = "What I wrote (plain-text excerpt):\n" + fragmentText.take(2200) +
            (if (sources.isNotEmpty()) "\n\nSources actually cited (with URLs): " + sources.joinToString(", ")
             else "\n\n(No web sources with URLs were retrieved this time.)")
        val (code, text) = callMessages(sys, JSONArray().put(JSONObject().put("role", "user").put("content", u)), 450, MODEL)
        return if (code == 200) text.trim().trim('"') else "Done — I updated “$label”."
    }

    /**
     * Detect when the model returned a conversational REFUSAL / apology / "I can't touch your
     * document" meta-reply instead of real paper content — so we show it in the chat and NEVER
     * splice it into the document (the bug where an apology became a "New chapter").
     */
    private fun looksLikeRefusal(frag: String): Boolean {
        // A real paper fragment ALWAYS contains an <h2> chapter heading (that's what expandPaper and
        // reviseChapter are required to produce). A conversational apology/refusal won't. That gate
        // alone is enough to keep genuine content — only a short, heading-less, first-person "I can't
        // touch your document" reply is treated as a refusal. (Never match generic phrases like
        // "as an AI", which appear constantly in legitimate AI/ML papers.)
        if (Regex("(?i)<h2[ >]").containsMatchIn(frag)) return false
        val plain = frag.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (plain.length > 900) return false
        val s = plain.lowercase()
        val tells = listOf(
            "i can't actually", "i cannot actually", "i can't touch your", "i can't edit your",
            "i can't see what's on", "i can't retroactively", "i should've been clearer",
            "i can't directly edit", "i don't have access to your", "i'm not able to edit",
            "what i can do is help you", "paste me the", "share the formatting markup",
            "what works best for you")
        return tells.any { s.contains(it) }
    }

    /** Opus call with one retry on transient 5xx errors. Big token budget for long-form writing. */
    private fun paperCall(sys: String, userContent: String, web: Boolean, maxTokens: Int = 16000): Pair<Int, String> {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", userContent))
        val tools = if (web) webTool() else null
        val to = if (web) 280000 else 240000
        var (code, text) = callMessages(sys, msgs, maxTokens, OPUS, to, tools)
        if (code in 500..599) {
            try { Thread.sleep(1200) } catch (e: Exception) {}
            val r = callMessages(sys, msgs, maxTokens, OPUS, to, tools); code = r.first; text = r.second
        }
        return code to text
    }

    /**
     * Decide how a free-form instruction should change a paper, so the UI can be a single prompt bar.
     * Returns Pair(action, target): action = "add" (new chapter) or "edit"; target = the exact existing
     * heading to edit, "INTRO" for title/abstract, or "" for add. Defaults to "add" (never destructive).
     */
    fun routePaperEdit(title: String, outline: String, instruction: String): Pair<String, String> {
        val sys = "You manage edits to a paper titled \"$title\". Given its chapter headings and the user's " +
            "instruction, reply with ONLY compact JSON: {\"action\":\"add\"|\"edit\",\"target\":\"...\"}. " +
            "Use \"add\" when they want NEW material or a new chapter/section not already present (target=\"\"). " +
            "Use \"edit\" to change/expand/shorten/rewrite/fix an EXISTING part: set target to the EXACT heading " +
            "text from the list it refers to, or \"INTRO\" for the title/abstract/introduction/table of contents " +
            "(any request to fix, clean, rebuild or remove the table of contents → target \"INTRO\"). If unsure, prefer add.\n" +
            "HEADINGS:\n" + outline.ifBlank { "(none yet)" }
        val (code, text) = callMessages(sys, JSONArray().put(JSONObject().put("role", "user").put("content", instruction)), 150, MODEL)
        if (code != 200) return "add" to ""
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val o = JSONObject(text.substring(s, e + 1))
            val a = if (o.optString("action") == "edit") "edit" else "add"
            a to o.optString("target").trim()
        } catch (ex: Exception) { "add" to "" }
    }

    /** Revise a paper's FRONT MATTER (everything before the first chapter) in place. Returns HTML or ERR. */
    fun reviseFrontMatter(headHtml: String, instruction: String, memory: String = ""): String {
        val sys = "You are editing the FRONT MATTER of an HTML research paper — the part before the first " +
            "real chapter: the <head> (with its MathJax <script> tag), the <title>, the centered paper title, " +
            "the author line, the abstract, and (if present) a table of contents. Apply the instruction to that " +
            "front matter only. If the user asks to fix/clean/remove the table of contents, do exactly that here " +
            "(rebuild it as a clean <nav class=\"toc\"><h2>Contents</h2><ol>…</ol></nav> listing the chapter " +
            "titles, or remove it). Drop any stray placeholder lines (e.g. \"I'll research…\"). " +
            (if (memory.isNotBlank()) "Author: $memory. " else "") +
            "Return the SAME front matter as HTML: it MUST begin with <!DOCTYPE or <html, KEEP the full <head> " +
            "with the MathJax script unchanged, include the opening <body ...> tag, the title/author/abstract — " +
            "and MUST NOT include any numbered <h2> chapter or a closing </body>/</html>. Return ONLY that HTML."
        val (code, text) = paperCall(sys, "INSTRUCTION: $instruction\n\nFRONT MATTER:\n${headHtml.take(20000)}", false, 8000)
        if (code != 200) return "ERR::$code::${text.take(400)}"
        val frag = cleanHtml(text)
        return if (frag.contains("<body", true)) frag else "ERR::0::front-matter edit malformed"
    }

    /** Genre + voice lock + verified-citation rules + thesis anchor, shared by all paper calls. */
    private fun docBrief(docType: String, thesis: String): String {
        val genre = when (docType) {
            "memo" -> "Write as a crisp INVESTOR MEMO: lead with the thesis, then market, why-now, product, traction, and the ask. Confident, plain business prose. No theorems or proofs. "
            "whitepaper" -> "Write as a professional technical WHITE PAPER: authoritative, precise, neutral third person; explain the system, its design and rationale; minimal heavy math unless essential. "
            else -> "Write as a rigorous ACADEMIC PAPER: formal third person, precise definitions, claims and proofs where appropriate. "
        }
        return genre +
            "Keep ONE consistent professional voice and genre throughout — no casual asides, no snark, no jokes, no first-person quips. " +
            "Output ONLY publishable document content. NEVER address the reader or describe your own process — no closing remarks like \"That's the chapter\", \"the throughline is…\", \"grounded in real hardware\", or any summary of what you just wrote. End on the actual prose or the references, nothing else. " +
            "CITATIONS: cite ONLY sources you actually retrieved via web search. Put references in an <ol class=\"references\"> " +
            "list. Every entry MUST name the source AND show its full URL as VISIBLE text wrapped in a real link, e.g. " +
            "<li>Author/Org. Title. <a href=\"https://example.com/x\">https://example.com/x</a></li> — the printed URL must " +
            "be visible on the page, not just a hidden hyperlink, and never deferred to an appendix or written as 'URL available'. " +
            "Never list a source without its working link. " +
            "Never invent papers, arXiv IDs, statistics, or quotes, and never attribute an estimate or quote to a named real person or company unless you genuinely found it with a link. If you did not retrieve a real source for a claim, soften or drop the claim rather than fabricate a citation. " +
            (if (thesis.isNotBlank()) "CORE THESIS — stay anchored to this, do not drift into adjacent topics: $thesis. " else "")
    }

    /** Write a research/white paper (Opus). Returns HTML, or "ERR::code::body" on failure. */
    fun writePaper(prompt: String, source: String = "", web: Boolean = false, memory: String = "", library: String = "", docType: String = "paper", thesis: String = ""): String {
        val src = if (web) source.take(2500) else source.take(12000)
        val sys = docBrief(docType, thesis) +
            "You are an expert research writer. Write a well-structured document IN THE USER'S NAME on their topic. " +
            "Output a COMPLETE self-contained HTML document in a clean LaTeX-article style: a centered <h1> title, a " +
            "centered author/affiliation line directly under it (use <p class=\"author\">), then the abstract wrapped " +
            "in <div class=\"abstract\"> with a bold run-in 'Abstract.' label. " +
            "After the abstract, include a Table of Contents: <nav class=\"toc\"><h2>Contents</h2><ol>…</ol></nav> " +
            "listing each chapter title (no page numbers). " +
            "Structure the body as MULTIPLE NUMBERED CHAPTERS, each one an <h2> heading like " +
            "<h2>1. Introduction</h2>, with <h3> subsections, detailed multi-paragraph prose, examples. " +
            "Put EVERY citation in ONE <h2>References</h2> section at the very END as an <ol class=\"references\"> — " +
            "never scatter reference lists inside chapters. Write thoroughly — many pages of real content, not a summary. " +
            "Do NOT open the document or any chapter with meta/placeholder lines like \"I'll research…\", \"In this " +
            "section I will…\", or notes to the reader about your process — start directly with substantive prose. " +
            "Render math with MathJax — include exactly this in <head>: " +
            "<script src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\"></script> and write " +
            "math as \\( inline \\) and $$ display $$. Use a plain WHITE #ffffff background, near-black text, generous margins. " +
            (if (memory.isNotBlank()) "About the author (reflect their context/voice): $memory. " else "") +
            (if (web) "Use web search to find current, real sources and CITE them with links in the references. " else "") +
            (if (src.isNotBlank()) "Also ground it in this source material:\n$src\n" else "") +
            (if (library.isNotBlank()) "Draw on the author's OWN earlier papers for consistency and to build on " +
                "their prior work where relevant (don't copy verbatim):\n$library\n" else "") +
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

    /**
     * Grow a paper without rewriting it: generate ONE new chapter/section as an HTML fragment,
     * given the outline of what already exists. This is how a paper reaches hundreds of pages —
     * each call appends ~several pages instead of regenerating (and truncating) the whole doc.
     * Returns an HTML fragment (no <html>/<head>/<body>), or "ERR::code::body".
     */
    fun expandPaper(title: String, outline: String, instruction: String, web: Boolean = false, memory: String = "", library: String = "", docType: String = "paper", thesis: String = ""): String {
        val sys = docBrief(docType, thesis) +
            "You are extending an existing document titled \"$title\". " +
            "Write the NEXT chapter/section the user asks for — substantial and rigorous (aim for several " +
            "pages: multiple subsections, detailed prose, examples, and equations where useful). " +
            "Continue the existing numbering and do NOT repeat earlier content. " +
            "Output ONLY an HTML FRAGMENT to append to the body: start with an <h2> chapter heading and " +
            "its content (use <h3> subsections, <p>, <ul>, etc.). Do NOT include <html>, <head>, <body>, " +
            "<title>, the abstract, or the references list — only the new chapter markup. " +
            "Render math with MathJax syntax \\( inline \\) and $$ display $$. " +
            (if (memory.isNotBlank()) "About the author (match their voice): $memory. " else "") +
            (if (web) "Use web search for real, current sources and cite them inline as needed. " else "") +
            (if (library.isNotBlank()) "Build on the author's OWN other papers where relevant:\n$library\n" else "") +
            "EXISTING OUTLINE (headings so far):\n$outline\n"
        val (code, text) = paperCall(sys, "Add this next: $instruction", web)
        if (code != 200) return "ERR::$code::${text.take(400)}"
        var frag = cleanHtml(text)
        // Strip anything the model wrapped around the fragment, just in case.
        Regex("(?is)<body[^>]*>(.*)</body>").find(frag)?.let { frag = it.groupValues[1].trim() }
        if (looksLikeRefusal(frag)) return "REFUSAL::" + frag.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return stripChatter(frag)
    }

    /**
     * Revise ONE existing chapter in place (so editing works even on a huge paper, where rewriting
     * the whole document would blow the token limit). Returns the revised chapter as an HTML
     * fragment beginning with its <h2>, or "ERR::code::body".
     */
    fun reviseChapter(title: String, chapterHtml: String, instruction: String, web: Boolean = false, memory: String = "", docType: String = "paper", thesis: String = ""): String {
        val sys = docBrief(docType, thesis) +
            "You are editing ONE chapter of the document \"$title\". Apply the user's instruction " +
            "to THIS chapter only. Return ONLY the revised chapter as an HTML fragment that begins with its " +
            "<h2> heading (keep the same heading unless asked to change it), using <h3>, <p>, <ul>, etc. " +
            "Do NOT include <html>, <head>, <body>, the title, abstract, references, or any other chapter. " +
            "Render math with MathJax syntax \\( inline \\) and $$ display $$. " +
            (if (memory.isNotBlank()) "About the author (match their voice): $memory. " else "") +
            (if (web) "Use web search for any new facts/sources and cite them. " else "")
        val (code, text) = paperCall(sys, "INSTRUCTION: $instruction\n\nCHAPTER HTML:\n${chapterHtml.take(30000)}", web)
        if (code != 200) return "ERR::$code::${text.take(400)}"
        var frag = cleanHtml(text)
        Regex("(?is)<body[^>]*>(.*)</body>").find(frag)?.let { frag = it.groupValues[1].trim() }
        if (looksLikeRefusal(frag)) return "REFUSAL::" + frag.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return stripChatter(frag)
    }

    /** Shared Architect brief: design rules + the SlyOS bridge API available to every mini-app. */
    private fun architectSys(): String =
        "You are the SlyOS Architect. Build a genuinely USEFUL app as ONE self-contained HTML document — " +
        "inline CSS and JS only, no external URLs/fonts/libraries (runs offline in a WebView). Not a mockup: " +
        "real, working functionality with sensible defaults and empty states.\n" +
        "LAYOUT (must stay inside the phone frame — apps currently overflow, fix that): include " +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">; use " +
        "* { box-sizing:border-box } and html,body { margin:0; width:100%; max-width:100%; overflow-x:hidden }; " +
        "size everything in %/vw/rem, never fixed px widths; wrap long text; make it responsive to a narrow screen.\n" +
        "STYLE: warm ivory #F4EFE6 background, near-black #1A1714 text, ONE orange accent #E8642C, rounded corners, " +
        "generous padding, Apple-like minimalism, system sans-serif, comfortable tap targets (min 40px high).\n" +
        "SLYOS BRIDGE (already injected — use it to make the app smart and persistent; never redefine it):\n" +
        "• SlyOS.save(key, value)  — persist data (value can be an object; it's auto-JSON'd).\n" +
        "• SlyOS.load(key)  — returns the saved value (parsed) or null. Load on startup to restore state.\n" +
        "• SlyOS.memory()  — returns the user's profile/notes string (who they are). Personalize with it.\n" +
        "• SlyOS.remember(fact)  — write a durable note back into the user's brain/memory graph.\n" +
        "• await SlyOS.ask(prompt)  — returns an AI text answer (Promise). Use for any intelligence: " +
        "summarize, classify, generate, advise. For structured data, ask it to 'reply ONLY with JSON' and JSON.parse.\n" +
        "Prefer apps that actually use save/load (so data survives) and ask/memory (so they're intelligent and personal).\n" +
        "OUTPUT FORMAT: first line = a short app name (2-4 words), then a line with only ---, then the full HTML document."

    private fun parseArchitect(code: Int, text: String): Pair<String, String> {
        if (code != 200) return "Build failed" to "<body style='font-family:sans-serif;padding:20px'><h3>Couldn't build ($code)</h3><p>$text</p></body>"
        val idx = text.indexOf("---")
        val name = (if (idx > 0) text.substring(0, idx) else "New app").trim().take(40).ifBlank { "New app" }
        var html = (if (idx > 0) text.substring(idx + 3) else text).trim()
        if (html.startsWith("```")) html = html.substringAfter('\n', html).trim()
        if (html.endsWith("```")) html = html.removeSuffix("```").trim()
        return name to html
    }

    /**
     * Local Cowork — a provider-agnostic agentic loop. The model works on real on-device files by
     * replying with ONE JSON tool call per turn (no native tool-use API needed, so it runs on Claude,
     * GPT OR Gemini). The screen executes the tool, feeds back the result, and repeats until "done".
     */
    private fun coworkSys(memory: String): String =
        "You are SlyOS Cowork — a capable local agent that ACTUALLY CREATES REAL FILES on the user's phone, like " +
        "a desktop coding/writing assistant. NEVER paste code into chat. Work step by step, ONE action per reply, " +
        "using this EXACT plain-text format (NOT JSON — do not escape anything):\n\n" +
        "List files:\nTOOL list_files\n\n" +
        "Read a file:\nTOOL read_file\nNAME: path.py\n\n" +
        "Create/overwrite a file:\nTOOL write_file\nNAME: server.py\nNOTE: short line about this step\nCONTENT>>>\n" +
        "(the full raw file content here — real newlines, any characters, NO escaping)\n<<<END\n\n" +
        "Add to the end of a file:\nTOOL append_file\nNAME: server.py\nNOTE: …\nCONTENT>>>\n(more raw content)\n<<<END\n\n" +
        "Create a REAL Google Doc (opens online):\nTOOL create_gdoc\nNAME: Doc title\nNOTE: …\nCONTENT>>>\n(the document text)\n<<<END\n\n" +
        "Create a REAL Google Slides deck (separate each slide with a line of just ===, and the FIRST line of each block is that slide's title):\nTOOL create_gslides\nNAME: Deck title\nNOTE: …\nCONTENT>>>\nWhat Belto Is\n- point one\n- point two\n===\nHow It Works\n- point\n<<<END\n\n" +
        "Create a REAL Google Sheet (CSV, first row = headers):\nTOOL create_gsheet\nNAME: Sheet title\nCONTENT>>>\nName,Amount\nRent,1200\n<<<END\n\n" +
        "Create a PDF (saved to Downloads):\nTOOL create_pdf\nNAME: title\nCONTENT>>>\n(the text)\n<<<END\n\n" +
        "Run a REAL shell command in Termux (a full Linux env: python, pip, git, node, compilers — the user must have Termux):\nTOOL run_command\nNOTE: …\nCONTENT>>>\npython3 --version\n<<<END\n" +
        "Use run_command to actually BUILD AND RUN things locally: install packages (pip/npm), write files (heredoc: cat > app.py <<'EOF' … EOF), run scripts, start/test servers. I return the stdout/stderr. If it says Termux isn't installed, tell the user how to set it up and fall back to writing files. \n" +
        "SHARED PROJECT DIR: every run_command starts in ~/storage/downloads/SlyOS — the ONE shared folder Termux and the phone both see (the phone shows it as Downloads/SlyOS). Build real code PROJECTS here (not in the doc workspace) so the user can open the files and so build→run→push all happen in one place. Use relative paths from there. \n\n" +
        "SHIP IT — GitHub + deploy: pushing to GitHub is one-tap. If the user saved a GitHub token in Settings, run_command already exports GH_TOKEN and configures git credentials for you — so `gh` and `git push` work with NO login prompt. To publish: write the project into a Termux-readable dir first (run `termux-setup-storage` once, then work under ~/storage/downloads/SlyOS or ~/proj), then `cd` there and run 'gh repo create <name> --public --source=. --push'. Put the repo URL in DONE. If gh reports it's not authed, tell the user to paste a GitHub token in Settings ▸ Connections (github.com/settings/tokens, scope repo). To DEPLOY an Android app to THIS phone, build the APK with gradle ('./gradlew assembleDebug') then 'termux-open path/to/app.apk' opens the installer — put the APK path + 'tap Install' in DONE. Always tell the user exactly how to open/launch what you built. " +
        "When the user wants slides/a doc/a sheet 'in Google' or something to open online, use the Google tools above (Google must be connected). Include any link the tool returns in your final DONE message. " +
        "When the ENTIRE task is complete:\nDONE\n(a short summary + any link + how to run/deploy)\n\n" +
        "RULES: All code goes inside CONTENT blocks — never in chat, never in the DONE summary (the DONE summary is " +
        "plain English + shell commands only). Read a file before editing. For a big file, write_file the first part " +
        "then append_file the rest in a few chunks. Reply with ONLY one action — nothing before or after it. After " +
        "each action I send you the result; keep going until done, then reply DONE. " +
        "DESIGN BAR: when you build HTML/web files (pages, decks, one-pagers), make them STUNNING — " +
        "award-winning modern design, not a plain template: confident typography with a real type scale, " +
        "generous whitespace, a cohesive color palette, subtle gradients/shadows/rounded corners, strong " +
        "visual hierarchy, and responsive layout. Treat every document as a portfolio piece. " +
        (if (memory.isNotBlank()) "About the user (use when relevant): $memory. " else "")

    /** One turn of the Cowork loop. [messages] = the running user/assistant transcript. Returns raw text. */
    fun coworkTurn(messages: JSONArray, memory: String = ""): String {
        val (code, text) = callMessages(coworkSys(memory), messages, 20000, OPUS)
        return if (code == 200) text.trim() else "DONE\nI hit an error ($code) — likely a rate limit or key issue. Try routing Heavy work to Claude in Settings."
    }

    /** The Architect (Opus 4.8): turn a prompt into a self-contained, bridge-powered mini-app. */
    fun architect(prompt: String): Pair<String, String> {
        val (code, text) = callMessages(
            architectSys(), JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), 8000, OPUS, 240000
        )
        val (name, html) = parseArchitect(code, text)
        Log.i("SlyOS", "architect name='$name' htmlLen=${html.length}")
        return name to html
    }

    /** Refine an existing mini-app per an instruction, keeping it self-contained + bridge-powered. */
    fun reviseApp(currentHtml: String, instruction: String): Pair<String, String> {
        val sys = architectSys() + "\nYou are REVISING an existing app. Apply the instruction, keep everything that " +
            "still works (including any SlyOS.save/load data usage), and return the COMPLETE updated document."
        val user = "INSTRUCTION: $instruction\n\nCURRENT APP HTML:\n${currentHtml.take(40000)}"
        val (code, text) = callMessages(sys, JSONArray().put(JSONObject().put("role", "user").put("content", user)), 8000, OPUS, 240000)
        return parseArchitect(code, text)
    }

    /** Distill a concise "how I write" profile from real sample messages the user sent. */
    fun learnStyle(samples: List<String>): String {
        if (samples.isEmpty()) return ""
        val sys = "Analyze these real messages the user has sent and write a tight profile of HOW THEY WRITE — " +
            "tone, formality, sentence length, punctuation/capitalization habits, emoji use, slang, greetings/sign-offs, " +
            "and any verbal tics. 4–6 sentences, concrete and imitable, second person ('You tend to…'). " +
            "Describe ONLY their style, not the content."
        val joined = samples.take(120).joinToString("\n").take(9000)
        val (code, text) = callContent(sys, "MESSAGES:\n$joined", 500, VOICE)
        return if (code == 200) text.trim() else "[error $code: ${text.take(150)}]"
    }

    /** A warm, genuine message to reconnect with someone you've gone quiet on. In the owner's voice. */
    fun reconnectMessage(name: String, lastSnippet: String, daysSince: Int, memory: String = ""): String {
        val sys = persona(memory) +
            "You haven't spoken with $name in about $daysSince days. Write a short, warm, genuine message to " +
            "reconnect — natural and human, not salesy or generic, light and easy to reply to. Reference your last " +
            "exchange only if it helps. One or two lines, in your voice. Return ONLY the message."
        val user = if (lastSnippet.isNotBlank()) "Your last exchange was: \"$lastSnippet\"" else "You don't have the last message saved."
        val (code, text) = callContent(sys, user, 240)
        return if (code == 200) text.trim() else "[couldn't draft: $code]"
    }

    /** A genuine first-touch opener to a connection you've never actually spoken with. Owner's voice. */
    fun introMessage(name: String, company: String, role: String, source: String, memory: String = ""): String {
        val sys = persona(memory) +
            "You're connected with $name on $source but have never actually spoken. Write a short, warm, SPECIFIC " +
            "opener to start a real conversation — never a mass-blast or a pitch, no 'hope you're well' filler. " +
            "Reference what you can about them if useful. One or two lines, easy to reply to, in your voice. Return ONLY the message."
        val who = "Connection: $name" + (if (role.isNotBlank()) " — $role" else "") + (if (company.isNotBlank()) " at $company" else "")
        val (code, text) = callContent(sys, who, 240)
        return if (code == 200) text.trim() else "[couldn't draft: $code]"
    }

    /** Backing AI call for a mini-app's SlyOS.ask(). Concise, returns plain text (or JSON if asked). */
    fun appAsk(prompt: String, memory: String = ""): String {
        val sys = (if (memory.isNotBlank()) "User context (use if relevant): $memory. " else "") +
            "You are the intelligence inside a small app the user built. Answer the request directly and concisely. " +
            "If the app asks for JSON, return ONLY valid JSON with no prose or code fences."
        val (code, text) = callContent(sys, prompt, 1200)
        return if (code == 200) text.trim() else "Sorry, I couldn't process that."
    }

    /** Conversational reply for the Telegram bot (uses the document if relevant). */
    fun telegramReply(text: String, doc: String = "", memory: String = ""): String {
        val sys = persona(memory) +
            "You are texting on Telegram. Reply helpfully, warm and concise. " +
            (if (doc.isNotBlank()) "If relevant, use this document:\n$doc\n" else "")
        val (code, t) = call(sys, text)
        return if (code == 200) t.trim() else "Hmm, I hit an error ($code)."
    }

    /** Answer a question using ONLY the provided document excerpts — still in the owner's voice. */
    fun answerFromDoc(question: String, excerpts: String, memory: String = ""): String {
        if (excerpts.isBlank()) return "No document is loaded yet."
        val sys = persona(memory) +
            "Answer the question using ONLY the document excerpts below — if the answer isn't in them, " +
            "say it's not in the document and don't make things up. Keep it natural and texty, in your " +
            "own voice, not a formal report.\nDOCUMENT:\n" + excerpts
        val (code, text) = call(sys, question)
        return if (code == 200) text.trim() else "Couldn't check the document ($code)."
    }

    /**
     * Semantic-ish query expansion: turn a question into related keywords/synonyms/names that might
     * actually appear in messages, so keyword search retrieves by meaning, not just exact words.
     * (Until on-device embeddings exist, this is the lightweight semantic layer.)
     */
    fun expandQuery(query: String): List<String> {
        val sys = "Expand this search into 6–12 related keywords, synonyms, and likely phrasings someone " +
            "would have actually typed in casual chat messages about it. Include obvious variants. " +
            "Reply with ONLY a comma-separated list of lowercase words/short phrases — no explanation."
        val (code, text) = callContent(sys, query, 120)
        if (code != 200) return emptyList()
        return text.lowercase().split(",", "\n").map { it.trim() }.filter { it.length in 2..30 }.take(14)
    }

    /** Natural-language Q&A over the user's memories. Returns an answer. */
    fun askMemory(query: String, memories: List<String>): String {
        if (memories.isEmpty()) return "Your memory is empty so far — as you chat, reply, and learn, it fills up."
        val sys = "You are SlyOS memory. Answer the user's question grounded in the memories below — you " +
            "MAY reason over, filter, group, and RANK them to give a direct, helpful answer (e.g. 'which VCs " +
            "are most relevant' → pick and order the best-fitting people from the list and say why in a few words " +
            "each). Be specific; quote names, companies, roles. Only claim facts present in the memories; if there's " +
            "genuinely nothing relevant, say so. Give a straight answer, not a disclaimer. " +
            "Write in PLAIN TEXT — no markdown, no ** asterisks **, no # headers; if you list people use simple " +
            "'• ' bullets, one per line.\n" +
            "MEMORIES:\n" + memories.joinToString("\n")
        val (code, text) = callContent(sys, query, 900)
        return if (code == 200) text.trim() else "Couldn't search memory ($code)."
    }

    data class MissionAssessment(val percent: Int, val argument: String, val next: String)

    /**
     * Assess how close the brain is to the user's standing MISSION, grounded in real brain context.
     * Returns an honest percent (0-100), a short argument for that number, and 2-3 concrete next steps.
     */
    fun assessMission(mission: String, context: String, sinceDesc: String): MissionAssessment {
        if (mission.isBlank()) return MissionAssessment(0, "No mission set yet.", "Set a goal to track.")
        val sys = "You are SlyOS assessing progress toward the user's goal, acting as their strategist. " +
            "Be HONEST and specific — no cheerleading. Use ONLY the evidence in the context below (their " +
            "messages, contacts, calendar, tasks, papers). If there's little evidence of progress, the " +
            "percent should be low. Estimate a single completion percentage (0-100) for the goal, give a " +
            "2-3 sentence argument citing concrete evidence (or its absence), and list 2-3 specific next " +
            "actions that would move the needle most. Reply ONLY as JSON: " +
            "{\"percent\":0-100,\"argument\":\"…\",\"next\":\"1) …\\n2) …\\n3) …\"}"
        val user = "GOAL: $mission\nTracking since: $sinceDesc\n\nEVIDENCE FROM THE BRAIN:\n" +
            context.ifBlank { "(little data yet)" }
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val (code, text) = callMessages(sys, msgs, 700, VOICE)
        if (code != 200) return MissionAssessment(-1, "Couldn't assess ($code). Try routing Heavy/replies to Claude if this is a rate limit.", "")
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val o = JSONObject(text.substring(s, e + 1))
            MissionAssessment(o.optInt("percent", 0).coerceIn(0, 100), o.optString("argument").trim(), o.optString("next").trim())
        } catch (ex: Exception) {
            MissionAssessment(-1, text.trim().take(300).ifBlank { "Couldn't parse the assessment." }, "")
        }
    }

    /**
     * Break a mission into 4-6 concrete, sequenced milestones — a real plan the user can check off.
     * Returns the milestone texts (empty on failure).
     */
    fun planMission(mission: String, context: String): List<String> {
        if (mission.isBlank()) return emptyList()
        val sys = "You are SlyOS, the user's strategist. Break their goal into 4-6 CONCRETE, sequenced " +
            "milestones — specific and checkable (e.g. 'Draft an offer + list 20 target leads', not 'work hard'). " +
            "Tailor them to the evidence about the user. Each ≤ 14 words, action-first. " +
            "Reply ONLY as JSON: {\"milestones\":[\"…\",\"…\",\"…\"]}"
        val user = "GOAL: $mission\n\nWHAT I KNOW ABOUT THE USER:\n" + context.ifBlank { "(little data yet)" }
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val (code, text) = callMessages(sys, msgs, 500, VOICE)
        if (code != 200) return emptyList()
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val arr = JSONObject(text.substring(s, e + 1)).getJSONArray("milestones")
            (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotBlank() }
        } catch (ex: Exception) { emptyList() }
    }

    data class MissionMove(val label: String, val draft: String, val task: String)

    /**
     * Produce the single most impactful CONCRETE next action toward the mission, and actually draft it
     * — a ready-to-send outreach message, a post, an email, an offer, etc., written in the user's voice
     * so they can use it immediately. Also returns a one-line checklist task to track it.
     */
    fun missionNextMove(mission: String, context: String, openMilestone: String): MissionMove {
        if (mission.isBlank()) return MissionMove("", "", "")
        val sys = persona("") +
            "You are working AS this person toward their goal. Pick the ONE highest-leverage concrete " +
            "action to take right now and DO the writing for it — produce a finished, ready-to-use draft. " +
            "IF the goal is about FINDING or REACHING PEOPLE (candidates, customers, leads, hires, cofounders) " +
            "and the context lists 'People in my network relevant to this goal', DO NOT tell them to go search " +
            "elsewhere — instead pick the best real matches from that list and, in the draft, output a numbered " +
            "list where each item is that person's name + why they fit + a short personalized message ready to " +
            "send them. Otherwise produce the single best draft (DM/email/post/offer). Write in their voice, " +
            "specific — no [placeholders] unless truly unknown. " +
            "Reply ONLY as JSON: {\"label\":\"short title of the move\",\"draft\":\"the finished text / the candidate list with a message each\",\"task\":\"a one-line checklist item\"}"
        val user = "GOAL: $mission\n" + (if (openMilestone.isNotBlank()) "CURRENT MILESTONE: $openMilestone\n" else "") +
            "\nCONTEXT ABOUT ME AND MY WORLD:\n" + context.ifBlank { "(little data yet)" }
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val (code, text) = callMessages(sys, msgs, 900, VOICE)
        if (code != 200) return MissionMove("", "", "")
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val o = JSONObject(text.substring(s, e + 1))
            MissionMove(o.optString("label").trim(), o.optString("draft").trim(), o.optString("task").trim())
        } catch (ex: Exception) { MissionMove("Next move", text.trim(), "") }
    }

    // ── Job hunt ─────────────────────────────────────────────────────────────────────────────
    private fun jobCall(sys: String, user: String, maxTokens: Int = 1600): String {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val (code, text) = callMessages(sys, msgs, maxTokens, VOICE)
        return if (code == 200) text.trim() else "[error $code — likely a rate limit. Route Heavy/replies to Claude in Settings and retry.]"
    }

    /** Draft a clean résumé from everything the brain knows (LinkedIn history, about-you, messages). */
    fun jobResumeFromBrain(memory: String): String = jobCall(
        "You are a professional résumé writer. Using ONLY the facts below about the person, write a clean, " +
        "ATS-friendly résumé in plain text (Name, Summary, Experience with dates + bullet achievements, " +
        "Skills, Education). Be honest — never invent employers, titles, or dates. If something's missing, " +
        "leave a clearly marked [add: …] placeholder. No markdown symbols.",
        "WHAT I KNOW ABOUT THE PERSON:\n$memory")

    /** Suggest target roles + a short rationale, when the user isn't sure what to go for. */
    fun jobIdeas(resume: String, memory: String): String = jobCall(
        "You are a career coach. Based on the résumé and background, suggest 4-6 specific job titles worth " +
        "targeting, each with a one-line why-it-fits and a rough seniority. Plain text, '• ' bullets.",
        "RÉSUMÉ:\n$resume\n\nBACKGROUND:\n$memory")

    data class JobLead(val title: String, val company: String, val location: String, val url: String)

    /**
     * Web-search the internet for CURRENT real openings matching the ask, and return them with links.
     * Needs Anthropic routing (web search runs on Claude). Empty list if unavailable.
     */
    fun jobFindOpenings(query: String, memory: String): List<JobLead> {
        val sys = "You are a job scout WITH web search. Search the web for CURRENT, real job openings that " +
            "match the user's request, then return 5-8 of the best. Every posting MUST include a real listing/" +
            "application URL you actually found via search — never invent a URL. Prefer official career pages and " +
            "major boards. Reply ONLY as JSON: {\"jobs\":[{\"title\":\"…\",\"company\":\"…\",\"location\":\"…\",\"url\":\"https://…\"}]}"
        val user = "WHAT I WANT: $query\n\nABOUT ME (for fit): " + memory.take(1500)
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val (code, text) = callMessages(sys, msgs, 2500, VOICE, 120000, webTool())
        if (code != 200) return emptyList()
        return try {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            val arr = JSONObject(text.substring(s, e + 1)).getJSONArray("jobs")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                JobLead(o.optString("title").trim(), o.optString("company").trim(), o.optString("location").trim(), o.optString("url").trim())
            }.filter { it.url.startsWith("http") }
        } catch (e: Exception) { emptyList() }
    }

    private fun stripFences(s: String): String =
        s.trim().removePrefix("```html").removePrefix("```HTML").removePrefix("```").removeSuffix("```").trim()

    /** A clean, populated, print-ready HTML résumé (standard US format) tailored to the posting. */
    fun jobResumeHtmlDoc(resume: String, posting: String): String {
        val html = jobCall(
            "You are an expert résumé writer. Output a COMPLETE, self-contained, print-ready HTML résumé in the " +
            "STANDARD US single-column format — real content, NOT a wireframe. Structure top to bottom: " +
            "(1) Name (large) + contact line (email/phone/city/LinkedIn if known); (2) PROFESSIONAL SUMMARY — 2-3 " +
            "sentences; (3) EXPERIENCE — each role as 'Title, Company — Location (dates)' followed by 3-5 " +
            "achievement bullets with real detail; (4) SKILLS — comma-separated; (5) EDUCATION. " +
            "CRITICAL: fill every section with the ACTUAL details from the history below — write the real bullet " +
            "text, do NOT leave empty boxes, lorem, or placeholder lines. If a detail is genuinely missing, omit " +
            "that line entirely (never leave an empty box). Reword to emphasize what THIS posting wants, but never " +
            "invent employers, titles, or dates. Clean typography: system serif/sans, section headings in small " +
            "caps with a hairline rule, generous line-height, black text on white, one subtle accent color " +
            "(#E8642C) for headings only. Embedded <style>, no external assets/JS. Output ONLY the HTML.",
            "JOB POSTING:\n$posting\n\nMY REAL HISTORY (use this to populate every section):\n$resume", 5000)
        return stripFences(html)
    }

    /** A stunning, print-ready HTML cover letter in the user's voice. Returns a full HTML doc. */
    fun jobCoverHtmlDoc(resume: String, posting: String, memory: String): String {
        val html = jobCall(
            persona(memory) +
            "Output a COMPLETE, self-contained, print-ready HTML cover letter (single <html> doc with embedded " +
            "<style>, no external assets/JS). A4 width, matching the same clean modern style and #E8642C accent as " +
            "a premium résumé: sender block, date, greeting, 3 tight paragraphs (hook, 2-3 concrete achievements " +
            "tied to the role, close + ask), and a sign-off. In the person's own voice, specific, no clichés. " +
            "Output ONLY the HTML.",
            "JOB POSTING:\n$posting\n\nMY RÉSUMÉ / HISTORY:\n$resume", 3000)
        return stripFences(html)
    }

    /** Apply an edit instruction to an existing HTML doc and return the full revised HTML. */
    fun jobReviseHtmlDoc(currentHtml: String, instruction: String): String {
        val html = jobCall(
            "You are editing a print-ready HTML document. Apply the user's requested change and return the " +
            "COMPLETE, revised, self-contained HTML document ONLY — keep the existing visual style and structure, " +
            "change only what they asked, never invent facts.",
            "CHANGE TO MAKE: $instruction\n\nCURRENT HTML:\n$currentHtml", 4000)
        return stripFences(html)
    }

    /** Rewrite the résumé tailored to a specific posting (reorder/emphasize, keep it truthful). */
    fun jobTailorResume(resume: String, posting: String): String = jobCall(
        "You are a résumé expert. Rewrite the résumé to target the job posting: reorder and reword bullets to " +
        "mirror the posting's language and priorities, surface the most relevant experience first, and weave in " +
        "matching keywords — WITHOUT inventing anything untrue. Keep it plain text and ATS-friendly.",
        "JOB POSTING:\n$posting\n\nMY RÉSUMÉ:\n$resume")

    /** A tailored cover letter for the posting, in the user's voice. */
    fun jobCoverLetter(resume: String, posting: String, memory: String): String = jobCall(
        persona(memory) +
        "Write a concise, specific cover letter (250-350 words) for this posting, in the person's own voice — " +
        "warm, confident, no clichés or 'I am writing to apply'. Tie 2-3 concrete achievements to what the role " +
        "needs. Plain text, ready to send.",
        "JOB POSTING:\n$posting\n\nMY RÉSUMÉ:\n$resume")

    /** A short outreach email to the hiring contact, résumé + cover letter 'attached'. */
    fun jobEmail(resume: String, posting: String, memory: String): String = jobCall(
        persona(memory) +
        "Write a SHORT outreach email (under 140 words) to the hiring contact for this posting, in the person's " +
        "voice: a crisp intro, why they're a strong fit in 2 lines, and a clear ask for a conversation. Mention " +
        "the résumé and cover letter are attached. Include a 'Subject:' line. Plain text.",
        "JOB POSTING:\n$posting\n\nMY RÉSUMÉ:\n$resume")

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
        val sys = persona(memory) +
            "Write a reply email from your own account. Sound genuinely human — warm, natural, " +
            "concise and professional; vary sentence length, no robotic filler. " +
            (if (doc.isNotBlank())
                "Ground any factual or technical claims ONLY in this document; if it isn't covered, " +
                "stay general and don't invent specifics:\nDOCUMENT:\n$doc\n" else "") +
            "Return ONLY the email body (greeting, body, sign-off)."
        val (code, text) = call(sys, "Email from $sender:\n$snippet")
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    /** A short, human, personalized outreach email. Returns (subject, body). */
    fun draftOutreach(recipient: String, topic: String, content: String, memory: String = ""): Pair<String, String> {
        val sys = persona(memory) +
            "Write a short, genuinely human, personalized outreach email from your own account. Warm, specific, respectful, " +
            "with a clear ask and a polite one-line opt-out. Not spammy, no hype. " +
            "Format: first line 'SUBJECT: ...', then a blank line, then the body."
        val user = "Recipient: $recipient\nTopic: $topic" + (if (content.isNotBlank()) "\nReference:\n$content" else "")
        val (code, text) = callContent(sys, user, 400, VOICE)
        if (code != 200) return "Hello" to "[couldn't draft: $code $text]"
        val subj = Regex("(?i)subject:\\s*(.*)").find(text)?.groupValues?.get(1)?.trim() ?: "Hello"
        val body = text.substringAfter("\n").substringAfter(subj).trim().ifBlank { text.trim() }
        return subj to body
    }

    /** Telegram reply: natural texting voice, with the white paper used only when relevant. */
    fun telegramSmartReply(thread: List<Pair<String, String>>, doc: String, memory: String): String {
        val sys = persona(memory) +
            "You're texting on Telegram — this is a casual chat, not a help desk. Sound like a real person texting a friend: " +
            "short (usually one or two lines), relaxed, contractions, lowercase is fine, mirror their energy and length, " +
            "the occasional emoji only if it fits. Do NOT write paragraphs, do NOT use bullet points or headings, " +
            "do NOT sound like an assistant, do NOT over-explain, do NOT end with 'let me know if you need anything'. " +
            "It's fine to be brief, joke, react, or ask a quick question back. " +
            "Examples of the vibe — Them: 'yo you free tmrw?' You: 'should be, what's up?'  •  " +
            "Them: 'sent you the deck' You: 'got it, taking a look 👀'  •  Them: 'this is taking forever lol' You: 'fr 😭 almost done tho'. " +
            (if (doc.isNotBlank())
                "A document has been shared in this chat. If THIS message is asking about it (or clearly refers to it), answer accurately from the excerpts below — but STILL keep it casual and texty, not a lecture, and if the answer isn't in them say you're not sure. If the message is just casual chatter, ignore the document and chat normally. DOCUMENT EXCERPTS:\n$doc\n"
             else "Just chat naturally. ")
        val merged = ArrayList<Pair<String, String>>()
        thread.forEach { (role, text) ->
            val r = if (role == "me") "assistant" else "user"
            if (merged.isNotEmpty() && merged.last().first == r) merged[merged.size - 1] = r to (merged.last().second + "\n" + text)
            else merged.add(r to text)
        }
        while (merged.isNotEmpty() && merged.first().first == "assistant") merged.removeAt(0)
        if (merged.isEmpty()) return "hey! what's up?"
        val arr = JSONArray()
        merged.takeLast(30).forEach { (r, t) -> arr.put(JSONObject().put("role", r).put("content", t)) }
        val (code, text) = callMessages(sys, arr, 500, VOICE)
        return if (code == 200) text.trim() else "one sec, having trouble connecting ($code)."
    }

    private fun parseSubjectBody(text: String, fallbackSubject: String): Pair<String, String> {
        val subj = Regex("(?i)subject:\\s*(.*)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: fallbackSubject
        val body = text.substringAfter("\n").substringAfter(subj).trim().ifBlank { text.trim() }
        return subj to body
    }

    /** Draft a full email (subject + body) from a topic, in the user's voice. For the editable compose page. */
    fun composeEmail(recipient: String, topic: String, memory: String = ""): Pair<String, String> {
        val sys = persona(memory) +
            "Write a COMPLETE email from you to $recipient about the topic below, in your own natural voice — " +
            "clear and warm, as concise or detailed as the ask needs, never corporate boilerplate. Include a " +
            "natural greeting and sign-off. Format EXACTLY: first line 'SUBJECT: ...', then a blank line, then the body. Return ONLY that."
        val user = "Recipient: ${recipient.ifBlank { "the recipient" }}\nWhat it's about: $topic"
        val (code, text) = callContent(sys, user, 1000, VOICE)
        if (code != 200) return "" to "[couldn't draft: $code $text]"
        return parseSubjectBody(text, topic.take(60))
    }

    /** Revise a full email per a natural-language instruction, keeping the user's voice. */
    fun reviseEmail(subject: String, body: String, instruction: String, memory: String = ""): Pair<String, String> {
        val sys = persona(memory) +
            "Revise the user's email exactly per their instruction, keeping their voice. Format EXACTLY: " +
            "first line 'SUBJECT: ...', blank line, then the body. Return ONLY that."
        val user = "CURRENT EMAIL:\nSubject: $subject\n\n$body\n\nINSTRUCTION: $instruction"
        val (code, text) = callContent(sys, user, 1000, VOICE)
        if (code != 200) return subject to body
        return parseSubjectBody(text, subject)
    }

    /** Context-aware reply: sees the whole conversation thread with this person. */
    fun draftReplyThread(sender: String, thread: List<Pair<String, String>>, memory: String = "", imageB64: String? = null): String {
        if (thread.isEmpty()) return draftReply(sender, "", memory, imageB64)
        val system = persona(memory) +
            "You're texting with $sender in an ongoing conversation. FIRST read the notes above — they contain " +
            "your profile, what you know about $sender, and your prior + on-screen conversation history with them. " +
            "Ground your reply in that history: stay consistent, remember names/plans/details already mentioned, " +
            "reference what was actually said, and pick up exactly where things left off — never reset or ask " +
            "something already answered. Then reply like a real person texting: short (a line or two), warm, casual, " +
            "contractions, mirror their energy and length, emoji only if it fits. No assistant tone, no over-explaining, " +
            "no sign-offs. Write ONLY the next reply text — no quotes, no preamble."
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
        val recent = if (merged.size > 40) ArrayList(merged.takeLast(40)) else merged
        val arr = JSONArray()
        recent.forEachIndexed { i, (r, t) ->
            val content: Any = if (i == recent.size - 1 && r == "user" && imageB64 != null)
                JSONArray()
                    .put(JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)))
                    .put(JSONObject().put("type", "text").put("text", t))
            else t
            arr.put(JSONObject().put("role", r).put("content", content))
        }
        val (code, text) = callMessages(system, arr, 500)
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    /**
     * Thorough reply to ANY message or comment — used when the app has no inline reply box, so you
     * draft it here, then copy/open the app. Detailed and substantive, but still in your own voice.
     */
    fun draftReplyDetailed(sender: String, message: String, threadContext: String = "", memory: String = ""): String {
        val sys = persona(memory) +
            "Write a thoughtful, complete reply to $sender's message/comment below. Address every point " +
            "they actually raised, with real substance — but stay in your own natural human voice, warm " +
            "and specific, not corporate or essay-like. A few sentences up to a short paragraph. " +
            "Return ONLY the reply text, no quotes, no preamble."
        val user = (if (threadContext.isNotBlank()) "Earlier in the conversation:\n$threadContext\n\n" else "") +
            "Their latest message/comment:\n\"$message\""
        val (code, text) = callContent(sys, user, 700, VOICE)
        return if (code == 200) text.trim() else "[couldn't draft: $code $text]"
    }

    /** Draft a reply to an incoming message (optionally seeing an attached image). */
    fun draftReply(sender: String, message: String, memory: String = "", imageB64: String? = null): String {
        val system = persona(memory) +
            "You are replying to an incoming message. " +
            (if (imageB64 != null) "The message includes the attached image; consider it. " else "") +
            "Write ONLY the reply text — short, natural, friendly. No quotes, no preamble."
        val userText = "Message from $sender: \"$message\""
        val (code, text) = if (imageB64 != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)))
                .put(JSONObject().put("type", "text").put("text", userText))
            callContent(system, content, 400, VOICE)
        } else callContent(system, userText, 400, VOICE)
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
