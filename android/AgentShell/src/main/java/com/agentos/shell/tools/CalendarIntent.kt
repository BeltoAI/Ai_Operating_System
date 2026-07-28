package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A local safety net for calendar requests.
 *
 * Every other unmistakable request already has one: [ScreenIntent] synthesises the action when the
 * model fails to emit it, because "opening a screen must NOT depend on how clever the model is."
 * The calendar was the one flow left without that net — and it is the flow where a miss is most
 * expensive, because the answering path then *narrates* the action instead.
 *
 * Observed on a device: "invite Joslyn to a call tomorrow at 4pm with a google meet" produced the
 * reply *"Creating a calendar event for tomorrow, Wed Jul 29, 4:00–5:00 PM PDT with a Google Meet
 * link, inviting you and Joslyn (joslyn.barragan@gmail.com) now."* — and Google's own record shows
 * no such event was ever created. Everything about that sentence was right except that it did not
 * happen. That is the single worst failure mode this app has, and it is why this file exists.
 *
 * This does not replace the planner. It fills in only when the planner produced no `add_event` for
 * a request that plainly is one.
 */
object CalendarIntent {

    /** Words that mean "put something on my calendar", in the shapes people actually use. */
    private val TRIGGER = Regex(
        "(?i)\\b(" +
            "block (my |out |off )?(calendar|time|the )?|" +
            "schedule (a|an|the )?|set up (a|an|the )?(call|meeting|chat|sync)|" +
            "book (a|an|the )?|put (a|an|the )?.{0,20}(on|in) (my )?calendar|" +
            "invite \\w+ to|add (a|an|the )?(meeting|event|call)|" +
            "(create|make|start) (a|an|the )?(google )?meet|" +
            "meeting with|call with|appointment|" +
            "(lunch|dinner|coffee|breakfast|drinks|catch ?up|1:1|one on one) with" +
            ")\\b")

    /** Not a request to create anything — a question about what already exists. */
    private val QUESTION = Regex(
        "(?i)\\b(what'?s on|what is on|do i have|am i free|when is|who accepted|who declined|" +
            "cancel|move|reschedule|what did i|show me)\\b")

    /**
     * A ready-to-run `add_event` argument, or null when this is not a create-an-event request.
     *
     * Deliberately conservative: with no readable time, null. Guessing an hour and writing it into
     * someone's calendar is worse than doing nothing, because a wrong time looks exactly like a
     * right one until the meeting is missed.
     */
    fun addEventArg(ctx: Context, prompt: String): String? {
        if (QUESTION.containsMatchIn(prompt)) return null
        if (!TRIGGER.containsMatchIn(prompt)) return null

        val (startMs, endMs) = window(prompt) ?: return null

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val o = JSONObject()
            .put("title", title(prompt))
            .put("start", fmt.format(java.util.Date(startMs)))
            .put("end", fmt.format(java.util.Date(endMs)))

        // A Meet link when it was asked for. The word is unambiguous; nobody says "google meet"
        // by accident.
        if (Regex("(?i)\\b(google meet|meet link|video call|hangout|zoom|with meet)\\b")
                .containsMatchIn(prompt)) o.put("meet", true)

        // Names, left as names. addEvent resolves them through PersonResolver — this must not
        // duplicate that logic, or the two will disagree about who "Anna" is.
        val people = people(prompt)
        if (people.isNotEmpty()) o.put("attendees", JSONArray(people))

        return o.toString()
    }

    // MARK: - Time

    /** Start and end in millis, or null when no time was stated. */
    private fun window(prompt: String): Pair<Long, Long>? {
        val p = prompt.lowercase()
        val cal = Calendar.getInstance()

        // "right now", "from now" — the start is this moment. Demanding a stated hour for this is
        // why a perfectly clear request used to come back as "I need a time".
        if (Regex("\\b(right now|from now|starting now|now until)\\b").containsMatchIn(p)) {
            val start = cal.timeInMillis
            val end = until(p, start) ?: (start + 30 * 60_000)
            return start to end
        }

        var dayShift = 0
        if (p.contains("tomorrow")) dayShift = 1
        else if (p.contains("day after tomorrow")) dayShift = 2
        else {
            // "on Friday", "next Tuesday" — the next one that has not happened yet.
            val names = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
            val idx = names.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(p) }
            if (idx >= 0) {
                val today = cal.get(Calendar.DAY_OF_WEEK) - 1
                dayShift = ((idx - today + 7) % 7).let { if (it == 0) 7 else it }
            }
        }

