package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * FIND A FILE BY WHAT THE USER CALLS IT. "send my SlyOS white paper to Carlos" — no attachment, just a
 * description. This looks it up: first in the SlyOS folder (files the AI filed, which it owns and can read),
 * then in the phone's Downloads/Documents by name. So the AI can act on files it isn't holding yet.
 */
object FileResolver {
    private const val TAG = "SlyOS-Find"

    data class Found(val uri: Uri, val name: String, val where: String, val score: Int)

    private val STOP = setOf(
        "my", "the", "a", "an", "that", "this", "it", "please", "file", "document", "doc",
        "attachment", "the", "of", "for", "to", "over", "via", "on", "with", "and"
    )

    private fun tokens(desc: String): List<String> =
        desc.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }
            .distinct()

    /** Best matches for a description, most relevant first. */
    fun find(ctx: Context, description: String): List<Found> {
        val toks = tokens(description)
        if (toks.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Found>()

        // 1) The SlyOS folder index — reliable, app-owned URIs we can read & send.
        for (d in SlyFolder.index(ctx)) {
            val hay = (d.name + " " + d.category + " " + d.summary).lowercase()
            val score = toks.count { hay.contains(it) }
            if (score > 0) out[d.uri] = Found(Uri.parse(d.uri), d.name, "SlyOS › ${d.category}", score + 1)
        }

        // 2) Downloads + Documents by filename (best-effort; covers files the AI made or that are visible).
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val like = toks.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
            val args = toks.map { "%$it%" }.toTypedArray()
            for (collection in listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))) {
                ctx.contentResolver.query(collection, proj, like, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    var seen = 0
                    while (c.moveToNext() && seen < 40) {
                        seen++
                        val name = c.getString(nameCol) ?: continue
                        val low = name.lowercase()
                        val score = toks.count { low.contains(it) }
                        if (score == 0) continue
                        val uri = Uri.withAppendedPath(collection, c.getLong(idCol).toString())
                        out.putIfAbsent(uri.toString(), Found(uri, name, "your phone", score))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "storage search: ${e.message}") }

        return out.values.sortedByDescending { it.score }.take(6)
    }

    /** True if the sentence is asking to send a NAMED file (vs. just "send a text to mom"). */
    fun describesAFile(text: String): Boolean {
        val t = text.lowercase()
        if (Regex("\\.(pdf|docx?|xlsx?|pptx?|png|jpe?g|csv|txt)\\b").containsMatchIn(t)) return true
        return Regex("(?i)\\b(paper|resume|cv|invoice|receipt|contract|report|form|letter|deck|slides?|spreadsheet|doc|document|file|photo|picture|image|scan|statement|agreement)\\b").containsMatchIn(t)
    }

    /** Pull the "what" out of a send sentence: send <WHAT> to/via/over … */
    fun extractWhat(q: String): String? =
        Regex("(?i)\\b(?:send|share|forward|email|text)\\s+(.+?)\\s+(?:to|via|over|on|through|using)\\b")
            .find(q)?.groupValues?.get(1)?.trim()
            ?: Regex("(?i)\\b(?:send|share|forward|email)\\s+(.+)$").find(q)?.groupValues?.get(1)?.trim()
}
