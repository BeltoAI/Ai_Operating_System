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
    @Volatile private var building = false

    /**
     * Ready to search, or still being built.
     *
     * The first version was called straight from a composable and did four hundred
     * [PersonResolver.resolve] lookups plus a four-hundred-row text search on the main thread. That
     * is not slow, it is a watchdog kill: the app vanished and restarted with no exception in the
     * log, which reads exactly like a crash and is impossible to find by looking for one.
     *
     * So it builds once, off the main thread, and callers ask whether it is ready rather than
     * blocking on it.
     */
    val ready: Boolean get() = cache.isNotEmpty()

    /** Build in the background. Safe to call repeatedly; only the first call does any work. */
    fun warm(ctx: Context) {
        if (ready || building) return
        building = true
        Thread {
            try { cache = build(ctx.applicationContext) } catch (e: Exception) {} finally { building = false }
        }.start()
    }

    fun all(ctx: Context): List<Entry> {
        if (!ready) warm(ctx)
        return cache
    }

    /**
     * Every address the phone can reach, read straight from the stores.
     *
     * Deliberately NOT via PersonResolver. That resolves one name at a time by scoring it against
     * contacts, message history and the network graph — excellent for "which Anna did you mean",
     * and hopeless four hundred times in a row. It was also lossy: anyone the resolver could not
     * confidently match simply never appeared, which is why addresses that plainly exist in the
     * brain were missing from the picker.
     *
     * Here the addresses are taken directly instead: contacts, then every stored message whose
     * sender IS an address, then every address appearing in any stored body. Nothing is inferred,
     * so nothing is lost.
     */
    private fun build(ctx: Context): List<Entry> {
        val byEmail = LinkedHashMap<String, Entry>()

        fun add(name: String, email: String, source: String, weight: Int) {
            val e = email.trim().trimEnd('.', ',', ';', ')', '>').lowercase()
            if (!e.contains('@') || !e.contains('.') || e.length < 6 || e.length > 100) return
            if (Regex("(?i)(no-?reply|do-?not-?reply|notifications?@|mailer-|bounce|postmaster|" +
                    "@sentry\\.|@example\\.|\\.png|\\.jpg)").containsMatchIn(e)) return
            val prev = byEmail[e]
            byEmail[e] = Entry(
                name.trim().ifBlank { prev?.name.orEmpty() }.ifBlank { e.substringBefore('@') },
                e,
                if (prev != null && prev.weight >= weight) prev.source else source,
                maxOf(weight, prev?.weight ?: 0) + (prev?.let { 1 } ?: 0))
        }

        // 1. Contacts — deliberate, so ranked above anything inferred.
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

        // 2. EVERY stored sender that is itself an address — not just the top few hundred.
        //     topContacts ranks and truncates, which is right for "who do you talk to most" and
        //     wrong for an address book: the person emailed twice in March is exactly the one
        //     someone is surprised to find missing.
        try {
            MessageStore.allContacts(ctx, 8000).forEach { c ->
                if (c.contains('@')) add("", c, "you've corresponded", 600)
            }
        } catch (e: Exception) {}

        // 3. Addresses written inside message bodies — signatures, forwards, cc lines.
        //
        //    By LIKE, not by the full-text index. FTS tokenises, so punctuation is not searchable
        //    and a search for "@" matched NOTHING — which is why the picker offered nineteen
        //    addresses on a phone holding thousands of messages.
        try {
            val re = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
            MessageStore.bodiesContaining(ctx, "@", 4000).forEach { (contact, body) ->
                re.findAll(body).take(8).forEach { add("", it.value, "seen in your messages", 120) }
                if (contact.contains('@')) add("", contact, "you've corresponded", 500)
            }
        } catch (e: Exception) {}

        return byEmail.values.sortedWith(
            compareByDescending<Entry> { it.weight }.thenBy { it.name.lowercase() })
    }

    /**
     * Matches for what has been typed so far.
     *
     * Name first, then address — someone typing "car" means Carlos, not carrier@shipping.com, and a
     * picker that leads with the second one is a picker people stop trusting.
     */
    fun search(ctx: Context, q: String, max: Int = 8): List<Entry> {
        val t = q.trim().lowercase()
        if (!ready) { warm(ctx); return emptyList() }
        if (t.length < 2) return cache.take(max)
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

    /** How many addresses are behind the picker. Zero while it is still building. */
    fun count(ctx: Context): Int = cache.size
}
