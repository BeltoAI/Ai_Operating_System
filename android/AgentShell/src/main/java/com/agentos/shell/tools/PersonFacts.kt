package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The things you learn about someone and then forget.
 *
 * The CRM knows who somebody is and where you stand. It does not know that they mentioned their
 * daughter starting school, that their birthday is in March, that they moved to Lisbon, or that they
 * cannot do Tuesdays — and every one of those was said to you in writing and is sitting in the
 * message table. A relationship is made of exactly that residue, and a book that holds the metadata
 * and drops the substance is an address list with extra steps.
 *
 * So facts accumulate. Each one is extracted once, kept with the evidence it came from, and never
 * silently revised — a fact that quietly changes is worse than one that is missing, because you will
 * repeat it to the person's face.
 *
 * Two rules that matter:
 *
 *  - **Nothing is inferred beyond what was written.** "See you in Lisbon" is not "they live in
 *    Lisbon". The extractor is told to quote, and a fact with no quote behind it is discarded.
 *  - **A date is only a birthday if it was called one.** The single most valuable fact here is also
 *    the easiest to get wrong, and congratulating somebody on the wrong day is worse than not
 *    congratulating them at all.
 */
object PersonFacts {

    private const val PREFS = "slyos_person_facts"

    /** One thing known about somebody, and the words it came from. */
    data class Fact(
        val kind: String,          // birthday · family · place · work · preference · health · other
        val value: String,
        /** What they actually said, so the fact can be checked rather than trusted. */
        val evidence: String,
        val found: Long
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun facts(ctx: Context, personKey: String): List<Fact> = try {
        val arr = JSONArray(p(ctx).getString("f_$personKey", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Fact(it.optString("kind"), it.optString("value"),
                    it.optString("evidence"), it.optLong("found"))
            }
        }.filter { it.value.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, personKey: String, list: List<Fact>) {
        val arr = JSONArray()
        // Bounded per person: a book of four thousand people cannot hold an essay about each.
        list.take(14).forEach {
            arr.put(JSONObject().put("kind", it.kind).put("value", it.value)
                .put("evidence", it.evidence.take(200)).put("found", it.found))
        }
        p(ctx).edit().putString("f_$personKey", arr.toString()).apply()
    }

    fun hasRun(ctx: Context, personKey: String): Boolean = p(ctx).contains("f_$personKey")

    /**
     * Read the history and write down what it says about them.
     *
     * One model call per person, on demand, and remembered — not a background sweep over four
     * thousand people, which would be a fortune spent to learn things about strangers.
     */
    fun learn(ctx: Context, person: Crm.Person): List<Fact> {
        val history = try { Crm.historyBrief(ctx, person, 20) } catch (e: Exception) { "" }
        if (history.length < 200) return facts(ctx, person.key)
        val sys = "You extract only stated facts about a person from messages. Output ONLY a JSON " +
            "array. Each item: {\"kind\":\"birthday|family|place|work|preference|health|other\"," +
            "\"value\":\"<short fact, under 12 words>\",\"evidence\":\"<the exact words they or " +
            "the user wrote, quoted>\"}\n" +
            "RULES. Only what is explicitly stated — never inferred. Every item MUST have a real " +
            "quote from the text in \"evidence\"; if you cannot quote it, leave the item out. " +
            "\"birthday\" ONLY if a birthday is named as such, with the date in the value. Do not " +
            "include anything about the user themselves — only about the other person. Ignore " +
            "greetings, logistics and small talk. Maximum 10 items. If there is nothing solid, " +
            "output []."
        val raw = try {
            AgentClient.complete(sys,
                "The other person is ${person.name}.\n\n$history\n\nJSON array only.", 800)
        } catch (e: Exception) { "" }
        val found = ArrayList<Fact>()
        try {
            val json = raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1)
            val arr = JSONArray(json)
            (0 until arr.length()).forEach { i ->
                arr.optJSONObject(i)?.let { o ->
                    val v = o.optString("value").trim()
                    val ev = o.optString("evidence").trim()
                    // NO QUOTE, NO FACT. The one guard that stops this becoming a rumour mill.
                    if (v.isNotBlank() && ev.length >= 8)
                        found.add(Fact(o.optString("kind").ifBlank { "other" }, v, ev,
                            System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {}

        // Keep what was already known; add only what is new. A fact does not get rewritten by a
        // later pass, because a fact that quietly changes is worse than one that is missing.
        val existing = facts(ctx, person.key)
        val merged = existing + found.filterNot { n ->
            existing.any { it.value.equals(n.value, true) || it.kind == "birthday" && n.kind == "birthday" }
        }
        save(ctx, person.key, merged)
        // Into the brain, so the facts answer questions asked anywhere, not just on this page.
        if (found.isNotEmpty()) try {
            Brain.remember(ctx, "note", "About ${person.name}",
                found.joinToString("\n") { "${it.kind}: ${it.value} — “${it.evidence.take(90)}”" },
                actors = listOf(person.name), role = "system")
        } catch (e: Exception) {}
        return merged
    }

    fun label(kind: String): String = when (kind) {
        "birthday" -> "Birthday"; "family" -> "Family"; "place" -> "Where"
        "work" -> "Work"; "preference" -> "Prefers"; "health" -> "Health"; else -> "Also"
    }

    // MARK: - Birthdays

    /**
     * A birthday as a day and month, when one was found.
     *
     * Deliberately no year: almost nobody states one, and a reminder does not need it. Accepts the
     * forms people actually write — "March 14", "14 March", "14/03", "3/14" — and gives up rather
     * than guessing, because a wrong date here is worse than none.
     */
    fun birthday(ctx: Context, personKey: String): Pair<Int, Int>? {
        val v = facts(ctx, personKey).firstOrNull { it.kind == "birthday" }?.value ?: return null
        val months = listOf("january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december")
        val lower = v.lowercase()
        months.forEachIndexed { i, m ->
            if (lower.contains(m.take(3))) {
                val d = Regex("\\b(\\d{1,2})\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                if (d != null && d in 1..31) return d to (i + 1)
            }
        }
        // Numeric, day-first — the international reading. Ambiguous cases (3/14) resolve by which
        // number cannot be a month.
        Regex("\\b(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\b").find(lower)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            return when {
                a in 1..31 && b in 1..12 -> a to b
                b in 1..31 && a in 1..12 -> b to a
                else -> null
            }
        }
        return null
    }

    data class Upcoming(val name: String, val personKey: String, val daysAway: Int)

    /**
     * Whose birthday is close.
     *
     * Reads the facts already extracted — never a fresh sweep — so this is cheap enough to run on
     * the Now feed. Seven days out, because that is when you could still do something about it, and
     * the day itself, because that is when you say it.
     */
    fun upcoming(ctx: Context, people: List<Crm.Person>, withinDays: Int = 7): List<Upcoming> {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val yearLen = cal.getActualMaximum(java.util.Calendar.DAY_OF_YEAR)
        return people.mapNotNull { p ->
            val (d, m) = birthday(ctx, p.key) ?: return@mapNotNull null
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MONTH, m - 1)
                set(java.util.Calendar.DAY_OF_MONTH, d)
            }
            var away = c.get(java.util.Calendar.DAY_OF_YEAR) - today
            if (away < 0) away += yearLen                       // it is next year's
            if (away <= withinDays) Upcoming(p.name, p.key, away) else null
        }.sortedBy { it.daysAway }
    }

    fun birthdayLine(u: Upcoming): String = when (u.daysAway) {
        0 -> "${u.name}'s birthday is today"
        1 -> "${u.name}'s birthday is tomorrow"
        else -> "${u.name}'s birthday is in ${u.daysAway} days"
    }
}
