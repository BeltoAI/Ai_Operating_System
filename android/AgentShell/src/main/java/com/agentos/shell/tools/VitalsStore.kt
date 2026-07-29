package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Everything the wearables have said, on this phone.
 *
 * Named Vitals, not Health: [HealthStore] already exists and is about whether the model providers
 * are answering. Two different things called the same thing is how the wrong one gets called.
 *
 * SQLite rather than preferences because this is the only store in SlyOS with a real volume problem:
 * a heart-rate series alone is thousands of rows a day, and ninety days of it is what makes any of
 * the maths worth doing. Daily rollups are computed once and cached, so the page opens instantly
 * instead of scanning the series every time it is looked at.
 *
 * Nothing here leaves the device. It reaches the brain as daily summaries, never as raw samples — a
 * million rows would drown recall, and "Tue 14 Jan: slept 6h12, HRV 52" is what a question actually
 * gets asked against.
 */
object VitalsStore {

    /** The metrics the page knows how to talk about. Keys are stable — they end up in the brain. */
    object M {
        const val HRV = "hrv"                 // ms (RMSSD)
        const val RHR = "rhr"                 // bpm
        const val SLEEP = "sleep"             // minutes
        const val STEPS = "steps"
        const val RESP = "resp"               // breaths/min
        const val SPO2 = "spo2"               // %
        const val WEIGHT = "weight"           // kg
        const val CALORIES = "calories"       // kcal
        const val VO2 = "vo2"                 // ml/kg/min
        const val EXERCISE = "exercise"       // minutes
        const val RECOVERY = "recovery"       // % — Whoop only
        const val STRAIN = "strain"           // 0–21 — Whoop only

        /** In the order the page shows them: the ones that change a decision first. */
        val ORDER = listOf(HRV, RHR, SLEEP, RECOVERY, STRAIN, STEPS, RESP, SPO2, WEIGHT, VO2, EXERCISE, CALORIES)

        fun label(m: String) = when (m) {
            HRV -> "HRV"; RHR -> "Resting HR"; SLEEP -> "Sleep"; STEPS -> "Steps"
            RESP -> "Respiratory"; SPO2 -> "SpO2"; WEIGHT -> "Weight"; CALORIES -> "Calories"
            VO2 -> "VO₂ max"; EXERCISE -> "Exercise"
            RECOVERY -> "Recovery"; STRAIN -> "Strain"; else -> m
        }

        fun unit(m: String) = when (m) {
            HRV -> "ms"; RHR -> "bpm"; RESP -> "/min"; SPO2 -> "%"
            WEIGHT -> "kg"; CALORIES -> "kcal"; EXERCISE -> "min"; RECOVERY -> "%"
            else -> ""
        }

        /**
         * Whether a HIGHER number is the better one.
         *
         * Needed so a change can be coloured honestly: resting heart rate going up is not the same
         * news as HRV going up, and a page that paints every increase green is lying half the time.
         * Null where it depends entirely on the person's goal — weight, strain, calories.
         */
        fun higherIsBetter(m: String): Boolean? = when (m) {
            HRV, SLEEP, STEPS, SPO2, VO2, RECOVERY, EXERCISE -> true
            RHR, RESP -> false
            else -> null
        }

        /** How a value reads to a person: sleep in hours and minutes, steps with a separator. */
        fun format(m: String, v: Double): String = when (m) {
            SLEEP -> "${(v / 60).toInt()}h${String.format("%02d", (v % 60).toInt())}"
            STEPS, CALORIES -> String.format("%,d", v.toInt())
            HRV, RHR, RESP, SPO2, EXERCISE, RECOVERY -> v.toInt().toString()
            else -> String.format("%.1f", v)
        }

        /** A signed change, in the metric's own terms. */
        fun formatDelta(m: String, d: Double): String {
            val sign = if (d >= 0) "+" else "−"
            val v = kotlin.math.abs(d)
            return sign + when (m) {
                SLEEP -> "${(v / 60).toInt()}h${String.format("%02d", (v % 60).toInt())}"
                STEPS, CALORIES -> String.format("%,d", v.toInt())
                WEIGHT -> String.format("%.1f", v)
                else -> if (v < 10) String.format("%.1f", v) else v.toInt().toString()
            }
        }
    }

