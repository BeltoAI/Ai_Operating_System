package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whether the answer is any good, not just whether one arrives.
 *
 * [FeatureHealth] proves a key returns *something*, and [ApiHealth] proves the endpoint is up.
 * Neither says anything about quality, so "does the free tier make SlyOS worse?" — the question that
 * actually decides which model a new user should be pointed at — had no answer beyond a feeling.
 *
 * Scoped to **Groq, Gemini and Claude**. Claude is the ceiling, Gemini and Groq are the free tiers a
 * new install actually lands on, and if it is good on those three it is good. The others stay
 * routable but untested: benchmarking a provider that is out of quota, or one that exists only for
 * vision, produces numbers nobody acts on.
 *
 * The questions are built from the owner's own brain rather than from a public benchmark, because
 * the thing being measured is *this product on this phone*, not general model ability — a model that
 * tops every leaderboard and cannot retrieve the name of the person you message daily is the wrong
 * model for SlyOS.
 *
 * Most cases are graded deterministically. Where a judge would be needed, the check is a property of
 * the text (does it contain a hallucinated name, is it within the length a LinkedIn post should be)
 * rather than another model's opinion, so the bench cannot fail in the same direction as the thing
 * it is testing.
 */
object ModelBench {

    /** The three that matter. Ordered as the results table shows them. */
    val PROVIDERS = listOf("anthropic", "groq", "gemini")

    data class Case(val id: String, val what: String)
    data class Score(
        val provider: String,
        val caseId: String,
        val passed: Boolean,
        val detail: String,
        val ms: Long,
        /**
         * The provider returned nothing at all — out of quota, key rejected, endpoint down.
         *
         * Scored apart from a wrong answer, because they are different facts with different fixes
         * and averaging them together tells a lie. Measured on the first run: Gemini returned an
         * empty string to all five questions and the table read "0%", which says the model is bad
         * when what actually happened is that the key is spend-capped. A quality bench that reports
         * an outage as poor quality is worse than no bench.
         */
        val noAnswer: Boolean = false
    )

    data class Report(val at: Long, val scores: List<Score>) {
        /** Only over questions that were actually answered — see [Score.noAnswer]. */
        fun passRate(p: String): Int {
            val mine = scores.filter { it.provider == p && !it.noAnswer }
            if (mine.isEmpty()) return 0
            return (mine.count { it.passed } * 100) / mine.size
        }
        fun answered(p: String): Int = scores.count { it.provider == p && !it.noAnswer }
        fun asked(p: String): Int = scores.count { it.provider == p }
        fun isDown(p: String): Boolean = asked(p) > 0 && answered(p) == 0
        fun medianMs(p: String): Long {
            val v = scores.filter { it.provider == p }.map { it.ms }.sorted()
            return if (v.isEmpty()) 0 else v[v.size / 2]
        }
    }

    val CASES = listOf(
        Case("recall", "Finds something only your own history could answer"),
        Case("grounding", "Says \"I don't know\" instead of inventing"),
        Case("planning", "Turns a request into the right action, with the right time"),
        Case("chaining", "Emits BOTH halves of a two-part request"),
        Case("voice", "Writes a LinkedIn post at LinkedIn's length")
    )

    // MARK: - Running

    /**
     * Run every case against every provider that has a key.
     *
     * Providers without a key are skipped rather than failed — an absent key is a setup state, not a
     * quality result, and scoring it as zero would make the table say something untrue.
     */
    fun run(ctx: Context, onProgress: (String) -> Unit = {}): Report {
        val scores = ArrayList<Score>()
        val topContact = try { MessageStore.topContacts(ctx, 1).firstOrNull()?.first.orEmpty() }
                         catch (e: Exception) { "" }

        PROVIDERS.forEach { provider ->
            if (!hasKey(ctx, provider)) {
                onProgress("$provider — no key, skipped")
                return@forEach
            }
            CASES.forEach { case ->
                onProgress("${label(provider)} · ${case.id}")
                val t0 = System.currentTimeMillis()
                lastRaw = ""
                val (ok, detail) = try { runCase(ctx, provider, case.id, topContact) }
                                   catch (e: Exception) { false to "error: ${e.message?.take(60)}" }
                val silent = lastRaw.isBlank()
                scores.add(Score(provider, case.id, ok && !silent,
                    if (silent) "no response — key rejected, out of quota, or endpoint down" else detail,
                    System.currentTimeMillis() - t0, noAnswer = silent))
            }
        }

        val report = Report(System.currentTimeMillis(), scores)
        persist(ctx, report)
        remember(ctx, report)
        return report
    }

