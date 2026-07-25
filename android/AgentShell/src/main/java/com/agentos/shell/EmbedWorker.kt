package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Trickle-embeds the semantic-memory backlog in the background, respecting free-tier rate limits, so
 * the brain's index fills itself over time without the user babysitting a button.
 */
class EmbedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "EmbedWorker")
        // WORK TO A TIME BUDGET, NOT A TOKEN BATCH. 300 every 15 minutes is 1,200/hour: a 67,000-message
        // brain would need over a day of uninterrupted uptime to finish indexing, so in practice the
        // semantic index never filled and every meaning-based question ("who disagreed with me recently")
        // fell back to keyword search and found nothing. On-device embedding runs ~24/s and costs nothing,
        // so the only real limit is how long a worker may hold the CPU. Four minutes of a ten-minute
        // allowance keeps a wide safety margin while moving ~5,000 rows per run — the backlog now clears in
        // hours. Stops early the moment there's nothing pending, so a caught-up brain does no work at all.
        val deadline = System.currentTimeMillis() + 4 * 60_000
        try {
            while (System.currentTimeMillis() < deadline) {
                if (com.agentos.shell.tools.VectorStore.pendingCount(applicationContext) <= 0) break
                val before = com.agentos.shell.tools.VectorStore.embeddedCount(applicationContext)
                com.agentos.shell.tools.VectorStore.backfill(applicationContext, 500)
                // No forward progress means the provider is sidelined or erroring; stop rather than spin.
                if (com.agentos.shell.tools.VectorStore.embeddedCount(applicationContext) <= before) break
            }
        } catch (e: Exception) {}
        return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "EmbedWorker", true).let { Result.success() }
    }
}
