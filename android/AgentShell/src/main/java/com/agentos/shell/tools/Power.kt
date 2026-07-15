package com.agentos.shell.tools

/**
 * THE POWER STORE model.
 *
 * A "Power" gives the phone (and every AI on it) a new ability — "give your phone the power to X".
 * Powers come in three shapes by where the intelligence lives:
 *   • SKILL   — a prompt/workflow pack that upgrades the brain itself (injected into the system prompt).
 *   • CONNECT — a self-hosted app/service the brain reads & writes over an endpoint you connect.
 *   • TOOL    — a file/media transform the AI can run (on your machine or a connected backend).
 */
enum class PowerType(val label: String, val glyph: String) {
    SKILL("Skill", "⚡"), CONNECT("Connect", "🔌"), TOOL("Tool", "🛠");
    companion object { fun from(s: String) = values().firstOrNull { it.name.equals(s, true) } ?: SKILL }
}

data class Power(
    val id: String,
    val name: String,
    val tagline: String,          // "…the power to <tagline>"
    val type: PowerType,
    val category: String,
    val icon: String,
    val repo: String,             // owner/name on GitHub
    val stars: String,
    val description: String,
    val instructions: String = "", // SKILL: the guidance injected into the brain when installed
    val rating: Double = 4.7,      // editorial rating shown as ★
    val featured: Boolean = false,
    val trending: Boolean = false,
    val onPhone: Boolean = false   // true = runs natively on-device, zero setup (no server, no Termux)
) {
    val repoUrl get() = "https://github.com/$repo"
    /** GitHub stars as a number, for ranking ("35k" → 35000). */
    val starCount: Int get() {
        val t = stars.trim().lowercase()
        return when {
            t.endsWith("k") -> (t.dropLast(1).toDoubleOrNull()?.times(1000))?.toInt() ?: 0
            else -> t.toIntOrNull() ?: 0
        }
    }
}

/** The curated, baked-in catalogue — the store is full and beautiful on first open, no server needed. */
object PowerCatalog {
    val CATEGORIES = listOf("See", "Speak", "Create", "Remember", "Automate", "Own your data", "Taste")

