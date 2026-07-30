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

        // HEALTH DATA WAS ONLY EVER PULLED BY A SCREEN.
        //
        // VitalsSource.sync ran from exactly one place: opening the Health page. So the numbers every
        // other surface answered from — Home, the brain, the Telegram bot, every agent — were as old
        // as the last time the owner happened to visit that tab.
        //
        // Measured on the device: it had last read at 08:44, and by 20:20 two completed workouts
        // (62 min and 20 min) were sitting in Health Connect, invisible to the whole app. "How did I
        // train today?" would have answered with nothing, all afternoon, while the answer was on the
        // phone. That is worse than a missing feature — it is a confident wrong answer.
        //
        // A screen is the wrong owner for a sync. This worker already runs every ~15 minutes.
        try {
            if (com.agentos.shell.tools.VitalsSource.grantedAny(ctx)) {
                // A short window: this runs four times an hour and only needs what is new. The 90-day
                // backfill stays where it belongs, on the Health page's own explicit refresh.
                com.agentos.shell.tools.VitalsSource.sync(ctx, 3)
                com.agentos.shell.tools.VitalsStore.recomputeAll(ctx)
            }
        } catch (e: Exception) {}

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
            // ONE-TIME CRM CLEAN-UP.
            //
            // Runs here rather than at launch because it walks every row, and because the damage is
            // already done: the CRM feeds twelve of its contacts into every brain prompt, so while
            // newsletters were filed as people the assistant was being told that "Zapier News" and
            // "F6S Startup Alert" were contacts of the owner's. Guarded by a flag, so it is once.
            try {
                val flags = ctx.getSharedPreferences("slyos_migrations", android.content.Context.MODE_PRIVATE)
                if (!flags.getBoolean("crm_tidy_v1", false)) {
                    val (dropped, filled) = com.agentos.shell.tools.LeadStore.tidy(ctx)
                    flags.edit().putBoolean("crm_tidy_v1", true).apply()
                    if (dropped > 0 || filled > 0)
                        com.agentos.shell.tools.MemoryLog.add(ctx, "note", "CRM tidied",
                            "Removed $dropped bulk senders that were filed as contacts and filled in " +
                            "$filled companies from their addresses.", "Team")
                }
            } catch (e: Exception) {}

            val now = System.currentTimeMillis()
            val due = EmployeeStore.all(ctx).filter { e ->
                e.intervalMin > 0 && (now - e.lastRun) >= EmployeeStore.dueAfterMs(ctx, e)
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
