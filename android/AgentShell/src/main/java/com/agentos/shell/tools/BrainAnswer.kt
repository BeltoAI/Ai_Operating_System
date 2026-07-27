package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The Memory tab's answer pipeline, lifted out of the composable that used to own it.
 *
 * WHY IT MOVED: this logic decided what the brain "knows", and it lived inside `MemoryGraphScreen.ask()`
 * — reachable only by a human typing into a text field. Every diagnosis of "the memory tab knows nothing"
 * was therefore guesswork, and several confident fixes in a row missed because nobody could see the corpus
 * the model was actually handed. Here it can be run from adb (`-e mode ask -e q "…"`), printed, and judged.
 *
 * The screen keeps the UI concerns (vault gate, search history, synapse highlighting); this owns retrieval.
 */
object BrainAnswer {
    private const val TAG = "SlyOS-Audit"
    /** Matches Home AI's memory budget — the tab was answering the same questions from 30% less evidence. */
    private const val BUDGET = 20000

    /** What the model will be shown, in priority order. Separated from [answer] so it can be inspected. */
    suspend fun corpus(ctx: Context, query: String): List<String> = coroutineScope {
        val q = query
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        val taskQuery = Regex("task|to-?do|checklist|errand|chore|remind|due|what.*do|need.*do|outstanding|pending",
            RegexOption.IGNORE_CASE).containsMatchIn(query)
        val schedQ = Regex("free|busy|schedule|calendar|meeting|available|blocked|book|when am i", RegexOption.IGNORE_CASE).containsMatchIn(query)
        val paperQuery = Regex("paper|whitepaper|white ?paper|research|document|wrote|writ|publish|essay|report|zenodo|doi",
            RegexOption.IGNORE_CASE).containsMatchIn(query)

        val dRecall = async(Dispatchers.IO) { if (MemoryStore.recallEnabled(ctx))
            InteractionStore.search(ctx, q, 40).map { "Seen in ${it.app}: ${it.text}" } else emptyList() }
        val dConns = async(Dispatchers.IO) { ConnectionStore.search(ctx, q, 60)
            .map { "Connection: ${it.name}" + (if (it.role.isNotBlank()) " — ${it.role}" else "") + (if (it.company.isNotBlank()) " at ${it.company}" else "") } }
        val dDb = async(Dispatchers.IO) { MessageStore.search(ctx, q, 70)
            .map { (if (it.role == "me") "You to ${it.contact}" else it.contact) + ": " + it.body } }
        val dSem = async(Dispatchers.IO) { try {
            VectorStore.search(ctx, query, 30).map { (if (it.role == "me") "You to ${it.contact}" else it.contact) + ": " + it.body }
        } catch (e: Exception) { emptyList() } }
        // WHO the question is about, as a relationship rather than a sample of messages. Ranked retrieval
        // reaches the right person's threads but hands the model small talk; these lines carry the thing
        // small talk can't ("5,879 messages on Instagram, Dec 2021 → Jul 2026, 2,103 sent by you").
        val dWho = async(Dispatchers.IO) {
            terms.filter { it.length > 2 }.take(4).flatMap {
                try { MessageStore.personDossier(ctx, it) } catch (e: Exception) { emptyList() }
            }.distinct()
        }
        // "What am I working on right now" reduced to the keyword terms working/right/now and retrieved
        // noise, because a question about the OWNER isn't answered by keyword-matching their messages —
        // it's answered by the brain's own summary plus what has actually been happening. Measured: that
        // query produced a one-line answer about "Bylaws" off 37 lines of keyword hits.
        val broadQ = Regex("(?i)\\b(working on|work on|projects?|priorit|focus(ing)?|going on|status|busy with|" +
            "building|launch|this week|lately|recently|right now|currently|my (day|week|life|goals?|plans?))\\b")
            .containsMatchIn(query)
        val dDigest = async(Dispatchers.IO) { try {
            BrainDigest.getOrFull(ctx).split("\n").map { it.trim() }
                .filter { it.length > 15 }.map { "Brain summary: $it" }
        } catch (e: Exception) { emptyList() } }
        val dRecent = async(Dispatchers.IO) { if (broadQ) try {
            MessageStore.recentLines(ctx, 70).map { "Recent activity: $it" }
        } catch (e: Exception) { emptyList() } else emptyList() }
        val dPaperHits = async(Dispatchers.IO) { PaperStore.libraryContext(ctx, 0L, q, 3000)
            .split("\n\n").map { it.trim() }.filter { it.isNotBlank() } }
        val dDoc = async(Dispatchers.IO) { if (KnowledgeStore.hasDoc(ctx))
            KnowledgeStore.retrieve(ctx, q, 2500).split("\n").map { it.trim() }.filter { it.length > 20 } else emptyList() }
        val dTasks = async(Dispatchers.IO) { ChecklistStore.load(ctx)
            .filter { taskQuery || terms.any { t -> it.text.lowercase().contains(t) } }
            .map { "Checklist task: ${it.text} — ${if (it.done) "done" else "to do"}" } }
        val dCal = async(Dispatchers.IO) { if (schedQ) CalendarTool.upcoming(ctx)
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }.map { "Schedule: $it" } else emptyList() }
        val dPaperTitles = async(Dispatchers.IO) { if (paperQuery) PaperStore.list(ctx)
            .map { "Your paper: “${it.title}” (${it.docType})" } else emptyList() }
        val dProfile = async(Dispatchers.IO) { BrainContext.profileBlock(ctx)
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }.map { "About you: $it" } }

        val recall = dRecall.await(); val conns = dConns.await(); val dbHits = dDb.await(); val semHits = dSem.await()
        val who = dWho.await(); val digest = dDigest.await(); val recent = dRecent.await()
        val paperHits = dPaperHits.await(); val docHits = dDoc.await(); val taskLines = dTasks.await()
        val calLines = dCal.await(); val paperTitles = dPaperTitles.await(); val profile = dProfile.await()

        val extra = MemoryGraphStore.memoryLines() + recall
        val rankedExtra = if (terms.isEmpty()) extra.takeLast(40)
            else extra.map { it to terms.count { t -> it.lowercase().contains(t) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(60).map { it.first }

        // ACTUAL MESSAGES COME FIRST, and the relationship summary before them — `conns` is up to 60
        // "Connection: <name>" lines from a 20,000-strong LinkedIn network and used to exhaust the budget
        // before a single real message was included. Semantic hits sit behind real messages only while the
        // index is partially built; restore them higher once the re-embed completes.
        val net = conns.take(12)
        val ordered = who + digest.take(if (broadQ) 40 else 12) + profile.take(20) + recent + when {
            paperQuery -> paperTitles + paperHits + dbHits + semHits + docHits + net + taskLines + rankedExtra
            schedQ     -> calLines + taskLines + dbHits + semHits + net + paperHits + docHits + rankedExtra
            taskQuery  -> taskLines + dbHits + semHits + net + paperHits + docHits + rankedExtra
            else       -> dbHits + semHits + net + paperHits + docHits + taskLines + rankedExtra
        }
        // TRUNCATE, DON'T DROP. SKIP, DON'T STOP.
        // This loop used to `break` on the first line that didn't fit. One oversized profile blob therefore
        // ended the corpus at 3,416 of 20,000 characters and silently discarded all 70 retrieved messages
        // sitting behind it — retrieval had been working fine for who knows how long while the model was
        // handed almost nothing. Measured, not guessed: the probe reported `db=70 … corpus=24 lines`.
        val out = ArrayList<String>(); var chars = 0
        for (raw in ordered) {
            if (chars >= BUDGET) break
            // No single line may eat the budget; a 20k-char blob becomes a usable 1.2k excerpt instead.
            val l = if (raw.length > 1200) raw.take(1200) + "…" else raw
            if (chars + l.length > BUDGET) continue
            out.add(l); chars += l.length
        }
        Log.i("SlyOS-Perf", "memory corpus=${out.size} lines (who=${who.size} db=${dbHits.size} sem=${semHits.size} net=${conns.size}) for \"${query.take(40)}\"")
        out
    }

    /**
     * MUST hop to IO for the model call. The Memory tab calls this from `scope.launch {}`, which is the MAIN
     * dispatcher — a network call there throws NetworkOnMainThreadException, which surfaced to the user as
     * "Couldn't search memory (-1)". `corpus()` was safe because its work is inside async(Dispatchers.IO);
     * the model call was not, and the adb probe hid it by running on a background thread already.
     */
    suspend fun answer(ctx: Context, query: String): String {
        val c = corpus(ctx, query)
        if (c.isEmpty()) return "I don't have anything on that yet."
        return withContext(Dispatchers.IO) { AgentClient.askMemory(query, c) }
    }

    /** adb probe: print the corpus the model is handed AND the answer it produces, for one query. */
    suspend fun probe(ctx: Context, query: String) {
        Log.i(TAG, "══════ BRAIN ANSWER: \"$query\" ══════")
        val c = corpus(ctx, query)
        Log.i(TAG, "corpus: ${c.size} lines, ${c.sumOf { it.length }} chars")
        c.take(14).forEach { Log.i(TAG, "   | " + it.replace("\n", " ").take(150)) }
        if (c.size > 14) Log.i(TAG, "   | …${c.size - 14} more")
        val a = if (c.isEmpty()) "I don't have anything on that yet." else withContext(Dispatchers.IO) { AgentClient.askMemory(query, c) }
        Log.i(TAG, "──── ANSWER ────")
        a.split("\n").forEach { Log.i(TAG, "   $it") }
        Log.i(TAG, "══════ END BRAIN ANSWER ══════")
    }
}
