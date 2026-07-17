package com.agentos.shell.tools

import android.content.Context

/**
 * What the agent knows about the user. Persisted locally (SharedPreferences) and injected
 * into every prompt so replies and answers are personalized. The user edits this on the
 * Memory screen.
 */
object MemoryStore {
    private const val PREF = "slyos"
    private const val KEY_ABOUT = "about_you"
    private const val KEY_AUTO = "autonomous_reply"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun about(ctx: Context): String = prefs(ctx).getString(KEY_ABOUT, "") ?: ""
    fun setAbout(ctx: Context, value: String) = prefs(ctx).edit().putString(KEY_ABOUT, value).apply()

    /**
     * Facts the agent LEARNS on its own from conversations — kept separate from the hand-written
     * About so the brain can grow automatically without polluting what the user curated. Deduped,
     * capped, newest last. Surfaced into reply/home context as "Things you've learned about me".
     */
    private const val KEY_LEARNED = "learned_facts"
    fun learnedFacts(ctx: Context): List<String> = try {
        org.json.JSONArray(prefs(ctx).getString(KEY_LEARNED, "[]")).let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
    } catch (e: Exception) { emptyList() }
    fun addLearnedFact(ctx: Context, fact: String) {
        val f = fact.trim(); if (f.length < 3) return
        val cur = learnedFacts(ctx).toMutableList()
        if (cur.any { it.equals(f, true) }) return            // dedup
        // P1.5: the prefs list stays a BOUNDED working set for the prompt (recency), but the fact is ALSO
        // written to the unbounded, indexed, deduped brain DB — so fact #251 no longer erases fact #1;
        // every fact remains permanently retrievable via keyword + semantic search.
        try { MessageStore.insertOne(ctx, "Learned facts", "Brain", "me", "me", f) } catch (e: Exception) {}
        cur.add(f); val capped = cur.takeLast(250)            // bounded prompt working set only
        val arr = org.json.JSONArray(); capped.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_LEARNED, arr.toString()).apply()
    }
    /** Your real work history + education, parsed from the LinkedIn export (Positions.csv/Education.csv). */
    fun positions(ctx: Context): String = prefs(ctx).getString("li_positions", "") ?: ""
    fun setPositions(ctx: Context, v: String) = prefs(ctx).edit().putString("li_positions", v.trim()).apply()
    fun education(ctx: Context): String = prefs(ctx).getString("li_education", "") ?: ""
    fun setEducation(ctx: Context, v: String) = prefs(ctx).edit().putString("li_education", v.trim()).apply()

    /** About + learned facts + LinkedIn work history — the full personal profile to feed the AI. */
    fun fullProfile(ctx: Context): String {
        val a = about(ctx); val l = learnedFacts(ctx); val p = positions(ctx); val e = education(ctx)
        val c = shippingProfile(ctx)   // Name / Email / Phone / Address from Settings — real contact details for letterheads, résumés, forms.
        return buildString {
            if (c.isNotBlank()) append("My contact details (use verbatim in résumé headers, cover-letter sender blocks, and signatures):\n").append(c)
            if (a.isNotBlank()) { if (isNotEmpty()) append("\n"); append(a) }
            if (p.isNotBlank()) { if (isNotEmpty()) append("\n"); append("My work history (from LinkedIn):\n").append(p) }
            if (e.isNotBlank()) { if (isNotEmpty()) append("\n"); append("My education:\n").append(e) }
            if (l.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("Things you've learned about me: ").append(l.joinToString(" · ")) }
        }
    }

    /** Best-effort owner first name from the About text (for tagging imported chats). */
    fun ownerName(ctx: Context): String {
        val pats = listOf(
            Regex("(?i)\\bmy name is\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI'?m\\s+([A-Z][\\p{L}'’-]+)"),
            Regex("(?i)\\bI am\\s+([A-Z][\\p{L}'’-]+)")
        )
        for (p in pats) p.find(about(ctx))?.groupValues?.get(1)?.let { return it.trim() }
        return ""
    }

    // ── Owner profile: real-world details for shopping, forms, signups, letterheads. Stored ONLY on
    //    this device. Address/phone are used to pre-fill checkout & forms; email for outreach/receipts.
    fun profileName(ctx: Context): String = prefs(ctx).getString("pf_name", "") ?: ""
    fun setProfileName(ctx: Context, v: String) = prefs(ctx).edit().putString("pf_name", v.trim()).apply()
    fun profileEmail(ctx: Context): String = prefs(ctx).getString("pf_email", "") ?: ""
    fun setProfileEmail(ctx: Context, v: String) = prefs(ctx).edit().putString("pf_email", v.trim()).apply()
    fun profilePhone(ctx: Context): String = prefs(ctx).getString("pf_phone", "") ?: ""
    fun setProfilePhone(ctx: Context, v: String) = prefs(ctx).edit().putString("pf_phone", v.trim()).apply()
    fun profileAddress(ctx: Context): String = prefs(ctx).getString("pf_addr", "") ?: ""
    fun setProfileAddress(ctx: Context, v: String) = prefs(ctx).edit().putString("pf_addr", v.trim()).apply()

    /** Headshot: saved as a file on-device; returns the absolute path ("" if none). */
    fun headshotPath(ctx: Context): String {
        val f = java.io.File(ctx.filesDir, "headshot.jpg")
        return if (f.exists()) f.absolutePath else ""
    }
    fun saveHeadshot(ctx: Context, bytes: ByteArray): Boolean = try {
        java.io.File(ctx.filesDir, "headshot.jpg").outputStream().use { it.write(bytes) }; true
    } catch (e: Exception) { false }

    /** A compact block of the owner's real-world details for checkout / form-filling / signatures. */
    fun shippingProfile(ctx: Context): String = buildString {
        val n = profileName(ctx).ifBlank { ownerName(ctx) }
        if (n.isNotBlank()) append("Name: ").append(n).append("\n")
        if (profileEmail(ctx).isNotBlank()) append("Email: ").append(profileEmail(ctx)).append("\n")
        if (profilePhone(ctx).isNotBlank()) append("Phone: ").append(profilePhone(ctx)).append("\n")
        if (profileAddress(ctx).isNotBlank()) append("Address: ").append(profileAddress(ctx))
    }.trim()

    /**
     * Write the owner's profile into the searchable brain DB (idempotent — MessageStore de-dupes by
     * content hash, so calling this repeatedly is safe). This makes address/email/phone/name
     * retrievable by semantic + keyword search too, not only via the always-injected profile block.
     */
    fun syncProfileToBrain(ctx: Context) {
        val facts = buildList {
            profileName(ctx).ifBlank { ownerName(ctx) }.takeIf { it.isNotBlank() }?.let { add("My name is $it.") }
            profileEmail(ctx).takeIf { it.isNotBlank() }?.let { add("My email address is $it.") }
            profilePhone(ctx).takeIf { it.isNotBlank() }?.let { add("My phone number is $it.") }
            profileAddress(ctx).takeIf { it.isNotBlank() }?.let { add("My home/shipping address is $it.") }
        }
        for (f in facts) try { MessageStore.insertOne(ctx, "You", "Profile", "me", "me", f) } catch (e: Exception) {}
    }

    /** A booking/scheduling link (e.g. Calendly) the agent shares when someone wants to talk live. */
    fun bookingLink(ctx: Context): String = prefs(ctx).getString("booking_link", "") ?: ""
    fun setBookingLink(ctx: Context, value: String) = prefs(ctx).edit().putString("booking_link", value.trim()).apply()

    /**
     * "Hands-off" investing: when ON, the AI may EXECUTE practice buy/sell moves itself (still logged +
     * reversible), instead of only proposing them for one-tap confirm. Default OFF — money moves are
     * proposed and confirmed unless the user deliberately opts in.
     */
    fun autoTrade(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_trade", false)
    fun setAutoTrade(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_trade", v).apply()

    // P6: OPTIONAL paid add-on — the user's own ElevenLabs key + voice id for CLONED-VOICE agent calls.
    // Never shipped in the APK. When blank, the free tier uses the device's generic on-device TTS voice.
    fun elevenKey(ctx: Context): String = prefs(ctx).getString("eleven_key", "") ?: ""
    fun setElevenKey(ctx: Context, v: String) = prefs(ctx).edit().putString("eleven_key", v.trim()).apply()
    fun elevenVoiceId(ctx: Context): String = prefs(ctx).getString("eleven_voice", "") ?: ""
    fun setElevenVoiceId(ctx: Context, v: String) = prefs(ctx).edit().putString("eleven_voice", v.trim()).apply()

    /** Global dark mode for the whole app UI. */
    fun darkMode(ctx: Context): Boolean = prefs(ctx).getBoolean("dark_mode", false)
    fun setDarkMode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("dark_mode", v).apply()

    /** Optional free Finnhub API key for reliable real-time stock quotes in the Invest screen. */
    fun finnhubKey(ctx: Context): String = prefs(ctx).getString("finnhub_key", "") ?: ""
    fun setFinnhubKey(ctx: Context, v: String) = prefs(ctx).edit().putString("finnhub_key", v.trim()).apply()

    /** Zenodo personal access token — for one-tap publishing of papers. Stored ONLY on this device. */
    fun zenodoToken(ctx: Context): String = prefs(ctx).getString("zenodo_token", "") ?: ""
    fun setZenodoToken(ctx: Context, value: String) = prefs(ctx).edit().putString("zenodo_token", value.trim()).apply()
    fun musicIdToken(ctx: Context): String = prefs(ctx).getString("audd_token", "") ?: ""
    fun setMusicIdToken(ctx: Context, value: String) = prefs(ctx).edit().putString("audd_token", value.trim()).apply()
    fun vercelToken(ctx: Context): String = prefs(ctx).getString("vercel_token", "") ?: ""
    fun setVercelToken(ctx: Context, value: String) = prefs(ctx).edit().putString("vercel_token", value.trim()).apply()
    fun supabaseUrl(ctx: Context): String = prefs(ctx).getString("supabase_url", "") ?: ""
    fun setSupabaseUrl(ctx: Context, value: String) = prefs(ctx).edit().putString("supabase_url", value.trim()).apply()
    fun supabaseAnon(ctx: Context): String = prefs(ctx).getString("supabase_anon", "") ?: ""
    fun setSupabaseAnon(ctx: Context, value: String) = prefs(ctx).edit().putString("supabase_anon", value.trim()).apply()
    fun lovableToken(ctx: Context): String = prefs(ctx).getString("lovable_token", "") ?: ""
    fun setLovableToken(ctx: Context, value: String) = prefs(ctx).edit().putString("lovable_token", value.trim()).apply()

    /** Your Anthropic API key (the brain). Stored ONLY on this device; lets a prebuilt APK run with no
     *  key compiled in — each person pastes their own. */
    fun anthropicKey(ctx: Context): String = prefs(ctx).getString("anthropic_key", "") ?: ""
    fun setAnthropicKey(ctx: Context, value: String) = prefs(ctx).edit().putString("anthropic_key", value.trim()).apply()

    /** Effective Anthropic key: the one pasted in-app, else the build-time key (dev builds). */
    fun anthropicKeyEffective(ctx: Context): String =
        anthropicKey(ctx).ifBlank { com.agentos.shell.BuildConfig.ANTHROPIC_API_KEY }

    /** Other model providers — each user can bring whichever they like (Gemini has a free tier). */
    fun openaiKey(ctx: Context): String = prefs(ctx).getString("openai_key", "") ?: ""
    fun setOpenaiKey(ctx: Context, value: String) = prefs(ctx).edit().putString("openai_key", value.trim()).apply()
    fun geminiKey(ctx: Context): String = prefs(ctx).getString("gemini_key", "") ?: ""
    fun setGeminiKey(ctx: Context, value: String) = prefs(ctx).edit().putString("gemini_key", value.trim()).apply()

    /** Monthly spend cap in USD (0 = no cap). When the month's spend crosses it, SlyOS forces every
     *  paid task onto the free Gemini tier so the bill can't run away. */
    fun monthlyBudget(ctx: Context): Double = try { (prefs(ctx).getString("monthly_budget", "0") ?: "0").toDouble() } catch (e: Exception) { 0.0 }
    fun setMonthlyBudget(ctx: Context, v: String) = prefs(ctx).edit().putString("monthly_budget", v.trim().ifBlank { "0" }).apply()

    /** Which provider embeds semantic memory: "auto" (Gemini free → OpenAI), "gemini", or "openai".
     *  Lets a user force reliable paid OpenAI indexing when the free Gemini tier is rate-limited. */
    fun embedProvider(ctx: Context): String = prefs(ctx).getString("embed_provider", "auto") ?: "auto"
    fun setEmbedProvider(ctx: Context, value: String) = prefs(ctx).edit().putString("embed_provider", value).apply()

    /** GitHub Personal Access Token — set once, then Cowork can push to GitHub non-interactively. */
    fun githubToken(ctx: Context): String = prefs(ctx).getString("github_token", "") ?: ""
    fun setGithubToken(ctx: Context, value: String) = prefs(ctx).edit().putString("github_token", value.trim()).apply()

    /** True if ANY provider has a usable key — so a Gemini-only (free) user is fully set up. */
    fun anyProviderKey(ctx: Context): Boolean =
        anthropicKeyEffective(ctx).isNotBlank() || openaiKey(ctx).isNotBlank() || geminiKey(ctx).isNotBlank()

    /**
     * Preferred provider to try first ("anthropic"|"openai"|"gemini"). If unset, auto-prefers whichever
     * key exists (Anthropic → OpenAI → Gemini); the router still falls back to any keyed provider.
     */
    fun preferredProvider(ctx: Context): String {
        prefs(ctx).getString("pref_provider", "")?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            anthropicKeyEffective(ctx).isNotBlank() -> "anthropic"
            openaiKey(ctx).isNotBlank() -> "openai"
            geminiKey(ctx).isNotBlank() -> "gemini"
            else -> ""
        }
    }
    fun setPreferredProvider(ctx: Context, p: String) = prefs(ctx).edit().putString("pref_provider", p).apply()

    /** Optional per-tier model id override (advanced). Empty → router uses its default for that tier. */
    fun modelOverride(ctx: Context, provider: String, tier: ModelRouter.Tier): String =
        prefs(ctx).getString("model_${provider}_${tier.name}", "") ?: ""
    fun setModelOverride(ctx: Context, provider: String, tier: ModelRouter.Tier, model: String) =
        prefs(ctx).edit().putString("model_${provider}_${tier.name}", model.trim()).apply()

    /**
     * Per-task routing: which provider should handle each tier. "" = Auto (use preferred + fallback).
     * Lets the user say e.g. "everyday replies on Gemini (free), papers on Claude."
     */
    fun tierProvider(ctx: Context, tier: ModelRouter.Tier): String =
        prefs(ctx).getString("tier_prov_${tier.name}", "") ?: ""
    fun setTierProvider(ctx: Context, tier: ModelRouter.Tier, provider: String) =
        prefs(ctx).edit().putString("tier_prov_${tier.name}", provider).apply()

    /** Canonical platform key from an app label (so "WhatsApp Business" → whatsapp, etc.). */
    fun platformKey(app: String): String {
        val a = app.lowercase()
        return when {
            a.contains("linkedin") -> "linkedin"
            a.contains("instagram") -> "instagram"
            a.contains("twitter") || a == "x" || a.contains("x.com") -> "x"
            a.contains("reddit") -> "reddit"
            a.contains("whatsapp") -> "whatsapp"
            a.contains("telegram") -> "telegram"
            a.contains("messeng") || a.contains("orca") -> "messenger"
            a.contains("slack") -> "slack"
            a.contains("discord") -> "discord"
            a.contains("sms") || a.contains("messag") || a.contains("mms") -> "sms"
            a.contains("gmail") || a.contains("mail") -> "email"
            else -> a.take(20)
        }
    }

    /** Per-platform persona/tone, e.g. LinkedIn = "professional, warm CEO"; Instagram = "funny". */
    fun styleFor(ctx: Context, app: String): String =
        prefs(ctx).getString("style_${platformKey(app)}", "") ?: ""
    fun setStyleFor(ctx: Context, platformKey: String, value: String) =
        prefs(ctx).edit().putString("style_$platformKey", value.trim()).apply()

    /** Accumulated samples of YOUR own messages across imports, for learning your voice. */
    fun addVoiceSamples(ctx: Context, samples: List<String>) {
        if (samples.isEmpty()) return
        val cur = voiceSamples(ctx).toMutableList()
        cur.addAll(samples.map { it.replace("\n", " ").trim() }.filter { it.length in 2..400 })
        // P1.5: keep a large rolling window of your own writing so old samples aren't dropped as you use
        // the app (the style-learner samples from this pool; the raw messages also live in the brain DB).
        val capped = cur.distinct().takeLast(5000)
        val arr = org.json.JSONArray(); capped.forEach { arr.put(it) }
        prefs(ctx).edit().putString("voice_samples", arr.toString()).apply()
    }
    fun voiceSamples(ctx: Context): List<String> = try {
        val arr = org.json.JSONArray(prefs(ctx).getString("voice_samples", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }
    fun clearVoice(ctx: Context) = prefs(ctx).edit().remove("voice_samples").remove("style_profile").apply()

    /** A learned "this is how I write" profile, distilled from your real past messages. */
    fun styleProfile(ctx: Context): String = prefs(ctx).getString("style_profile", "") ?: ""
    fun setStyleProfile(ctx: Context, v: String) = prefs(ctx).edit().putString("style_profile", v.trim()).apply()

    /** How many voice samples existed last time the style profile was (re)learned — for auto-refresh. */
    fun voiceLearnedCount(ctx: Context): Int = prefs(ctx).getInt("voice_learned_count", 0)
    fun setVoiceLearnedCount(ctx: Context, n: Int) = prefs(ctx).edit().putInt("voice_learned_count", n).apply()

    /** Recent Memory-tab searches, newest first — so you can re-run or clear them. */
    fun searchHistory(ctx: Context): List<String> = try {
        org.json.JSONArray(prefs(ctx).getString("search_history", "[]")).let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
    } catch (e: Exception) { emptyList() }
    fun addSearch(ctx: Context, q: String) {
        val s = q.trim(); if (s.length < 2) return
        val cur = searchHistory(ctx).filterNot { it.equals(s, true) }.toMutableList()
        cur.add(0, s); val capped = cur.take(20)
        val arr = org.json.JSONArray(); capped.forEach { arr.put(it) }
        prefs(ctx).edit().putString("search_history", arr.toString()).apply()
    }
    fun clearSearchHistory(ctx: Context) = prefs(ctx).edit().remove("search_history").apply()

    /** The booking link to actually use: the explicit field, else auto-detected from your About text. */
    fun effectiveBookingLink(ctx: Context): String {
        val explicit = bookingLink(ctx)
        if (explicit.isNotBlank()) return explicit
        val m = Regex("https?://(?:www\\.)?(?:calendly\\.com|cal\\.com|savvycal\\.com|app\\.usemotion\\.com|tidycal\\.com)/\\S+", RegexOption.IGNORE_CASE)
            .find(about(ctx))
        return m?.value?.trimEnd('.', ',', ')', ' ') ?: ""
    }

    /** When true, the agent auto-replies to incoming messages (after a short undo window). */
    fun autonomous(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)
    fun setAutonomous(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO, value).apply()

    /** On-device AI call handling: when on, unknown callers are screened by SlyOS instead of ringing. */
    fun aiCallHandling(ctx: Context): Boolean = prefs(ctx).getBoolean("ai_calls", false)
    fun setAiCallHandling(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("ai_calls", v).apply()
    /** When screening an unknown caller, also text them a brain-written reply in your voice. */
    fun callTextBack(ctx: Context): Boolean = prefs(ctx).getBoolean("call_textback", true)
    fun setCallTextBack(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("call_textback", v).apply()
    /** EXPERIMENTAL: auto-answer incoming WhatsApp calls and let the AI talk to the caller (speaker loop). */
    fun answerCalls(ctx: Context): Boolean = prefs(ctx).getBoolean("answer_calls", false)
    fun setAnswerCalls(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("answer_calls", v).apply()

    /**
     * Night schedule: when on, auto-reply is FORCED on between [autoStartHour] and [autoEndHour]
     * (defaults 20:00–06:00). Outside that window the manual [autonomous] toggle is the default.
     */
    fun nightAuto(ctx: Context): Boolean = prefs(ctx).getBoolean("night_auto", false)
    fun setNightAuto(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("night_auto", value).apply()
    fun autoStartHour(ctx: Context): Int = prefs(ctx).getInt("auto_start", 20)
    fun autoEndHour(ctx: Context): Int = prefs(ctx).getInt("auto_end", 6)
    fun setAutoWindow(ctx: Context, start: Int, end: Int) =
        prefs(ctx).edit().putInt("auto_start", start).putInt("auto_end", end).apply()

    /** Is the given hour (0–23) inside the night window? Handles windows that wrap past midnight. */
    fun inNightWindow(ctx: Context, hour: Int): Boolean {
        val s = autoStartHour(ctx); val e = autoEndHour(ctx)
        return when {
            s == e -> true                       // 24h window
            s < e  -> hour in s until e          // same-day window
            else   -> hour >= s || hour < e      // wraps midnight (e.g. 20→6)
        }
    }

    /**
     * Per-app opt-out. Auto-reply is ON for every app by default; the user can switch individual
     * apps off here. We persist only the DISABLED packages.
     */
    private fun disabledApps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("auto_disabled_apps", emptySet()) ?: emptySet()
    fun appAutoEnabled(ctx: Context, pkg: String): Boolean = !disabledApps(ctx).contains(pkg)
    fun setAppAuto(ctx: Context, pkg: String, enabled: Boolean) {
        val cur = HashSet(disabledApps(ctx))
        if (enabled) cur.remove(pkg) else cur.add(pkg)
        prefs(ctx).edit().putStringSet("auto_disabled_apps", cur).apply()
    }

    /**
     * Per-app automation level: "off" | "draft" | "full".
     *   off   — nothing automatic; you still tap "agent reply" by hand.
     *   draft — the agent auto-WRITES a reply the moment a message lands and STAGES it on the Now
     *           card (the exact text, to the exact person) so all you do is tap Send. Never sends itself.
     *   full  — auto-writes AND auto-sends after an 8-second undo window.
     * If never set explicitly we derive a sensible default from the legacy switches so existing
     * users keep their behavior: a previously-disabled app stays "off"; otherwise "full" when the
     * global auto-reply master is on, else "draft" (pre-write & stage — the new helpful default).
     */
    // Public social feeds fire huge volumes of reply-able "mention/reply" notifications. Auto-drafting a
    // reply to each one buries the Now feed (the "100 unsent X replies" pile-up) and is a real ban risk on
    // those platforms — so they default to OFF and only auto-draft if the user EXPLICITLY turns them on.
    private val NOISY_SOCIAL = setOf(
        "com.twitter.android", "com.x.android", "com.reddit.frontpage", "com.instagram.android",
        "com.linkedin.android", "com.zhiliaoapp.musically"
    )
    fun appMode(ctx: Context, pkg: String): String {
        prefs(ctx).getString("app_mode_$pkg", null)?.let { return it }   // explicit choice always wins
        if (!appAutoEnabled(ctx, pkg)) return "off"
        if (pkg in NOISY_SOCIAL) return "off"                            // noisy feeds: opt-in only
        return if (autonomous(ctx)) "full" else "draft"
    }
    fun setAppMode(ctx: Context, pkg: String, mode: String) =
        prefs(ctx).edit().putString("app_mode_$pkg", mode).apply()

    /**
     * Per-app opt-in for the overnight full-auto window (P0.3). The night window may ONLY escalate an
     * app to full-send if the user explicitly opted THAT app in here — it never overrides a draft/off/
     * default app on its own. Default false, so nothing auto-sends overnight unless deliberately enabled.
     */
    fun appNightAuto(ctx: Context, pkg: String): Boolean = prefs(ctx).getBoolean("app_night_$pkg", false)
    fun setAppNightAuto(ctx: Context, pkg: String, v: Boolean) = prefs(ctx).edit().putBoolean("app_night_$pkg", v).apply()

    /**
     * P1.4: per-mini-app permission to read/write the brain. Default DENY — a generated (or injected)
     * mini-app cannot see your profile or write facts until you explicitly grant it on the app's screen.
     */
    fun appMemGranted(ctx: Context, appId: Long): Boolean = prefs(ctx).getBoolean("app_mem_$appId", false)
    fun setAppMemGranted(ctx: Context, appId: Long, v: Boolean) = prefs(ctx).edit().putBoolean("app_mem_$appId", v).apply()

    /** The effective auto-reply state right now: forced on by the night window, else the toggle. */
    fun autonomousEffective(ctx: Context): Boolean {
        if (nightAuto(ctx)) {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (inNightWindow(ctx, h)) return true
        }
        return autonomous(ctx)
    }

    /** When true, a weekly "reach out to these people" nudge is posted. */
    fun reconnectWeekly(ctx: Context): Boolean = prefs(ctx).getBoolean("reconnect_weekly", false)
    fun setReconnectWeekly(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("reconnect_weekly", value).apply()

    /** When true, a spicy take is generated and notified once each morning. */
    fun spicyDaily(ctx: Context): Boolean = prefs(ctx).getBoolean("spicy_daily", false)
    fun setSpicyDaily(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("spicy_daily", value).apply()

    /** When true, Telegram messages are auto-answered from the loaded PDF. */
    fun docTelegram(ctx: Context): Boolean = prefs(ctx).getBoolean("doc_telegram", false)
    fun setDocTelegram(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("doc_telegram", value).apply()

    /** When true, the Telegram bot service runs (reads attachments, answers, ingests PDFs). */
    fun telegramBot(ctx: Context): Boolean = prefs(ctx).getBoolean("telegram_bot", false)
    fun setTelegramBot(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("telegram_bot", value).apply()

    // ── Telegram OWNER allowlist. The bot impersonates you and holds your whole brain, so it must ONLY
    //    ever talk to YOU. The owner pairs once by sending a one-time code (shown in Settings) from their
    //    own Telegram account; the paired chatId is persisted and every other chat is refused.
    fun telegramOwnerId(ctx: Context): Long = prefs(ctx).getLong("tg_owner_id", 0L)
    fun setTelegramOwnerId(ctx: Context, id: Long) = prefs(ctx).edit().putLong("tg_owner_id", id).apply()
    fun clearTelegramOwner(ctx: Context) = prefs(ctx).edit().remove("tg_owner_id").apply()
    /** A stable, per-install pairing code shown in-app. The user sends it to the bot once to pair. */
    fun telegramPairCode(ctx: Context): String {
        prefs(ctx).getString("tg_pair_code", null)?.let { if (it.isNotBlank()) return it }
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = "SLY-" + (1..6).map { chars.random() }.joinToString("")
        prefs(ctx).edit().putString("tg_pair_code", code).apply()
        return code
    }

    /**
     * When true, the Accessibility service logs on-screen text into InteractionStore for recall.
     * (The OS-level Accessibility permission must also be granted in Settings.)
     */
    fun recallEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("recall_capture", false)
    fun setRecallEnabled(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("recall_capture", value).apply()

    /** When true, a persistent lock-screen notification offers one-tap voice to SlyOS. */
    fun lockVoice(ctx: Context): Boolean = prefs(ctx).getBoolean("lock_voice", false)
    fun setLockVoice(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("lock_voice", value).apply()
}
