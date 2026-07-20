package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * UNIVERSAL FAILURE LOG — one place where EVERY failure lands, wherever it happens.
 *
 * The debugging problem this solves: failures were scattered and mostly silent. An email address that
 * couldn't be found, a message that didn't send, a model that 402'd, a PDF that wouldn't render, a
 * contact that couldn't be resolved — each was handled locally with a `catch {}` or a "couldn't…" string
 * and then forgotten. Nothing accumulated, so there was no way to answer "what went wrong today?".
 *
 * Now anything that doesn't work calls Fail.log() with WHERE it happened, WHAT was attempted and WHY it
 * failed. The log is persisted, capped, deduplicated by burst, and surfaced both in Settings and in the
 * adb pull — so intermittent problems remain visible long after they recover.
 */
object Fail {
    private const val TAG = "SlyOS-Fail"
    private const val PREFS = "slyos_failures"
    private const val KEY = "log"
    private const val CAP = 300

    /**
     * App context, set once in SlyApp. Without this, failure logging would only be possible where a Context
     * happens to be in scope — which is exactly the places that already log. Everywhere else (deep in a
     * store, a parser, a background thread) would stay silent, which defeats the point.
     */
    @Volatile var appContext: Context? = null

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Log from ANYWHERE — no Context needed. */
    fun log(area: String, what: String, why: String, severity: String = "error") =
        log(appContext, area, what, why, severity)

    /**
     * Run a block, and if it throws, RECORD it instead of swallowing it. This is the replacement for
     * `try { … } catch (e: Exception) {}` — same "never crash" behaviour, but the failure stops being
     * invisible. Returns null on failure so callers can keep their existing fallbacks.
     */
    inline fun <T> guard(area: String, what: String, block: () -> T): T? = try { block() }
    catch (e: Exception) { log(area, what, e.message ?: e.javaClass.simpleName); null }

    /** Install a process-wide crash handler so even a hard crash is recorded before the app dies. */
    fun installCrashHandler(ctx: Context) {
        appContext = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val where = e.stackTrace.firstOrNull { it.className.startsWith("com.agentos.shell") }
                log(ctx.applicationContext, "CRASH", where?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: t.name,
                    "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            } catch (ignored: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** severity: "error" (broken) · "warn" (degraded, recovered) · "blocked" (needs the user to act) */
    data class Entry(val time: Long, val area: String, val what: String, val why: String,
                     val severity: String, val count: Int = 1)

    @Synchronized
    fun log(ctx: Context?, area: String, what: String, why: String, severity: String = "error") {
        if (ctx == null) return
        try {
            Log.w(TAG, "[$area] $what — $why")
            val arr = try { JSONArray(p(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
            val now = System.currentTimeMillis()
            // Collapse a repeating failure into a count instead of flooding the log with identical rows.
            val lastIdx = arr.length() - 1
            if (lastIdx >= 0) {
                val last = arr.optJSONObject(lastIdx)
                if (last != null && last.optString("area") == area && last.optString("what") == what &&
                    last.optString("why") == why && now - last.optLong("t") < 10 * 60 * 1000L) {
                    last.put("t", now).put("n", last.optInt("n", 1) + 1)
                    p(ctx).edit().putString(KEY, arr.toString()).apply()
                    return
                }
            }
            arr.put(JSONObject().put("t", now).put("area", area.take(40)).put("what", what.take(120))
                .put("why", why.take(220)).put("sev", severity).put("n", 1))
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - CAP)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            p(ctx).edit().putString(KEY, trimmed.toString()).apply()
        } catch (e: Exception) { /* the failure logger must never itself fail */ }
    }

    /**
     * Convenience for the extremely common shape "an operation returned a 'couldn't…' string".
     * Returns the result unchanged so it can wrap a call site without restructuring it.
     */
    fun check(ctx: Context?, area: String, what: String, result: String): String {
        if (looksFailed(result)) log(ctx, area, what, result.take(200))
        return result
    }

    /** Heuristic for result strings that represent a failure rather than a success. */
    fun looksFailed(result: String): Boolean {
        val r = result.trim().lowercase()
        if (r.isBlank()) return false
        return r.startsWith("couldn't") || r.startsWith("could not") || r.startsWith("can't") ||
            r.startsWith("cannot") || r.startsWith("no app") || r.startsWith("failed") ||
            r.startsWith("error") || r.startsWith("nothing") || r.contains("not found") ||
            r.contains("didn't send") || r.contains("not sent") || r.contains("wasn't sent") ||
            r.contains("no email") || r.contains("no address") || r.contains("permission denied") ||
            r.contains("err::") || r.contains("not connected") || r.contains("not granted")
    }

    fun recent(ctx: Context, n: Int = 60): List<Entry> = try {
        val a = JSONArray(p(ctx).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                Entry(it.optLong("t"), it.optString("area"), it.optString("what"),
                    it.optString("why"), it.optString("sev", "error"), it.optInt("n", 1))
            }
        }.reversed().take(n)
    } catch (e: Exception) { emptyList() }

    /** How many failures in the last [hours] — the "is something wrong right now" number. */
    fun countSince(ctx: Context, hours: Int = 24): Int {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return recent(ctx, CAP).filter { it.time >= cutoff }.sumOf { it.count }
    }

    /** Grouped by area, worst first — the fastest way to see WHERE things break most. */
    fun byArea(ctx: Context, hours: Int = 24): List<Pair<String, Int>> {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return recent(ctx, CAP).filter { it.time >= cutoff }
            .groupBy { it.area }.map { (a, l) -> a to l.sumOf { it.count } }
            .sortedByDescending { it.second }
    }

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()
}
