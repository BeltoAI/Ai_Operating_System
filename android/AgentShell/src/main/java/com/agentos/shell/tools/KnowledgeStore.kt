package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** A single PDF knowledge base: extracted text + simple keyword retrieval for Q&A. */
object KnowledgeStore {
    private const val FILE = "knowledge.txt"
    private const val PREF = "slyos_kb"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun hasDoc(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 0 }
    fun name(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""
    fun text(ctx: Context): String = try { if (hasDoc(ctx)) file(ctx).readText() else "" } catch (e: Exception) { "" }

    fun clear(ctx: Context) { file(ctx).delete(); prefs(ctx).edit().remove("name").apply() }

    /** Extract text from the PDF at [uri] and store it. Returns characters extracted (0 = failed). */
    fun load(ctx: Context, uri: Uri, displayName: String): Int {
        return try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val text = ctx.contentResolver.openInputStream(uri).use { ins ->
                val doc = PDDocument.load(ins)
                val t = PDFTextStripper().getText(doc)
                doc.close(); t
            }
            file(ctx).writeText(text)
            prefs(ctx).edit().putString("name", displayName).apply()
            text.length
        } catch (e: Exception) { 0 }
    }

    /** Extract text from PDF bytes (e.g. a Telegram document) and store it. Returns char count. */
    fun loadFromBytes(ctx: Context, bytes: ByteArray, displayName: String): Int {
        return try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val doc = PDDocument.load(bytes)
            val text = PDFTextStripper().getText(doc); doc.close()
            file(ctx).writeText(text)
            prefs(ctx).edit().putString("name", displayName).apply()
            text.length
        } catch (e: Exception) { 0 }
    }

    /** Return the most relevant excerpts for [query], capped to fit a prompt. */
    fun retrieve(ctx: Context, query: String, maxChars: Int = 9000): String {
        val t = text(ctx)
        if (t.length <= maxChars) return t
        val chunks = t.chunked(1500)
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 3 }
        val scored = chunks.map { c -> c to terms.count { c.lowercase().contains(it) } }
            .sortedByDescending { it.second }
        val sb = StringBuilder()
        for ((c, _) in scored) { if (sb.length + c.length > maxChars) break; sb.append(c).append("\n…\n") }
        return sb.toString().ifBlank { t.take(maxChars) }
    }
}
