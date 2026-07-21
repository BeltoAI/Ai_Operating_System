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

    private data class Rule(
        val action: String,
        val pattern: Regex,
        val argAfter: Regex? = null,
        /** Builds the argument from the whole sentence. Returning null means "this rule doesn't
         *  actually apply" — e.g. a timer phrase we couldn't extract a duration from, where guessing
         *  would be worse than letting the model try. */
        val argOf: ((String) -> String?)? = null
    )

    private fun r(action: String, pattern: String, argAfter: String? = null,
                  argOf: ((String) -> String?)? = null) =
        Rule(action, Regex(pattern, RegexOption.IGNORE_CASE),
            argAfter?.let { Regex(it, RegexOption.IGNORE_CASE) }, argOf)

    // ── Argument parsing for time-based actions ────────────────────────────────────────────────
    // These are the reason timers and reminders "worked" only on strong models: the action needs a
    // NUMBER (seconds) or a clock time, and a weak model that can't do that arithmetic silently
    // answered in prose instead — "Timer set: 30 seconds" with no timer anywhere. Parsing it here
    // means the arithmetic never depends on the model at all.

    private val WORD_NUM = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "fortyfive" to 45,
        "forty five" to 45, "sixty" to 60, "half" to 30)

    /** Total duration in seconds from phrasings like "2 minutes", "1h30", "90 secs", "half an hour". */
    fun durationSeconds(text: String): Int? {
        val t = text.lowercase()
        if (Regex("\\bhalf an hour\\b").containsMatchIn(t)) return 1800
        if (Regex("\\ban? hour and a half\\b").containsMatchIn(t)) return 5400
        var total = 0; var found = false
        val m = Regex("(\\d+|" + WORD_NUM.keys.joinToString("|") { Regex.escape(it) } + ")\\s*" +
            "(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        for (g in m.findAll(t)) {
            val nRaw = g.groupValues[1]
            val n = nRaw.toIntOrNull() ?: WORD_NUM[nRaw] ?: continue
            val unit = g.groupValues[2]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("s") -> n
                else -> n * 60
            }
            found = true
        }
        return if (found && total > 0) total else null
    }

    /** Clock time as "HH:MM" (24h) from "7:30am", "at 6", "18:45", "7 pm". */
    fun clockTime(text: String): String? {
        val t = text.lowercase()
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").findAll(t)
            .firstOrNull { g ->
                val h = g.groupValues[1].toIntOrNull() ?: return@firstOrNull false
                // Only treat it as a clock time when it has minutes, an am/pm, or sits after "at".
                g.groupValues[2].isNotBlank() || g.groupValues[3].isNotBlank() ||
                    Regex("\\bat\\s*$h\\b").containsMatchIn(t)
            } ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return String.format(java.util.Locale.US, "%02d:%02d", h, min)
    }

    /** The thing to be reminded ABOUT — "remind me in 20 min to call mom" → "call mom". */
    private fun reminderText(text: String): String {
        val m = Regex("\\bto\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
        val raw = m?.groupValues?.get(1)
            ?: text.replace(Regex("(?i)\\bremind me\\b|\\bset a reminder\\b"), "")
                   .replace(Regex("(?i)\\b(in|at)\\b.*$"), "")
        // "remind me to call mom in 20 minutes" — the time lives AFTER the subject, so strip it off the
        // end too, or the notification reads "call mom in 20 minutes" when it fires.
        val cleaned = raw
            .replace(Regex("(?i)\\s+in\\s+\\d+\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b.*$"), "")
            .replace(Regex("(?i)\\s+at\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?\\s*(tomorrow|today)?\\s*$"), "")
            .replace(Regex("(?i)\\s+(tomorrow|today|tonight)\\s*$"), "")
        return cleaned.trim().trim('.', ',').ifBlank { "Reminder" }
    }

    /** Reminder arg: {"text":..,"in":seconds} or {"text":..,"at":"ISO local"} — the shape ToolRouter wants. */
    private fun reminderArg(text: String): String? {
        val body = reminderText(text)
        durationSeconds(text)?.let {
            return org.json.JSONObject().put("text", body).put("in", it).toString()
        }
        val hhmm = clockTime(text) ?: return null
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hhmm.substringBefore(':').toInt())
            set(java.util.Calendar.MINUTE, hhmm.substringAfter(':').toInt())
            set(java.util.Calendar.SECOND, 0)
            // "remind me at 8" when it's already 9pm means TOMORROW at 8 — a reminder in the past
            // silently never fires, which reads exactly like the feature being broken.
            if (timeInMillis <= System.currentTimeMillis() ||
                Regex("(?i)\\btomorrow\\b").containsMatchIn(text)) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).format(cal.time)
        return org.json.JSONObject().put("text", body).put("at", iso).toString()
    }

    /** The item to put on the checklist — strips the command wrapper around it. */
    private fun checklistItem(text: String): String? {
        val m = Regex("(?i)\\badd\\s+(.+?)\\s+(?:to|on)\\s+(?:my\\s+|the\\s+)?(?:checklist|to-?do|task)").find(text)
        if (m != null) return m.groupValues[1].trim().trim('"').ifBlank { null }
        val m2 = Regex("(?i)\\b(?:checklist|to-?do list|task list)\\s*:?\\s*(?:add\\s+)?(.+)$").find(text)
        if (m2 != null) return m2.groupValues[1].trim().trim('"').ifBlank { null }
        val m3 = Regex("(?i)\\b(?:add|put|note)\\s+(.+)$").find(text)
        return m3?.groupValues?.get(1)?.trim()?.trim('"')?.ifBlank { null }
    }

    /**
     * Order matters — the most specific phrasings come first. Each pattern is written to be
     * unmistakable, so a false positive would require genuinely odd phrasing.
     */
    private val RULES = listOf(
        // ── Time. FIRST, because these are the most literal commands a person gives and the ones the
        //    model most often answered with prose instead of performing. "set a timer for 2 minutes"
        //    has exactly one possible meaning; it must never depend on which provider is on duty. ──
        r("remind",
            "\\bremind me\\b|\\bset a reminder\\b|\\breminder to\\b|\\bdon'?t let me forget\\b",
            argOf = { reminderArg(it) }),
        r("alarm",
            "\\b(set|make|create)\\b.{0,15}\\balarm\\b|\\balarm (for|at)\\b|\\bwake me( up)?\\b",
            argOf = { clockTime(it) }),
        r("timer",
            "\\b(set|start|make|put|run)\\b.{0,15}\\b(timer|countdown)\\b|\\btimer for\\b|" +
            "\\bcount ?down\\b|\\btime me for\\b",
            argOf = { durationSeconds(it)?.toString() }),

        // ── Checklist ──
        r("checklist_clear",
            "\\b(clear|empty|wipe|reset|delete all)\\b.{0,20}\\b(checklist|to-?do|task list|tasks)\\b",
            argOf = { it }),
        r("checklist_remove",
            "\\b(remove|delete|take off|cross off|tick off|check off)\\b.{0,40}\\b(from )?(my |the )?(checklist|to-?do|task list)\\b",
            argOf = { Regex("(?i)\\b(?:remove|delete|take off|cross off|tick off|check off)\\s+(.+?)\\s*(?:from|off)\\b")
                .find(it)?.groupValues?.get(1)?.trim() }),
        r("checklist_add",
            "\\badd\\b.{0,60}\\b(to )?(my |the )?(checklist|to-?do list|to-?do|task list)\\b|" +
            "\\b(checklist|to-?do list)\\b\\s*:?\\s*add\\b",
            argOf = { checklistItem(it) }),

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
            val build = rule.argOf
            if (build != null) {
                // A builder that returns null means we matched the phrasing but could NOT extract a
                // usable argument (no duration in a timer request, say). Firing "timer" with an empty
                // arg would create a broken timer, which is worse than the model's own attempt — so
                // we keep looking instead.
                val built = build(t) ?: continue
                return Hit(rule.action, built)
            }
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
        "timer" -> "Timer"; "alarm" -> "Alarm"; "remind" -> "Reminder"
        "checklist_add" -> "Checklist"; "checklist_clear" -> "Checklist"; "checklist_remove" -> "Checklist"
        else -> action
    }
}
