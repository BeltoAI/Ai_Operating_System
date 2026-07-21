package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.GmailClient
import com.agentos.shell.tools.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps the brain fresh: pulls new inbox mail (incrementally, deduped) in the background so you
 *  don't have to reopen the app for recent emails to be searchable. No-op if Google isn't connected. */
class GmailSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "GmailSyncWorker")
        val ctx = applicationContext
        if (!GoogleAuth.isConnected(ctx)) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "GmailSyncWorker", true).let { Result.success() }
        return try {
            withContext(Dispatchers.IO) {
                GmailClient.syncToBrain(ctx)      // subjects + bodies + PDF text → brain
                GmailClient.syncReceipts(ctx)     // receipts / orders / invoices → Expenses (+ brain)
                GmailClient.syncDocs(ctx)         // invoices / forms / contracts / attachments → Documents (+ brain)
            }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}
