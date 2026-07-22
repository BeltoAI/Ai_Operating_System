package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE SLYOS FOLDER — a real folder on the phone (Documents/SlyOS) that the AI keeps tidy.
 *
 * Everything SlyOS touches gets filed into a category — Resumes, Cover Letters, Expenses, Contracts,
 * Images, Research… — and an index of what's in there is injected into EVERY AI, so any chat in SlyOS
 * (Home, Chat, Research) can say "your resume is in SlyOS/Resumes" and actually use it in what it writes.
 *
 * The index lives in the app (fast, permission-free, survives OEM quirks); the files live in a normal
 * folder the user can open in any file manager.
 */
object SlyFolder {
    private const val TAG = "SlyOS-Folder"
    private const val PREFS = "slyos_folder"
    const val ROOT = "SlyOS"

    /** The cabinet. The AI files into these; it can also invent a new one when nothing fits. */
    val CATEGORIES = listOf(
        "Documents", "Resumes", "Cover Letters", "Applications", "Contracts", "Invoices",
        "Receipts", "Expenses", "IDs", "Medical", "Images", "Screenshots", "Research", "Notes", "Exports"
    )

    data class Doc(
        val name: String, val category: String, val uri: String,
        val summary: String = "", val ts: Long = System.currentTimeMillis()
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Index ─────────────────────────────────────────────────────────────────────────────────────
    fun index(ctx: Context): List<Doc> = try {
        val arr = JSONArray(prefs(ctx).getString("index", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Doc(it.optString("name"), it.optString("category"), it.optString("uri"),
                    it.optString("summary"), it.optLong("ts"))
            }
        }.sortedByDescending { it.ts }
    } catch (e: Exception) { emptyList() }

    private fun writeIndex(ctx: Context, docs: List<Doc>) {
        val arr = JSONArray()
        docs.take(400).forEach {
            arr.put(JSONObject().put("name", it.name).put("category", it.category)
                .put("uri", it.uri).put("summary", it.summary).put("ts", it.ts))
        }
        prefs(ctx).edit().putString("index", arr.toString()).apply()
    }

    fun record(ctx: Context, doc: Doc) {
        val all = index(ctx).filterNot { it.uri == doc.uri }
        writeIndex(ctx, listOf(doc) + all)
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("index").apply()

    // ── Filing ────────────────────────────────────────────────────────────────────────────────────
    /** Pick a category from the file name + contents. Cheap heuristics first; the AI can override. */
    fun classify(name: String, text: String = ""): String {
        val s = (name + " " + text.take(1200)).lowercase()
        return when {
            Regex("cover ?letter").containsMatchIn(s) -> "Cover Letters"
            Regex("\\bresume\\b|\\bcv\\b|curriculum vitae").containsMatchIn(s) -> "Resumes"
            Regex("invoice|bill to|amount due").containsMatchIn(s) -> "Invoices"
            Regex("receipt|total paid|subtotal").containsMatchIn(s) -> "Receipts"
            Regex("expense|reimburse").containsMatchIn(s) -> "Expenses"
            Regex("contract|agreement|lease|terms and conditions|nda").containsMatchIn(s) -> "Contracts"
            Regex("passport|driver'?s licen|\\bid card\\b|social security").containsMatchIn(s) -> "IDs"
            Regex("prescription|diagnos|medical|patient|lab result").containsMatchIn(s) -> "Medical"
            Regex("application|admission|enrol").containsMatchIn(s) -> "Applications"
            Regex("abstract|references|doi:|arxiv|paper").containsMatchIn(s) -> "Research"
            Regex("screenshot").containsMatchIn(s) -> "Screenshots"
            Regex("\\.(png|jpe?g|webp|heic)$").containsMatchIn(name.lowercase()) -> "Images"
            else -> "Documents"
        }
    }

    /** Write bytes into SlyOS/<category>/ and remember it. Returns the Uri, or null. */
    fun file(ctx: Context, name: String, mime: String, bytes: ByteArray, category: String, summary: String = ""): Uri? {
        val cat = category.ifBlank { "Documents" }
        val uri = insert(ctx, name, mime, bytes, cat) ?: return null
        record(ctx, Doc(name, cat, uri.toString(), summary))
        return uri
    }

    /** Copy an existing attachment into the cabinet, classifying it automatically. */
    fun fileExisting(ctx: Context, src: Uri, textForClassify: String = "", categoryOverride: String = ""): Pair<Uri?, String> = try {
        val name = FileOps.displayName(ctx, src)
        val mime = FileOps.mimeOf(ctx, src)
        val bytes = ctx.contentResolver.openInputStream(src)?.use { it.readBytes() }
        if (bytes == null) null to "I couldn't read that file."
        else {
            val cat = categoryOverride.ifBlank { classify(name, textForClassify) }
            val out = file(ctx, name, mime, bytes, cat, textForClassify.take(160))
            if (out != null) out to "Filed \"$name\" in SlyOS › $cat." else null to "I couldn't file that one."
        }
    } catch (e: Exception) { null to "I couldn't file that one." }

    private fun insert(ctx: Context, name: String, mime: String, bytes: ByteArray, cat: String): Uri? {
        // Documents/SlyOS/<cat> is the natural home; some OEMs are fussy, so fall back to Downloads/SlyOS/<cat>.
        for (base in listOf(Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS)) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/$ROOT/$cat")
                }
                val collection =
                    if (base == Environment.DIRECTORY_DOWNLOADS) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    else MediaStore.Files.getContentUri("external")
                val uri = ctx.contentResolver.insert(collection, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    return uri
                }
            } catch (e: Exception) { Log.w(TAG, "insert into $base failed: ${e.message}") }
        }
        return null
    }

    /** Create the cabinet so the user can SEE it, even before anything is filed. */
    fun ensure(ctx: Context) {
        if (prefs(ctx).getBoolean("built", false)) return
        val note = ("This is your SlyOS folder.\n\nEvery AI in SlyOS files things here and can read them back:\n" +
            CATEGORIES.joinToString("\n") { "  • $it" } + "\n").toByteArray()
        var ok = false
        for (cat in CATEGORIES) if (insert(ctx, "About $cat.txt", "text/plain", note, cat) != null) ok = true
        if (ok) prefs(ctx).edit().putBoolean("built", true).apply()
    }

    // ── What every AI is told ─────────────────────────────────────────────────────────────────────
    fun find(ctx: Context, query: String): List<Doc> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return emptyList()
        return index(ctx).filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) || it.summary.lowercase().contains(q) }
    }

    /** Read a filed document's raw bytes back from its MediaStore uri. Null if it's gone. */
    fun bytesOf(ctx: Context, doc: Doc): ByteArray? = try {
        ctx.contentResolver.openInputStream(Uri.parse(doc.uri))?.use { it.readBytes() }
    } catch (e: Exception) { null }

    /** Best-effort mime from a file name, for re-filing restored bytes. */
    fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "html", "htm" -> "text/html"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    /**
     * Re-file bytes that came from a backup on another device, restoring the SAME drawer + summary. Used
     * by [BrainBackup.restore] so a new phone rebuilds Documents/SlyOS/<category>/ exactly as it was, with
     * a fresh (valid) uri in the index instead of the dead uri the old device recorded.
     */
    fun restoreDoc(ctx: Context, name: String, category: String, bytes: ByteArray, summary: String, ts: Long): Boolean {
        val uri = insert(ctx, name, mimeForName(name), bytes, category.ifBlank { "Documents" }) ?: return false
        // Preserve the original timestamp so ordering/recency survives the move to a new device.
        record(ctx, Doc(name, category.ifBlank { "Documents" }, uri.toString(), summary, if (ts > 0) ts else System.currentTimeMillis()))
        return true
    }

    fun brief(ctx: Context): String {
        val docs = index(ctx)
        if (docs.isEmpty())
            return "SLYOS FOLDER — the user has a folder on their phone at Documents/SlyOS with these drawers: " +
                CATEGORIES.joinToString(", ") + ". It's empty so far. When you produce or receive a file, file it there."
        val byCat = docs.groupBy { it.category }
        return buildString {
            append("SLYOS FOLDER (Documents/SlyOS) — the user's filing cabinet. You can refer to these files by name and use them in anything you write:\n")
            byCat.entries.sortedBy { it.key }.forEach { (cat, list) ->
                append("• ").append(cat).append(" (").append(list.size).append("): ")
                append(list.take(6).joinToString(", ") { it.name })
                append("\n")
            }
            append("If the user asks for something that would use one of these (a resume, an invoice, last month's expenses), use it. ")
            append("File anything new you create into the right drawer. ")
        }
    }
}
