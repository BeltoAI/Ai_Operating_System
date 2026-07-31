package com.agentos.shell.tools

import android.content.Context

/**
 * Simple mode — the whole phone reduced to one question and six answers.
 *
 * SlyOS assumes you want power: five destinations along the bottom, a field of twenty thousand
 * people, a settings screen with forty cards in it. For most of the people who would get the most
 * out of an assistant that answers the phone and remembers everything — the ones who currently ask
 * a grandchild to do it — every one of those is a reason to put the phone down.
 *
 * So this takes everything away. No navigation, no field, no settings, no shortcuts to arrange.
 * One line of text at the top and a handful of very large buttons for the things somebody actually
 * wants a phone to do: call a person, hear their messages, know what is happening today, get a
 * ride, order the shopping.
 *
 * Two rules it must never break:
 *
 *  - **It is always reversible, from inside itself.** Hiding the navigation is a serious thing to
 *    do to somebody's phone. The way out is on screen at all times, in the same large type as
 *    everything else — never buried in a settings screen they can no longer reach.
 *  - **The buttons are real.** Each is a sentence handed to the same assistant the full app uses,
 *    so nothing here is a simplified imitation of the product. It is the product, with the
 *    scaffolding removed.
 */
object SimpleMode {

    private const val PREF = "slyos_simple"

    fun on(ctx: Context): Boolean =
        try { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("on", false) }
        catch (e: Exception) { false }

    fun set(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
            Brain.remember(ctx, "note", if (on) "Simple mode on" else "Simple mode off",
                if (on) "The phone was switched to simple mode — large buttons, no navigation."
                else "The phone was switched back to the full layout.", role = "system")
        } catch (e: Exception) {}
    }

    /** Anything a person might plausibly type or say to mean this. */
    val TRIGGER = Regex("(?i)\\b(grand ?parent|grand ?ma|grand ?pa|grandmother|grandfather|" +
        "simple mode|easy mode|senior mode|big buttons?|large text mode)\\b")

    data class Task(val label: String, val prompt: String, val kind: String = "ask")

    /**
     * The local emergency number.
     *
     * 112 is the GSM standard and is redirected to the local service almost everywhere, which makes
     * it the right fallback — but where a country's own number is what people know and what works
     * on a landline-style dialer, use that.
     */
    fun emergencyNumber(ctx: Context): String {
        val cc = try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.simCountryIso.ifBlank { tm.networkCountryIso }.ifBlank {
                ctx.resources.configuration.locales[0].country
            }.uppercase()
        } catch (e: Exception) { "" }
        return when (cc) {
            "US", "CA", "MX", "PH" -> "911"
            "GB", "IE", "PL", "UA" -> "999"
            "AU" -> "000"
            "NZ" -> "111"
            "IN" -> "112"
            "JP" -> "119"
            else -> "112"
        }
    }

    /** More people to call, for the second page. Closest first, because that is who they mean. */
    fun callable(ctx: Context, max: Int = 8): List<Crm.Person> = try {
        Crm.peopleCached(ctx, 400)
            .filter { it.reciprocal && it.name.isNotBlank() && !it.name.contains("@") }
            .sortedByDescending { it.totalMessages }
            .take(max)
    } catch (e: Exception) { emptyList() }

    /**
     * What the buttons say.
     *
     * The first two are people, resolved from who is actually spoken to most on this phone rather
     * than from a made-up "family" list — the phone already knows, and "Call Joslyn" is a button
     * somebody can use where "Call a contact" is one they have to think about.
     */
    fun tasks(ctx: Context): List<Task> {
        val people = try {
            Crm.peopleCached(ctx, 400)
                .filter { it.reciprocal && it.name.isNotBlank() && !it.name.contains("@") }
                .sortedByDescending { it.totalMessages }
                .take(2)
        } catch (e: Exception) { emptyList() }

        val out = ArrayList<Task>(6)
        people.forEach { p ->
            val first = p.name.split(' ').first().take(14)
            out.add(Task("Call $first", "Call ${p.name}"))
        }
        if (people.isNotEmpty()) {
            val first = people[0].name.split(' ').first().take(14)
            out.add(Task("Message $first", "Send a message to ${people[0].name}"))
        }
        out.add(Task("Call someone else", "", kind = "people"))
        out.add(Task("What's on today?", "What is on my calendar today, in one short answer?"))
        out.add(Task("Read my messages", "Read me my new messages, briefly"))
        // The things people this age actually reach for, rather than the things a founder assumes.
        out.add(Task("My photos", "", kind = "photos"))
        out.add(Task("Remind me: pills", "Remind me to take my pills every day at 9am"))
        out.add(Task("Get me a ride", "Get me a ride"))
        out.add(Task("Order the shopping", "Order my usual groceries"))
        return out
    }
}
