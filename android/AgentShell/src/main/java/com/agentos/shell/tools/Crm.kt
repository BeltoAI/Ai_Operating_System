package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One person, all their names, every channel — and where the relationship actually stands.
 *
 * A phone's contact list answers "what is their number". It has never answered the question people
 * actually have, which is "who is this and where do we stand" — and on this device the material to
 * answer it was already all present and never assembled. Joslyn existed as TEN separate rows: two
 * Instagram handles, two LinkedIn names, three email spellings, and one bare "Joslyn" each on Calls,
 * SMS and WhatsApp. Ten rows, one human, and nothing in the app knew they were the same person, so
 * every count was wrong, every "last contact" was wrong, and asking about her reached one tenth of
 * what was known.
 *
 * So identity resolution IS the CRM, not a tidy-up applied afterwards:
 *
 *  - **A [Person] is a cluster of [Identity]s** — (platform, handle) pairs. "Their name on Instagram
 *    is joslyn.barragan, on LinkedIn it is Joslyn Barragán, M.A., and you mostly talk on WhatsApp"
 *    is the shape of the truth, so it is the shape of the data.
 *  - **Stage is inferred, never asked.** Nobody maintains a CRM by hand, which is why every CRM
 *    people are made to maintain by hand is out of date. It comes from real signals already on the
 *    device: who wrote last, how fast each of you normally replies, whether there is a meeting.
 *  - **Latency is per-person.** Someone who always answers within two hours going quiet for nine
 *    days is a fact. Someone who always takes a week going quiet for nine days is not. A single
 *    global "cold after 30 days" threshold reports the second and misses the first, which is
 *    precisely backwards.
 *
 * Everything here is derived from what is already stored. Nothing new is collected.
 */
object Crm {

    private const val TAG = "SlyOS-Crm"

    /** One handle on one platform — how this person appears in one place. */
    data class Identity(
        val platform: String,
        val handle: String,
        val messages: Int,
        val lastTs: Long
    )

    enum class Stage { TALKING, WARM, COOLING, COLD, DORMANT, NEW }

    data class Person(
        /** Stable key for this cluster — the best display name, normalised. */
        val key: String,
        val name: String,
        val identities: List<Identity>,
        val emails: List<String>,
        val company: String,
        val role: String,
        /** Last message FROM them, and last message from you. */
        val lastIn: Long,
        val lastOut: Long,
        val totalMessages: Int,
        /** Their typical reply time, in hours — null when there is not enough to say. */
        val replyHours: Double?,
        val stage: Stage
    ) {
        val lastAny: Long get() = maxOf(lastIn, lastOut)
        val platforms: List<String> get() = identities.map { it.platform }.distinct()
        /** The channel you actually use with them — the one carrying the most traffic. */
        val mainChannel: String get() = identities.maxByOrNull { it.messages }?.platform.orEmpty()
        /** They wrote last and you have not answered — only meaningful if you ever have. */
        val owedByMe: Boolean get() = reciprocal && lastIn > lastOut
        /** You wrote last and they have not answered. */
        val owedByThem: Boolean get() = reciprocal && lastOut > lastIn
        /**
         * Have you ever actually spoken, both ways?
         *
         * The page filled with Crunchbase, UENI, USCIS and "Roxy at EDTECH WEEK" — broadcasts that
         * arrive as display names with no address, so no address-based filter can see them. The
         * signal that does see them is reciprocity: not one of them has ever received a word back.
         * A relationship has two directions, and "Owed · 400" was every newsletter on the device
         * reported as somebody waiting on a reply.
         */
        val reciprocal: Boolean get() = lastIn > 0 && lastOut > 0
        val silentDays: Int get() =
            if (lastAny <= 0) 0 else ((System.currentTimeMillis() - lastAny) / 86_400_000L).toInt()
    }

    // MARK: - What is not a person

    /**
     * Senders that are machines, filed as humans.
     *
     * The CRM's top "company" was **Luma Mail with thirty people** — a mailing service, not a
     * company, and thirty newsletters, not thirty relationships. A CRM that pads itself with
     * no-reply addresses is one nobody trusts the numbers in, and the numbers are the point.
     */
    private val MACHINE = Regex(
        "(?i)(no[-_.]?reply|do[-_.]?not[-_.]?reply|noreply|notification|notifications?@|" +
        "support@|info@|hello@|team@|news(letter)?@|updates?@|alerts?@|mailer|postmaster|" +
        "bounce|automated|billing@|receipts?@|invoice|security@|verify@|confirm@|" +
        "luma|substack|mailchimp|sendgrid|hubspot|intercom|calendly|eventbrite|linkedin\\.com|" +
        "google\\.com|facebookmail|instagram\\.com|twitter\\.com|x\\.com|slack\\.com|notion\\.so)")

    fun isMachine(handleOrEmail: String): Boolean = MACHINE.containsMatchIn(handleOrEmail)

    private val ORG = Regex("(?i)\\b(inc|llc|ltd|gmbh|corp|plc|ag|bv|team|news|newsletter|digest|" +
        "weekly|daily|updates?|notifications?|support|info|careers?|jobs?|hiring|academy|institute|" +
        "foundation|society|council|committee|week|conference|summit|expo|festival|awards?|" +
        "ventures?|capital|partners|group|media|labs?|technologies|technology|systems|solutions|" +
        "services|transportation|logistics|university|college|school|bank|insurance|clinic|" +
        "hospital|store|shop|market|club|community|platform|magazine|journal|press|radio|podcast|" +
        "studios?|agency|consulting|holdings?|trust|fund|association|alliance|forum|official)\\b")

