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
    data class Person(val name: String, val aliases: Set<String>, val email: String = "", val company: String = "",
                      /** Other people you actually message who share this first name — a genuine ambiguity the
                       *  owner should resolve, rather than us silently picking one. */
                      val candidates: List<String> = emptyList())

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

        // 4) WHO DO YOU ACTUALLY TALK TO? A bare first name is ambiguous ("Anna" → co-founder, advisor, or a
        //    LinkedIn contact never messaged). Rank by REAL interaction: the person you exchange messages with
        //    wins over a cold connection who merely shares the name. Resolving "Anna" to a never-messaged
        //    LinkedIn entry put a stranger's identity into replies.
        val ambiguous = ArrayList<String>()
        val isBareFirstName = display.trim().split(" ").size == 1
        try {
            var real = MessageStore.contactsNamed(ctx, display.split(" ").first(), 8)
            // When the caller gave a FULL name, a first-name match is not enough: "Nabeel Khan" was picking up
            // "Nabeel Aslam" (a different human) and inheriting his history. Require a second matching token.
            if (!isBareFirstName) {
                fun toks(x: String) = x.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
                val mine = toks(display)
                real = real.filter { toks(it.first).intersect(mine).size >= 2 || it.first.equals(display, true) }
            }
            // Drop junk contact strings that aren't a person ("Elon Musk reposted", "… via LinkedIn").
            real = real.filter { !Regex("(?i)\\b(reposted|shared|via|from|notification|update)\\b").containsMatchIn(it.first) }
            if (real.isNotEmpty()) {
                real.forEach { ambiguous.add("${it.first} (${it.second} msgs)") }
                val best = real.first()
                // Only promote to a fuller name when we're resolving a bare first name; never override an
                // explicit full name the caller supplied.
                if (isBareFirstName && best.first.split(" ").size > 1) display = best.first
                // ONLY the winner becomes an alias. Adding every same-first-name contact merged DIFFERENT
                // people (the co-founder and the advisor both being "Anna") into one identity, so one person's
                // messages would surface as the other's. The rest are reported as candidates, not aliases.
                aliases.add(best.first)
            }
        } catch (e: Exception) {}

        // 5) The LinkedIn network — fills in the professional identity (company) for whoever we settled on.
        //    Only consult it when messages didn't already identify someone, so a cold connection can't
        //    outrank a real conversation partner.
        try {
            val first = display.split(" ").firstOrNull().orEmpty()
            if (first.length > 2 && ambiguous.isEmpty()) {
                // WORD-BOUNDARY match only. Plain substring matching merged unrelated people: "Elon" is
                // literally inside "Mont-elon-go", so Elon Musk inherited a stranger's identity and history.
                fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
                val mine = words(display).toSet()
                ConnectionStore.search(ctx, display.ifBlank { first }, 5)
                    .firstOrNull { c -> words(c.name).any { it in mine } }
                    ?.let { c ->
                        aliases.add(c.name)
                        if (c.company.isNotBlank()) company = c.company
                        if (isBareFirstName && c.name.split(" ").size > 1) display = c.name
                    }
            } else if (first.length > 2) {
                // We know who they are from messages — just enrich with their company if we have it.
                fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
                val mine = words(display).toSet()
                ConnectionStore.search(ctx, display, 5)
                    .firstOrNull { c -> words(c.name).count { it in mine } >= 2 }   // require a real 2-token match
                    ?.let { c -> if (c.company.isNotBlank()) company = c.company }
            }
        } catch (e: Exception) {}

        val clean = aliases.map { it.trim() }.filter { it.length > 2 }.toCollection(LinkedHashSet())
        // Surface genuine ambiguity rather than silently picking one — the caller can ask the owner.
        val amb = if (isBareFirstName && ambiguous.size > 1) ambiguous else emptyList()
        return Person(display, clean, email, company, amb)
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
            // Dedupe: the same message often lands in the brain more than once (live capture + import), and
            // repeated lines waste context and make the model think something was said twice.
            val seen = LinkedHashSet<String>()
            val lines = msgs.takeLast(12).mapNotNull {
                val body = it.body.replace("\n", " ").trim().take(180)
                val k = body.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                if (k.length < 3 || !seen.add(k)) null
                else (if (it.role == "me") "you" else p.name) + ": " + body
            }.takeLast(8)
            sb.append(lines.joinToString(" · "))
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
            if (p.candidates.size > 1)
                append("\n⚠ AMBIGUOUS first name — people you actually message with this name: ")
                    .append(p.candidates.joinToString(", "))
                    .append(". If the thread doesn't make it obvious which one this is, do NOT assume — ask.")
        }
    }
}
