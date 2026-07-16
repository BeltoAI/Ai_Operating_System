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
            Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }
}
