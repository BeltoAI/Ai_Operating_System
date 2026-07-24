package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * THE SELF-MODEL. The settings "characteristics card" works great in replies because it's ALWAYS in context,
 * verbatim — but it only covers what the user typed. Everything else in the brain (messages, activity, network,
 * documents, schedule, what they're working on) reaches replies only through lossy per-query search, so a
 * question like "write everything you know about me" comes back thin.
 *
 * BrainDigest fixes that: once (in the background) it synthesizes the WHOLE brain into one dense, organized,
 * first-person dossier and caches it. Any surface that wants to speak/act AS the user includes [getOrFull] —
 * so it's ONE call at reply time, with the full picture, not just the card. Regenerated periodically as the
 * brain grows ([ensureFresh]).
 */
object BrainDigest {
    private const val TAG = "SlyOS-BrainDigest"
    private const val PREF = "slyos_brain_digest"
    private const val KEY = "digest"
    private const val KEY_TS = "ts"
    private val DEFAULT_MAX_AGE = 12 * 3600_000L   // refresh at most ~twice a day

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** The cached self-model, or "" if never built. Instant (no work). */
    fun get(ctx: Context): String = prefs(ctx).getString(KEY, "").orEmpty()

    fun builtAt(ctx: Context): Long = prefs(ctx).getLong(KEY_TS, 0L)

    /** The comprehensive self-model if we have one, else the settings profile card (never worse than today). */
    fun getOrFull(ctx: Context): String {
        val d = get(ctx)
        return if (d.isNotBlank()) d else MemoryStore.fullProfile(ctx)
    }

    fun isStale(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE): Boolean =
        System.currentTimeMillis() - builtAt(ctx) > maxAgeMs

    /** Regenerate in the background if stale. Safe to call on startup (call off the main thread). */
    fun ensureFresh(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE) {
        if (isStale(ctx, maxAgeMs)) try { generate(ctx) } catch (t: Throwable) { Log.w(TAG, "ensureFresh: ${t.message}") }
    }

    /** Pull raw material from EVERY corner of the brain (bounded), synthesize it into the dossier, cache it. */
    fun generate(ctx: Context): String {
        val raw = gather(ctx)
        if (raw.length < 200) return get(ctx)   // nothing meaningful yet — keep whatever we have
        val digest = try { AgentClient.buildSelfDigest(raw, targetChars = 10000) } catch (t: Throwable) { "" }
        if (digest.isBlank()) return get(ctx)
        prefs(ctx).edit().putString(KEY, digest).putLong(KEY_TS, System.currentTimeMillis()).apply()
        Log.i(TAG, "digest rebuilt: ${digest.length} chars from ${raw.length} chars of brain")
        return digest
    }

    /** Assemble the brain's raw material — profile + recent messages + network + docs + schedule + tasks +
     *  team activity — each source guarded so a single failure never blocks the digest. */
    private fun gather(ctx: Context): String = buildString {
        fun section(title: String, body: String) { if (body.isNotBlank()) { append("\n\n=== ").append(title).append(" ===\n").append(body.trim()) } }

        section("PROFILE (settings card)", try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" })

        section("RECENT MESSAGES & ACTIVITY (newest last)", try {
            MessageStore.recentLines(ctx, 250).joinToString("\n").take(22000)
        } catch (e: Exception) { "" })

        section("MY NETWORK (key people)", try {
            ConnectionStore.recent(ctx, 80).joinToString("\n") { c ->
                "• ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") + (if (c.company.isNotBlank()) " @ ${c.company}" else "")
            }.take(6000)
        } catch (e: Exception) { "" })

        section("MY DOCUMENTS", try {
            DocStore.list(ctx).sortedByDescending { it.ts }.take(30).joinToString("\n") { d ->
                "• ${d.title} [${d.category}]" + (if (d.summary.isNotBlank()) " — ${d.summary}" else "")
            }.take(4000)
        } catch (e: Exception) { "" })

        section("MY WRITING / PAPERS", try {
            PaperStore.list(ctx).joinToString("\n") { "• “${it.title}” (${it.docType})" }.take(2000)
        } catch (e: Exception) { "" })

        section("MY SCHEDULE (next 30 days)", try {
            if (CalendarTool.hasPermission(ctx)) CalendarTool.upcoming(ctx).take(2500) else ""
        } catch (e: Exception) { "" })

        section("MY OPEN TASKS", try {
            ChecklistStore.load(ctx).filter { !it.done }.joinToString("\n") { "• ${it.text}" }.take(2000)
        } catch (e: Exception) { "" })

        section("MY AI TEAM'S RECENT WORK", try {
            EmployeeStore.recentActivity(ctx, 20).joinToString("\n") { "• ${it.line}" }.take(2500)
        } catch (e: Exception) { "" })
    }.trim()
}
