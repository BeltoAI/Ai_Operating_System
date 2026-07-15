package com.agentos.shell.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * ONE FILING PIPELINE. An attachment (email PDF, browsed file, gallery photo) is sorted exactly like the
 * camera "Scan doc" and the email sync already do:
 *   • a receipt/invoice  → ExpenseStore   (shows up in Expenses + the brain)
 *   • any other document → DocStore        (categorised document folder + the brain)
 *   • a photo            → PhotoIndex       (described into the photo RAG)
 *
 * So "file it" doesn't create a parallel world — it feeds the same expense tracking, document sorting and
 * brain everything else already uses.
 */
object AutoFile {
    private const val TAG = "SlyOS-AutoFile"

    fun file(ctx: Context, uri: Uri): String = try {
        when {
            FileOps.isPdf(ctx, uri) -> filePdf(ctx, uri)
            FileOps.isImage(ctx, uri) -> fileImage(ctx, uri)
            else -> "I can auto-sort PDFs and photos so far — this one I can only keep attached."
        }
    } catch (e: Exception) { Log.w(TAG, "file: ${e.message}"); "I couldn't sort that one." }

    private fun filePdf(ctx: Context, uri: Uri): String {
        val text = FileOps.pdfText(ctx, uri)
        if (text.isBlank()) return "That PDF is a scan with no text layer — add the OCR power and I'll sort it."
        // Receipt/invoice → Expenses (same guard the camera & email use).
        val r = AgentClient.extractReceiptText(text)
        if (r != null && r.total > 0.0 && r.confidence >= 0.55) {
            ExpenseStore.record(ctx, r.merchant, r.dateIso, r.total, r.currency, r.tax, r.category, r.itemsJson, "attachment", "", text.take(1500), r.confidence)
            return "Logged ${r.currency} ${"%.2f".format(r.total)} at ${r.merchant} in Expenses."
        }
        // Otherwise a document → DocStore category folder.
        val j = AgentClient.extractFormText(text) ?: return "Kept it, but I couldn't tell what type it is."
        val title = j.optString("title", "Document")
        val folder = DocStore.addText(ctx, j.optString("category", "other"), title,
            j.optString("summary", ""), j.optJSONObject("fields") ?: JSONObject(), "attachment")
        try { DocText.add(ctx, title, "attachment", text) } catch (e: Exception) {}   // full PDF text → readable by agents
        return "Sorted into $folder — $title."
    }

    private fun fileImage(ctx: Context, uri: Uri): String {
        val b64 = ImageUtil.encode(ctx, uri, 1568) ?: return "I couldn't read that image."
        // Receipt photo → Expenses.
        val r = AgentClient.extractReceipt(b64)
        if (r != null && r.total > 0.0 && r.confidence >= 0.55) {
            val date = r.dateIso.ifBlank { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) }
            ExpenseStore.record(ctx, r.merchant, date, r.total, r.currency, r.tax, r.category, r.itemsJson, "attachment", "", "", r.confidence)
            return "Logged ${r.currency} ${"%.2f".format(r.total)} at ${r.merchant} in Expenses."
        }
        // A document photo (ID, letter, form) → DocStore with the picture; otherwise it's just a photo (already
        // described into the photo RAG by the indexer), so we note that.
        val j = AgentClient.extractForm(b64)
        if (j != null && j.optString("category").isNotBlank() && !j.optString("category").equals("photo", true)) {
            val bmp = try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
            val folder = if (bmp != null)
                DocStore.add(ctx, j.optString("category", "other"), j.optString("title", "Document"),
                    j.optString("summary", ""), j.optJSONObject("fields") ?: JSONObject(), bmp)
            else DocStore.addText(ctx, j.optString("category", "other"), j.optString("title", "Document"),
                    j.optString("summary", ""), j.optJSONObject("fields") ?: JSONObject(), "attachment")
            return "Sorted into $folder — ${j.optString("title", "Document")}."
        }
        return "That's a regular photo — it's already described in your brain, so you can find it by asking."
    }
}
