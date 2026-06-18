package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your LinkedIn network, imported from the official Connections.csv export. Lets SlyOS reach the
 * whole graph (not just people you've chatted with) and find who you've never actually messaged.
 */
object ConnectionStore {
    data class Conn(
        val name: String, val company: String, val role: String, val connectedOn: String,
        val url: String, val source: String = "LinkedIn", var reachedOut: Boolean = false
    )

    private const val PREF = "slyos_connections"
    private const val KEY = "items"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Conn> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Conn(o.getString("name"), o.optString("company"), o.optString("role"),
                o.optString("connectedOn"), o.optString("url"),
                o.optString("source", "LinkedIn"), o.optBoolean("reachedOut", false))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, items: List<Conn>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("name", it.name).put("company", it.company).put("role", it.role)
                .put("connectedOn", it.connectedOn).put("url", it.url)
                .put("source", it.source).put("reachedOut", it.reachedOut))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun count(ctx: Context): Int = load(ctx).size
    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    fun markReachedOut(ctx: Context, name: String) =
        save(ctx, load(ctx).map { if (it.name == name) it.copy(reachedOut = true) else it })

    /** Connections you have NOT messaged (not in conversation history) and not yet marked reached-out. */
    fun neverReachedOut(ctx: Context): List<Conn> {
        val talkedTo = ConversationStore.all(ctx).keys
            .map { it.substringAfter("|").lowercase().trim() }.filter { it.isNotBlank() }.toHashSet()
        return load(ctx).filter { !it.reachedOut && !talkedTo.contains(it.name.lowercase().trim()) }
    }

    /**
     * Import a connections CSV from ANY platform, tagged with [source].
     * Handles LinkedIn's export (First Name, Last Name, URL, Company, Position, Connected On) AND any
     * generic CSV with a "Name" column (plus optional Company/Title/Handle/URL). Appends to existing
     * connections (so you can import LinkedIn, then X, then Instagram…). Returns number imported.
     */
    fun importCsv(ctx: Context, uri: Uri, source: String = "LinkedIn"): Int {
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return 0
        } catch (e: Exception) { return 0 }
        val lines = text.split(Regex("\r?\n")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        // Find the header row (skip LinkedIn's preamble); else assume the first line is the header.
        val headerIdx = lines.indexOfFirst {
            val l = it.lowercase()
            (l.contains("first name") && l.contains("last name")) || l.contains("name")
        }.let { if (it < 0) 0 else it }
        val header = splitCsv(lines[headerIdx]).map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || h.contains(it) } }
        val iFirst = col("first name"); val iLast = col("last name")
        val iName = col("name", "full name", "display name")
        val iUrl = col("url", "profile", "link"); val iHandle = col("handle", "username", "@")
        val iCompany = col("company", "organization"); val iRole = col("position", "title", "role")
        val iWhen = col("connected on", "date", "since")
        val out = ArrayList<Conn>()
        for (i in headerIdx + 1 until lines.size) {
            val c = splitCsv(lines[i])
            fun g(idx: Int) = if (idx >= 0 && idx in c.indices) c[idx].trim() else ""
            val name = when {
                iFirst >= 0 || iLast >= 0 -> (g(iFirst) + " " + g(iLast)).trim()
                iName >= 0 -> g(iName)
                else -> g(0)
            }
            if (name.isBlank() || name.equals("name", true)) continue
            out.add(Conn(name, g(iCompany), g(iRole), g(iWhen), g(iUrl).ifBlank { g(iHandle) }, source))
        }
        if (out.isEmpty()) return 0
        // Append, de-duping by name+source.
        val existing = load(ctx)
        val seen = existing.map { it.name.lowercase() + "|" + it.source.lowercase() }.toHashSet()
        val merged = existing + out.filter { seen.add(it.name.lowercase() + "|" + it.source.lowercase()) }
        save(ctx, merged)
        return out.size
    }

    /** Minimal CSV field splitter that respects double-quoted fields. */
    private fun splitCsv(line: String): List<String> {
        val res = ArrayList<String>(); val sb = StringBuilder(); var inQ = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQ && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { res.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        res.add(sb.toString())
        return res
    }
}
