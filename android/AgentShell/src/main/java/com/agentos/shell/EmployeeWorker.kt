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
        val ctx = applicationContext
        return try {
            // Spam-safe outreach drip: send at most ONE queued email per cadence window, independent of API keys.
            try {
                com.agentos.shell.tools.OutreachQueue.drainOne(ctx)?.let { line ->
                    com.agentos.shell.tools.MemoryLog.add(ctx, "note", "Outreach", line, "Outreach")
                    try { com.agentos.shell.tools.TeamChat.post(ctx, "Outreach", line) } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
            if (!com.agentos.shell.tools.AgentClient.hasKey()) return Result.success()   // nothing to spend, skip
            val now = System.currentTimeMillis()
            val due = EmployeeStore.all(ctx).filter { e ->
                e.intervalMin > 0 && (now - e.lastRun) >= e.intervalMin * 60_000L
            }
            // Run at most a few per cycle so a big team doesn't hammer the API in one wake-up.
            due.sortedBy { it.lastRun }.take(3).forEach { e ->
                try { EmployeeRunner.runShift(ctx, e) } catch (ex: Exception) {}
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
