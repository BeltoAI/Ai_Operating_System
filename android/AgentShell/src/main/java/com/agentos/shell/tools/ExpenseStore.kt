package com.agentos.shell.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * P1 — the queryable spending ledger. Every expense (from a photographed receipt, an email receipt, or
 * manual entry) is one row in a local SQLite table. Deduped by a stable hash so the SAME purchase seen as
 * both a photo AND its email confirmation counts once. Powers the expenditure review.
 */
object ExpenseStore {

    val CATEGORIES = listOf("Food", "Transport", "Shopping", "Bills", "Travel", "Health", "Subscriptions", "Entertainment", "Other")
    fun normalizeCategory(c: String): String =
        CATEGORIES.firstOrNull { it.equals(c.trim(), true) } ?: "Other"

    data class Expense(
        val id: Long, val merchant: String, val ts: Long, val total: Double, val currency: String,
        val tax: Double, val category: String, val itemsJson: String, val source: String,
        val rawText: String, val imagePath: String, val confidence: Double, val hash: String
    )

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_expenses.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses(id INTEGER PRIMARY KEY AUTOINCREMENT, merchant TEXT, ts INTEGER, " +
                "total REAL, currency TEXT, tax REAL, category TEXT, items_json TEXT, source TEXT, raw_text TEXT, " +
                "image_path TEXT, confidence REAL, hash TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_ts ON expenses(ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_cat ON expenses(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_hash ON expenses(hash)")
        }
        // NEVER drop the ledger on a schema bump — money data is irreplaceable (same pattern as MessageStore).
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = onCreate(db)
        override fun onOpen(db: SQLiteDatabase) { onCreate(db) }
    }

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    /** Stable signature so a photo receipt and its email confirmation dedupe: merchant + DAY + total. */
    fun hashOf(merchant: String, ts: Long, total: Double): String {
        val day = ts / 86_400_000L
        val key = merchant.lowercase().replace(Regex("[^a-z0-9]"), "") + "|" + day + "|" + Math.round(total * 100)
        return try {
            java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { key }
    }

    /** Insert; returns the new row id, or -1 if a row with the same hash already exists (deduped). */
    fun add(ctx: Context, e: Expense): Long {
        val d = db(ctx)
        val h = if (e.hash.isNotBlank()) e.hash else hashOf(e.merchant, e.ts, e.total)
        try {
            d.rawQuery("SELECT id FROM expenses WHERE hash=? LIMIT 1", arrayOf(h)).use { if (it.moveToFirst()) return -1L }
        } catch (ex: Exception) {}
        val cv = android.content.ContentValues().apply {
            put("merchant", e.merchant); put("ts", e.ts); put("total", e.total); put("currency", e.currency.ifBlank { "USD" })
            put("tax", e.tax); put("category", normalizeCategory(e.category)); put("items_json", e.itemsJson)
            put("source", e.source); put("raw_text", e.rawText.take(4000)); put("image_path", e.imagePath)
            put("confidence", e.confidence); put("hash", h)
        }
        return try { d.insert("expenses", null, cv) } catch (ex: Exception) { -1L }
    }

    private fun parseDate(iso: String): Long {
        for (f in listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd", "MM/dd/yyyy", "yyyy/MM/dd")) {
            try { java.text.SimpleDateFormat(f, java.util.Locale.US).apply { isLenient = true }.parse(iso.trim())?.let { return it.time } } catch (e: Exception) {}
        }
        return System.currentTimeMillis()
    }

    /**
     * Save a parsed receipt: dedupes into the ledger AND (if new) writes a human line into the brain so
     * "what did I buy at Costco?" recall works too. Returns the row id, or -1 if it was a duplicate.
     */
    fun record(ctx: Context, merchant: String, dateIso: String, total: Double, currency: String, tax: Double,
               category: String, itemsJson: String, source: String, imagePath: String, rawText: String, confidence: Double): Long {
        val ts = parseDate(dateIso)
        val id = add(ctx, Expense(0, merchant, ts, total, currency.ifBlank { "USD" }, tax,
            normalizeCategory(category), itemsJson, source, rawText, imagePath, confidence, hashOf(merchant, ts, total)))
        if (id > 0) {
            try {
                val day = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ts))
                // Include the actual items bought so "what did I buy at X?" is answerable from the brain.
                val itemNames = try {
                    val arr = org.json.JSONArray(itemsJson)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                } catch (e: Exception) { emptyList() }
                val itemsStr = if (itemNames.isNotEmpty()) " · items: " + itemNames.take(25).joinToString(", ") else ""
                MessageStore.insertOne(ctx, merchant.ifBlank { "Expense" }, "Expenses", "system", "system",
                    "Spent ${currency.ifBlank { "USD" }} ${"%.2f".format(total)} at $merchant on $day — ${normalizeCategory(category)}$itemsStr")
            } catch (e: Exception) {}
        }
        return id
    }

    fun update(ctx: Context, id: Long, merchant: String, total: Double, ts: Long, category: String) {
        try {
            val cv = android.content.ContentValues().apply {
                put("merchant", merchant); put("total", total); put("ts", ts); put("category", normalizeCategory(category))
                put("hash", hashOf(merchant, ts, total))
            }
            db(ctx).update("expenses", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {}
    }

    fun delete(ctx: Context, id: Long) { try { db(ctx).delete("expenses", "id=?", arrayOf(id.toString())) } catch (e: Exception) {} }

    private fun rows(ctx: Context, where: String, args: Array<String>): List<Expense> {
        val out = ArrayList<Expense>()
        try {
            db(ctx).rawQuery("SELECT id,merchant,ts,total,currency,tax,category,items_json,source,raw_text,image_path,confidence,hash FROM expenses" +
                (if (where.isNotBlank()) " WHERE $where" else "") + " ORDER BY ts DESC", args).use { c ->
                while (c.moveToNext()) out.add(Expense(
                    c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getDouble(3), c.getString(4) ?: "USD",
                    c.getDouble(5), c.getString(6) ?: "Other", c.getString(7) ?: "", c.getString(8) ?: "",
                    c.getString(9) ?: "", c.getString(10) ?: "", c.getDouble(11), c.getString(12) ?: ""))
            }
        } catch (e: Exception) {}
        return out
    }

    fun all(ctx: Context): List<Expense> = rows(ctx, "", emptyArray())
    fun byRange(ctx: Context, from: Long, to: Long): List<Expense> = rows(ctx, "ts>=? AND ts<=?", arrayOf(from.toString(), to.toString()))
    fun byCategory(ctx: Context, category: String, from: Long, to: Long): List<Expense> =
        rows(ctx, "category=? AND ts>=? AND ts<=?", arrayOf(normalizeCategory(category), from.toString(), to.toString()))
    fun search(ctx: Context, text: String): List<Expense> {
        val t = "%" + text.replace("%", "") + "%"
        return rows(ctx, "merchant LIKE ? OR raw_text LIKE ? OR items_json LIKE ?", arrayOf(t, t, t))
    }

    /** Category → summed total within [from,to]. */
    fun totalsByCategory(ctx: Context, from: Long, to: Long): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        try {
            db(ctx).rawQuery("SELECT category, SUM(total) FROM expenses WHERE ts>=? AND ts<=? GROUP BY category ORDER BY SUM(total) DESC",
                arrayOf(from.toString(), to.toString())).use { c ->
                while (c.moveToNext()) out[c.getString(0) ?: "Other"] = c.getDouble(1)
            }
        } catch (e: Exception) {}
        return out
    }

    fun topMerchants(ctx: Context, from: Long, to: Long, n: Int = 5): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        try {
            db(ctx).rawQuery("SELECT merchant, SUM(total) FROM expenses WHERE ts>=? AND ts<=? GROUP BY merchant ORDER BY SUM(total) DESC LIMIT ?",
                arrayOf(from.toString(), to.toString(), n.toString())).use { c ->
                while (c.moveToNext()) out.add((c.getString(0) ?: "") to c.getDouble(1))
            }
        } catch (e: Exception) {}
        return out
    }

    fun total(ctx: Context, from: Long, to: Long): Double =
        totalsByCategory(ctx, from, to).values.sum()

    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM expenses", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Resolve a natural range label ("this month", "last month", "this year", "last 30 days") → [from,to]. */
    fun rangeFor(label: String): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val l = label.lowercase()
        fun startOfDay(c: java.util.Calendar) { c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0) }
        return when {
            l.contains("last month") -> {
                cal.add(java.util.Calendar.MONTH, -1); cal.set(java.util.Calendar.DAY_OF_MONTH, 1); startOfDay(cal)
                val from = cal.timeInMillis; cal.add(java.util.Calendar.MONTH, 1); from to (cal.timeInMillis - 1)
            }
            l.contains("this year") || l.contains("year") -> { cal.set(java.util.Calendar.DAY_OF_YEAR, 1); startOfDay(cal); cal.timeInMillis to now }
            l.contains("week") -> { cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek); startOfDay(cal); cal.timeInMillis to now }
            Regex("last (\\d+) days").find(l) != null -> {
                val d = Regex("last (\\d+) days").find(l)!!.groupValues[1].toInt(); (now - d * 86_400_000L) to now
            }
            l.contains("all") || l.contains("total") -> 0L to now
            else -> { cal.set(java.util.Calendar.DAY_OF_MONTH, 1); startOfDay(cal); cal.timeInMillis to now }   // default: this month
        }
    }

    /** A narrated aggregate for the agent to read out / the review to build from. */
    fun summaryText(ctx: Context, from: Long, to: Long, category: String? = null): String {
        val items = if (category != null) byCategory(ctx, category, from, to) else byRange(ctx, from, to)
        if (items.isEmpty()) return "No expenses recorded in that period yet."
        val cur = items.firstOrNull()?.currency ?: "USD"
        val grand = items.sumOf { it.total }
        return buildString {
            append("Total: $cur ${"%.2f".format(grand)} across ${items.size} expenses.")
            if (category == null) {
                val cats = totalsByCategory(ctx, from, to)
                append("\nBy category: ").append(cats.entries.joinToString(", ") { "${it.key} ${"%.2f".format(it.value)}" })
            }
            val top = topMerchants(ctx, from, to, 5)
            if (top.isNotEmpty()) append("\nTop merchants: ").append(top.joinToString(", ") { "${it.first} ${"%.2f".format(it.second)}" })
        }
    }
}
