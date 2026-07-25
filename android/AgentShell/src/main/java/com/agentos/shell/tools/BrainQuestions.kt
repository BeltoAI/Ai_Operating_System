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
        // 60, not 25: at ~4 questions a batch, 25 covered barely six refreshes — less than two full passes of
        // the 5-way focus rotation, so a question fell out of the ban window and came back as "new".
        val log = (askedLog(ctx) + text).takeLast(60)
        prefs(ctx).edit().putString(KEY_ASKED_LOG, log.joinToString("\n")).apply()
    }

    /** Comparable form of a question. Wording drifts ("Who's Anna?" / "Who is Anna?" / "who is anna") while
     *  the question is identical, so raw string equality never caught the repeats the owner actually saw. */
    private fun normQ(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun markAnswered(ctx: Context, subject: String) {
        val s = answeredKeys(ctx); s.add(subject.lowercase())
        prefs(ctx).edit().putStringSet(KEY_ANSWERED, s).apply()
    }

    /** @return true if the question was actually accepted (false = duplicate/already asked). */
    private fun add(ctx: Context, kind: String, subject: String, text: String,
                    options: List<String> = emptyList(), freeform: Boolean = true): Boolean {
        if (subject.lowercase() in answeredKeys(ctx)) return false
        if (items.any { it.subject.equals(subject, true) || it.text.equals(text, true) }) return false
        // HARD anti-repeat, because the prompt-level "BANNED SUBJECTS" list is only ADVICE and the generator
        // ignores it often enough to be the bug the owner keeps seeing. Dedupe previously compared against
        // PENDING items and ANSWERED subjects only — so a question that was shown, then wiped by a refresh
        // without ever being answered, was free to come back word for word. askedLog is the actual record of
        // what has been put in front of the user; enforce against it here rather than hoping the model complies.
        val n = normQ(text)
        if (n.isNotEmpty() && askedLog(ctx).any { normQ(it) == n }) {
            Log.i(TAG, "repeat suppressed: ${text.take(70)}")
            return false
        }
        items.add(0, Question(System.currentTimeMillis() + items.size, kind, subject, text, options, freeform))
        rememberAsked(ctx, text)
        while (items.size > 8) items.removeAt(items.size - 1)
        persist(ctx)
        return true
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
    private val REFRESH_MS = 45 * 60_000L
    /** …or sooner, once this many new messages have landed in the brain. */
    private const val NEW_MSG_TRIGGER = 15
    private const val KEY_SHOWN = "shown_count"

    /** Rotate which pending question is surfaced, so opening Now twice doesn't show the same card twice. */
    fun nextToAsk(ctx: Context): Question? {
        if (items.isEmpty()) return null
        val p = prefs(ctx)
        val i = p.getInt(KEY_SHOWN, 0)
        p.edit().putInt(KEY_SHOWN, i + 1).apply()
        return items[i % items.size]
    }

    /** Force a fresh batch now, keeping the asked-history so banned subjects still apply (debug/testing). */
    fun forceRefresh(ctx: Context) {
        ensureLoaded(ctx)
        items.clear(); persist(ctx)
        prefs(ctx).edit().putLong(KEY_LAST, 0L).apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        ensureLoaded(ctx)
        // Whether this run is REPLACING an existing batch. The clear is deliberately deferred until new
        // questions actually exist (see below) — clearing up front meant one failed generation left the Now
        // card blank for the full 45-minute refresh interval.
        var clearOnRefill = false
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
            clearOnRefill = due && items.isNotEmpty()
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
            // ONE QUESTION PER AREA, FOUR DIFFERENT AREAS PER BATCH.
            // This used to pick a SINGLE focus per round and instruct that "every question MUST come from
            // this area". That guarantees the exact complaint it was meant to solve: within "work in flight"
            // everything the owner does is one venture, so a batch came back as four questions about Belto.
            // A later batch-level "spread the batch" rule in the prompt couldn't win against a hard,
            // specific instruction telling the model to stay in one area. So the SPREAD is structural now —
            // four areas are selected, each gets its own material, and each owes exactly one question. The
            // rotating offset means the fifth area leads the next round, so coverage still moves over time.
            val round = prefs(ctx).getInt("focus", 0)
            prefs(ctx).edit().putInt("focus", (round + 1) % 5).apply()
            val areaName = listOf(
                "PEOPLE & RELATIONSHIPS — who matters, who's unclear, who's gone quiet",
                "WORK IN FLIGHT — projects, deals, documents, what's stalled or undecided",
                "COMMITMENTS & TIME — calendar, deadlines, open tasks, what's slipping",
                "PREFERENCES & BOUNDARIES — how they want things done, what they'd never do",
                "CONTRADICTIONS & DRIFT — where stated intent and actual behaviour disagree")
            val picked = (0 until 4).map { (round + it) % 5 }

            fun material(area: Int): String = try {
                when (area) {
                    0 -> buildString {
                        val net = ConnectionStore.recent(ctx, 25).joinToString("\n") { c ->
                            "• ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") + (if (c.company.isNotBlank()) " @ ${c.company}" else "")
                        }.take(900)
                        if (net.isNotBlank()) append("NETWORK:\n").append(net).append("\n")
                        val quiet = ConnectionStore.staleConnections(ctx, 90).take(8)
                            .joinToString("\n") { (c, _) -> "• ${c.name} — no contact in 90+ days" }.take(500)
                        if (quiet.isNotBlank()) append("GONE QUIET:\n").append(quiet).append("\n")
                    }
                    1 -> buildString {
                        val docs = DocStore.list(ctx).sortedByDescending { it.ts }.take(10)
                            .joinToString("\n") { "• ${it.title} [${it.category}]" + (if (it.summary.isNotBlank()) " — ${it.summary.take(80)}" else "") }.take(900)
                        if (docs.isNotBlank()) append("DOCUMENTS:\n").append(docs).append("\n")
                        val papers = PaperStore.list(ctx).joinToString("\n") { "• “${it.title}” (${it.docType})" }.take(500)
                        if (papers.isNotBlank()) append("THEIR WRITING:\n").append(papers).append("\n")
                    }
                    2 -> buildString {
                        if (CalendarTool.hasPermission(ctx)) CalendarTool.upcoming(ctx).take(900)
                            .takeIf { it.isNotBlank() }?.let { append("UPCOMING CALENDAR:\n").append(it).append("\n") }
                        val tasks = ChecklistStore.load(ctx).filter { !it.done }.joinToString("\n") { "• ${it.text}" }.take(700)
                        if (tasks.isNotBlank()) append("OPEN TASKS:\n").append(tasks).append("\n")
                    }
                    else -> MessageStore.recentLines(ctx, 40).joinToString("\n").take(1400)
                        .let { if (it.isBlank()) "" else "RECENT ACTIVITY / CONVERSATIONS:\n$it\n" }
                }
            } catch (e: Exception) { "" }

            sb.append("\nASK EXACTLY ONE QUESTION FROM EACH AREA BELOW — four areas, four questions, drawn from " +
                "that area's own material. Two questions from the same area is a failed batch, and so is two " +
                "questions about the same project or person.\n")
            picked.forEachIndexed { i, area ->
                sb.append("\n=== AREA ${i + 1}: ").append(areaName[area]).append(" ===\n")
                val m = material(area)
                sb.append(if (m.isBlank()) "(little material here — ask about what's MISSING in this area)\n" else m)
            }

            if (sb.isBlank()) { Log.i(TAG, "nothing worth asking"); return }

            // WHAT'S ALREADY KNOWN — so it never asks something the brain already has (it was asking who the
            // owner's wife is, when the brain already knew). Digest + learned facts + profile.
            // ORDER IS LOad-BEARING. This whole block is truncated with take(...) on the way into the prompt,
            // and the anti-repeat rules used to sit at the BOTTOM — after the full brain digest and every
            // learned fact. On a brain this size they were being cut off before the model ever read them,
            // which is why "these subjects are CLOSED" had no effect and the same questions kept returning.
            // The exclusions go FIRST so they always survive; the digest fills whatever budget is left.
            val known = buildString {
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
                    // Sentence-INITIAL words get capitalised too, so a thin stop-list let "Where"/"Given"/"Since"
                    // through as "banned subjects" — noise that dilutes the real bans the model needs to obey.
                    }.filter { it !in setOf("What", "Is", "Are", "Do", "Does", "Did", "Done", "Would", "Should",
                        "Could", "Can", "Will", "Was", "Were", "Have", "Has", "Had", "Which", "How", "When",
                        "Who", "Whom", "Whose", "Where", "Why", "The", "Your", "You", "Yours", "That", "This",
                        "These", "Those", "There", "Their", "They", "And", "But", "For", "Not", "Any", "Some",
                        "If", "Since", "After", "Before", "Given", "Between", "About", "With", "From", "Into") }
                        .distinct().take(20)
                    if (banned.isNotEmpty())
                        append("\n\nBANNED SUBJECTS this round (already covered — do NOT mention or ask about ANY of " +
                            "these): ").append(banned.joinToString(", "))
                }
                // The brain itself comes AFTER the exclusions — this is the material the questions are drawn
                // FROM, and it's also the material the model must not re-ask about, so a partial view of it
                // costs far less than a truncated ban list.
                append("\n\nWHAT THE BRAIN ALREADY HOLDS:\n")
                // THE PROFILE WAS MISSING ENTIRELY. The generator is told "never ask what's already known or
                // inferable" and was then handed the digest and learned facts but NOT the owner's own profile —
                // where basics like who they're married to actually live. It duly asked whether the owner's
                // WIFE was "helping close Belto or supporting from outside the deal", and got told
                // "wtf no she's my wife not my coworker". Identity facts go in first; they're the cheapest
                // possible way to stop an embarrassing question.
                append(try { BrainContext.profileBlock(ctx) } catch (e: Exception) { "" }).append("\n")
                append(try { BrainDigest.getOrFull(ctx) } catch (e: Exception) { "" }).append("\n")
                append(try { MemoryStore.learnedFacts(ctx).joinToString("\n") } catch (e: Exception) { "" })
            }

            val qs = try { AgentClient.brainQuestions(known, sb.toString(), 4) } catch (e: Exception) { emptyList() }
            // Only NOW is it safe to drop the old batch: we have something to put in its place. A model
            // hiccup or a batch that turns out to be entirely repeats leaves the previous questions standing
            // instead of emptying the Now card.
            if (qs.isNotEmpty() && clearOnRefill) { items.clear(); persist(ctx) }
            var added = 0
            qs.forEach { (text, options, freeform) ->
                if (add(ctx, "open", "q-" + text.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(60),
                        text, options, freeform)) added++
            }
            // `added` vs `qs.size` is the diversity signal worth watching: a generator that keeps circling
            // produces a batch of 4 that yields 0 new questions, and that now shows up in logcat directly.
            Log.i(TAG, "areas=$picked generated ${qs.size} question(s), $added new after repeat-filter; pending: ${items.size}")
            // Print what was actually asked. "The questions keep circling the same topic" is impossible to
            // diagnose from counts alone, and this is the only place the batch exists as a whole.
            items.take(8).forEach { Log.i(TAG, "   Q: ${it.text}") }
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
