package com.agentos.shell.tools

/**
 * MODEL-INDEPENDENT SCREEN ROUTING.
 *
 * The bug this fixes: opening a screen ("how's my portfolio", "find me a job at Stripe") depended entirely
 * on the LLM emitting the right action type. But those types were listed in the planner schema as bare
 * names with no description, so only a strong model like Claude could infer what `invest` or `find_job`
 * meant. On Groq's llama-3.1-8b — which is what the CHEAP tier actually routes to — none of them were ever
 * emitted, so the pages simply never opened. The feature appeared broken while the model was "working".
 *
 * Screen navigation is far too important to leave to model inference. These are unambiguous phrasings that
 * map deterministically to a screen, checked BEFORE the model runs. The model stays in charge of anything
 * genuinely ambiguous; this just guarantees the obvious cases always work, on every provider, even offline.
 */
object ScreenIntent {

    /** [action] matches the planner action type HomeScreen already handles, so nothing downstream changes. */
    data class Hit(val action: String, val arg: String)

    private data class Rule(val action: String, val pattern: Regex, val argAfter: Regex? = null)

    private fun r(action: String, pattern: String, argAfter: String? = null) =
        Rule(action, Regex(pattern, RegexOption.IGNORE_CASE),
            argAfter?.let { Regex(it, RegexOption.IGNORE_CASE) })

    /**
     * Order matters — the most specific phrasings come first. Each pattern is written to be
     * unmistakable, so a false positive would require genuinely odd phrasing.
     */
    private val RULES = listOf(
        // ── Documents. Checked FIRST: "write me a report as a PDF" is a document request, not research. ──
        r("create_document",
            "\\b(make|create|build|write|generate|draft|give me|produce)\\b.{0,40}" +
            "\\b(pdf|docx?|word doc|word file|pptx?|powerpoint|slide deck|deck|presentation|slides|" +
            "xlsx?|excel|spreadsheet|html|one.?pager|onepager|brochure|proposal|memo)\\b|" +
            "\\b(pdf|docx|pptx|xlsx|html)\\b.{0,20}\\b(about|for|on)\\b"),
        r("refine_document",
            "\\b(make it|change it|turn it into|convert it to|shorten|lengthen|add a slide|redo it|" +
            "revise it|update it|more formal|less formal)\\b"),
        r("open_document", "\\bopen (it|the (doc|file|deck|pdf|sheet))\\b|\\bshow me (that|the) (doc|file|deck|pdf)\\b"),
        r("send_document", "\\bsend (it|the (doc|file|deck|pdf))\\b|\\bshare (it|the (doc|file|deck|pdf))\\b|\\bemail (it|that) to\\b"),

        // ── Trading / portfolio ──
        r("invest", "\\b(my )?(portfolio|holdings|positions|stocks?|shares|investments?)\\b|" +
            "\\bhow('?s| is| are)\\b.{0,12}\\b(my |the )?(portfolio|stocks?|investments?|market)\\b|" +
            "\\b(buy|sell)\\b.{0,20}\\b(stock|shares|crypto)\\b|\\binvest\\b"),
        // ── Job hunt ──
        r("find_job", "\\bfind me a job\\b|\\bjob (hunt|search|at|for)\\b|\\bapply (for|to)\\b.{0,25}\\b(job|role|position)\\b|" +
            "\\blooking for (a )?(job|work|role)\\b|\\bget me a job\\b|\\bhiring\\b.{0,20}\\bme\\b",
            "\\b(?:at|for|as)\\s+(.+)$"),
        // ── Mission / outreach campaign ──
        r("set_mission", "\\brun a mission\\b|\\bstart a (mission|campaign)\\b|\\bnew mission\\b|" +
            "\\bfind (me )?(customers|clients|investors|buyers|leads)\\b|\\bsell\\b.{0,25}\\bto\\b",
            "\\b(?:mission|campaign)\\b[:\\s]+(.+)$"),
        // ── Network ──
        r("network_search", "\\b(my|the) network\\b|\\bdo i (know|have)\\b.{0,30}\\b(in|at)\\b|" +
            "\\bwho do i know\\b|\\banyone (in|at|who)\\b|\\bintroduc(e|tion)\\b.{0,20}\\bto\\b",
            "\\b(?:in|at|who)\\s+(.+)$"),
        // ── Shopping ──
        r("shop", "\\b(find|buy|shop for|looking to buy|best price|cheapest)\\b.{0,30}\\b(under|below|for|deal)\\b|" +
            "\\bgo shopping\\b|\\bshop for\\b|\\bwhere can i buy\\b",
            "\\b(?:for|buy)\\s+(.+)$"),
        // ── Expenses ──
        r("expenses", "\\b(my )?(expenses|spending|receipts)\\b|\\bhow much (have i|did i) (spent|spend)\\b|" +
            "\\bwhat (did|have) i spen[dt]\\b|\\bshow (me )?(my )?(spend|expenses)\\b"),
        // ── Look (camera) ──
        r("look", "\\b(what am i looking at|what is this|identify this|point the camera|use the camera|scan this)\\b|" +
            "\\blook at (this|that)\\b"),
        // ── Documents / papers ──
        r("write_paper", "\\bwrite (me )?(a |an )?(paper|report|whitepaper|essay|research)\\b|\\bresearch\\b.{0,20}\\bwrite\\b"),
        // ── Cowork ──
        r("cowork", "\\bcowork\\b|\\bwork (with me|session)\\b|\\blet'?s work on\\b"),
        // ── Faces / people ──
        r("faces", "\\b(faces|who is in (this|that) photo|recognise|recognize)\\b.{0,20}\\b(photo|picture|face)\\b|\\bmy people\\b"),
        // ── Screen automation ──
        r("operate", "\\b(do (this|that) for me|operate|take over|control the screen|do it in the app)\\b"),
        // ── Social posts ──
        r("spicy_post", "\\bspicy (take|post)\\b|\\bhot take\\b"),
        r("compose_post", "\\b(write|draft|make) (me )?(a )?(post|tweet|linkedin post)\\b|\\bpost about\\b",
            "\\babout\\s+(.+)$")
    )

    /**
     * The screen this text unambiguously wants, or null to let the model decide.
     * Deliberately conservative: a miss just means the model handles it as before.
     */
    fun detect(text: String): Hit? {
        val t = text.trim()
        if (t.length < 3) return null
        for (rule in RULES) {
            if (!rule.pattern.containsMatchIn(t)) continue
            val arg = rule.argAfter?.find(t)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return Hit(rule.action, arg.ifBlank { t })
        }
        return null
    }

    /** Human label for the screen an action opens — used in logs and confirmations. */
    fun screenName(action: String): String = when (action) {
        "invest" -> "Trading"; "find_job" -> "Job hunt"; "set_mission" -> "Mission"
        "network_search" -> "My network"; "shop" -> "Shopping"; "expenses" -> "Expenses"
        "look" -> "Look"; "write_paper" -> "Research"; "cowork" -> "Cowork"
        "faces" -> "Faces"; "operate" -> "Screen control"; "spicy_post" -> "Spicy post"
        "compose_post" -> "Compose post"
        else -> action
    }
}
