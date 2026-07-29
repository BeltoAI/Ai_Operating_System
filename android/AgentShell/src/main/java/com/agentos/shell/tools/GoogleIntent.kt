package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Calendar, Gmail and Meet, parsed without a model.
 *
 * Measured across 24 real scenarios on two providers: moving an event, cancelling one, telling the
 * attendees you will be late, sending an existing PDF, and filling every field of an invitation all
 * produced **no actions at all** — on Claude *and* on Groq. Not a wrong action; nothing. The whole
 * surface the product is sold on depended on a model choosing to emit the right action name, and
 * when it did not, the request evaporated into a sentence.
 *
 * That is the wrong architecture for this. These requests are not ambiguous — "cancel my 3pm with
 * Carlos" has exactly one reading — and anything with one reading should be parsed, not inferred.
 * A regex cannot have an off day, cannot be out of quota, and behaves identically on the free tier.
 *
 * **The split this file exists to make: structure is parsed, prose is written.** Who, when, where,
 * which event, which action — all decided here, deterministically. What the email actually *says*
 * is still the model's job, because that is the part it is genuinely good at and the part where
 * being wrong is survivable. Nobody notices the difference except that it stops failing.
 *
 * Everything is resolved against the real calendar, so "my 2pm" means the event that is actually at
 * two o'clock rather than a guess about one.
 */
object GoogleIntent {

    enum class Kind { CREATE, MOVE, CANCEL, NOTIFY, LIST, EMAIL, SEND_DOC }

    /** One executable step, already in the shape [ToolRouter.executeAction] wants. */
    data class Step(val action: String, val arg: String, val label: String)

    /**
     * @param questions what could not be determined. A plan with questions is shown but not run —
     *   guessing an hour and writing it into someone's calendar looks exactly like getting it right
     *   until the meeting is missed.
     * @param answer a direct answer needing no execution at all, for "what's on my calendar".
     */
    data class Plan(
        val kind: Kind,
        val steps: List<Step>,
        val summary: String,
        val questions: List<String> = emptyList(),
        val answer: String = ""
    ) {
        val runnable: Boolean get() = questions.isEmpty() && steps.isNotEmpty()
    }

    // MARK: - Is this even about Google?

    private val CAL_WORDS = "(meeting|call|event|appointment|invite|invitation|calendar|" +
        "block|slot|sync|standup|stand-up|1:1|one on one|catch ?up|review|lunch|dinner|coffee)"

    /**
     * Whether this request belongs to the Google surface at all.
     *
     * Deliberately generous about what it CATCHES and strict about what it then does: it is better
     * to look at a sentence and decide it has no time in it than to let it fall through to a model
     * that will narrate a calendar entry it never made.
     */
    fun looksGoogle(prompt: String): Boolean {
        val p = prompt.lowercase()
        // `blocked?` meant "blocke" plus an optional d — it never matched the word "block", which
        // silently disabled the whole deterministic path for every "block Thursday 2-3" request.
        // One character, and it was the reason a scenario failed all day with no explanation.
        return Regex("(?i)\\b(schedule|book|block(ing|ed)?|reschedul\\w*|move|push|postpone|shift|" +
            "cancel|call off|delete)\\b.{0,30}\\b$CAL_WORDS\\b").containsMatchIn(p) ||
            Regex("(?i)\\b(what'?s|what is|do i have|am i free|anything)\\b.{0,24}\\b(on|in|my)\\b.{0,12}" +
                "\\b(calendar|schedule|diary|agenda|today|tomorrow|this week)\\b").containsMatchIn(p) ||
            Regex("(?i)\\b(e-?mail|send)\\b.{0,40}\\b(to|@)").containsMatchIn(p) ||
            Regex("(?i)\\b(invite|meet link|google meet|hangout)\\b").containsMatchIn(p) ||
            Regex("(?i)\\b(move|push|cancel|reschedul\\w*)\\b.{0,20}\\b(my|the)\\b.{0,16}" +
                "(\\d{1,2}\\s*(am|pm)|$CAL_WORDS)").containsMatchIn(p) ||
            Regex("(?i)\\b(let|tell)\\b.{0,24}\\b(know|heads up)\\b").containsMatchIn(p) ||
            // A day name IS a time word. Leaving them out meant "block Thursday 2-3 for the
            // review" read as having no time in it at all.
            CAL_WORDS.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(p) &&
                Regex("(?i)\\b(at|on|tomorrow|today|tonight|next|monday|tuesday|wednesday|thursday|" +
                    "friday|saturday|sunday|\\d{1,2}\\s*(am|pm)|\\d{1,2}\\s*[-–]\\s*\\d{1,2})\\b")
                    .containsMatchIn(p)
    }