    val SEED: List<Power> = listOf(
        // ── See ───────────────────────────────────────────────────────────────────────────────────
        Power("perplexica", "Perplexica", "see the live web, with sources", PowerType.CONNECT, "See", "🔎",
            "ItzCrazyKns/Perplexica", "35k", "Answers from the live web with citations, self-hosted. Like Perplexity, free.",
            rating = 4.8, featured = true),
        Power("deep-research", "Deep Research", "research any topic while you sleep", PowerType.SKILL, "See", "🌙",
            "dzhng/deep-research", "19k", "An iterative research agent that reads, cross-checks and writes a sourced brief.",
            "When the user asks to research a topic deeply or 'while I sleep', run an iterative loop: break the topic into sub-questions, search each, cross-check sources, then write a structured, cited brief. Prefer primary sources; flag uncertainty.",
            rating = 4.7, trending = true),
        // ── Speak ─────────────────────────────────────────────────────────────────────────────────
        Power("openvoice", "OpenVoice", "speak in your own voice", PowerType.TOOL, "Speak", "🗣️",
            "myshell-ai/OpenVoice", "37k", "Clone your voice from a short sample and narrate any text. MIT, by MIT & MyShell.",
            rating = 4.9, featured = true),
        Power("audiblez", "Audiblez", "turn any book into audio", PowerType.TOOL, "Speak", "🎧",
            "santinic/audiblez", "8k", "Generate a full audiobook from any ebook or PDF, in a natural voice."),
        // ── Create ────────────────────────────────────────────────────────────────────────────────
        Power("comfyui", "ComfyUI", "make any image", PowerType.CONNECT, "Create", "🎨",
            "comfyanonymous/ComfyUI", "119k", "The most powerful modular image/video pipeline — Midjourney-grade, on your own GPU.",
            rating = 4.8, featured = true),
        Power("huashu-design", "Huashu Design", "design things that actually look good", PowerType.SKILL, "Create", "✒️",
            "alchaincyf/huashu-design", "21k", "HTML-native design skill — slides, carousels and posters with real taste.",
            "When you create slides, carousels, posters, or mini-app UIs, apply strong visual design: clear hierarchy, generous whitespace, a restrained palette, expressive typography, and a single confident accent. Output clean self-contained HTML/CSS."),
        Power("rembg", "Background Remover", "cut the background off any photo", PowerType.TOOL, "Create", "✂️",
            "danielgatis/rembg", "23k", "Removes the background from a photo in one tap — runs right on your phone, no setup.",
            rating = 4.8, onPhone = true),
        Power("spleeter", "Spleeter", "pull the vocals out of any song", PowerType.TOOL, "Create", "🎵",
            "deezer/spleeter", "28k", "Split a track into clean stems — vocals, drums, bass — for karaoke or acapella."),
        Power("pyvideotrans", "pyvideotrans", "dub any video into another language", PowerType.TOOL, "Create", "🌐",
            "jianchang512/pyvideotrans", "18k", "Transcribes, translates, re-voices and subtitles any video."),
        Power("video-use", "video-use", "edit your videos by just typing", PowerType.TOOL, "Create", "🎬",
            "browser-use/video-use", "14k", "AI watches your video and cuts, tightens, zooms and adds effects on request."),
        // ── Remember ──────────────────────────────────────────────────────────────────────────────
        Power("ocrmypdf", "OCRmyPDF", "read any scan", PowerType.TOOL, "Remember", "🦉",
            "ocrmypdf/OCRmyPDF", "34k", "Adds a searchable, copyable text layer to scanned PDFs — and feeds the text into your brain."),
        Power("immich", "Immich", "search your whole photo library", PowerType.CONNECT, "Remember", "📸",
            "immich-app/immich", "60k", "Self-hosted Google Photos — your entire library, searchable by the AI.",
            rating = 4.9, trending = true),
        Power("appflowy", "AppFlowy", "own your notes & docs", PowerType.CONNECT, "Remember", "📓",
            "AppFlowy-IO/AppFlowy", "73k", "A full Notion — docs, wikis, databases — on your own machine, that the AI can read & write."),
        Power("anytype", "Anytype", "a private safe haven for your notes", PowerType.CONNECT, "Remember", "🗄️",
            "anyproto/anytype-ts", "10k", "Local-first, end-to-end encrypted notes and objects. Notion, but yours forever."),
        // ── Automate ──────────────────────────────────────────────────────────────────────────────
        Power("n8n", "n8n", "automate your whole workflow", PowerType.CONNECT, "Automate", "🔗",
            "n8n-io/n8n", "195k", "Wires your apps and AI into automations that run themselves — the AI can build & trigger them.",
            rating = 4.8, trending = true),
        Power("twenty", "Twenty CRM", "run your contacts & deals", PowerType.CONNECT, "Automate", "🤝",
            "twentyhq/twenty", "30k", "Open-source Salesforce — the AI reads and updates your people, companies and deals."),
        Power("cal", "Cal.com", "booking links, no monthly fee", PowerType.CONNECT, "Automate", "📅",
            "calcom/cal.com", "46k", "Scheduling for everyone — the open-source Calendly the AI can book into."),
        Power("listmonk", "listmonk", "email your whole list for pennies", PowerType.CONNECT, "Automate", "📮",
            "knadh/listmonk", "22k", "Self-hosted newsletter & mailing-list manager — no per-subscriber pricing."),
        // ── Own your data ───────────────────────────────────────────────────────────────────────────
        Power("nocodb", "NocoDB", "a team database, no seat fees", PowerType.CONNECT, "Own your data", "🧮",
            "nocodb/nocodb", "63k", "Turns any database into an Airtable-style grid the AI can query and fill."),
        Power("formbricks", "Formbricks", "forms & surveys, no response cap", PowerType.CONNECT, "Own your data", "📝",
            "formbricks/formbricks", "12k", "Sleek forms & surveys you host yourself — every answer flows into your brain."),
        Power("open-webui", "Open WebUI", "run your own free model", PowerType.CONNECT, "Own your data", "💬",
            "open-webui/open-webui", "144k", "Point it at Ollama for a fully local, private model — this is your no-key brain."),
        Power("papermark", "Papermark", "share documents and track them", PowerType.CONNECT, "Own your data", "📄",
            "mfts/papermark", "8k", "Open-source DocSend — tracked document sharing with analytics."),
        // ── Taste ─────────────────────────────────────────────────────────────────────────────────
        Power("taste-skill", "Taste", "have actual taste", PowerType.SKILL, "Taste", "👁️",
            "Leonx1nx/taste-skill", "55k", "Gives your AI real taste, so it stops making generic slop.",
            "Develop and apply aesthetic and editorial taste to every creative output. Before finalizing, critique your own work: where is it generic or slop, where can it be sharper, more specific, more surprising? Push for originality, restraint and quality over cliché.",
            rating = 4.9, trending = true),

        // ── Strong, phone-native skills (work instantly, reprogram the brain) ────────────────────────
        Power("write-like-me", "Write Like Me", "write in my exact voice", PowerType.SKILL, "Create", "✍️", "", "",
            "Writes anything — messages, emails, posts — in the user's own voice.",
            "Study how the user writes from their messages and past text: sentence length, warmth, slang, punctuation, how blunt or soft they are. When writing on their behalf, match that voice exactly — never generic or corporate. When unsure, lean casual and human.",
            rating = 4.9, trending = true),
        Power("bill-negotiator", "Bill Slayer", "fight my bills and fees", PowerType.SKILL, "Automate", "💸", "", "",
            "Drafts firm, effective messages to lower bills, cancel fees and win refunds.",
            "When the user faces a charge, bill, subscription or fee, help them push back. Draft a calm, firm, specific message citing the exact issue, what they want (a refund, a lower rate, a waived fee), and a reasonable deadline. Suggest the retention/complaints route. Never rude, always leverage-aware.",
            rating = 4.8, trending = true),
        Power("plan-from-ramble", "Untangle", "turn my rambles into a clear plan", PowerType.SKILL, "Automate", "🧭", "", "",
            "Turns a messy brain-dump into a clear, prioritized plan.",
            "When the user dumps a messy stream of thoughts, tasks or worries, turn it into a clear plan: group related items, cut noise, order by impact and urgency, and surface the ONE next action. Keep it short and doable — never a bloated list.",
            rating = 4.8),
        Power("explain-simply", "Explain Simply", "explain anything like I'm smart but new", PowerType.SKILL, "See", "💡", "", "",
            "Explains any topic clearly, with a great analogy, no jargon.",
            "Explain any topic as if to a sharp person new to it: one clear analogy, plain words, no jargon (or define it instantly), and a concrete example. Start with the core idea in one sentence, then build. Never condescending, never a wall of text.",
            rating = 4.8),
        Power("devils-advocate", "Devil's Advocate", "stress-test my thinking", PowerType.SKILL, "Taste", "⚖️", "", "",
            "Pokes real holes in your plans and arguments before the world does.",
            "When the user shares a plan, opinion or decision, challenge it honestly: name the strongest counter-argument, the biggest risk, and what they're not seeing — specifically, not vaguely. Then note what would change your mind. Be direct and useful, never contrarian for its own sake.",
            rating = 4.7),
        Power("coach-calm", "Steady", "talk me through it calmly", PowerType.SKILL, "Remember", "🌿", "", "",
            "A calm, grounded voice when things feel like a lot.",
            "When the user is stressed, overwhelmed or spiralling, respond calmly and concretely: acknowledge it briefly, help them name what's actually in their control, and offer one small next step. Warm and steady, never clinical, never toxic-positive. If they're in real crisis, gently point them to proper support.",
            rating = 4.8, featured = true),
        Power("money-eye", "Money Eye", "spot where my money leaks", PowerType.SKILL, "Remember", "🔍", "", "",
            "Reads your spending and flags the leaks worth fixing.",
            "Using the user's tracked expenses, find the money leaks that matter: recurring subscriptions they forgot, categories creeping up, small daily habits that add up. Rank by annual impact, and suggest the specific, painless cut. Use their real numbers, never generic advice.",
            rating = 4.7),
        Power("meeting-to-actions", "Recap", "turn talk into who-does-what", PowerType.SKILL, "Automate", "✅", "", "",
            "Turns a meeting, call or note into clear action items.",
            "From any meeting notes, transcript or voice memo, extract the decisions and the action items as 'who — does what — by when'. Flag anything left ambiguous or unowned. Keep it tight; skip the chit-chat.",
            rating = 4.7),
        Power("learn-fast", "Learn Fast", "learn anything, fast", PowerType.SKILL, "See", "🚀", "", "",
            "Builds a fast, personal path to learn any skill or topic.",
            "When the user wants to learn something, build a lean path: the 20% that gives 80%, in order, with one concrete practice task per step and a way to know they've got it. Adapt to their level and time. No filler, no 30-week syllabus.",
            rating = 4.8, trending = true)
    )

    fun byId(id: String) = SEED.firstOrNull { it.id == id }
}