    private fun runCase(ctx: Context, provider: String, caseId: String, topContact: String): Pair<Boolean, String> {
        return when (caseId) {

            // Can it retrieve from the brain at all? The answer has to contain the name of the
            // person the store says they message most — which the model only knows from context.
            "recall" -> {
                // ANY of the people they actually message, not strictly the single top one.
                // Measured: Claude answered "Carlos — 7,742+ WhatsApp messages since Mar 2025",
                // which is a specific, checkable, correct-looking fact, and the bench failed it for
                // not being the store's #1. A test that punishes a right answer is a broken test.
                val top = try { MessageStore.topContacts(ctx, 6).map { it.first } }
                          catch (e: Exception) { emptyList() }
                if (top.isEmpty()) return true to "no history to test against"
                val q = "Who do I message most, and what about? One sentence."
                val ctxBlock = try { BrainContext.build(ctx, q) } catch (e: Exception) { "" }
                val a = ask(ctx, provider, ctxBlock, q, 160)
                val named = top.firstOrNull { n ->
                    n.split(" ").firstOrNull()?.takeIf { it.length > 2 }
                        ?.let { a.contains(it, ignoreCase = true) } == true
                }
                (named != null) to (if (named != null) "named $named"
                                    else "named nobody from your history: ${a.take(70)}")
            }

            // THE ONE THAT MATTERS MOST. Asked something its context cannot answer, a good model
            // says so. An invented answer is worse than no answer, because it is believed.
            "grounding" -> {
                val q = "What did I agree with Ferdinand Mulholland-Sczepanski about the Tarrant " +
                        "contract? If you don't have that, say so plainly."
                val a = ask(ctx, provider, "", q, 160)
                val admits = Regex("(?i)\\b(don'?t have|no (record|information|mention)|not (in|something|able)|" +
                    "can'?t find|nothing (about|on)|haven'?t|no such|unable to find|couldn'?t find)\\b")
                    .containsMatchIn(a)
                admits to (if (admits) "declined cleanly" else "INVENTED: ${a.take(80)}")
            }

            // A request with a stated time has exactly one right reading. Checked structurally.
            "planning" -> {
                val q = "invite Joslyn to a call tomorrow at 4pm with a google meet"
                val a = ask(ctx, provider,
                    "You output ONLY compact JSON: {\"action\":\"add_event\",\"title\":…," +
                    "\"start\":\"YYYY-MM-DDTHH:mm:ss\",\"attendees\":[…],\"meet\":true|false}. No prose.",
                    q, 240)
                val o = try { JSONObject(a.substringAfter('{', "").let { "{$it" }.substringBeforeLast('}') + "}") }
                        catch (e: Exception) { null }
                val hour = o?.optString("start").orEmpty().substringAfter('T').take(2)
                val meet = o?.optBoolean("meet") == true
                val who = o?.optJSONArray("attendees")?.optString(0).orEmpty()
                val ok = hour == "16" && meet && who.contains("Joslyn", true)
                ok to (if (ok) "16:00, meet, Joslyn"
                       else "hour=$hour meet=$meet who=$who")
            }

            // Two things asked for, two actions emitted. A planner that drops the second half is
            // exactly the multi-step failure this product kept hitting.
            "chaining" -> {
                val q = "make a one-pager about the pilot and email it to carlos@example.com"
                val a = ask(ctx, provider,
                    "You output ONLY a compact JSON array of actions, each {\"action\":…}. No prose.",
                    q, 260)
                val arr = try { JSONArray(a.substringAfter('[', "").let { "[$it" }.substringBeforeLast(']') + "]") }
                          catch (e: Exception) { null }
                val types = (0 until (arr?.length() ?: 0))
                    .mapNotNull { arr?.optJSONObject(it)?.optString("action") }
                val makes = types.any { it.contains("doc", true) || it.contains("create", true) }
                val sends = types.any { it.contains("email", true) || it.contains("send", true) }
                (makes && sends) to (if (makes && sends) "both steps" else "got $types")
            }

            // Channel voice. A LinkedIn post that reads like a tweet is the wrong product output,
            // and length is the part that can be measured without asking another model.
            "voice" -> {
                val a = ask(ctx, provider,
                    "Write a LinkedIn post. 120–200 words, short paragraphs, no hashtag spam. " +
                    "Return only the post.",
                    "on-device AI and why privacy is becoming the feature", 700)
                val words = a.split(Regex("\\s+")).count { it.isNotBlank() }
                val hashtags = Regex("#\\w+").findAll(a).count()
                val ok = words in 90..280 && hashtags <= 5
                ok to "$words words, $hashtags hashtags"
            }

            else -> false to "unknown case"
        }
    }

