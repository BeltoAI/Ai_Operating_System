package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * CROSS-PLATFORM IDENTITY. One human is one person, no matter which app they arrive through.
 *
 * The brain stored each conversation under whatever string the platform happened to supply — "Anna" on
 * Telegram, "Anna Schmidt" on LinkedIn, "anna.schmidt@co.com" in email — and matched threads by EXACT contact
 * string. So a reply on LinkedIn had no idea you'd emailed her that morning, and "when did I last talk to Anna"
 * saw a third of the truth.
 *
 * [aliasesFor] expands whatever handle a surface gives us into every identity that person is known by (name
 * variants + their email + their LinkedIn/company identity), and [historyFor] pulls their real history across
 * EVERY platform, labelled per channel. Everything is best-effort and bounded — a miss degrades to today's
 * behaviour, never worse.
 */
object PersonResolver {
    private const val TAG = "SlyOS-Person"

    /** A resolved person: the best display name plus every string they're known by in the brain. */
    data class Person(val name: String, val aliases: Set<String>, val email: String = "", val company: String = "")

    private fun titleCase(s: String) = s.split(" ", ".", "_", "-").filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Expand [raw] (a display name, handle, or email address) into every identity this person is known by.
     * Cheap: contacts + LinkedIn connections + name-shape heuristics. Never returns fewer than the input.
     */
    fun resolve(ctx: Context, raw: String): Person {
        val input = raw.trim()
        if (input.isBlank()) return Person("", emptySet())
        val aliases = LinkedHashSet<String>()
        aliases.add(input)
        var email = ""
        var display = input
        var company = ""

        // 1) An email address → derive the human name from the local part ("anna.schmidt@co.com" → "Anna Schmidt",
        //    "Anna"), so email threads can be matched to chat threads.
        if (input.contains("@") && input.contains(".")) {
            email = input
            val local = input.substringBefore("@")
            val pretty = titleCase(local.replace(Regex("\\d+"), "").trim())
            if (pretty.length > 2) { aliases.add(pretty); display = pretty
                pretty.split(" ").firstOrNull()?.takeIf { it.length > 2 }?.let { aliases.add(it) } }
        } else {
            // 2) A human name → strip any "Name <email>" wrapper, add the first name and the full name.
            val bare = input.replace(Regex("<[^>]*>"), "").replace(Regex("[\\(\\)\\[\\]]"), "").trim()
            if (bare.length > 2) { aliases.add(bare); display = bare }
            Regex("[\\w.+-]+@[\\w.-]+\\.\\w+").find(input)?.value?.let { email = it; aliases.add(it) }
            bare.split(" ").firstOrNull()?.takeIf { it.length > 2 }?.let { aliases.add(it) }
        }

        // 3) The phone's contacts — gives us the email for a chat name (and vice versa), the strongest real link.
        try {
            if (email.isBlank()) ContactsTool.findEmail(ctx, display)?.takeIf { it.contains("@") }
                ?.let { email = it; aliases.add(it) }
        } catch (e: Exception) {}

        // 4) The LinkedIn network — matches a chat name to a full professional identity (and their company),
        //    so a LinkedIn DM knows who they are even if the chat only says "Anna".
        try {
            val first = display.split(" ").firstOrNull().orEmpty()
            if (first.length > 2) {
                ConnectionStore.search(ctx, display.ifBlank { first }, 5)
                    .firstOrNull { c -> c.name.contains(first, true) || display.contains(c.name.split(" ").first(), true) }
                    ?.let { c ->
                        aliases.add(c.name)
                        if (c.company.isNotBlank()) company = c.company
                        if (display.split(" ").size == 1 && c.name.split(" ").size > 1) display = c.name
                    }
            }
        } catch (e: Exception) {}

        val clean = aliases.map { it.trim() }.filter { it.length > 2 }.toCollection(LinkedHashSet())
        return Person(display, clean, email, company)
    }

    /**
     * This person's real history ACROSS EVERY PLATFORM, grouped by channel, newest last. This is what makes a
     * reply on one app aware of what was said on the others ("you emailed her this morning").
     * Returns "" when there's nothing.
     */
    fun historyFor(ctx: Context, raw: String, limit: Int = 24): String {
        val p = resolve(ctx, raw)
        if (p.aliases.isEmpty()) return ""
        val rows = try { MessageStore.threadAcross(ctx, p.aliases, limit) } catch (e: Exception) { emptyList() }
        if (rows.isEmpty()) return ""
        val byPlatform = rows.groupBy { it.platform.ifBlank { "Other" } }
        val sb = StringBuilder()
        byPlatform.forEach { (platform, msgs) ->
            sb.append("\n• On ").append(platform).append(": ")
            sb.append(msgs.takeLast(8).joinToString(" · ") {
                (if (it.role == "me") "you" else p.name) + ": " + it.body.replace("\n", " ").take(180)
            })
        }
        Log.i(TAG, "identity \"$raw\" → ${p.name} aliases=${p.aliases} · ${rows.size} msgs across ${byPlatform.keys}")
        return sb.toString().trim()
    }

    /** A one-line "who this is" header — name, company, email — for the top of a reply context. */
    fun identityLine(ctx: Context, raw: String): String {
        val p = resolve(ctx, raw)
        if (p.name.isBlank()) return ""
        return buildString {
            append(p.name)
            if (p.company.isNotBlank()) append(" (").append(p.company).append(")")
            if (p.email.isNotBlank()) append(" · ").append(p.email)
            val others = p.aliases.filter { !it.equals(p.name, true) && !it.equals(p.email, true) }
            if (others.isNotEmpty()) append(" · also appears as: ").append(others.joinToString(", "))
        }
    }
}
