package com.agentos.shell.tools

import android.content.Context
import android.provider.ContactsContract

/**
 * Everyone the phone could plausibly send something to, with a real address attached.
 *
 * Invitations were failing for a reason that had nothing to do with models: a name was typed, no
 * address was found, and an event was created with an empty guest list while the reply said it had
 * been sent. The addresses were not missing — they were sitting in the contacts database, in
 * imported message history, and in the headers of every email already received. Nothing had ever
 * gathered them into one list you could search.
 *
 * So this is the address book the guest field searches. Contacts first because those are deliberate,
 * then anyone the owner has actually corresponded with, ranked by how often — the person you email
 * weekly should be the first hit for three letters, not the one you emailed once in 2023.
 *
 * Cached for a minute. Rebuilding this on every keystroke of a guest field would make typing feel
 * broken, and it changes about as often as people change jobs.
 */
object Directory {

    /**
     * @param source where the address came from, shown next to it — "from your contacts" and "you
     *   emailed them in March" are different levels of confidence and the owner should see which.
     * @param weight how often they appear; the sort key, never displayed.
     */
    data class Entry(val name: String, val email: String, val source: String, val weight: Int)

    @Volatile private var cache: List<Entry> = emptyList()
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 60_000L

    fun all(ctx: Context): List<Entry> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < TTL_MS) return cache
        val byEmail = LinkedHashMap<String, Entry>()

        fun add(name: String, email: String, source: String, weight: Int) {
            val e = email.trim().lowercase()
            if (!e.contains('@') || !e.contains('.') || e.length < 6) return
            // Addresses nobody replies to are noise in a guest picker.
            if (Regex("(?i)(no-?reply|do-?not-?reply|notifications?@|mailer-|bounce|postmaster)")
                    .containsMatchIn(e)) return
            val existing = byEmail[e]
            if (existing == null || existing.weight < weight) {
                byEmail[e] = Entry(
                    name.trim().ifBlank { existing?.name ?: e.substringBefore('@') },
                    e, source, maxOf(weight, existing?.weight ?: 0))
            }
        }

        // 1. The contacts app — deliberate, so weighted above anything inferred.
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) add(c.getString(0).orEmpty(), c.getString(1).orEmpty(),
                    "in your contacts", 1000)
            }
        } catch (e: Exception) {}

        // 2. Everyone in the brain's message history — the addresses that actually get used.
        try {
            MessageStore.topContacts(ctx, 400).forEachIndexed { i, t ->
                val name = t.first
                // A contact whose "name" already IS an address needs no resolving.
                if (name.contains('@')) { add("", name, "you message them", 500 + (400 - i)); return@forEachIndexed }
                val p = try { PersonResolver.resolve(ctx, name) } catch (e: Exception) { null }
                if (p != null && p.email.isNotBlank())
                    add(p.name.ifBlank { name }, p.email, "you message them", 500 + (400 - i))
            }
        } catch (e: Exception) {}

        // 3. Addresses appearing anywhere in stored text — the long tail nothing else catches. A
        // search for "@" is a cheap way to reach every message that mentions one.
        try {
            MessageStore.search(ctx, "@", 400).forEach { hit ->
                Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(hit.body).forEach {
                    add("", it.value, "seen in your messages", 100)
                }
            }
        } catch (e: Exception) {}

        cache = byEmail.values.sortedByDescending { it.weight }
        cachedAt = System.currentTimeMillis()
        return cache
    }

    /**
     * Matches for what has been typed so far.
     *
     * Name first, then address — someone typing "car" means Carlos, not carrier@shipping.com, and a
     * picker that leads with the second one is a picker people stop trusting.
     */
    fun search(ctx: Context, q: String, max: Int = 6): List<Entry> {
        val t = q.trim().lowercase()
        if (t.length < 2) return all(ctx).take(max)
        return all(ctx).asSequence()
            .mapNotNull { e ->
                val n = e.name.lowercase()
                val score = when {
                    n == t || e.email == t -> 100
                    n.startsWith(t) -> 90
                    n.split(" ").any { it.startsWith(t) } -> 80
                    e.email.startsWith(t) -> 60
                    n.contains(t) -> 40
                    e.email.contains(t) -> 20
                    else -> 0
                }
                if (score > 0) (score + e.weight / 100) to e else null
            }
            .sortedByDescending { it.first }.take(max).map { it.second }.toList()
    }

    /** How many addresses are behind the picker — worth showing once, so it looks like a directory. */
    fun count(ctx: Context): Int = all(ctx).size
}
