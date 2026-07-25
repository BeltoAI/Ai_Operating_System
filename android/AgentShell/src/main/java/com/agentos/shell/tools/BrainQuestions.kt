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

    /** @param kind ambiguity|relationship|gap  @param subject the thing being asked about (dedupe key) */
    data class Question(val id: Long, val kind: String, val subject: String, val text: String,
                        val options: List<String> = emptyList())

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
                items.add(Question(o.optLong("id"), o.optString("kind"), o.optString("subject"), o.optString("text"), opts))
            }
        } catch (e: Exception) {}
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach { q ->
            val o = JSONObject().put("id", q.id).put("kind", q.kind).put("subject", q.subject).put("text", q.text)
            val a = JSONArray(); q.options.forEach { a.put(it) }
            arr.put(o.put("options", a))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun answeredKeys(ctx: Context): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(KEY_ANSWERED, emptySet()) ?: emptySet())

    private fun markAnswered(ctx: Context, subject: String) {
        val s = answeredKeys(ctx); s.add(subject.lowercase())
        prefs(ctx).edit().putStringSet(KEY_ANSWERED, s).apply()
    }

    private fun add(ctx: Context, kind: String, subject: String, text: String, options: List<String> = emptyList()) {
        if (subject.lowercase() in answeredKeys(ctx)) return
        if (items.any { it.subject.equals(subject, true) }) return
        items.add(0, Question(System.currentTimeMillis() + items.size, kind, subject, text, options))
        while (items.size > 8) items.removeAt(items.size - 1)
        persist(ctx)
    }

    /**
     * Look for things genuinely worth asking. Cheap + local (no LLM): reads the message store, the resolver and
     * the per-channel personas. Safe to call periodically from a background worker.
     */
    fun refresh(ctx: Context) {
        ensureLoaded(ctx)
        try {
            // 1) AMBIGUOUS FIRST NAMES among people you actually message.
            val top = MessageStore.topContacts(ctx, 60)
            val byFirst = top.filter { it.first.isNotBlank() && it.second >= 3 }
                .groupBy { it.first.trim().split(" ").first().lowercase() }
            byFirst.forEach { (first, people) ->
                val distinct = people.map { it.first }.distinct()
                if (first.length > 2 && distinct.size > 1) {
                    add(ctx, "ambiguity", "who-is-$first",
                        "When someone just says “${first.replaceFirstChar { it.uppercase() }}”, who do they usually mean?",
                        distinct.take(4))
                }
            }
            // 2) WHO IS THIS TO YOU — for people you talk to a lot but have no stated relationship for.
            val known = try { MemoryStore.learnedFacts(ctx).joinToString(" ").lowercase() } catch (e: Exception) { "" }
            top.take(8).forEach { (name, count, _) ->
                if (name.isNotBlank() && count >= 15 && !known.contains(name.lowercase()) && !name.contains("@")) {
                    add(ctx, "relationship", "relationship-$name",
                        "You talk to $name a lot ($count messages). Who are they to you?",
                        listOf("Co-founder", "Colleague", "Advisor / investor", "Friend / family"))
                }
            }
            // 3) CHANNELS WITH NO CHARACTER — drafts there have no voice.
            listOf("LinkedIn" to "linkedin", "Instagram" to "instagram", "WhatsApp" to "whatsapp",
                   "Telegram" to "telegram", "Slack" to "slack", "SMS" to "sms", "Email" to "email").forEach { (label, key) ->
                val used = top.any { it.third.equals(label, true) }
                if (used && MemoryStore.styleFor(ctx, key).isBlank())
                    add(ctx, "gap", "voice-$key",
                        "How should you come across on $label? I have no character set for it, so replies there have no voice.")
            }
            Log.i(TAG, "questions pending: ${items.size}")
        } catch (t: Throwable) { Log.w(TAG, "refresh: ${t.message}") }
    }

    /**
     * The owner answered. Persist it as a DURABLE fact (learned facts + searchable brain) so every future
     * reply and every agent uses it, and never ask this again.
     */
    fun answer(ctx: Context, q: Question, answer: String) {
        val fact = when (q.kind) {
            "ambiguity" -> "When I say “${q.subject.removePrefix("who-is-").replaceFirstChar { it.uppercase() }}” I mean $answer."
            "relationship" -> "${q.subject.removePrefix("relationship-")} is my ${answer.lowercase()}."
            else -> answer
        }
        try { MemoryStore.addLearnedFact(ctx, fact) } catch (e: Exception) {}
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

    fun dismiss(ctx: Context, q: Question) {
        markAnswered(ctx, q.subject)
        items.removeAll { it.id == q.id }
        persist(ctx)
    }
}
