package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * Time-series for the brain health check. Once a day we snapshot every metric so we can show trends ("+42 photos
 * today", "+1.2k messages this week") instead of a lone number. Stored compactly as JSON in prefs: a ring of the
 * last [KEEP] daily snapshots, keyed by yyyy-MM-dd.
 */
object StatsHistory {
    private const val PREFS = "slyos_stats_hist"
    private const val KEY = "snaps"
    private const val KEEP = 60

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun load(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun save(ctx: Context, o: JSONObject) = prefs(ctx).edit().putString(KEY, o.toString()).apply()

    /** Snapshot today's numeric metrics if we haven't already today. Safe to call often. */
    fun snapshotIfDue(ctx: Context) {
        val all = load(ctx)
        val day = today()
        if (all.has(day)) return
        val snap = JSONObject()
        try {
            BrainStats.lines(ctx).forEach { l ->
                val v = l.value.trim()
                if (v.matches(Regex("[0-9,]+"))) v.replace(",", "").toIntOrNull()?.let { snap.put(l.label, it) }
            }
        } catch (e: Exception) {}
        all.put(day, snap)
        // Trim to the most recent KEEP days.
        val keys = all.keys().asSequence().toList().sorted()
        if (keys.size > KEEP) keys.take(keys.size - KEEP).forEach { all.remove(it) }
        save(ctx, all)
    }

    /** Value of [label] from the snapshot on-or-before [daysAgo] days back, or null if we have no history yet. */
    fun past(ctx: Context, label: String, daysAgo: Int): Int? {
        val all = load(ctx)
        val target = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - daysAgo * 86_400_000L))
        // closest snapshot at or before target
        val key = all.keys().asSequence().filter { it <= target }.maxOrNull() ?: return null
        return all.optJSONObject(key)?.optInt(label, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
    }

    /** How many daily snapshots we have (for "tracking since N days"). */
    fun days(ctx: Context): Int = load(ctx).length()
}
