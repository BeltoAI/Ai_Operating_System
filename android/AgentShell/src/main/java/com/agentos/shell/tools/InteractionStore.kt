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
    fun record(ctx: Context, app: String, textRaw: String) {
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

    private fun maybeTrim(ctx: Context) {
        val f = file(ctx)
        val lines = try { f.readLines() } catch (e: Exception) { return }
        if (lines.size <= MAX_LINES) return
        try { f.writeText(lines.takeLast(TRIM_TO).joinToString("\n") + "\n") } catch (e: Exception) {}
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
