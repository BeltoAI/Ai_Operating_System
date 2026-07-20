package com.agentos.shell.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Automated document filing. A scanned form/receipt/ID/invoice is saved with its extracted fields and
 * dropped into a folder named after its category (documents/receipts, documents/invoices, …) that is
 * created automatically. Everything stays on the device.
 */
object DocStore {
    data class Doc(
        val id: Long, val category: String, val title: String,
        val summary: String, val fieldsJson: String, val ts: Long
    )

    private const val PREF = "slyos_docs"
    private const val KEY = "docs"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun safe(s: String): String =
        s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "other" }.take(24)

    private fun dir(ctx: Context, category: String): File =
        File(File(ctx.filesDir, "documents"), safe(category)).apply { mkdirs() }

    fun photoFile(ctx: Context, id: Long, category: String): File = File(dir(ctx, category), "$id.jpg")

    /** Human folder path shown in the UI, e.g. "documents/receipts". */
    fun folderPath(category: String): String = "documents/" + safe(category)

    fun list(ctx: Context): List<Doc> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Doc(o.getLong("id"), o.getString("category"), o.getString("title"),
                o.optString("summary"), o.optString("fields", "{}"), o.optLong("ts"))
        }.sortedByDescending { it.ts }
    } catch (e: Exception) { Fail.log(ctx, "Documents", "list filed documents", "index unreadable: ${e.message}"); emptyList() }

    private fun save(ctx: Context, docs: List<Doc>) {
        val arr = JSONArray()
        docs.forEach {
            arr.put(JSONObject().put("id", it.id).put("category", it.category).put("title", it.title)
                .put("summary", it.summary).put("fields", it.fieldsJson).put("ts", it.ts))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** File a scanned document. Returns the folder it was auto-sorted into. */
    fun add(ctx: Context, category: String, title: String, summary: String, fields: JSONObject, photo: Bitmap): String {
        val cat = safe(category)
        val id = System.currentTimeMillis()
        try { photoFile(ctx, id, cat).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (e: Exception) {}
        save(ctx, list(ctx) + Doc(id, cat, title.trim().ifBlank { "Document" }, summary.trim(), fields.toString(), id))
        // Also drop a line into the brain so "what receipts do I have?" recalls it.
        try { MessageStore.insertOne(ctx, "Documents", "Docs", "system", "system",
            "Filed $cat: ${title.trim()} — ${summary.trim()}") } catch (e: Exception) {}
        return folderPath(cat)
    }

    /** File a document that arrived as TEXT (email body, PDF text) — no photo. Also logged to the brain,
     *  so "what invoices did I get?" is answerable. Deduped by category+title so re-syncs don't pile up. */
    fun addText(ctx: Context, category: String, title: String, summary: String, fields: JSONObject, source: String): String {
        val cat = safe(category)
        val t = title.trim().ifBlank { "Document" }
        if (list(ctx).any { it.category == cat && it.title.equals(t, ignoreCase = true) }) return folderPath(cat)
        val id = System.currentTimeMillis()
        save(ctx, list(ctx) + Doc(id, cat, t, summary.trim(), fields.toString(), id))
        try { MessageStore.insertOne(ctx, "Documents", "Docs", "system", "system",
            "Filed $cat from $source: $t — ${summary.trim()}") } catch (e: Exception) {}
        return folderPath(cat)
    }

    fun byCategory(ctx: Context): Map<String, List<Doc>> = list(ctx).groupBy { it.category }

    fun remove(ctx: Context, id: Long) {
        val d = list(ctx).firstOrNull { it.id == id }
        if (d != null) try { photoFile(ctx, id, d.category).delete() } catch (e: Exception) {}
        save(ctx, list(ctx).filterNot { it.id == id })
    }

    fun photoBitmap(ctx: Context, id: Long, category: String): Bitmap? = try {
        val f = photoFile(ctx, id, category); if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) { null }
}
