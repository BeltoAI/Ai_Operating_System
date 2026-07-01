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
        try { com.agentos.shell.tools.VectorStore.backfill(applicationContext, 300) } catch (e: Exception) {}
        return Result.success()
    }
}
