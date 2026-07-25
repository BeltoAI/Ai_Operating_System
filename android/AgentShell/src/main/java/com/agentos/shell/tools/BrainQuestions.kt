package com.agentos.shell.tools

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE BRAIN ASKS BACK. Until now the brain only absorbed — it never asked, so a wrong inference (resolving
 * "Anna" to a LinkedIn contact the owner has never messaged instead of their co-founder) stayed wrong forever
 * and quietly poisoned every reply about that person.
 *
 * This surfaces short, high-value questions in the Now feed. Answering one writes a durable fact straight into
 * the brain (learned facts + the searchable store), so the correction sticks and compounds. Questions are only
 * raised where the payoff is real:
 *   • AMBIGUITY  — several people you actually message share a first name; which one is "Anna"?
 *   • RELATIONSHIP — who is this person to you (co-founder? advisor? client?) for people you talk to a lot.
 *   • GAPS — a channel you use with no character set, so drafts there have no voice.
 */
object BrainQuestions {
    private const val TAG = "SlyOS-Ask"
    private const val PREF = "slyos_brain_questions"
    private const val KEY = "items"
    private const val KEY_ANSWERED = "answered"

    /** @param kind ambiguity|relationship|gap|open  @param subject dedupe key
     *  @param freeform the owner can type an answer (true unless it's a strict yes/no) */
    data class Question(val id: Long, val kind: String, val subject: String, val text: String,
                        val options: List<String> = emptyList(), val freeform: Boolean = true)

