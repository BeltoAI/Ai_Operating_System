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
    val trending: Boolean = false
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
        Power("rembg", "rembg", "cut the background off any photo", PowerType.TOOL, "Create", "✂️",
            "danielgatis/rembg", "23k", "Removes the background from any image in one step, clean edges and all."),
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
            rating = 4.9, trending = true)
    )

    fun byId(id: String) = SEED.firstOrNull { it.id == id }
}
