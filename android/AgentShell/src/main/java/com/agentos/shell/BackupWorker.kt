package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.BrainBackup
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore

/**
 * Periodic + on-demand brain backup. Snapshots the whole brain and pushes it to Google Drive (and a
 * local Downloads copy) so the memory is never one uninstall/wipe away from gone. No-op when the user
 * turned auto-backup off. Self-throttles so a burst of triggers can't hammer Drive.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "BackupWorker")
        val ctx = applicationContext
        if (!BrainBackup.autoEnabled(ctx)) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "BackupWorker", true).let { Result.success() }
        // Debounce: skip if we backed up within the last ~90 minutes (manual "Back up now" bypasses this).
        val force = inputData.getBoolean("force", false)
        if (!force && System.currentTimeMillis() - BrainBackup.lastBackup(ctx) < 90 * 60_000L) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "BackupWorker", true).let { Result.success() }
        // SAFETY: never let an automatic run overwrite a good Drive backup with an essentially empty brain
        // (e.g. right after a reinstall, before the user has restored). Manual "Back up now" bypasses this.
        if (!force) {
            val empty = try { MessageStore.count(ctx) == 0 && MemoryStore.about(ctx).isBlank() } catch (e: Exception) { false }
            if (empty) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "BackupWorker", true).let { Result.success() }
        }
        return try { BrainBackup.backupNow(ctx); Result.success() } catch (e: Exception) { Result.retry() }
    }
}
