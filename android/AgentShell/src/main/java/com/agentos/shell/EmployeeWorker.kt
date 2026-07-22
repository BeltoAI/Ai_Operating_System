package com.agentos.shell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.EmployeeRunner
import com.agentos.shell.tools.EmployeeStore

/**
 * The heartbeat that makes the team ACTUALLY 24/7. Fires every ~15 min; for each employee whose
 * interval has elapsed since its last run, it runs a real shift on its own — no tapping "Run".
 * Employees with intervalMin == 0 stay on-demand only. Self-throttling + serialized inside runShift.
 */
class EmployeeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "EmployeeWorker")
        val ctx = applicationContext
        // Self-heal the Telegram poller: if Android froze/killed the foreground service in the background, revive
        // it every worker cycle so incoming @mentions get answered instead of silently dropped.
        try {
            if ((com.agentos.shell.tools.MemoryStore.telegramBot(ctx) || com.agentos.shell.tools.TeamChat.enabled(ctx)) &&
                com.agentos.shell.tools.TelegramClient.configured())
                TelegramService.start(ctx)
        } catch (e: Exception) {}
        return try {
            // Spam-safe outreach drip: send at most ONE queued email per cadence window, independent of API keys.
            try {
                com.agentos.shell.tools.OutreachQueue.drainOne(ctx)?.let { line ->
                    com.agentos.shell.tools.MemoryLog.add(ctx, "note", "Outreach", line, "Outreach")
                    try { com.agentos.shell.tools.TeamChat.post(ctx, "Outreach", line) } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
            if (!com.agentos.shell.tools.AgentClient.hasKey()) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "EmployeeWorker", true).let { Result.success() }   // nothing to spend, skip
            val now = System.currentTimeMillis()
            val due = EmployeeStore.all(ctx).filter { e ->
                e.intervalMin > 0 && (now - e.lastRun) >= e.intervalMin * 60_000L
            }
            // Run at most a few per cycle so a big team doesn't hammer the API in one wake-up.
            due.sortedBy { it.lastRun }.take(3).forEach { e ->
                try { EmployeeRunner.runShift(ctx, e) } catch (ex: Exception) {}
            }
            com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "EmployeeWorker", true, "ran ${due.size} due").let { Result.success() }
        } catch (e: Exception) {
            com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "EmployeeWorker", false, e.message ?: "error").let { Result.retry() }
        }
    }
}
