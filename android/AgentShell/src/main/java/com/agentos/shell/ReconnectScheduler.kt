package com.agentos.shell

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Schedules (or cancels) the weekly "reconnect" nudge — Mondays at ~9am. */
object ReconnectScheduler {
    private const val WORK = "reconnect_weekly"

    fun set(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val req = PeriodicWorkRequestBuilder<ReconnectWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
