package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A library of research papers: lightweight index in prefs, full HTML stored per-file. */
object PaperStore {
    data class Paper(val id: Long, val title: String, val updated: Long, val docType: String = "paper")

    private const val PREF = "slyos_papers"
    private const val KEY = "index"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun file(ctx: Context, id: Long) = File(ctx.filesDir, "paper_$id.html")

    fun list(ctx: Context): List<Paper> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Paper(o.getLong("id"), o.getString("title"), o.optLong("updated"), o.optString("docType", "paper"))
        }.sortedByDescending { it.updated }
    } catch (e: Exception) { emptyList() }

    fun docType(ctx: Context, id: Long): String = list(ctx).firstOrNull { it.id == id }?.docType ?: "paper"

    fun thesis(ctx: Context, id: Long): String = prefs(ctx).getString("thesis_$id", "") ?: ""
    fun setThesis(ctx: Context, id: Long, v: String) = prefs(ctx).edit().putString("thesis_$id", v.trim()).apply()

    // Zenodo deposition id this paper was last published as — so re-publishing makes a NEW VERSION
    // of the same record (shared concept-DOI) instead of a duplicate record.
    fun zenodoId(ctx: Context, id: Long): Long = prefs(ctx).getLong("zenodo_dep_$id", 0L)
    fun setZenodoId(ctx: Context, id: Long, dep: Long) = prefs(ctx).edit().putLong("zenodo_dep_$id", dep).apply()

    // ---- Per-paper conversation (chat thread between you and the writer) ----
    data class Chat(val role: String, val text: String)   // role: "you" | "ai"
    fun chatLog(ctx: Context, id: Long): List<Chat> = try {
        val arr = JSONArray(prefs(ctx).getString("chat_$id", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Chat(o.getString("r"), o.getString("t")) }
    } catch (e: Exception) { emptyList() }
    fun addChat(ctx: Context, id: Long, role: String, text: String) {
        val cur = chatLog(ctx, id).toMutableList()
        cur.add(Chat(role, text)); val capped = cur.takeLast(80)
        val arr = JSONArray(); capped.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        prefs(ctx).edit().putString("chat_$id", arr.toString()).apply()
    }

    private fun writeIndex(ctx: Context, papers: List<Paper>) {
        val arr = JSONArray()
        papers.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated).put("docType", it.docType)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun html(ctx: Context, id: Long): String = try {
        file(ctx, id).let { if (it.exists()) it.readText() else "" }
    } catch (e: Exception) { "" }

    /** Create a new paper, returns its id. */
    fun create(ctx: Context, title: String, html: String, docType: String = "paper"): Long {
        val id = System.currentTimeMillis()
        file(ctx, id).writeText(html)
        writeIndex(ctx, listOf(Paper(id, title.ifBlank { "Untitled" }.take(60), id, docType)) + list(ctx))
        return id
    }

    // ---- Version history: timestamped snapshots so you never lose a version ----
    data class Version(val ts: Long, val label: String)
    private const val VCAP = 25
    private fun vKey(id: Long) = "versions_$id"
    private fun vFile(ctx: Context, id: Long, ts: Long) = File(ctx.filesDir, "paper_${id}_v$ts.html")

    fun versions(ctx: Context, id: Long): List<Version> = try {
        val arr = JSONArray(prefs(ctx).getString(vKey(id), "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Version(o.getLong("ts"), o.optString("label")) }
            .sortedByDescending { it.ts }
    } catch (e: Exception) { emptyList() }

    fun versionHtml(ctx: Context, id: Long, ts: Long): String = try {
        vFile(ctx, id, ts).let { if (it.exists()) it.readText() else "" }
    } catch (e: Exception) { "" }

    /** Save the CURRENT html of [id] as a labeled checkpoint. */
    fun snapshot(ctx: Context, id: Long, label: String) {
        val cur = html(ctx, id)
        if (cur.isBlank()) return
        val ts = System.currentTimeMillis()
        vFile(ctx, id, ts).writeText(cur)
        val list = versions(ctx, id).toMutableList()
        list.add(0, Version(ts, label.take(60)))
        // Cap: delete oldest files beyond VCAP.
        while (list.size > VCAP) { val old = list.removeAt(list.size - 1); try { vFile(ctx, id, old.ts).delete() } catch (e: Exception) {} }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ts", it.ts).put("label", it.label)) }
        prefs(ctx).edit().putString(vKey(id), arr.toString()).apply()
    }

    /** Update an existing paper's html (and bump its updated time). */
    fun save(ctx: Context, id: Long, html: String, title: String? = null) {
        file(ctx, id).writeText(html)
        writeIndex(ctx, list(ctx).map {
            if (it.id == id) it.copy(updated = System.currentTimeMillis(), title = title ?: it.title) else it
        })
    }

    /** Rename a paper (index title only; doesn't touch the document). */
    fun rename(ctx: Context, id: Long, title: String) {
        writeIndex(ctx, list(ctx).map { if (it.id == id) it.copy(title = title.trim().take(80).ifBlank { it.title }) else it })
    }

    fun delete(ctx: Context, id: Long) {
        file(ctx, id).delete()
        versions(ctx, id).forEach { try { vFile(ctx, id, it.ts).delete() } catch (e: Exception) {} }
        prefs(ctx).edit().remove(vKey(id)).apply()
        writeIndex(ctx, list(ctx).filterNot { it.id == id })
    }

    private fun stripHtml(h: String): String = h
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** A paper's readable body text (HTML stripped) — so the vector index can embed it and the brain
     *  can recall your own research by MEANING, not just exact keywords. */
    fun plainText(ctx: Context, id: Long): String = try { stripHtml(html(ctx, id)) } catch (e: Exception) { "" }

    /**
     * Relevant context drawn from your OTHER papers — this is how one paper can build on another
     * through the brain. Returns excerpts most related to [query], excluding the current paper.
     */
    fun libraryContext(ctx: Context, excludeId: Long, query: String, maxChars: Int = 3500): String {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }
        val out = StringBuilder()
        for (p in list(ctx)) {
            if (p.id == excludeId) continue
            val text = stripHtml(html(ctx, p.id))
            if (text.length < 40) continue
            val low = text.lowercase()
            val hit = terms.firstNotNullOfOrNull { t -> low.indexOf(t).takeIf { it >= 0 } }
            val snippet = if (hit != null)
                text.substring((hit - 200).coerceAtLeast(0), (hit + 500).coerceAtMost(text.length))
            else text.take(350)
            out.append("From your paper “${p.title}”: ").append(snippet.trim()).append("\n\n")
            if (out.length > maxChars) break
        }
        return out.toString().take(maxChars)
    }
}