    // MARK: - The parse

    fun parse(ctx: Context, prompt: String): Plan? {
        val p = prompt.trim()
        if (p.isBlank()) return null

        return when {
            isList(p) -> list(ctx, p)
            isCancel(p) -> cancel(ctx, p)
            isMove(p) -> move(ctx, p)
            isNotify(p) -> notify(ctx, p)
            isMakeAndSend(p) -> makeAndSend(ctx, p)
            isSendDoc(p) -> sendDoc(ctx, p)
            isCreate(p) -> create(ctx, p)
            isEmail(p) -> email(ctx, p)
            else -> null
        }
    }

    // ── Which kind ─────────────────────────────────────────────────────────────────────────────

    private fun isList(p: String) = Regex("(?i)\\b(what'?s|what is|do i have|am i free|anything|" +
        "show me|when is|who'?s)\\b.{0,30}\\b(on|in|my|for)?\\b.{0,14}" +
        "\\b(calendar|schedule|diary|agenda|today|tomorrow|this (week|afternoon|morning)|next week)\\b")
        .containsMatchIn(p)

    private fun isCancel(p: String) = Regex("(?i)\\b(cancel|call off|delete|drop|scrap)\\b")
        .containsMatchIn(p) && !Regex("(?i)\\bcancel(l)?ation policy\\b").containsMatchIn(p)

    private fun isMove(p: String) = Regex("(?i)\\b(move|push|shift|reschedul\\w*|postpone|bump|" +
        "change .{0,12}\\bto\\b)\\b").containsMatchIn(p)

    private fun isNotify(p: String) = Regex("(?i)\\b(let|tell|give|send)\\b.{0,30}" +
        "\\b(know|heads up|a heads-up|late|running late|delayed)\\b").containsMatchIn(p)

    private fun isSendDoc(p: String) = Regex("(?i)\\bsend\\b.{0,26}\\b(pdf|doc|document|deck|" +
        "one.?pager|slides|sheet|file|attachment|report)\\b").containsMatchIn(p)

    private fun isCreate(p: String) = Regex("(?i)\\b(schedule|book|block(ing|ed)?|set up|arrange|" +
        "put|add|invite|create|pencil|slot)\\b").containsMatchIn(p) && hasTime(p)

    private fun isEmail(p: String) = Regex("(?i)\\b(e-?mail|write to|drop .{0,10}a line)\\b")
        .containsMatchIn(p)

    /**
     * "Make a one-pager and email it to Carlos" — one sentence, two or three things.
     *
     * Without this the email half matched first and the document was silently dropped, which is the
     * exact multi-step failure that started all of this: the file was made, the email went without
     * it, and the reply said both had happened.
     */
    private fun isMakeAndSend(p: String) =
        Regex("(?i)\\b(make|write|draft|create|put together|prepare)\\b.{0,50}" +
            "\\b(one.?pager|doc|document|deck|slides|pdf|summary|report|sheet|brief)\\b")
            .containsMatchIn(p) &&
        Regex("(?i)\\b(and|then)\\b.{0,30}\\b(e-?mail|send|share)\\b").containsMatchIn(p)

    // ── Time ───────────────────────────────────────────────────────────────────────────────────

