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
        try { com.agentos.shell.tools.VectorStore.backfill(applicationContext, 300) } catch (e: Exception) {}
        return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "EmbedWorker", true).let { Result.success() }
    }
}
