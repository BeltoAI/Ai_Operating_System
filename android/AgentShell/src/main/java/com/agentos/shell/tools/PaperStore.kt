package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A library of research papers: lightweight index in prefs, full HTML stored per-file. */
object PaperStore {
    data class Paper(val id: Long, val title: String, val updated: Long)

    private const val PREF = "slyos_papers"
    private const val KEY = "index"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun file(ctx: Context, id: Long) = File(ctx.filesDir, "paper_$id.html")

    fun list(ctx: Context): List<Paper> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Paper(o.getLong("id"), o.getString("title"), o.optLong("updated"))
        }.sortedByDescending { it.updated }
    } catch (e: Exception) { emptyList() }

    private fun writeIndex(ctx: Context, papers: List<Paper>) {
        val arr = JSONArray()
        papers.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun html(ctx: Context, id: Long): String = try {
        file(ctx, id).let { if (it.exists()) it.readText() else "" }
    } catch (e: Exception) { "" }

    /** Create a new paper, returns its id. */
    fun create(ctx: Context, title: String, html: String): Long {
        val id = System.currentTimeMillis()
        file(ctx, id).writeText(html)
        writeIndex(ctx, listOf(Paper(id, title.ifBlank { "Untitled" }.take(60), id)) + list(ctx))
        return id
    }

    /** Update an existing paper's html (and bump its updated time). */
    fun save(ctx: Context, id: Long, html: String, title: String? = null) {
        file(ctx, id).writeText(html)
        writeIndex(ctx, list(ctx).map {
            if (it.id == id) it.copy(updated = System.currentTimeMillis(), title = title ?: it.title) else it
        })
    }

    fun delete(ctx: Context, id: Long) {
        file(ctx, id).delete()
        writeIndex(ctx, list(ctx).filterNot { it.id == id })
    }

    private fun stripHtml(h: String): String = h
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

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
