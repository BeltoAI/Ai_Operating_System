package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.PhotoIndex

/**
 * Quietly builds the on-device photo index in the background — labels + face/kind for a batch of un-analyzed
 * photos each run, entirely on the phone (ML Kit), so it costs NOTHING and scales to a whole gallery over a
 * day or two. This is what lets "find a full-body photo of me" work without captioning thousands via the API.
 */
class PhotoScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            // Bigger batches so a large gallery gets fully understood in days, not never.
            PhotoIndex.analyzeRecent(applicationContext, 200)
            PhotoIndex.analyzeVideosRecent(applicationContext, 40)
            // Keep draining the embedding queue every run (not just once at launch) so semantic memory actually
            // fills instead of sitting at 0 with tens of thousands queued.
            try { com.agentos.shell.tools.VectorStore.backfill(applicationContext, 500) } catch (e: Exception) {}
            // Daily brain-stats snapshot so we can track growth over time, not just a moment.
            try { com.agentos.shell.tools.StatsHistory.snapshotIfDue(applicationContext) } catch (e: Exception) {}
            // Nightly wake-up planner: at the user's chosen hour, suggest tomorrow's alarm.
            try { com.agentos.shell.tools.AlarmPlanner.tick(applicationContext) } catch (e: Exception) {}
            Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }
}
