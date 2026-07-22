package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore

/**
 * P5.4 — nightly memory consolidation. Instead of letting recall get noisier forever, once a day this
 * distills a batch of recent raw messages into a few DURABLE facts ("Anna = designer, prefers mornings")
 * and writes them via the durable brain path (which de-dupes). Cheap on the free tier (one small call).
 * Every downstream reply gets a little sharper.
 */
class MemoryConsolidationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "MemoryConsolidationWorker")
        return try {
            val ctx = applicationContext
            if (!AgentClient.hasKey()) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "MemoryConsolidationWorker", true).let { Result.success() }
            // A compact digest of recent conversation, newest first — bounded so it's a single cheap call.
            val digest = MessageStore.recentLines(ctx, 120).joinToString("\n")
            if (digest.length < 40) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "MemoryConsolidationWorker", true).let { Result.success() }
            val facts = AgentClient.distillFacts(digest)
            // addLearnedFact de-dupes AND dual-writes to the unbounded brain DB (P1.5), so nothing is lost.
            facts.forEach { MemoryStore.addLearnedFact(ctx, it) }
            // ALSO distill the user's OWN positions/opinions/boundaries from THEIR OWN messages — this is what
            // lets the agent act AS them (take a stance, accept/decline), not just recall facts about others.
            val ownMsgs = MessageStore.myRecentBodies(ctx, 120).joinToString("\n")
            if (ownMsgs.length >= 40) AgentClient.distillSelf(ownMsgs).forEach { MemoryStore.addLearnedFact(ctx, it) }
            com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "MemoryConsolidationWorker", true, "distilled").let { Result.success() }
        } catch (e: Exception) {
            com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "MemoryConsolidationWorker", false, e.message ?: "error").let { Result.success() }
        }   // never crash the scheduler
    }
}
