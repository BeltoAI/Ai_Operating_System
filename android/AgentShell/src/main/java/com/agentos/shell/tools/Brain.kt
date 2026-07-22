package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * THE INGESTION CHOKE POINT.
 *
 * Principle: the brain IS the user's digital self, so EVERYTHING the phone does must grow it, uniformly.
 * Before this, feeding was scattered manual calls (MessageStore.insertOne / MemoryLog.add / VectorStore.enqueue)
 * sprinkled across ~50 features — present wherever a dev remembered, absent (or summary-only) everywhere else.
 * That made "self-feeding" a best-effort, not a guarantee, and left whole verticals (jobs, trades, missions,
 * team, outreach, cowork) reachable only as a regex-gated one-liner.
 *
 * [remember] is the ONE path every feature, store and worker routes through. It writes at a single fidelity:
 *  - MessageStore  → keyword-searchable AND (via its auto-enqueue) semantically indexed in VectorStore
 *  - MemoryLog     → the timeline/graph moment
 * so nothing can be built that feeds the brain at a weaker fidelity than anything else, and completeness is
 * auditable in ONE place (FeatureHealth asserts every feature writes a memory).
 */
object Brain {
    private const val TAG = "SlyOS-Brain"

    /** NORMAL memories can inform any answer (incl. cloud model calls, via assembly). SENSITIVE memories are
     *  stored on-device but flagged so the assembly layer can keep them out of cloud prompts (privacy tier). */
    enum class Sensitivity { NORMAL, SENSITIVE }

    /** Kinds are free-form but keep them stable — they double as the "platform" facet and the graph node type. */
    const val MESSAGE = "message"; const val ACTION = "action"; const val EXPENSE = "Expenses"
    const val JOB = "Job"; const val TRADE = "Trading"; const val MISSION = "Mission"
    const val DOC = "Document"; const val PHOTO = "Photo"; const val SCREEN = "Screen"
    const val NOTE = "Note"; const val ASSISTANT = "SlyOS"; const val TEAM = "Team"
    const val OUTREACH = "Outreach"; const val BUILD = "Build"; const val EDIT = "edit_pair"

    /**
     * Record one thing the phone did / learned so it grows the brain and can be recalled forever.
     *
     * @param kind     the facet/type ([JOB], [TRADE], "message"…). Also the MessageStore "platform".
     * @param subject  who/what it concerns (contact, merchant, title, symbol) — the memory's handle.
     * @param body     the actual human-readable content (this is what search + recall match on).
     * @param actors   people involved (first one is stored as the sender).
     * @param role     "me" (the user's own words — highest-value), "them", or "system".
     * @param ts       when it happened (defaults to now).
     * @param sensitivity NORMAL by default; SENSITIVE flags it for the on-device-only privacy tier.
     */
    fun remember(
        ctx: Context,
        kind: String,
        subject: String,
        body: String,
        actors: List<String> = emptyList(),
        role: String = "system",
        ts: Long = System.currentTimeMillis(),
        sensitivity: Sensitivity = Sensitivity.NORMAL
    ) {
        val text = body.trim()
        if (text.isEmpty()) return
        val subj = subject.trim().ifBlank { kind }.take(80)
        val sender = actors.firstOrNull()?.takeIf { it.isNotBlank() } ?: subj
        // 1) Searchable + semantic. insertBatch auto-enqueues each row into VectorStore, so ONE call gives
        //    both keyword (FTS) and meaning (cosine) recall — the uniform fidelity guarantee.
        try {
            MessageStore.insertBatch(ctx, listOf(
                MessageStore.Row(subj, kind, sender, role, text, ts)))
        } catch (e: Exception) { Log.w(TAG, "remember/msg: ${e.message}") }
        // 2) The timeline/graph moment (bounded log; also carried in every brain backup).
        try { MemoryLog.add(ctx, kind, subj, text.take(400), if (role == "me") "you" else "SlyOS") }
        catch (e: Exception) { Log.w(TAG, "remember/log: ${e.message}") }
        // [sensitivity] is accepted now so call-sites can classify at the source; the assembly-side cloud
        // filter that acts on it (keeping SENSITIVE memories out of cloud prompts) lands in the privacy-tier
        // phase. Storage is always on-device SQLite, so nothing is exposed by storing it uniformly today.
    }

    /**
     * The highest-value signal in the whole system: the delta between what the AI drafted AS the user and
     * what the user actually sent after editing it. Captured on every reviewed send so (a) recent corrections
     * feed back into the voice layer as live exemplars, and (b) it accumulates as a preference dataset
     * (rejected = draft, chosen = sent) for an eventual personal model. Only stored when the user CHANGED it.
     */
    fun rememberEdit(ctx: Context, channel: String, recipient: String, aiDraft: String, sentText: String) {
        val d = aiDraft.trim(); val s = sentText.trim()
        if (s.isEmpty()) return
        if (d.isEmpty() || d == s) return   // no correction to learn from
        try { EditPairStore.add(ctx, channel, recipient, d, s) } catch (e: Exception) { Log.w(TAG, "edit: ${e.message}") }
        // NOTE: we deliberately do NOT re-store the sent text as a 'me' message here — the send path itself
        // already writes it to the brain (NotificationStore.sendReply / GmailClient.send), so storing it again
        // would duplicate it. This captures ONLY the correction signal (the draft→sent pair).
    }
}