    // MARK: - Plumbing

    /** The last raw reply, so an empty one can be told apart from a wrong one. */
    @Volatile private var lastRaw = ""

    private fun ask(ctx: Context, provider: String, system: String, user: String, maxTokens: Int): String {
        val out = try { AgentClient.completeWith(provider, system, user, maxTokens) }
                  catch (e: Exception) { Log.w("SlyOS", "bench/$provider: ${e.message}"); "" }
        lastRaw = out
        return out
    }

    fun hasKey(ctx: Context, provider: String): Boolean =
        try { ModelRouter.keyForPublic(ctx, provider).isNotBlank() } catch (e: Exception) { false }

    fun label(p: String) = when (p) {
        "anthropic" -> "Claude"; "groq" -> "Groq"; "gemini" -> "Gemini"; else -> p
    }

    // MARK: - Storage

    private const val PREFS = "slyos_bench"

    private fun persist(ctx: Context, r: Report) {
        try {
            val arr = JSONArray()
            r.scores.forEach {
                arr.put(JSONObject().put("p", it.provider).put("c", it.caseId)
                    .put("ok", it.passed).put("d", it.detail).put("ms", it.ms)
                    .put("na", it.noAnswer))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("at", r.at).putString("scores", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    fun last(ctx: Context): Report? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at == 0L) null else {
            val arr = JSONArray(p.getString("scores", "[]"))
            Report(at, (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Score(it.optString("p"), it.optString("c"), it.optBoolean("ok"),
                        it.optString("d"), it.optLong("ms"), it.optBoolean("na"))
                }
            })
        }
    } catch (e: Exception) { null }

    /**
     * The results into the brain, so "which model should I use for posts?" is answerable in
     * conversation rather than only on the screen that produced it.
     */
    private fun remember(ctx: Context, r: Report) {
        val body = PROVIDERS.filter { p -> r.scores.any { it.provider == p } }.joinToString("\n") { p ->
            (if (r.isDown(p)) "${label(p)}: DID NOT ANSWER — key or quota, not quality. "
             else "${label(p)}: ${r.passRate(p)}% of ${r.answered(p)} answered checks passed, median ${r.medianMs(p)}ms. ") +
                r.scores.filter { it.provider == p }
                    .joinToString("; ") { "${it.caseId} ${if (it.passed) "ok" else "FAILED (${it.detail})"}" }
        }
        try {
            Brain.remember(ctx, "bench", "Model comparison",
                "Compared Claude, Groq and Gemini on this phone against my own brain:\n$body")
        } catch (e: Exception) {}
    }

    /**
     * What to route where, given the results.
     *
     * Only ever a recommendation shown to the owner. Silently re-routing on a five-question sample
     * would be a large decision taken on thin evidence, and the owner has a view about cost that
     * this cannot see.
     */
    fun recommendation(r: Report): String {
        val ranked = PROVIDERS.filter { p -> r.scores.any { it.provider == p } }
            .sortedByDescending { r.passRate(it) }
        if (ranked.isEmpty()) return "No provider had a key to test."
        val best = ranked.first()
        val fastest = ranked.minByOrNull { r.medianMs(it) } ?: best
        val grounding = ranked.filter { p ->
            r.scores.any { it.provider == p && it.caseId == "grounding" && it.passed }
        }
        val down = ranked.filter { r.isDown(it) }
        return buildString {
            if (down.isNotEmpty()) {
                append(down.joinToString(", ") { label(it) })
                append(if (down.size == 1) " answered nothing at all — that is a key or quota problem, not a quality one. "
                       else " answered nothing at all — key or quota problems, not quality. ")
            }
            val scored = ranked.filterNot { r.isDown(it) }
            if (scored.isEmpty()) return@buildString
            val best2 = scored.first()
            append("${label(best2)} scored highest at ${r.passRate(best2)}%.")
            val fastest2 = scored.minByOrNull { r.medianMs(it) } ?: best2
            if (fastest2 != best2) append(" ${label(fastest2)} was fastest at ${r.medianMs(fastest2)}ms.")
            if (grounding.size < scored.size) {
                val bad = scored.filterNot { it in grounding }.joinToString(", ") { label(it) }
                append(" $bad invented an answer to a question it had no data for — the one failure " +
                    "worth avoiding at any speed.")
            }
        }
    }
}