    private fun hasTime(p: String) = clock(p) != null ||
        Regex("(?i)\\b(tomorrow|today|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
            .containsMatchIn(p)

    /** Hour and minute of the FIRST time in the sentence — "2 to 4" starts at two, not four. */
    private fun clock(p: String): Pair<Int, Int>? {
        val t = p.lowercase()
        if (Regex("\\bnoon\\b").containsMatchIn(t)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return 0 to 0
        // A DAY NAME MAKES A BARE NUMBER A TIME.
        //
        // "Thursday 10 to 11" and "block Thursday 2-3" carry no am/pm, no minutes and no "at", so
        // the original test rejected them and the whole request fell through as having no time in
        // it — which is how a fully-specified invitation with a room and two guests produced
        // nothing at all.
        val dayNamed = Regex("(?i)\\b(tomorrow|today|tonight|monday|tuesday|wednesday|thursday|" +
            "friday|saturday|sunday)\\b").containsMatchIn(t)
        val ranged = Regex("\\b\\d{1,2}\\s*(?:to|-|–|until|till)\\s*\\d{1,2}\\b").containsMatchIn(t)
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").findAll(t).firstOrNull { hit ->
            val h = hit.groupValues[1].toIntOrNull() ?: return@firstOrNull false
            h in 0..24 && (hit.groupValues[3].isNotBlank() || hit.groupValues[2].isNotBlank() ||
                Regex("\\b(at|from|for)\\s+\\d").containsMatchIn(t) || dayNamed || ranged)
        } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        // No am/pm and an hour nobody schedules into: assume the working day. "book 3" is 3pm.
        if (ap.isBlank() && h in 1..7) h += 12
        return (h % 24) to min
    }

    /** How many days ahead the sentence points. 0 = today. */
    private fun dayShift(p: String): Int {
        val t = p.lowercase()
        if (t.contains("day after tomorrow")) return 2
        if (t.contains("tomorrow")) return 1
        val names = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
        val idx = names.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(t) }
        if (idx >= 0) {
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
            return ((idx - today + 7) % 7).let { if (it == 0) 7 else it }
        }
        return 0
    }