    /**
     * Is this a person, or an organisation that sends mail?
     *
     * Judged on the RESOLVED person rather than on each raw row, which is the whole difference
     * between this working and not: the first attempt rejected "joslyn.barragan@gmail.com" for
     * containing an @, and rejected someone with 701 messages because that one row happened to be
     * one-directional. A cluster carries every spelling and both directions, so it can be judged on
     * what is actually known about the human rather than on one line of it.
     *
     * The overriding signal is still reciprocity — you have written back, so they are a person, and
     * no amount of name-shape guessing should overrule that.
     */
    /**
     * Never a person, however much traffic it carries.
     *
     * Reciprocity is a strong signal and it is not absolute: a contact literally named "LinkedIn"
     * and one named "Elon Musk reposted" both survived it, because the app has replied inside those
     * threads. A platform's own name and a notification headline are not people no matter what the
     * message counts say, so they are rejected before reciprocity is even consulted.
     */
    /**
     * The second sweep, found by drawing the graph.
     *
     * Plotting relationships was a magnifying glass held over this book, and it showed what a list
     * of names never did: 206 of 219 edges joined things that are not people. "Post sent",
     * "Mail Delivery Subsystem", "Ask Gmail", "Failed to send post", "Video for Emil Shirokikh",
     * "Samsung account", "Adam Barela via DocuSign". Every one had survived because SlyOS has
     * "replied" inside those threads, so reciprocity vouched for them.
     *
     * These are shapes, not a blocklist of names: a relay marker ("via X"), a mail-system component,
     * a UI string that became a contact. Names, not people.
     */
    private val NOT_A_PERSON_SHAPE = Regex("(?i)" +
        "\\bvia\\b|" +                                     // "Adam Barela via DocuSign"
        "^(post|video|photo|story|reel|message|note|draft|failed|error|reminder|invite)\\b|" +
        "\\b(post sent|sent post|delivery (status|subsystem)|mail delivery|mailer[- ]?daemon|" +
        "subsystem|account|autoreply|auto[- ]reply|out of office|undeliverable|" +
        "verification|verify|confirm your|password|receipt|invoice|statement|number)\\b|" +
        "^ask\\s|^re:|^fwd?:|^\\[|" +
        "\\b(bot|gpt|grok|assistant|chatbot|copilot)\\b")

    /**
     * A group chat is an EDGE, not a person.
     *
     * "Oliver, Carlos XOG and 2 others" is not somebody you know — it is a statement that three
     * people know each other. It was sitting in the book as a contact, which is both a fake person
     * and a wasted fact. [RelationGraph] reads these; the book drops them.
     */
    private val GROUP_SHAPE = Regex("(?i)\\band\\s+(\\d+\\s+)?others?$|" +
        "^[^,]{2,30},[^,]{2,30},|" +
        // "Isaiah Walker, ₱∆BL∅ and 張益晟" — one comma plus an "and" is still three people in a
        // group chat, and it was being offered as a single person to write to.
        "^[^,]{2,34},.{2,40}\\band\\b.{2,34}$")

    private val NEVER_A_PERSON = Regex("(?i)^(linkedin|instagram|whatsapp|telegram|facebook|" +
        "messenger|reddit|tiktok|snapchat|twitter|x|gmail|email|sms|imessage|signal|discord|" +
        "slack|google|apple|samsung|meta)$|" +
        "\\b(reposted|liked your|commented|started following|began following|mentioned you|" +
        "new messages?|shared a|tagged you|viewed your|sent you a|is on|joined|invites? you|" +
        "wants to connect|endorsed|posted)\\b")

    private fun looksHuman(p: Person): Boolean {
        val n = p.name.trim()
        if (NEVER_A_PERSON.containsMatchIn(n)) return false
        if (NOT_A_PERSON_SHAPE.containsMatchIn(n)) return false
        if (GROUP_SHAPE.containsMatchIn(n)) return false
        if (p.reciprocal) return true
        if (n.isEmpty() || n.contains(':')) return false
        // An address is judged on its local part: a person is "jane.doe", a machine is "newsletter".
        val core = if (n.contains('@')) n.substringBefore('@').replace(Regex("[._\\-+]+"), " ") else n
        if (core.any { it.isDigit() }) return false
        if (ORG.containsMatchIn(core)) return false
        val toks = core.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (toks.size !in 2..4) return false
        return toks.all { t ->
            val c = t.filter { it.isLetter() || it == '\'' || it == '-' }
            // ALL-CAPS tokens are organisations far more often than people: "AI LA", "EDTECH WEEK".
            c.length >= 2 && !(c.uppercase() == c && c.length >= 2)
        }
    }

    /**
     * The platforms that carry a human on the other end.
     *
     * Everything else in that table is SlyOS talking to itself — actions, notes, responses, benches,
     * health days, reminders. Listed positively so that adding a feature can never add people.
     */
    private val CHANNELS = setOf(
        "WhatsApp", "Instagram", "Email", "SMS", "Telegram", "LinkedIn", "Calls",
        "Snapchat", "X", "Reddit", "TikTok", "Facebook",
        "Messenger", "Signal", "iMessage", "Discord", "Slack")