    data class Sample(val metric: String, val value: Double, val start: Long, val end: Long, val source: String)
    /** One metric on one day — what every chart and every average is built from. */
    data class Day(val metric: String, val dayStart: Long, val value: Double)

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "slyos_vitals.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples (metric TEXT, value REAL, start INTEGER, end INTEGER, source TEXT)")
            // Every read is "this metric, over this window", so that is the index.
            db.execSQL("CREATE INDEX idx_metric_start ON samples (metric, start)")
            // One row per metric per day, replaced on re-sync — the page reads only this.
            db.execSQL("CREATE TABLE days (metric TEXT, dayStart INTEGER, value REAL, PRIMARY KEY (metric, dayStart))")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var db: Db? = null
    private fun db(ctx: Context): Db = db ?: synchronized(this) {
        db ?: Db(ctx.applicationContext).also { db = it }
    }

    // MARK: - Write

    fun put(ctx: Context, samples: List<Sample>) {
        if (samples.isEmpty()) return
        try {
            val w = db(ctx).writableDatabase
            w.beginTransaction()
            try {
                samples.forEach { s ->
                    // Idempotent on (metric, start): re-syncing an overlapping window must not
                    // double the day's total, which for steps would be silently and badly wrong.
                    w.delete("samples", "metric=? AND start=?", arrayOf(s.metric, s.start.toString()))
                    w.insert("samples", null, ContentValues().apply {
                        put("metric", s.metric); put("value", s.value)
                        put("start", s.start); put("end", s.end); put("source", s.source)
                    })
                }
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
        } catch (e: Exception) { Log.w("SlyOS", "vitals/put: ${e.message}") }
        rollUp(ctx, samples.map { it.metric }.toSet())
    }

    /**
     * Collapse the series into one number per day.
     *
     * Which number depends on the metric, and getting it wrong is the difference between a page that
     * means something and one that does not: steps are a SUM over the day, resting heart rate is the
     * MINIMUM, HRV is the overnight average, weight is an average of what few readings there are.
     */
    private fun rollUp(ctx: Context, metrics: Set<String>) {
        try {
            val w = db(ctx).writableDatabase
            metrics.forEach { m ->
                val agg = when (m) {
                    M.STEPS, M.CALORIES, M.SLEEP, M.EXERCISE -> "SUM(value)"
                    M.RHR -> "MIN(value)"
                    else -> "AVG(value)"
                }
                w.execSQL(
                    "INSERT OR REPLACE INTO days (metric, dayStart, value) " +
                    "SELECT metric, (start/86400000)*86400000 AS d, $agg FROM samples " +
                    "WHERE metric=? GROUP BY metric, d", arrayOf(m))
            }
        } catch (e: Exception) { Log.w("SlyOS", "vitals/rollup: ${e.message}") }
    }

    // MARK: - Read

    /** Daily values for a metric, oldest first, over the last [days] days. */
    fun series(ctx: Context, metric: String, days: Int = 90): List<Day> = try {
        val since = System.currentTimeMillis() - days * 86_400_000L
        db(ctx).readableDatabase.rawQuery(
            "SELECT dayStart, value FROM days WHERE metric=? AND dayStart>=? ORDER BY dayStart ASC",
            arrayOf(metric, since.toString())).use { c ->
            val out = ArrayList<Day>()
            while (c.moveToNext()) out.add(Day(metric, c.getLong(0), c.getDouble(1)))
            out
        }
    } catch (e: Exception) { emptyList() }

    /** The most recent day that actually has a reading — not necessarily today. */
    fun latest(ctx: Context, metric: String): Day? = series(ctx, metric, 30).lastOrNull()

    /** Which metrics have any data at all — the page shows only these, in the order above. */
    fun present(ctx: Context): List<String> = try {
        db(ctx).readableDatabase.rawQuery("SELECT DISTINCT metric FROM days", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            M.ORDER.filter { it in out }
        }
    } catch (e: Exception) { emptyList() }

    fun count(ctx: Context): Int = try {
        db(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) { 0 }

    /** Where the numbers came from, so the page can say "from Whoop" rather than just showing them. */
    fun sources(ctx: Context): List<String> = try {
        db(ctx).readableDatabase.rawQuery("SELECT DISTINCT source FROM samples", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            out.distinct()
        }
    } catch (e: Exception) { emptyList() }

    /** Deletes everything, and means it. */
    fun wipe(ctx: Context) {
        try {
            val w = db(ctx).writableDatabase
            w.delete("samples", null, null)
            w.delete("days", null, null)
        } catch (e: Exception) {}
    }
}