    /** Start and end in millis. Null when no time was stated — which is a question, not a default. */
    private fun window(p: String): Pair<Long, Long>? {
        val (h, min) = clock(p) ?: return null
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayShift(p))
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // A time already gone today, with no day named, means tomorrow. Booking into the past is a
        // silent no-show: the entry exists, no reminder fires, nobody notices until after.
        if (dayShift(p) == 0 && cal.timeInMillis < System.currentTimeMillis() - 60_000)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        val start = cal.timeInMillis
        return start to (endOf(p, start) ?: (start + 60 * 60_000))
    }

    /** An explicit end: "until 4", "to 4pm", "for 30 minutes". */
    private fun endOf(p: String, start: Long): Long? {
        Regex("(?i)\\bfor\\s+(\\d{1,3})\\s*(min|minute|minutes|hour|hours|hr|hrs|h)\\b").find(p)?.let {
            val n = it.groupValues[1].toInt()
            return start + n * (if (it.groupValues[2].startsWith("h")) 3_600_000L else 60_000L)
        }
        Regex("(?i)\\b(?:until|till|til|to|through|-|–)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
            .find(p)?.let {
                var h = it.groupValues[1].toIntOrNull() ?: return null
                val min = it.groupValues[2].toIntOrNull() ?: 0
                val ap = it.groupValues[3].lowercase()
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                // "2 to 4" — the end shares the start's half of the day.
                if (ap.isBlank()) {
                    val startH = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.HOUR_OF_DAY)
                    if (startH >= 12 && h < 12) h += 12
                }
                val c = Calendar.getInstance().apply {
                    timeInMillis = start
                    set(Calendar.HOUR_OF_DAY, h % 24); set(Calendar.MINUTE, min)
                }
                if (c.timeInMillis <= start) c.add(Calendar.DAY_OF_YEAR, 1)
                return c.timeInMillis
            }
        return null
    }

    // ── People, place, subject ──────────────────────────────────────────────────────────────────

    private val EMAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Addresses written out, plus capitalised names that look like people. */
    private fun people(ctx: Context, p: String): Pair<List<String>, List<String>> {
        val emails = EMAIL_RE.findAll(p).map { it.value }.toList()
        val stop = setOf("Google", "Meet", "Zoom", "Boardroom", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April",
            "May", "June", "July", "August", "September", "October", "November", "December", "I")
        val names = Regex("\\b(?:with|invite|and|to|for)\\s+([A-Z][a-z]{2,})\\b").findAll(p)
            .map { it.groupValues[1] }.filterNot { it in stop }.distinct().toList()
        return emails to names
    }

    private fun location(p: String): String =
        Regex("(?i)\\bin\\s+(?:the\\s+)?([A-Z][\\w' ]{2,24})\\b").find(p)?.groupValues?.get(1)?.trim()
            ?: Regex("(?i)\\bat\\s+(?:the\\s+)?([A-Z][\\w' ]{2,24})\\b").find(p)?.groupValues?.get(1)?.trim()
            ?: ""

    /** What it is about, with the scaffolding stripped off. */
    private fun subject(p: String): String {
        var t = p
            .replace(Regex("(?i)\\b(schedule|book|block|set up|arrange|put|add|invite|create|" +
                "make|send|email|e-mail)\\b"), " ")
            .replace(Regex("(?i)\\b(tomorrow|today|tonight|next week|this week)\\b"), " ")
            .replace(Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), " ")
            .replace(Regex("(?i)\\bat\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?"), " ")
            .replace(Regex("(?i)\\b(from|for)\\s+\\d{1,3}\\s*\\w*"), " ")
            .replace(Regex("(?i)\\bwith a (google )?meet( link)?\\b"), " ")
            .replace(EMAIL_RE, " ")
            .replace(Regex("\\s{2,}"), " ").trim().trim(',', '.', '-')
        Regex("(?i)\\b(about|on|re:?|regarding)\\s+(.{3,70})$").find(p)?.let { t = it.groupValues[2].trim() }
        return t.take(70)
    }

    /**
     * "Every Monday", "weekly", "every other week" — as an RFC-5545 rule.
     *
     * Recurring events were not parsed at all: "book a standup every Monday at 10" produced ONE
     * standup, next Monday, and nothing after it. The owner then believes a recurring meeting
     * exists, which is a worse state than knowing it does not.
     */
    fun recurrence(p: String): String? {
        val t = p.lowercase()
        if (!Regex("\\b(every|each|weekly|daily|monthly|recurring|repeats?)\\b").containsMatchIn(t)) return null
        val until = Regex("\\b(?:for|next)\\s+(\\d{1,2})\\s*(weeks?|months?)\\b").find(t)?.let {
            val n = it.groupValues[1].toInt()
            ";COUNT=" + (if (it.groupValues[2].startsWith("month")) n * 4 else n)
        }.orEmpty()

        val days = listOf("sunday" to "SU", "monday" to "MO", "tuesday" to "TU", "wednesday" to "WE",
            "thursday" to "TH", "friday" to "FR", "saturday" to "SA")
            .filter { Regex("\\b${it.first}s?\\b").containsMatchIn(t) }.map { it.second }

        val everyOther = Regex("\\bevery other\\b|\\bfortnight|\\bbi.?weekly\\b").containsMatchIn(t)
        return when {
            days.isNotEmpty() ->
                "RRULE:FREQ=WEEKLY${if (everyOther) ";INTERVAL=2" else ""};BYDAY=${days.joinToString(",")}$until"
            Regex("\\bevery (week|weekly)\\b|\\bweekly\\b").containsMatchIn(t) ->
                "RRULE:FREQ=WEEKLY${if (everyOther) ";INTERVAL=2" else ""}$until"
            Regex("\\bevery (day|morning|afternoon)\\b|\\bdaily\\b").containsMatchIn(t) ->
                "RRULE:FREQ=DAILY$until"
            Regex("\\bevery month\\b|\\bmonthly\\b").containsMatchIn(t) -> "RRULE:FREQ=MONTHLY$until"
            Regex("\\bevery (weekday|working day)\\b").containsMatchIn(t) ->
                "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR$until"
            else -> null
        }
    }

    /**
     * The zone a stated time is IN, when the sentence names one.
     *
     * "3pm CET" and "9am their time" book at three and nine LOCAL otherwise — the meeting exists,
     * everyone accepts, and it happens at the wrong hour for at least one person. Silent, and only
     * discovered by someone sitting alone on a call.
     *
     * Null when nothing is named, which is the overwhelming majority and correctly means "here".
     */
    fun timeZone(p: String): String? {
        val t = p.lowercase()
        val named = mapOf(
            "cet" to "Europe/Berlin", "cest" to "Europe/Berlin", "central european" to "Europe/Berlin",
            "gmt" to "Europe/London", "bst" to "Europe/London", "uk time" to "Europe/London",
            "london time" to "Europe/London", "berlin time" to "Europe/Berlin",
            "est" to "America/New_York", "edt" to "America/New_York",
            "new york time" to "America/New_York", "eastern" to "America/New_York",
            "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
            "pacific time" to "America/Los_Angeles", "california time" to "America/Los_Angeles",
            "ist" to "Asia/Kolkata", "india time" to "Asia/Kolkata",
            "jst" to "Asia/Tokyo", "tokyo time" to "Asia/Tokyo",
            "sgt" to "Asia/Singapore", "aest" to "Australia/Sydney", "sydney time" to "Australia/Sydney"
        )
        named.forEach { (k, v) -> if (Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(t)) return v }
        return null
    }

    private fun wantsMeet(p: String) =
        Regex("(?i)\\b(google meet|meet link|video call|hangout|zoom|with meet|conference)\\b")
            .containsMatchIn(p)

    private fun iso(ms: Long) =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    private fun pretty(ms: Long) =
        java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    // ── Finding the event a sentence is about ──────────────────────────────────────────────────

    /**
     * The event "my 2pm" refers to — by clock time first, then by name.
     *
     * Against the real calendar, never a guess. Moving the wrong event is worse than failing to move
     * anything, because the owner believes it worked.
     */
    private fun target(ctx: Context, p: String): CalendarTool.Found? {
        val (_, names) = people(ctx, p)
        val at = clock(p)
        if (at != null) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayShift(p))
                set(Calendar.HOUR_OF_DAY, at.first); set(Calendar.MINUTE, at.second)
                set(Calendar.SECOND, 0)
            }
            val from = cal.timeInMillis - 45 * 60_000
            val to = cal.timeInMillis + 45 * 60_000
            CalendarTool.eventsBetween(ctx, from, to, 5).firstOrNull()?.let { ev ->
                return CalendarTool.findEvent(ctx, ev.title, ev.begin)
                    ?: CalendarTool.Found(0L, ev.title, ev.begin, ev.end)
            }
        }
        names.forEach { n -> CalendarTool.findEvent(ctx, n)?.let { return it } }
        val subj = subject(p)
        if (subj.length > 2) CalendarTool.findEvent(ctx, subj)?.let { return it }
        return null
    }

    // ── The plans ──────────────────────────────────────────────────────────────────────────────

    /** Read the calendar and answer. No model, no action — the answer IS the calendar. */
    private fun list(ctx: Context, p: String): Plan {
        val shift = dayShift(p)
        val week = Regex("(?i)\\b(this|next) week\\b").containsMatchIn(p)
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, shift)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val from = cal.timeInMillis
        val to = from + (if (week) 7 else 1) * 86_400_000L
        val evs = CalendarTool.eventsBetween(ctx, from, to, 40)
        val label = when {
            week -> "this week"
            shift == 1 -> "tomorrow"
            shift > 1 -> pretty(from).substringBefore(',')
            else -> "today"
        }
        val answer = if (evs.isEmpty()) "Nothing on $label."
        else "On $label:\n" + evs.joinToString("\n") { e ->
            "• " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(e.begin)) + "  " + e.title +
                (if (e.location.isNotBlank()) " · ${e.location}" else "")
        }
        return Plan(Kind.LIST, emptyList(), "What's on $label", answer = answer)
    }

    private fun create(ctx: Context, p: String): Plan {
        val w = window(p)
        val (emails, names) = people(ctx, p)
        val guests = JSONArray().apply { (emails + names).forEach { put(it) } }
        val title = subject(p).ifBlank { "Meeting" }
        if (w == null) {
            return Plan(Kind.CREATE, emptyList(), "New event: $title",
                questions = listOf("What time should it be?"))
        }
        val o = JSONObject()
            .put("title", title)
            .put("start", iso(w.first)).put("end", iso(w.second))
        if (guests.length() > 0) o.put("attendees", guests)
        if (wantsMeet(p)) o.put("meet", true)
        location(p).takeIf { it.isNotBlank() }?.let { o.put("location", it) }
        recurrence(p)?.let { o.put("recurrence", it) }
        timeZone(p)?.let { o.put("tz", it) }
        // "agenda is pricing and the March timeline" — the reason people accept an invitation.
        Regex("(?i)\\bagenda\\s*(?:is|:)?\\s*(.{4,120})$").find(p)?.groupValues?.get(1)
            ?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("description", it) }

        val steps = arrayListOf(Step("add_event", o.toString(),
            "$title · ${pretty(w.first)}" +
                (if (guests.length() > 0) " · ${guests.length()} invited" else "") +
                (if (wantsMeet(p)) " · Meet" else "")))

        // "and text her the link" / "and email them the agenda" — the second half, parsed here
        // rather than hoped for from the planner.
        if (Regex("(?i)\\b(and|then)\\b.{0,24}\\b(text|message|sms)\\b").containsMatchIn(p) &&
            (emails + names).isNotEmpty()) {
            steps.add(Step("send_sms",
                JSONObject().put("to", (names + emails).first())
                    .put("text", "Details: $title, ${pretty(w.first)}").toString(),
                "Text ${(names + emails).first()} the details"))
        }
        if (Regex("(?i)\\b(and|then)\\b.{0,30}\\b(e-?mail|send)\\b.{0,24}\\b(agenda|details|invite|them|everyone)\\b")
                .containsMatchIn(p) && (emails + names).isNotEmpty()) {
            steps.add(Step("compose_email",
                JSONObject().put("to", (emails + names).first())
                    .put("topic", "the agenda for $title on ${pretty(w.first)}").toString(),
                "Email the agenda"))
        }
        return Plan(Kind.CREATE, steps, "$title · ${pretty(w.first)}")
    }

    private fun move(ctx: Context, p: String): Plan {
        val found = target(ctx, p)
            ?: return Plan(Kind.MOVE, emptyList(), "Move an event",
                questions = listOf("Which event should I move? I couldn't find one matching that."))
        // The NEW time is the last one mentioned — "move my 2pm to 4pm".
        val times = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").findAll(p).toList()
        val newAt = if (times.size >= 2) clock(p.substring(times.last().range.first)) else clock(p)
        if (newAt == null) return Plan(Kind.MOVE, emptyList(), "Move “${found.title}”",
            questions = listOf("What time should “${found.title}” move to?"))
        val cal = Calendar.getInstance().apply {
            timeInMillis = found.begin
            if (dayShift(p) > 0) { timeInMillis = System.currentTimeMillis(); add(Calendar.DAY_OF_YEAR, dayShift(p)) }
            set(Calendar.HOUR_OF_DAY, newAt.first); set(Calendar.MINUTE, newAt.second)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val len = (found.end - found.begin).coerceAtLeast(30 * 60_000L)
        val steps = arrayListOf(Step("move_event",
            JSONObject().put("id", found.id).put("title", found.title)
                .put("start_ms", cal.timeInMillis).put("end_ms", cal.timeInMillis + len).toString(),
            "Move “${found.title}” to ${pretty(cal.timeInMillis)}"))

        if (isNotify(p) || Regex("(?i)\\b(and )?let (her|him|them|everyone)\\b").containsMatchIn(p)) {
            steps.add(Step("event_followup",
                JSONObject().put("title", found.title)
                    .put("message", "Heads up — I've moved “${found.title}” to ${pretty(cal.timeInMillis)}.")
                    .toString(),
                "Tell the attendees"))
        }
        return Plan(Kind.MOVE, steps, "Move “${found.title}” → ${pretty(cal.timeInMillis)}")
    }

    private fun cancel(ctx: Context, p: String): Plan {
        val found = target(ctx, p)
            ?: return Plan(Kind.CANCEL, emptyList(), "Cancel an event",
                questions = listOf("Which event should I cancel? I couldn't find one matching that."))
        val steps = arrayListOf(Step("cancel_event",
            JSONObject().put("id", found.id).put("title", found.title).toString(),
            "Cancel “${found.title}” · ${pretty(found.begin)}"))
        if (isNotify(p) || Regex("(?i)\\blet (her|him|them|everyone)\\b").containsMatchIn(p)) {
            steps.add(Step("event_followup",
                JSONObject().put("title", found.title)
                    .put("message", "Sorry — I've had to cancel “${found.title}”.").toString(),
                "Tell the attendees"))
        }
        return Plan(Kind.CANCEL, steps, "Cancel “${found.title}”")
    }

    private fun notify(ctx: Context, p: String): Plan {
        val found = target(ctx, p)
            ?: return Plan(Kind.NOTIFY, emptyList(), "Tell the attendees",
                questions = listOf("Which meeting are they on? I couldn't find it."))
        val late = Regex("(?i)\\b(\\d{1,3})\\s*(min|minutes|mins)\\b").find(p)?.groupValues?.get(1)
        val msg = when {
            late != null -> "Running about $late minutes late for “${found.title}” — sorry."
            Regex("(?i)\\blate\\b").containsMatchIn(p) -> "Running a little late for “${found.title}” — sorry."
            else -> "A quick heads-up about “${found.title}”."
        }
        return Plan(Kind.NOTIFY, listOf(Step("event_followup",
            JSONObject().put("title", found.title).put("message", msg).toString(),
            "Message everyone on “${found.title}”")),
            "Heads-up to “${found.title}”")
    }

    private fun sendDoc(ctx: Context, p: String): Plan {
        val (emails, names) = people(ctx, p)
        val to = (emails + names).firstOrNull()
            ?: return Plan(Kind.SEND_DOC, emptyList(), "Send a document",
                questions = listOf("Who should I send it to?"))
        val which = Regex("(?i)\\bthe\\s+([\\w' -]{2,30}?)\\s*(pdf|doc|document|deck|one.?pager|slides|sheet|report|file)\\b")
            .find(p)?.groupValues?.get(1)?.trim().orEmpty()
        val doc = try { if (which.isBlank()) DocForge.library(ctx).firstOrNull() else DocForge.find(ctx, which) }
                  catch (e: Exception) { null }
        if (doc == null) {
            return Plan(Kind.SEND_DOC, emptyList(), "Send a document",
                questions = listOf(
                    if (which.isBlank()) "I haven't made any documents yet — what should it say?"
                    else "I can't find a document matching “$which”."))
        }
        return Plan(Kind.SEND_DOC, listOf(Step("send_document",
            JSONObject().put("name", doc.name).put("to", to).toString(),
            "Send “${doc.name}” to $to")),
            "Send “${doc.name}” to $to")
    }

    /**
     * Document, then optionally an event, then the sending — in that order, because the later steps
     * depend on the earlier ones existing.
     */
    private fun makeAndSend(ctx: Context, p: String): Plan {
        val (emails, names) = people(ctx, p)
        val to = emails.firstOrNull() ?: names.firstOrNull()
        val what = Regex("(?i)\\b(?:make|write|draft|create|put together|prepare)\\s+(?:an?\\s+)?" +
            "([\\w' -]{2,40}?)\\s*(?:about|on|for|,|and|$)").find(p)?.groupValues?.get(1)?.trim()
            .orEmpty().ifBlank { "one-pager" }
        val about = Regex("(?i)\\b(?:about|on)\\s+([^,.]{3,60})").find(p)?.groupValues?.get(1)?.trim()
            .orEmpty()
        val fmt = when {
            Regex("(?i)\\bpdf\\b").containsMatchIn(p) -> "pdf"
            Regex("(?i)\\b(deck|slides|presentation)\\b").containsMatchIn(p) -> "pptx"
            Regex("(?i)\\b(sheet|spreadsheet)\\b").containsMatchIn(p) -> "xlsx"
            else -> "pdf"
        }
        val steps = arrayListOf(Step("create_document",
            JSONObject().put("title", (about.ifBlank { what }).take(60))
                .put("brief", "A $what about ${about.ifBlank { "the topic just discussed" }}")
                .put("format", fmt).toString(),
            "Make the $what" + (if (about.isNotBlank()) " on $about" else "")))

        // A booking in the same breath.
        val w = window(p)
        if (w != null && Regex("(?i)\\b(book|schedule|call|meeting|slot)\\b").containsMatchIn(p)) {
            val o = JSONObject().put("title", (about.ifBlank { what }).take(60))
                .put("start", iso(w.first)).put("end", iso(w.second))
            val guests = JSONArray().apply { (emails + names).forEach { put(it) } }
            if (guests.length() > 0) o.put("attendees", guests)
            if (wantsMeet(p)) o.put("meet", true)
            steps.add(Step("add_event", o.toString(),
                "Book ${pretty(w.first)}" + (if (wantsMeet(p)) " with Meet" else "")))
        }

        if (to != null) {
            // send_document attaches the file that was just made; the chain resolves it at run time.
            steps.add(Step("send_document",
                JSONObject().put("to", to).put("name", "").toString(),
                "Email it to $to"))
        }
        return Plan(Kind.SEND_DOC, steps,
            "Make the $what" + (if (to != null) " and send it to $to" else ""),
            questions = if (to == null) listOf("Who should I send it to?") else emptyList())
    }

    private fun email(ctx: Context, p: String): Plan {
        val (emails, names) = people(ctx, p)
        val to = emails.firstOrNull() ?: names.firstOrNull()
            ?: return Plan(Kind.EMAIL, emptyList(), "Write an email",
                questions = listOf("Who should it go to?"))
        val topic = subject(p).ifBlank { "" }
        if (topic.isBlank()) return Plan(Kind.EMAIL, emptyList(), "Email $to",
            questions = listOf("What should the email be about?"))
        return Plan(Kind.EMAIL, listOf(Step("compose_email",
            JSONObject().put("to", to).put("topic", topic).toString(),
            "Email $to about $topic")),
            "Email $to about $topic")
    }
}
