package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Append-only, on-device log of on-screen interactions captured by the Accessibility service.
 * Stored as JSON lines in filesDir; capped so it can't grow without bound. The agent searches
 * it to answer recall questions ("what did Anna say about the deck?"). Never leaves the phone
 * except as part of a prompt the user triggers.
 */
object InteractionStore {
    private const val FILE = "interactions.log"
    private const val MAX_LINES = 5000        // hard cap; trims to TRIM_TO when exceeded
    private const val TRIM_TO = 3500
    private const val MIN_LEN = 3             // ignore tiny scraps
    private const val MAX_TEXT = 600          // clamp any single capture

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    // In-memory guard so we don't write the same screen text over and over.
    @Volatile private var lastApp = ""
    @Volatile private var lastText = ""

    @Synchronized
    /**
     * Apps that generate constant screen churn with no memory value — system chrome, keyboards, always-on
     * displays, and games that repaint every move.
     *
     * WHY THIS EXISTS: the recall buffer holds 5,000 entries and was completely unfiltered, so a single
     * noisy app could evict everything worth remembering. On a real device Chess alone occupied 3,167 of
     * 4,375 entries (72%) while WhatsApp — where the actual people are — held 30. That's precisely why
     * "have I seen this person on screen?" came back empty: the useful memories had been pushed out by a
     * chess board repainting itself.
     */
    private val NOISE_APPS = setOf(
        "chess", "system ui", "systemui", "samsung keyboard", "gboard", "keyboard",
        "alwaysondisplay", "always on display", "android system", "one ui home", "launcher",
        "biometrics", "voice wake-up", "voice wake up", "settings", "clock", "calculator")

    private fun isNoise(app: String): Boolean {
        val a = app.trim().lowercase()
        return NOISE_APPS.any { a == it || a.contains(it) }
    }

    /** No single app may occupy more than this share of the buffer — keeps one chatty app from crowding out
     *  everything else even if it isn't on the noise list. */
    private const val MAX_APP_SHARE = 0.35

    fun record(ctx: Context, app: String, textRaw: String) {
        // Drop UI chrome outright — it is never the thing you want to recall later.
        if (isNoise(app)) return
        var text = textRaw.trim().replace("\\s+".toRegex(), " ")
        if (text.length < MIN_LEN) return
        if (text.length > MAX_TEXT) text = text.substring(0, MAX_TEXT)
        if (app == lastApp && text == lastText) return     // exact repeat
        if (app == lastApp && lastText.contains(text)) return
        lastApp = app; lastText = text
        try {
            val o = JSONObject().put("t", System.currentTimeMillis()).put("a", app).put("x", text)
            file(ctx).appendText(o.toString() + "\n")
            maybeTrim(ctx)
        } catch (e: Exception) { /* ignore IO hiccups */ }
    }

    /**
     * Trim FAIRLY. The old version kept the most recent N lines regardless of source, so whichever app was
     * loudest at the time survived and everything else was discarded — which is how months of WhatsApp
     * context could vanish behind an afternoon of chess.
     *
     * Now: if one app exceeds [MAX_APP_SHARE] of the buffer, its OLDEST entries are dropped first, and only
     * then do we fall back to plain age-based trimming. Quiet-but-valuable apps survive.
     */
    private fun maybeTrim(ctx: Context) {
        val f = file(ctx)
        val lines = try { f.readLines() } catch (e: Exception) { return }
        if (lines.size <= MAX_LINES) return
        try {
            data class L(val raw: String, val app: String, val t: Long)
            val parsed = lines.mapNotNull {
                try { val o = JSONObject(it); L(it, o.optString("a"), o.optLong("t")) } catch (e: Exception) { null }
            }
            if (parsed.isEmpty()) { f.writeText(lines.takeLast(TRIM_TO).joinToString("\n") + "\n"); return }

            val cap = (TRIM_TO * MAX_APP_SHARE).toInt().coerceAtLeast(50)
            val byApp = parsed.groupBy { it.app }
            val keep = ArrayList<L>()
            byApp.forEach { (_, entries) ->
                // Any single app keeps at most `cap` entries — its newest ones.
                keep.addAll(if (entries.size > cap) entries.sortedBy { it.t }.takeLast(cap) else entries)
            }
            // Still over budget after per-app capping? Drop globally oldest.
            val finalKeep = keep.sortedBy { it.t }.let { if (it.size > TRIM_TO) it.takeLast(TRIM_TO) else it }
            f.writeText(finalKeep.joinToString("\n") { it.raw } + "\n")
        } catch (e: Exception) {
            try { f.writeText(lines.takeLast(TRIM_TO).joinToString("\n") + "\n") } catch (e2: Exception) {}
        }
    }

    /** One-time cleanup of buffers already polluted by noisy apps, so the fix applies retroactively. */
    fun purgeNoise(ctx: Context): Int {
        val f = file(ctx)
        val lines = try { f.readLines() } catch (e: Exception) { return 0 }
        if (lines.isEmpty()) return 0
        val kept = lines.filter {
            try { !isNoise(JSONObject(it).optString("a")) } catch (e: Exception) { true }
        }
        val removed = lines.size - kept.size
        if (removed > 0) try { f.writeText(kept.joinToString("\n") + "\n") } catch (e: Exception) { return 0 }
        return removed
    }

    data class Entry(val time: Long, val app: String, val text: String)

    private fun all(ctx: Context): List<Entry> = try {
        file(ctx).readLines().mapNotNull {
            try { val o = JSONObject(it); Entry(o.getLong("t"), o.getString("a"), o.getString("x")) }
            catch (e: Exception) { null }
        }
    } catch (e: Exception) { emptyList() }

    fun count(ctx: Context): Int = try { file(ctx).readLines().size } catch (e: Exception) { 0 }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) {}; lastApp = ""; lastText = "" }

    /** Keyword-scored search over the log. Returns the best-matching entries, newest first on ties. */
    fun search(ctx: Context, query: String, limit: Int = 40): List<Entry> {
        val terms: List<String> = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        val entries: List<Entry> = all(ctx)
        if (terms.isEmpty()) return entries.takeLast(limit).reversed()
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in entries) {
            val low = e.text.lowercase()
            var score = 0
            for (t in terms) if (low.contains(t)) score++
            if (score > 0) scored.add(Pair(e, score))
        }
        scored.sortWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.time })
        return scored.take(limit).map { it.first }
    }

    /** Capture count per app, most-captured first. */
    fun appCounts(ctx: Context): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (e in all(ctx)) counts[e.app] = (counts[e.app] ?: 0) + 1
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Most recent captures for one app, newest first. */
    fun recentForApp(ctx: Context, app: String, limit: Int = 8): List<Entry> =
        all(ctx).filter { it.app == app }.takeLast(limit).reversed()

    /** Formatted recall block for a prompt, or "" if nothing relevant. */
    fun retrieve(ctx: Context, query: String, max: Int = 25): String {
        val hits = search(ctx, query, max)
        if (hits.isEmpty()) return ""
        val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        return hits.joinToString("\n") { "[${fmt.format(java.util.Date(it.time))} · ${it.app}] ${it.text}" }
    }
}