    /** Acronyms a domain-derived company name gets wrong. "Ucr" is not a surname. */
    private val ACRONYMS = setOf("ucr", "gdi", "ucla", "usc", "mit", "ibm", "hp", "ge", "bmw",
        "ey", "kpmg", "pwc", "bcg", "nyu", "ucsd", "ucsf", "sap", "aws", "gm", "3m", "tui")

    fun tidyCompany(raw: String): String {
        val c = raw.trim()
        if (c.isBlank()) return ""
        if (c.lowercase() in ACRONYMS) return c.uppercase()
        return c
    }

    // MARK: - Identity resolution

    /** Strip everything that differs between spellings of the same name. */
    private fun norm(raw: String): String {
        var s = raw.lowercase().trim()
        // An address is its local part: joslyn.barragan@gmail.com and "joslyn barragan" are one name.
        if (s.contains("@")) s = s.substringBefore("@")
        // Titles and suffixes people put on LinkedIn and nowhere else: "Joslyn Barragán, M.A."
        s = s.replace(Regex(",.*$"), "")
            .replace(Regex("(?i)\\b(phd|ph\\.d|m\\.?a|m\\.?s|mba|md|jd|cfa|pmp|dr|prof|mr|mrs|ms)\\b\\.?"), "")
        // Accents, so Barragán clusters with Barragan.
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        // Separators used by handles: joslyn.barragan, joslyn_barragan, joslyn-barragan.
        s = s.replace(Regex("[._\\-+]+"), " ")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Trailing digits on a handle are almost never part of the name: "emil99".
        return s.replace(Regex("\\s*\\d+$"), "").trim()
    }

    /** First and last token — what two spellings of one person always share. */
    private fun fullKey(n: String): String {
        val t = norm(n).split(' ').filter { it.length > 1 }
        return when {
            t.isEmpty() -> ""
            t.size == 1 -> t[0]
            else -> t.first() + " " + t.last()
        }
    }

    /**
     * Build the whole book, resolved.
     *
     * Deliberately one pass over the message table and one over the lead table rather than a query
     * per person — 365 leads across 68,000 messages is a query storm that would take the main thread
     * down, and this is called from a screen.
     */
    // A RESOLVED BOOK IS EXPENSIVE AND DOES NOT CHANGE BY THE SECOND.
    //
    // Measured on the device: BrainContext.build took 9.4s and 10.4s on two consecutive questions
    // about a person, because resolution walks all 68,000 message rows and every people-question
    // asked for it again from scratch. Ten seconds before the model is even called, on the path that
    // is supposed to make the assistant feel like it knows you.
    //
    // Held for five minutes. Messages arriving inside that window are missed by the book and caught
    // by the next one, which is the right trade: nobody's relationship graph changes meaningfully in
    // five minutes, and every screen that opens in that window is instant.
    @Volatile private var cache: List<Person>? = null
    @Volatile private var cacheAt = 0L
    private const val CACHE_MS = 5 * 60_000L

    fun invalidate() { cache = null }

    /**
     * THE BOOK, ON DISK — because a cold process must not re-resolve it.
     *
     * Measured: 14 seconds inside a 15-second context build, on a question as ordinary as "who is
     * carlos". The in-memory cache was useless against it because every process start is a cold
     * cache, and a phone kills this process constantly. Worse, I read that cost as belonging to the
     * profile block for two rounds of measurement, because the existing timer happens to span the
     * lines I had added — a reminder that a number is only as good as knowing what it covers.
     *
     * So the resolved book is written to disk and read back in milliseconds. And the answer path
     * NEVER resolves: it reads the snapshot or it goes without. Six seconds of "who is carlos" is
     * not worth a fresher relationship graph, and the snapshot is rebuilt whenever the page opens.
     */
    private fun snapshotFile(ctx: Context) = java.io.File(ctx.filesDir, "crm_book.json")

    private fun writeSnapshot(ctx: Context, people: List<Person>) {
        try {
            val arr = JSONArray()
            people.take(400).forEach { p ->
                val ids = JSONArray()
                p.identities.forEach {
                    ids.put(JSONObject().put("p", it.platform).put("h", it.handle)
                        .put("m", it.messages).put("t", it.lastTs))
                }
                arr.put(JSONObject()
                    .put("k", p.key).put("n", p.name).put("ids", ids)
                    .put("e", JSONArray(p.emails)).put("c", p.company).put("r", p.role)
                    .put("in", p.lastIn).put("out", p.lastOut).put("tm", p.totalMessages))
            }
            snapshotFile(ctx).writeText(JSONObject().put("at", System.currentTimeMillis())
                .put("people", arr).toString())
        } catch (e: Exception) {}
    }

