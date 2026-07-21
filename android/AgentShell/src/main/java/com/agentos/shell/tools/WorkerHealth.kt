package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * BACKGROUND WORK OBSERVABILITY.
 *
 * The largest blind spot in SlyOS: eleven background workers do the autonomous work — backups, embedding,
 * photo scanning, Gmail sync, memory consolidation, missions, reconnect, spicy posts, trades, checklist
 * reminders — and ten of them recorded NOTHING. If Android silently stopped scheduling one (Doze, battery
 * optimisation, a thrown exception, a cancelled WorkManager chain), it would simply never run again and
 * nothing anywhere would say so. The feature would look "fine" and quietly do nothing for weeks.
 *
 * This records every run: when it started, whether it finished, what it did, how long it took, and what
 * went wrong. It also knows each worker's EXPECTED cadence, so "hasn't run in 3 days when it should run
 * daily" is itself reported as a failure — silence is the thing we're actually hunting.
 */
object WorkerHealth {
    private const val PREFS = "slyos_workers"
    private const val CAP = 60
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Expected cadence in hours — used to detect a worker that has gone silent. 0 = on-demand only. */
    private val EXPECTED_HOURS = mapOf(
        "BackupWorker" to 24, "EmbedWorker" to 6, "PhotoScanWorker" to 12,
        "GmailSyncWorker" to 6, "MemoryConsolidationWorker" to 24,
        "SpicyWorker" to 24, "ReconnectWorker" to 168, "ChecklistReminderWorker" to 24,
        "MissionWorker" to 24, "TradeWorker" to 24, "EmployeeWorker" to 6)

    data class Run(val worker: String, val at: Long, val ok: Boolean, val ms: Long, val detail: String)

    /** Call at the START of doWork so a worker that dies mid-run is still visible as "started, never finished". */
    fun started(ctx: Context, worker: String) {
        p(ctx).edit().putLong("start_$worker", System.currentTimeMillis()).apply()
    }

    /** Call at the END of doWork with the outcome. */
    fun finished(ctx: Context, worker: String, ok: Boolean, detail: String = "") {
        val now = System.currentTimeMillis()
        val started = p(ctx).getLong("start_$worker", now)
        val ms = (now - started).coerceAtLeast(0)
        val e = p(ctx).edit()
            .putLong("last_$worker", now)
            .putBoolean("lastok_$worker", ok)
            .putString("lastdetail_$worker", detail.take(140))
            .putLong("lastms_$worker", ms)
            .putInt("runs_$worker", p(ctx).getInt("runs_$worker", 0) + 1)
        if (!ok) e.putInt("fails_$worker", p(ctx).getInt("fails_$worker", 0) + 1)
        // rolling history
        val hist = try { JSONArray(p(ctx).getString("history", "[]")) } catch (ex: Exception) { JSONArray() }
        hist.put(JSONObject().put("w", worker).put("t", now).put("ok", ok).put("ms", ms).put("d", detail.take(120)))
        val trimmed = JSONArray()
        for (i in maxOf(0, hist.length() - CAP) until hist.length()) trimmed.put(hist.get(i))
        e.putString("history", trimmed.toString()).apply()

        if (!ok) Fail.log(ctx, "Worker", "$worker failed", detail.ifBlank { "no detail" })
    }

    /** Wrap a worker body so success, failure AND exceptions are all recorded without touching the logic. */
    inline fun <T> track(ctx: Context, worker: String, block: () -> T): T? {
        started(ctx, worker)
        return try {
            val r = block()
            finished(ctx, worker, true, r?.toString()?.take(100) ?: "done")
            r
        } catch (e: Exception) {
            finished(ctx, worker, false, (e.message ?: e.javaClass.simpleName).take(120))
            null
        }
    }

    data class Status(val worker: String, val lastRun: Long, val ok: Boolean, val detail: String,
                      val runs: Int, val fails: Int, val ms: Long, val overdue: Boolean, val expectedHours: Int)

    fun statuses(ctx: Context): List<Status> {
        val now = System.currentTimeMillis()
        return EXPECTED_HOURS.map { (w, hours) ->
            val last = p(ctx).getLong("last_$w", 0L)
            // Overdue = expected to run on a cadence, but hasn't within 2x that window. Silence is failure.
            val overdue = hours > 0 && (last == 0L || now - last > hours * 3_600_000L * 2)
            Status(w, last, p(ctx).getBoolean("lastok_$w", true),
                p(ctx).getString("lastdetail_$w", "").orEmpty(),
                p(ctx).getInt("runs_$w", 0), p(ctx).getInt("fails_$w", 0),
                p(ctx).getLong("lastms_$w", 0L), overdue, hours)
        }.sortedWith(compareByDescending<Status> { it.overdue }.thenBy { it.worker })
    }

    /** Anything that has never run, or has gone silent past its cadence. */
    fun silent(ctx: Context): List<Status> = statuses(ctx).filter { it.overdue }

    fun history(ctx: Context, n: Int = 30): List<Run> = try {
        val a = JSONArray(p(ctx).getString("history", "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Run(it.optString("w"), it.optLong("t"), it.optBoolean("ok"), it.optLong("ms"), it.optString("d")) }
        }.reversed().take(n)
    } catch (e: Exception) { emptyList() }
}
