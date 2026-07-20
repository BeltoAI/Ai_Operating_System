package com.agentos.shell.tools

import android.content.Context
import java.util.Calendar

/**
 * Lightweight self-diagnostics so you can SEE what's working during a test week instead of guessing.
 * Two layers:
 *   1) recent notes — an in-memory ring of the last ~60 component events (embed init/download, etc.),
 *      shown live in the Health card. Cleared on process restart (fine for live debugging).
 *   2) per-provider LLM counters — persisted per day: how many calls each brain handled, how many failed,
 *      its last error, and when it last succeeded. This is the "is every orchestration actually working"
 *      dashboard.
 * Everything here is best-effort and never throws.
 */
object HealthStore {
    data class Note(val component: String, val ok: Boolean, val detail: String, val ts: Long)

    private val notes = ArrayDeque<Note>()

    @Synchronized fun note(component: String, ok: Boolean, detail: String = "") {
        notes.addFirst(Note(component, ok, detail.take(140), System.currentTimeMillis()))
        while (notes.size > 60) notes.removeLast()
    }
    @Synchronized fun recent(n: Int = 40): List<Note> = notes.take(n)

    // ---- persistent per-provider LLM counters ----
    private fun p(ctx: Context) = ctx.getSharedPreferences("slyos_health", Context.MODE_PRIVATE)
    private fun day(): String { val c = Calendar.getInstance(); return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}" }

    fun recordLlm(ctx: Context, provider: String, ok: Boolean, err: String = "") {
        val e = p(ctx).edit()
        val k = if (ok) "ok_${day()}_$provider" else "fail_${day()}_$provider"
        e.putInt(k, p(ctx).getInt(k, 0) + 1)
        // Daily counters alone can't tell "broken NOW" from "broke earlier today and was since fixed" —
        // a provider stays 0-ok/65-fail all day even after a fix lands. Timestamping BOTH outcomes lets
        // the diagnostics say which one actually happened most recently.
        if (ok) e.putLong("lastok_$provider", System.currentTimeMillis())
        else {
            e.putLong("lastfail_$provider", System.currentTimeMillis())
            if (err.isNotBlank()) e.putString("lasterr_$provider", err.take(160))
        }
        e.apply()
    }

    fun lastFail(ctx: Context, provider: String): Long = p(ctx).getLong("lastfail_$provider", 0L)
    /** Current verdict for a provider: true if its most recent outcome was a success. */
    fun healthyNow(ctx: Context, provider: String): Boolean =
        p(ctx).getLong("lastok_$provider", 0L) >= p(ctx).getLong("lastfail_$provider", 0L)

    fun okToday(ctx: Context, provider: String): Int = p(ctx).getInt("ok_${day()}_$provider", 0)
    fun failToday(ctx: Context, provider: String): Int = p(ctx).getInt("fail_${day()}_$provider", 0)
    fun lastError(ctx: Context, provider: String): String = p(ctx).getString("lasterr_$provider", "").orEmpty()
    fun lastOk(ctx: Context, provider: String): Long = p(ctx).getLong("lastok_$provider", 0L)
}