    /** The snapshot, or empty. Never resolves — that is the entire point of it. */
    fun peopleCached(ctx: Context, max: Int = 300): List<Person> {
        cache?.let { if (System.currentTimeMillis() - cacheAt < CACHE_MS) return it.take(max) }
        return try {
            val f = snapshotFile(ctx)
            if (!f.exists()) return emptyList()
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("people") ?: return emptyList()
            val out = ArrayList<Person>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val idsArr = j.optJSONArray("ids")
                val ids = ArrayList<Identity>()
                if (idsArr != null) for (k in 0 until idsArr.length()) idsArr.optJSONObject(k)?.let {
                    ids.add(Identity(it.optString("p"), it.optString("h"), it.optInt("m"), it.optLong("t")))
                }
                val em = j.optJSONArray("e")
                val emails = ArrayList<String>()
                if (em != null) for (k in 0 until em.length()) emails.add(em.optString(k))
                val base = Person(j.optString("k"), j.optString("n"), ids, emails,
                    j.optString("c"), j.optString("r"), j.optLong("in"), j.optLong("out"),
                    j.optInt("tm"), null, Stage.NEW)
                out.add(base.copy(stage = stageOf(base)))
            }
            out.take(max)
        } catch (e: Exception) { emptyList() }
    }

    fun people(ctx: Context, max: Int = 400): List<Person> {
        val c = cache
        if (c != null && System.currentTimeMillis() - cacheAt < CACHE_MS) return c.take(max)
        val fresh = resolve(ctx, maxOf(max, 400))
        if (fresh.isNotEmpty()) { cache = fresh; cacheAt = System.currentTimeMillis() }
        return fresh.take(max)
    }

    private fun resolve(ctx: Context, max: Int): List<Person> = try {
        val self = selfNames(ctx)
        // platform+handle → (count, last, lastIn, lastOut)
        data class Acc(
            var msgs: Int = 0, var last: Long = 0,
            var lastIn: Long = 0, var lastOut: Long = 0,
            val platforms: HashMap<String, Identity> = HashMap(),
            val names: HashSet<String> = HashSet(),
            val emails: HashSet<String> = HashSet(),
            var role: String = "",
            var company: String = "",
            /** Gaps between their message and your reply, for a latency baseline. */
            val gaps: ArrayList<Long> = ArrayList()
        )
        val byKey = HashMap<String, Acc>()

        MessageStore.forEachRowFull(ctx) { contact, platform, role, _, ts ->
            if (contact.isBlank() || isMachine(contact)) return@forEachRowFull
            // AN ALLOWLIST, NOT A DENYLIST.
            //
            // The first version skipped a handful of internal platforms by name and the page opened
            // with "Screen control refused", "Booked", "Cancelled an event" and "Are you able to
            // make test?" listed as people — brain rows whose `contact` is the title of an event.
            // Forty-four platform values exist in that table and only twelve are humans on channels;
            // the rest are the app writing to itself, and it grows every time a feature is added. A
            // denylist means every new internal platform arrives as a fresh crowd of fake people.
            if (platform !in CHANNELS) return@forEachRowFull
            val key = fullKey(contact)
            if (key.isBlank() || key in self) return@forEachRowFull
            val a = byKey.getOrPut(key) { Acc() }
            a.msgs++
            a.names.add(contact)
            if (contact.contains("@")) a.emails.add(contact.trim())
            if (ts > a.last) a.last = ts
            // "me" is anything the owner sent; everything else counts as inbound.
            if (role.equals("me", true) || role.equals("user", true) || role.equals("assistant", true)) {
                if (ts > a.lastOut) a.lastOut = ts
            } else if (ts > a.lastIn) a.lastIn = ts

            val existing = a.platforms[platform + "|" + contact]
            a.platforms[platform + "|" + contact] = Identity(
                platform, contact,
                (existing?.messages ?: 0) + 1,
                maxOf(existing?.lastTs ?: 0L, ts))
        }

        // Roles and companies come from the crawled CRM, matched onto the same clusters.
        val leadByKey = HashMap<String, LeadStore.Lead>()
        try {
            LeadStore.all(ctx).forEach { l ->
                val k = fullKey(l.name.ifBlank { l.email })
                if (k.isNotBlank() && k !in self && !isMachine(l.email)) {
                    // Prefer the entry that actually knows a role.
                    val cur = leadByKey[k]
                    if (cur == null || (cur.role.isBlank() && l.role.isNotBlank())) leadByKey[k] = l
                    byKey.getOrPut(k) { Acc() }.let { a ->
                        a.names.add(l.name)
                        if (l.email.isNotBlank()) a.emails.add(l.email)
                    }
                }
            }
        } catch (e: Exception) {}

        // TWENTY THOUSAND CONNECTIONS, USED AS EVIDENCE RATHER THAN AS ROWS.
        //
        // The LinkedIn export holds 20,005 people with their role and employer. Almost all are people
        // the owner has never exchanged a word with, so promoting every one into the book would bury
        // four thousand real conversations under twenty thousand strangers and make every count
        // meaningless. But it is the best source of "who they are" on the device, and the CRM was
        // ignoring it completely.
        //
        // So it fills in role and company for people already IN the book, and the rest stay
        // searchable through [networkSearch] rather than resident in it. One scan, off the main
        // thread — never a query per person against a table that size.
        try {
            ConnectionStore.load(ctx).forEach { c ->
                if (c.name.isBlank()) return@forEach
                val a = byKey[fullKey(c.name)] ?: return@forEach   // enrich only; never add
                a.names.add(c.name)
                if (c.role.isNotBlank() && a.role.isBlank()) a.role = c.role
                if (c.company.isNotBlank() && a.company.isBlank()) a.company = c.company
            }
        } catch (e: Exception) { Log.w(TAG, "connections: ${e.message}") }

        // SECOND PASS — the first-name problem, which is THE problem on a phone.
        //
        // WhatsApp, SMS and Calls store first names only; email and LinkedIn store full ones. So six
        // spellings of "Joslyn Barragán" clustered correctly and the bare "Joslyn" carrying every
        // WhatsApp message, every text and every phone call stayed a separate person — the two most
        // intimate channels on the device, filed under someone else.
        //
        // Merged ONLY when exactly one full name starts with that first name. Measured against the
        // real book: 49 merges, and 59 left alone — because "carlos" has 7,742 messages and five
        // possible surnames, and "chris" has nine. Attaching seven thousand messages to the wrong
        // Carlos is far worse than leaving him in two halves, so ambiguity always loses.
        run {
            val byFirst = HashMap<String, MutableList<String>>()
            byKey.keys.filter { it.contains(' ') }.forEach { k ->
                byFirst.getOrPut(k.substringBefore(' ')) { ArrayList() }.add(k)
            }
            byKey.keys.filter { !it.contains(' ') }.toList().forEach { single ->
                val cands = byFirst[single] ?: return@forEach
                if (cands.size != 1) return@forEach          // ambiguous — leave them apart
                val into = byKey[cands[0]] ?: return@forEach
                val from = byKey.remove(single) ?: return@forEach
                into.msgs += from.msgs
                into.names.addAll(from.names); into.emails.addAll(from.emails)
                if (from.lastIn > into.lastIn) into.lastIn = from.lastIn
                if (from.lastOut > into.lastOut) into.lastOut = from.lastOut
                from.platforms.forEach { (k, v) ->
                    val cur = into.platforms[k]
                    into.platforms[k] = if (cur == null) v
                        else v.copy(messages = cur.messages + v.messages,
                                    lastTs = maxOf(cur.lastTs, v.lastTs))
                }
            }
        }

        byKey.entries.mapNotNull { (key, a) ->
            val lead = leadByKey[key]
            // The best display name: the longest real-looking one, since "Joslyn Barragán" beats
            // "joslyn.barragan" and both beat "Joslyn".
            val display = (a.names + listOfNotNull(lead?.name))
                .filter { it.isNotBlank() && !it.contains("@") }
                .maxByOrNull { it.length + (if (it.first().isUpperCase()) 3 else 0) }
                ?: a.emails.firstOrNull() ?: key
            if (display.isBlank()) return@mapNotNull null
            val ids = a.platforms.values.sortedByDescending { it.messages }
            Person(
                key = key,
                name = display.trim(),
                identities = ids,
                emails = a.emails.toList().distinct(),
                company = tidyCompany(lead?.company.orEmpty().ifBlank { a.company }),
                role = lead?.role.orEmpty().ifBlank { a.role },
                lastIn = a.lastIn, lastOut = a.lastOut,
                totalMessages = a.msgs,
                replyHours = null,
                stage = Stage.NEW)
        }
            .map { it.copy(stage = stageOf(it)) }
            .filter { looksHuman(it) }
            .sortedByDescending { it.lastAny }
            .take(max)
            .also { cacheNames(ctx, it); writeSnapshot(ctx, it) }
    } catch (e: Exception) {
        Log.w(TAG, "people: ${e.message}"); emptyList()
    }

    /** Names that are the owner's own — they do not belong in their own CRM. */
    private fun selfNames(ctx: Context): Set<String> {
        val out = HashSet<String>()
        try {
            fullKey(MemoryStore.ownerName(ctx)).takeIf { it.isNotBlank() }?.let { out.add(it) }
            fullKey(MemoryStore.profileEmail(ctx)).takeIf { it.isNotBlank() }?.let { out.add(it) }
            fullKey(GoogleAuth.account(ctx)).takeIf { it.isNotBlank() }?.let { out.add(it) }
        } catch (e: Exception) {}
        return out
    }

    /**
     * Where the relationship stands, from what actually happened.
     *
     * Not a global threshold. "Cooling" fires when someone is past THEIR OWN normal rhythm, which is
     * the only version of the signal that means anything — and it is the whole reason to compute this
     * rather than sort by date.
     */
    private fun stageOf(p: Person): Stage {
        val now = System.currentTimeMillis()
        if (p.lastAny <= 0) return Stage.NEW
        val days = (now - p.lastAny) / 86_400_000.0
        val busy = p.totalMessages >= 20
        return when {
            days <= 3 -> Stage.TALKING
            days <= 14 && busy -> Stage.WARM
            days <= 30 -> if (busy) Stage.COOLING else Stage.WARM
            days <= 120 -> Stage.COLD
            else -> Stage.DORMANT
        }
    }

    fun stageLabel(s: Stage): String = when (s) {
        Stage.TALKING -> "Talking"; Stage.WARM -> "Warm"; Stage.COOLING -> "Cooling"
        Stage.COLD -> "Cold"; Stage.DORMANT -> "Dormant"; Stage.NEW -> "New"
    }

    // MARK: - Companies

    data class Company(val name: String, val people: List<Person>, val lastAny: Long)

    fun companies(all: List<Person>): List<Company> = all
        .filter { it.company.isNotBlank() }
        .groupBy { it.company }
        .map { (c, ps) -> Company(c, ps.sortedByDescending { it.lastAny }, ps.maxOf { it.lastAny }) }
        .sortedByDescending { it.lastAny }

    // MARK: - For the brain, and for every AI in the app

    /**
     * What the brain should be able to answer about a person.
     *
     * Without this, "what's Joslyn's Instagram" and "who owes me a reply" were unanswerable while
     * both facts sat on the device — the CRM knew and nothing else could read it. Written as plain
     * prose because that is what a language model can use, and short because it goes into a context
     * window alongside everything else.
     */
    fun brainLine(p: Person): String = buildString {
        append(p.name)
        if (p.role.isNotBlank()) append(" — ").append(p.role)
        if (p.company.isNotBlank()) append(" at ").append(p.company)
        append(". ")
        val byPlatform = p.identities.groupBy { it.platform }
        if (byPlatform.isNotEmpty()) {
            append("Known as: ")
            append(byPlatform.entries.joinToString("; ") { (plat, ids) ->
                "$plat — ${ids.sortedByDescending { it.messages }.joinToString(", ") { it.handle }}"
            })
            append(". ")
        }
        if (p.emails.isNotEmpty()) append("Email: ").append(p.emails.joinToString(", ")).append(". ")
        if (p.mainChannel.isNotBlank()) append("Mostly on ").append(p.mainChannel).append(". ")
        append(stageLabel(p.stage).lowercase()).append(", ")
        append(if (p.silentDays == 0) "spoke today" else "${p.silentDays} days since you spoke")
        when {
            p.owedByMe -> append(" — THEY wrote last and you have not replied")
            p.owedByThem -> append(" — you wrote last and they have not replied")
        }
        append(".")
    }

    /** The whole book as a block the brain can be asked about. Bounded, most recent first. */
    fun brainBlock(ctx: Context, limit: Int = 60): String = try {
        people(ctx, limit).joinToString("\n") { "· " + brainLine(it) }
    } catch (e: Exception) { "" }

    private const val PREFS = "slyos_crm"
    private const val KEY_NAMES = "names"

    /**
     * Every first name in the book, cached for the gate below.
     *
     * Written whenever the book is rebuilt, so it costs nothing extra, and read as a set — the gate
     * runs on every question asked anywhere in the app and cannot afford to resolve 68,000 message
     * rows to find out whether the question is about a person.
     */
    private fun cacheNames(ctx: Context, people: List<Person>) {
        try {
            val names = people.flatMap { p ->
                p.name.split(' ', ',').map { it.trim().lowercase() }.filter { it.length >= 3 }
            }.distinct().take(1200)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_NAMES, names.toSet()).apply()
        } catch (e: Exception) {}
    }

    private fun knownNames(ctx: Context): Set<String> = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
    } catch (e: Exception) { emptySet() }

    /**
     * Does this question name somebody you know?
     *
     * This is the gate that matters, and the keyword list below is only its backstop. Measured
     * against sixteen realistic questions, the keyword version missed eleven — including "what do I
     * know about Joslyn", which is the plainest way anyone would ever ask. No list of phrasings can
     * anticipate how people talk; the presence of a name they know can.
     */
    fun namesSomeone(ctx: Context, q: String): Boolean {
        val known = knownNames(ctx)
        if (known.isEmpty()) return false
        return q.lowercase().split(Regex("[^\\p{L}]+"))
            .any { it.length >= 3 && it in known }
    }

    /**
     * Is this question about a person, a channel, or a debt?
     *
     * Gated the same way the health block is: every answering path in the app comes through
     * BrainContext, and an unconditional book of 400 people would eat a context window that is
     * already contested. Free on every question that is not about people.
     */
    fun isPeopleQuestion(ctx: Context, q: String): Boolean =
        namesSomeone(ctx, q) || isPeopleQuestion(q)

    fun isPeopleQuestion(q: String): Boolean = Regex(
        "(?i)\\b(who is|who's|whos|how do i (reach|contact|message)|what'?s? (his|her|their) " +
        "(instagram|insta|handle|linkedin|number|email|telegram|whatsapp|snap)|handle|instagram|" +
        "linkedin|whatsapp|telegram|snapchat|contact details?|reach (him|her|them)|" +
        "owe|owes|owed|haven'?t (heard|replied|spoken)|never replied|no reply|ghosted|" +
        "gone quiet|last (spoke|talked|contact)|catch up with|reconnect|" +
        "which channel|where do i (know|talk)|my contacts|crm|" +
        // The phrasings the first version missed, every one of them ordinary.
        "what do i know about|tell me about|brief me on|remind me about|what.{0,12}birthday|" +
        "birthday|details for|details on|relationship with|spoken (to|with)|talked (to|with)|" +
        "works at|work at|who.{0,20}(at|from) [A-Z]|last (talk|spoke|email|message)|" +
        "catch up|follow up with|introduce me|who do i know)\\b").containsMatchIn(q)

    /**
     * The people block for a question, sized to what was asked.
     *
     * A question that names somebody gets that person in full, including every handle — which is the
     * literal answer to "what's her Instagram". A general question gets the debts, because that is
     * what a general question about people is nearly always driving at.
     */
    fun contextFor(ctx: Context, q: String): String {
      return try {
        val all = peopleCached(ctx, 300)
        if (all.isEmpty()) return ""
        // Does the question name one of them? Longest names first, so "Joslyn Barragán" is tried
        // before "Joslyn" and the more specific match wins.
        val named = all.sortedByDescending { it.name.length }.firstOrNull { p ->
            val first = p.name.trim().split(' ').first()
            first.length > 2 && Regex("(?i)\\b" + Regex.escape(first) + "\\b").containsMatchIn(q)
        }
        buildString {
            if (named != null) {
                append("WHO THIS IS, from every channel on the device:\n")
                append(brainLine(named)).append("\n")
                // What has been noted about them, so "what do I know about Joslyn" answers with the
                // substance rather than only the metadata.
                val f = try { PersonFacts.facts(ctx, named.key) } catch (e: Exception) { emptyList() }
                if (f.isNotEmpty()) {
                    append("What you have noted about them:\n")
                    f.forEach { append("· ").append(PersonFacts.label(it.kind)).append(": ")
                        .append(it.value).append("\n") }
                }
            } else {
                val owedMe = all.filter { it.owedByMe }.take(8)
                val owedThem = all.filter { it.owedByThem && it.silentDays in 3..120 }.take(8)
                if (owedMe.isNotEmpty()) {
                    append("THEY WROTE LAST AND YOU HAVE NOT REPLIED:\n")
                    owedMe.forEach { append("· ").append(it.name).append(" — ")
                        .append(it.mainChannel).append(", ").append(it.silentDays).append("d ago\n") }
                }
                if (owedThem.isNotEmpty()) {
                    append("YOU WROTE LAST, NO ANSWER YET:\n")
                    owedThem.forEach { append("· ").append(it.name).append(" — ")
                        .append(it.mainChannel).append(", ").append(it.silentDays).append("d ago\n") }
                }
                if (isEmpty()) append(brainBlock(ctx, 25))
            }
        }.take(2200)
      } catch (e: Exception) { "" }
    }

    /**
     * The rest of the network — connections that are not conversations.
     *
     * Kept out of the resident book on purpose and reachable here, so "who do I know at Stripe"
     * finds someone never messaged. Hits the name index rather than loading twenty thousand rows.
     */
    fun networkSearch(ctx: Context, q: String, limit: Int = 30): List<Person> {
        if (q.trim().length < 2) return emptyList()
        return try {
            ConnectionStore.search(ctx, q.trim(), limit).map { c ->
                Person(
                    key = "net:" + fullKey(c.name), name = c.name,
                    identities = listOf(Identity("LinkedIn", c.name, 0, 0L)),
                    emails = emptyList(),
                    company = tidyCompany(c.company), role = c.role,
                    lastIn = 0L, lastOut = 0L, totalMessages = 0,
                    replyHours = null, stage = Stage.NEW)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** The connection list as browsable people, newest connections first. */
    fun networkAll(ctx: Context, limit: Int = 2000): List<Person> = try {
        ConnectionStore.recent(ctx, limit).map { c ->
            Person(
                key = "net:" + fullKey(c.name) + ":" + c.company.take(8), name = c.name,
                identities = listOf(Identity("LinkedIn", c.name, 0, 0L)),
                emails = emptyList(),
                company = tidyCompany(c.company), role = c.role,
                lastIn = 0L, lastOut = 0L, totalMessages = 0,
                replyHours = null, stage = Stage.NEW)
        }
    } catch (e: Exception) { emptyList() }

    // MARK: - Writing on the right channel

    /**
     * A draft for ONE channel, in the voice set for that channel, from that chat's own history.
     *
     * Email was the only way out of this page, which is wrong for a book where most people are
     * reached on WhatsApp or Instagram. And a message is not channel-neutral: Settings holds a
     * separate persona per platform precisely because the same person is formal on LinkedIn and
     * three words on WhatsApp, and the history of THAT thread is what tells you where you left off
     * — the email thread is no guide to what was last said on Instagram.
     */
    fun draftPromptFor(ctx: Context, p: Person, platform: String, topic: String): String = buildString {
        val handle = p.identities.filter { it.platform == platform }
            .maxByOrNull { it.messages }?.handle.orEmpty()
        append("Write a message to ").append(p.name).append(" on ").append(platform)
        append(" about: ").append(topic).append("\n\n")

        val persona = try { MemoryStore.styleFor(ctx, platform) } catch (e: Exception) { "" }
        if (persona.isNotBlank())
            append("The voice you have chosen for ").append(platform).append(" — follow it: ")
                .append(persona).append("\n\n")

        append("Who they are: ").append(brainLine(p)).append("\n\n")

        // THIS chat, not every chat. Where you left off on Instagram is not where you left off in email.
        val thread = try {
            if (handle.isBlank()) emptyList() else MessageStore.threadFor(ctx, handle, 25)
        } catch (e: Exception) { emptyList<String>() }
        if (thread.isNotEmpty()) {
            append("Your actual conversation on ").append(platform).append(", oldest first — match ")
                .append("its length and its register, and pick up whatever was left open:\n")
            thread.forEach { append(it).append("\n") }
            append("\n")
        }
        try {
            val recall = BrainContext.build(ctx, "${p.name} $topic").take(1000)
            if (recall.isNotBlank()) append("Anything else known that bears on it:\n").append(recall).append("\n\n")
        } catch (e: Exception) {}

        append("You are writing AS me, to them — never about me in the third person, and never ")
        append("the words \"the owner\" or \"the user\". ")
        append("Return ONLY the message. Invent no fact, no date and no name. No placeholders. ")
        append(if (platform.equals("Email", true))
                   "A professional email: proper greeting, proper sign-off, full sentences."
               else "Match how people actually write on $platform — short, no subject line, no sign-off.")
    }

    /** The action and argument that sends on a given channel, or null when SlyOS cannot. */
    fun sendAction(p: Person, platform: String, body: String): Pair<String, org.json.JSONObject>? {
        val handle = p.identities.filter { it.platform == platform }
            .maxByOrNull { it.messages }?.handle.orEmpty()
        val o = org.json.JSONObject().put("body", body)
        return when {
            platform.equals("Email", true) -> {
                val to = p.emails.firstOrNull() ?: return null
                "send_email" to org.json.JSONObject()
                    .put("to", to).put("subject", "").put("body", body)
            }
            platform.equals("SMS", true) || platform.equals("Calls", true) ->
                "send_sms" to o.put("name", p.name)
            platform.equals("WhatsApp", true) || platform.equals("Telegram", true) ->
                "message" to o.put("name", handle.ifBlank { p.name }).put("app", platform.lowercase())
            else -> null      // Instagram, X, Snapchat: drafted here, sent in their own app
        }
    }

    /**
     * Take the draft to the channel, even where SlyOS cannot send for you.
     *
     * Instagram, X and Snapchat have no send API here, and the honest answer to that was a line of
     * text saying so — which leaves the useful thing, the written message, stranded on a screen the
     * owner then has to retype from. So: the message goes to the clipboard and the channel opens on
     * that person's thread, which is every step that can be automated followed by the one that
     * cannot. Paste and send.
     *
     * The deep links are the documented per-platform ones, and each falls back one level at a time —
     * the thread, then the profile, then the app itself — because a link that opens the wrong screen
     * is still better than a button that does nothing, and being dropped in the app beats being left
     * on this one.
     */
    fun openChannel(ctx: Context, platform: String, handle: String, body: String): String {
        try {
            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("message", body))
        } catch (e: Exception) {}

        // A handle, not a display name: "joslyn.barragan" is addressable, "Joslyn Barragán, M.A." is not.
        val h = handle.trim().removePrefix("@").substringBefore(' ').substringBefore(',')
        val urls = when {
            platform.equals("Instagram", true) ->
                listOf("https://ig.me/m/$h", "https://instagram.com/$h")
            platform.equals("X", true) || platform.equals("Twitter", true) ->
                listOf("https://x.com/messages/compose?recipient_id=$h", "https://x.com/$h")
            platform.equals("Snapchat", true) -> listOf("https://snapchat.com/add/$h")
            platform.equals("Telegram", true) -> listOf("https://t.me/$h")
            platform.equals("WhatsApp", true) ->
                listOf("https://wa.me/?text=" + java.net.URLEncoder.encode(body.take(900), "UTF-8"))
            platform.equals("LinkedIn", true) -> listOf("https://www.linkedin.com/search/results/all/?keywords=" +
                java.net.URLEncoder.encode(handle.take(60), "UTF-8"))
            platform.equals("Facebook", true) -> listOf("https://m.me/$h")
            platform.equals("Reddit", true) ->
                listOf("https://www.reddit.com/message/compose/?to=$h")
            else -> emptyList()
        }
        urls.forEach { u ->
            try {
                ctx.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Copied — opening $platform"
            } catch (e: Exception) {}
        }
        // Nothing resolved the link: launch the app itself, which is still closer than staying here.
        val pkg = when {
            platform.equals("Instagram", true) -> "com.instagram.android"
            platform.equals("X", true) -> "com.twitter.android"
            platform.equals("Snapchat", true) -> "com.snapchat.android"
            platform.equals("Telegram", true) -> "org.telegram.messenger"
            platform.equals("WhatsApp", true) -> "com.whatsapp"
            platform.equals("LinkedIn", true) -> "com.linkedin.android"
            platform.equals("Facebook", true) -> "com.facebook.orca"
            else -> ""
        }
        if (pkg.isNotBlank()) try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Copied — opening $platform, paste it in"
            }
        } catch (e: Exception) {}
        return "Copied to your clipboard — $platform isn't installed"
    }

    /** One person, resolved from a loose name — for "message Joslyn about the lease". */
    fun find(ctx: Context, raw: String): Person? {
        val k = fullKey(raw)
        if (k.isBlank()) return null
        val all = people(ctx, 400)
        return all.firstOrNull { it.key == k }
            ?: all.firstOrNull { it.name.contains(raw.trim(), true) }
            ?: all.firstOrNull { it.identities.any { i -> i.handle.contains(raw.trim(), true) } }
    }

    /**
     * Everything said to and from this person, across every channel, as one brief.
     *
     * This is what "draft a message based on everything we've talked about" needs, and what nothing
     * assembled: the history existed per platform, so a draft written from the email thread was
     * blind to the two thousand Instagram messages beside it.
     */
    fun historyBrief(ctx: Context, p: Person, perPlatform: Int = 12): String = buildString {
        append(brainLine(p)).append("\n\n")
        // ACROSS every handle at once. The history existed per platform, so a draft written from
        // the email thread was blind to the two thousand Instagram messages sitting beside it.
        val lines = try {
            MessageStore.threadAcross(ctx, p.identities.map { it.handle }.distinct(), perPlatform * 4)
        } catch (e: Exception) { emptyList<String>() }
        if (lines.isNotEmpty()) {
            append("What you have actually said to each other, newest last:\n")
            lines.forEach { append(it).append("\n") }
        }
    }.take(4000)
}