    val items = mutableStateListOf<Question>()
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val opts = ArrayList<String>()
                o.optJSONArray("options")?.let { a -> for (j in 0 until a.length()) opts.add(a.optString(j)) }
                items.add(Question(o.optLong("id"), o.optString("kind"), o.optString("subject"), o.optString("text"), opts, o.optBoolean("freeform", true)))
            }
        } catch (e: Exception) {}
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach { q ->
            val o = JSONObject().put("id", q.id).put("kind", q.kind).put("subject", q.subject)
                .put("text", q.text).put("freeform", q.freeform)
            val a = JSONArray(); q.options.forEach { a.put(it) }
            arr.put(o.put("options", a))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun answeredKeys(ctx: Context): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(KEY_ANSWERED, emptySet()) ?: emptySet())

    private const val KEY_ASKED_LOG = "asked_log"
    /** The last questions actually shown — passed to the generator so it stops circling one topic. */
    private fun askedLog(ctx: Context): List<String> =
        (prefs(ctx).getString(KEY_ASKED_LOG, "") ?: "").split("\n").filter { it.isNotBlank() }
    private fun rememberAsked(ctx: Context, text: String) {
        val log = (askedLog(ctx) + text).takeLast(25)
        prefs(ctx).edit().putString(KEY_ASKED_LOG, log.joinToString("\n")).apply()
    }

    private fun markAnswered(ctx: Context, subject: String) {
        val s = answeredKeys(ctx); s.add(subject.lowercase())
        prefs(ctx).edit().putStringSet(KEY_ANSWERED, s).apply()
    }

    private fun add(ctx: Context, kind: String, subject: String, text: String,
                    options: List<String> = emptyList(), freeform: Boolean = true) {
        if (subject.lowercase() in answeredKeys(ctx)) return
        if (items.any { it.subject.equals(subject, true) || it.text.equals(text, true) }) return
        items.add(0, Question(System.currentTimeMillis() + items.size, kind, subject, text, options, freeform))
        rememberAsked(ctx, text)
        while (items.size > 8) items.removeAt(items.size - 1)
        persist(ctx)
    }

    /**
     * Look for things genuinely worth asking. Cheap + local (no LLM): reads the message store, the resolver and
     * the per-channel personas. Safe to call periodically from a background worker.
     */
    /** Junk that isn't an individual human: app placeholders, groups, bots, channels, bare handles/numbers. */
    private fun looksLikeAPerson(name: String): Boolean {
        val n = name.trim()
        if (n.length < 2 || n.length > 40) return false
        if (n.contains("@")) return false
        if (Regex("(?i)\\b(user|instagram|facebook|whatsapp|telegram|group|channel|bot|team|support|noreply|" +
                "no-reply|notification|admin|info|service|updates?|news|alerts?|security|verify|newsletter)\\b")
                .containsMatchIn(n)) return false
        if (Regex("^[+\\d\\s()\\-]+$").matches(n)) return false           // phone numbers
        // Case-INSENSITIVE: "K9" slipped past a lowercase-only pattern and got asked about as if it were a person.
        if (Regex("(?i)^[a-z0-9._-]+$").matches(n) && !n.contains(" ")) return false   // bare handles like "K9"
        if (n.length <= 3 && !n.contains(" ")) return false                            // "K9", "Lea" w/o surname
        if (!Regex("\\p{L}").containsMatchIn(n)) return false
        return true
    }

    private const val KEY_LAST = "last_gen"
    private const val KEY_SEEN_MSGS = "seen_msgs"
    /** Regenerate at least this often, so questions track what's happening now instead of going stale. */
    private val REFRESH_MS = 4 * 3600_000L
    /** …or sooner, once this many new messages have landed in the brain. */
    private const val NEW_MSG_TRIGGER = 40

    /** Force a fresh batch now, keeping the asked-history so banned subjects still apply (debug/testing). */
    fun forceRefresh(ctx: Context) {
        ensureLoaded(ctx)
        items.clear(); persist(ctx)
        prefs(ctx).edit().putLong(KEY_LAST, 0L).apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        ensureLoaded(ctx)
        try {
            val p = prefs(ctx)
            val msgs = try { MessageStore.count(ctx) } catch (e: Exception) { 0 }
            val newSince = msgs - p.getInt(KEY_SEEN_MSGS, 0)
            val age = System.currentTimeMillis() - p.getLong(KEY_LAST, 0L)
            // Questions were persisting forever, so the same three kept reappearing instead of tracking new
            // input. Refresh when enough time has passed OR enough new material has arrived; on a refresh the
            // UNANSWERED ones are cleared so the new batch reflects what's happening now.
            val due = age > REFRESH_MS || newSince >= NEW_MSG_TRIGGER
            if (!due && items.isNotEmpty()) return
            if (due && items.isNotEmpty()) { items.clear(); persist(ctx) }
            p.edit().putLong(KEY_LAST, System.currentTimeMillis()).putInt(KEY_SEEN_MSGS, msgs).apply()
        } catch (e: Exception) {}
        try {
            val top = MessageStore.topContacts(ctx, 60).filter { looksLikeAPerson(it.first) && it.second >= 8 }

            // Build the SIGNALS the model should judge — never asked verbatim.
            val sb = StringBuilder()
            val byFirst = top.groupBy { it.first.trim().split(" ").first().lowercase() }
            byFirst.filter { it.value.map { p -> p.first }.distinct().size > 1 }.forEach { (first, people) ->
                sb.append("AMBIGUOUS FIRST NAME '$first': ")
                    .append(people.map { "${it.first} (${it.second} msgs)" }.distinct().joinToString(", ")).append("\n")
            }
            top.take(12).forEach { (name, count, plat) ->
                sb.append("FREQUENT CONTACT: $name — $count messages" + (if (plat.isNotBlank()) " on $plat" else "") + "\n")
            }
            listOf("LinkedIn" to "linkedin", "Instagram" to "instagram", "WhatsApp" to "whatsapp",
                   "Telegram" to "telegram", "Slack" to "slack", "SMS" to "sms", "Email" to "email").forEach { (label, key) ->
                if (top.any { it.third.equals(label, true) } && MemoryStore.styleFor(ctx, key).isBlank())
                    sb.append("NO VOICE/CHARACTER SET for $label, which the owner actively uses\n")
            }
            // ROTATE WHICH PART OF THE BRAIN WE MINE. Feeding the same recent-messages blob every run made the
            // questions circle one topic forever. Each refresh emphasises a different area, so over time the
            // questioning actually spans the whole brain instead of re-interrogating this week's inbox.
            val focus = (prefs(ctx).getInt("focus", 0)) % 5
            prefs(ctx).edit().putInt("focus", focus + 1).apply()
            sb.append("\nFOCUS THIS ROUND — every question MUST come from this area and from the material listed " +
                "under it below; ignore anything else you know: ").append(
                when (focus) {
                    0 -> "people & relationships — who matters, who's unclear, who's gone quiet"
                    1 -> "work in flight — projects, deals, documents, what's stalled or undecided"
                    2 -> "commitments & time — calendar, deadlines, open tasks, what's slipping"
                    3 -> "preferences & boundaries — how they want things done, what they'd never do"
                    else -> "contradictions & drift — where stated intent and actual behaviour disagree"
                }).append("\n")

            when (focus) {
                0 -> try {
                    val net = ConnectionStore.recent(ctx, 60).joinToString("\n") { c ->
                        "• ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") + (if (c.company.isNotBlank()) " @ ${c.company}" else "")
                    }.take(3000)
                    if (net.isNotBlank()) sb.append("\nNETWORK:\n").append(net).append("\n")
                    val quiet = ConnectionStore.staleConnections(ctx, 90).take(15)
                        .joinToString("\n") { (c, _) -> "• ${c.name} — no contact in 90+ days" }.take(1200)
                    if (quiet.isNotBlank()) sb.append("\nGONE QUIET:\n").append(quiet).append("\n")
                } catch (e: Exception) {}
                1 -> {
                    try {
                        val docs = DocStore.list(ctx).sortedByDescending { it.ts }.take(20)
                            .joinToString("\n") { "• ${it.title} [${it.category}]" + (if (it.summary.isNotBlank()) " — ${it.summary.take(120)}" else "") }.take(3000)
                        if (docs.isNotBlank()) sb.append("\nDOCUMENTS:\n").append(docs).append("\n")
                    } catch (e: Exception) {}
                    try {
                        val papers = PaperStore.list(ctx).joinToString("\n") { "• “${it.title}” (${it.docType})" }.take(1200)
                        if (papers.isNotBlank()) sb.append("\nTHEIR WRITING:\n").append(papers).append("\n")
                    } catch (e: Exception) {}
                }
                2 -> {
                    try {
                        if (CalendarTool.hasPermission(ctx)) CalendarTool.upcoming(ctx).take(2500)
                            .takeIf { it.isNotBlank() }?.let { sb.append("\nUPCOMING CALENDAR:\n").append(it).append("\n") }
                    } catch (e: Exception) {}
                    try {
                        val tasks = ChecklistStore.load(ctx).filter { !it.done }.joinToString("\n") { "• ${it.text}" }.take(2000)
                        if (tasks.isNotBlank()) sb.append("\nOPEN TASKS:\n").append(tasks).append("\n")
                    } catch (e: Exception) {}
                }
                else -> try {
                    val recent = MessageStore.recentLines(ctx, 80).joinToString("\n").take(6000)
                    if (recent.isNotBlank()) sb.append("\nRECENT ACTIVITY / CONVERSATIONS:\n").append(recent).append("\n")
                } catch (e: Exception) {}
            }

            if (sb.isBlank()) { Log.i(TAG, "nothing worth asking"); return }

            // WHAT'S ALREADY KNOWN — so it never asks something the brain already has (it was asking who the
            // owner's wife is, when the brain already knew). Digest + learned facts + profile.
            val known = buildString {
                append(try { BrainDigest.getOrFull(ctx) } catch (e: Exception) { "" }).append("\n")
                append(try { MemoryStore.learnedFacts(ctx).joinToString("\n") } catch (e: Exception) { "" })
                append("\nAlready answered before: ").append(answeredKeys(ctx).joinToString(", "))
                // Everything recently ASKED — so the next batch explores a different corner of the brain
                // instead of re-asking around the same subject.
                val prior = askedLog(ctx)
                if (prior.isNotEmpty()) {
                    append("\n\nQUESTIONS ALREADY ASKED (these subjects are CLOSED — asking anything about them " +
                        "again, in any wording, is a failure):\n").append(prior.joinToString("\n"))
                    // Name the actual subjects to ban. The self-model is dominated by a few big projects, so the
                    // model kept gravitating back to them however the question was phrased; listing the banned
                    // nouns explicitly is what finally breaks the loop.
                    val banned = prior.flatMap { q ->
                        Regex("\\b[A-Z][A-Za-z0-9]{2,}\\b").findAll(q).map { it.value }.toList()
                    }.filter { it !in setOf("What", "Is", "Are", "Do", "Does", "Would", "Should", "Which", "How", "When", "Who", "The", "Your", "You") }
                        .distinct().take(20)
                    if (banned.isNotEmpty())
                        append("\n\nBANNED SUBJECTS this round (already covered — do NOT mention or ask about ANY of " +
                            "these): ").append(banned.joinToString(", "))
                }
            }

            val qs = try { AgentClient.brainQuestions(known, sb.toString(), 4) } catch (e: Exception) { emptyList() }
            qs.forEach { (text, options, freeform) ->
                add(ctx, "open", "q-" + text.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(60),
                    text, options, freeform)
            }
            Log.i(TAG, "generated ${qs.size} question(s); pending: ${items.size}")
        } catch (t: Throwable) { Log.w(TAG, "refresh: ${t.message}") }
    }

    /**
     * The owner answered. Persist it as a DURABLE fact (learned facts + searchable brain) so every future
     * reply and every agent uses it, and never ask this again.
     */
    fun answer(ctx: Context, q: Question, answer: String, attachName: String = "", attachText: String = "") {
        // Store the question WITH the answer — the pair is what carries meaning ("Which Anna do you mean?" →
        // "Anna Atlasova" is useless without the question). Kept first-person so it reads as the owner's own fact.
        val fact = when (q.kind) {
            "ambiguity" -> "When I say “${q.subject.removePrefix("who-is-").replaceFirstChar { it.uppercase() }}” I mean $answer."
            "relationship" -> "${q.subject.removePrefix("relationship-")} is my ${answer.lowercase()}."
            else -> "${q.text.trimEnd('?', ' ')}? → $answer"
        }
        try {
            if (answer.isNotBlank()) {
                MemoryStore.addLearnedFact(ctx, fact)
                // SUPERSEDE STALE BELIEFS. A correction used to just pile on top: the brain ended up holding
                // both "committing to Harvard ALM regardless of traction" AND "flexible — fundraise takes
                // priority". Contradictions make every downstream answer unreliable, so the newer, explicitly
                // given answer wins and the beliefs it contradicts are dropped.
                Thread { try { supersede(ctx, fact) } catch (t: Throwable) {} }.start()
            }
        } catch (e: Exception) {}
        // An attached document is the SOURCE, not a summary — file it and index its text so every future
        // reply and agent can actually read it, not just know it exists.
        if (attachName.isNotBlank()) {
            val label = "Attached while answering: ${q.text.take(80)}"
            try { MessageStore.insertOne(ctx, "Me", "Profile", "me", "me", "$label — file: $attachName") } catch (e: Exception) {}
            if (attachText.isNotBlank()) {
                try { DocText.add(ctx, attachName, "attachment", attachText.take(60000)) } catch (e: Exception) {}
                try {
                    attachText.take(24000).chunked(1200).take(20)
                        .forEach { VectorStore.enqueue(ctx, "Document: $attachName", "doc", it) }
                } catch (e: Exception) {}
            }
            Log.i(TAG, "attached '$attachName' (${attachText.length} chars) to: ${q.text.take(60)}")
        }
        try { MessageStore.insertOne(ctx, "Me", "Profile", "me", "me", fact) } catch (e: Exception) {}
        try { VectorStore.enqueue(ctx, "About me", "me", fact) } catch (e: Exception) {}
        // A per-channel voice answer is a setting, not just a fact.
        if (q.kind == "gap") {
            val key = q.subject.removePrefix("voice-")
            try { MemoryStore.setStyleFor(ctx, key, answer) } catch (e: Exception) {}
        }
        markAnswered(ctx, q.subject)
        items.removeAll { it.id == q.id }
        persist(ctx)
        // The self-model should reflect the correction promptly.
        try { Thread { BrainDigest.generate(ctx) }.start() } catch (e: Exception) {}
        Log.i(TAG, "learned: $fact")
    }

    /** Drop previously-learned facts that the owner's new answer directly contradicts. */
    private fun supersede(ctx: Context, newFact: String) {
        val facts = try { MemoryStore.learnedFacts(ctx) } catch (e: Exception) { return }
        val others = facts.filter { !it.equals(newFact, true) }
        if (others.size < 2) return
        val stale = try { AgentClient.contradictedBy(newFact, others) } catch (t: Throwable) { emptyList() }
        if (stale.isEmpty()) return
        val keep = facts.filter { f -> stale.none { it.equals(f, true) } }
        if (keep.size != facts.size) {
            MemoryStore.setLearnedFacts(ctx, keep)
            Log.i(TAG, "superseded ${facts.size - keep.size} stale belief(s) after: ${newFact.take(70)}")
        }
    }

    fun dismiss(ctx: Context, q: Question) {
        markAnswered(ctx, q.subject)
        items.removeAll { it.id == q.id }
        persist(ctx)
    }
}