        val clock = clock(p) ?: return null
        cal.add(Calendar.DAY_OF_YEAR, dayShift)
        cal.set(Calendar.HOUR_OF_DAY, clock.first)
        cal.set(Calendar.MINUTE, clock.second)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)

        // A time earlier today with no day named means tomorrow — nobody schedules into the past.
        if (dayShift == 0 && cal.timeInMillis < System.currentTimeMillis() - 60_000) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val start = cal.timeInMillis
        val end = until(p, start) ?: (start + 60 * 60_000)
        return start to end
    }

    /** "4pm", "16:00", "4:30". Returns hour (0–23) and minute. */
    private fun clock(p: String): Pair<Int, Int>? {
        // The FIRST time mentioned is the start; "4 to 6" must not be read as starting at 6.
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").findAll(p)
            .firstOrNull { hit ->
                val h = hit.groupValues[1].toIntOrNull() ?: return@firstOrNull false
                // A bare number over 24 is not a clock time; "30 minutes" is not 30 o'clock.
                h in 0..24 && (hit.groupValues[3].isNotBlank() || hit.groupValues[2].isNotBlank() ||
                    Regex("\\b(at|from)\\s+\\d").containsMatchIn(p))
            } ?: return null

        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].lowercase()
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        // No am/pm and an hour that would be the middle of the night: assume the working day.
        if (ampm.isBlank() && h in 1..7) h += 12
        return (h % 24) to min
    }

    /** An explicit end — "until 12:30", "to 6pm", "for 90 minutes". */
    private fun until(p: String, start: Long): Long? {
        Regex("\\bfor\\s+(\\d{1,3})\\s*(min|minute|minutes|hour|hours|hr|hrs|h)\\b").find(p)?.let {
            val n = it.groupValues[1].toInt()
            val unit = if (it.groupValues[2].startsWith("h")) 3_600_000L else 60_000L
            return start + n * unit
        }
        Regex("\\b(?:until|till|til|to|through)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
            .find(p)?.let {
                var h = it.groupValues[1].toIntOrNull() ?: return null
                val min = it.groupValues[2].toIntOrNull() ?: 0
                val ampm = it.groupValues[3].lowercase()
                if (ampm == "pm" && h < 12) h += 12
                if (ampm == "am" && h == 12) h = 0
                val c = Calendar.getInstance().apply {
                    timeInMillis = start
                    set(Calendar.HOUR_OF_DAY, h % 24); set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                // An end at or before the start needs interpreting, and how depends on whether the
                // user was explicit. A bare "to 6" after a 4pm start means 6pm — shift twelve hours.
                // But "until 12:30pm" is not ambiguous at all, and shifting it produced an event
                // ending at 00:30 the next morning. When am/pm was stated, believe it and move the
                // day instead.
                if (c.timeInMillis <= start) {
                    if (ampm.isBlank()) c.add(Calendar.HOUR_OF_DAY, 12) else c.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (c.timeInMillis <= start) c.add(Calendar.DAY_OF_YEAR, 1)
                return c.timeInMillis
            }
        return null
    }

    // MARK: - Title and people

    /** Names written in the request, left unresolved for `addEvent` to look up. */
    private fun people(prompt: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").findAll(prompt).forEach { out.add(it.value) }
        Regex("(?i)\\b(?:invite|with|and)\\s+([A-Z][\\w'-]{1,20})").findAll(prompt).forEach {
            val name = it.groupValues[1]
            if (!STOP.contains(name.lowercase())) out.add(name)
        }
        return out.toList()
    }

    private val STOP = setOf(
        "google", "meet", "zoom", "calendar", "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday", "today", "tomorrow", "call", "meeting", "a", "an", "the")

    /**
     * A title someone would recognise in their own calendar.
     *
     * Deliberately picked from known shapes rather than assembled from leftover words. Building it
     * out of whatever was not consumed by the parser is how an event ends up called
     * "Block my calendar right now until ." — which is what the phrasing-leftovers approach
     * actually produced.
     */
    private fun title(prompt: String): String {
        val p = prompt.lowercase()
        Regex("(?i)\\b(?:called|titled|for the|about)\\s+\"?([\\w][\\w \\-']{2,40})\"?")
            .find(prompt)?.let { return it.groupValues[1].trim().replaceFirstChar(Char::uppercase) }

        val names = people(prompt).filter { !it.contains("@") }
        if (names.isNotEmpty()) {
            val who = names.joinToString(" and ")
            return when {
                p.contains("lunch") -> "Lunch with $who"
                p.contains("dinner") -> "Dinner with $who"
                p.contains("coffee") -> "Coffee with $who"
                p.contains("interview") -> "Interview with $who"
                else -> "Call with $who"
            }
        }
        return when {
            p.contains("lunch") -> "Lunch"
            p.contains("dinner") -> "Dinner"
            p.contains("coffee") -> "Coffee"
            p.contains("gym") || p.contains("workout") -> "Gym"
            p.contains("focus") || p.contains("deep work") -> "Focus"
            p.contains("block") || p.contains("busy") || p.contains("hold") -> "Busy"
            p.contains("interview") -> "Interview"
            else -> "Meeting"
        }
    }
}
