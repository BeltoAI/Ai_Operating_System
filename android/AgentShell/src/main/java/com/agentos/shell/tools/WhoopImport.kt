package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.zip.ZipInputStream

/**
 * Your whole Whoop history, from the export Whoop already offers.
 *
 * Health Connect is the wrong tool for a Whoop that has been worn for months. It carries only what
 * Whoop chooses to push and only from the moment it starts pushing, so a strap that spent a year
 * paired to an iPhone arrives on a new phone with nothing behind it. Worse, Whoop writes no HRV
 * into Health Connect at all — its manifest has no WRITE_HEART_RATE_VARIABILITY — and Health Connect
 * has no record type for recovery or day strain, which are the two numbers a Whoop owner actually
 * looks at.
 *
 * The export has all of them. `physiological_cycles.csv` carries recovery, HRV, resting heart rate,
 * day strain, sleep performance, respiratory rate and blood oxygen, one row per day, going back to
 * the beginning. It is a better source than the live integration in every respect except freshness,
 * so SlyOS reads both: this for the history, Health Connect for today.
 *
 * Accepts the zip as downloaded, or a single CSV pulled out of it — people unzip things.
 */
object WhoopImport {

    data class Result(val rows: Int, val samples: Int, val from: Long, val to: Long, val error: String = "")

    /** Column headings Whoop uses, mapped to what we store. Matched loosely — Whoop renames them. */
    private val COLUMNS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)recovery score") to VitalsStore.M.RECOVERY,
        Regex("(?i)heart rate variability") to VitalsStore.M.HRV,
        Regex("(?i)resting heart rate") to VitalsStore.M.RHR,
        Regex("(?i)day strain") to VitalsStore.M.STRAIN,
        Regex("(?i)asleep duration") to VitalsStore.M.SLEEP,
        Regex("(?i)respiratory rate") to VitalsStore.M.RESP,
        Regex("(?i)blood oxygen") to VitalsStore.M.SPO2,
        Regex("(?i)energy burned") to VitalsStore.M.CALORIES
    )

    /** The date column, whichever of Whoop's names this export uses. */
    private val DATE_COLUMN = Regex("(?i)(cycle start time|sleep onset|wake onset|start time|date)")

    /**
     * When you WOKE — the day a night's sleep belongs to.
     *
     * Whoop files a night under the cycle that contains it, and a cycle can start either side of
     * midnight. Measured on a real export: 2026-06-28 held one cycle beginning 00:20 (the night of
     * the 27th) and another beginning 22:05 (the night of the 28th), so filing both under their
     * start date put 16h32 of sleep on a single day. A night belongs to the morning you woke up,
     * which is also the day you would ask about it.
     */
    private val WAKE_COLUMN = Regex("(?i)wake onset")

    fun importFrom(ctx: Context, uri: Uri): Result {
        val csvs = try { readCsvs(ctx, uri) } catch (e: Exception) {
            Log.w("SlyOS", "whoop/read: ${e.message}")
            return Result(0, 0, 0, 0, "Couldn't open that file.")
        }
        if (csvs.isEmpty()) return Result(0, 0, 0, 0, "No CSV found in there.")

        val samples = ArrayList<VitalsStore.Sample>()
        var rows = 0
        var from = Long.MAX_VALUE; var to = 0L

        // ORDER MATTERS. Several files in the export carry the same column names against the same
        // cycle start time — workouts.csv has "Energy burned" per workout, physiological_cycles.csv
        // has it per day — and a later write wins on (metric, timestamp). Processed so the daily
        // file lands last, or a day with three gym sessions would end up reporting the calories of
        // whichever one happened to be read last.
        val ordered = csvs.sortedBy { (name, _) ->
            when {
                name.contains("physiological", true) -> 3
                name.contains("sleep", true) -> 2
                else -> 1
            }
        }
        ordered.forEach { (_, text) ->
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size < 2) return@forEach
            val header = splitCsv(lines.first())
            val dateAt = header.indexOfFirst { DATE_COLUMN.containsMatchIn(it) }
            if (dateAt < 0) return@forEach
            // Which columns in THIS file map to which metric — the export has several files and
            // each carries a different subset.
            val mapped = header.mapIndexedNotNull { i, h ->
                COLUMNS.firstOrNull { (re, _) -> re.containsMatchIn(h) }?.let { i to it.second }
            }
            if (mapped.isEmpty()) return@forEach

            val wakeAt = header.indexOfFirst { WAKE_COLUMN.containsMatchIn(it) }

            lines.drop(1).forEach { line ->
                val cells = splitCsv(line)
                if (cells.size <= dateAt) return@forEach
                val ts = parseDate(cells[dateAt]) ?: return@forEach
                rows++
                if (ts < from) from = ts
                if (ts > to) to = ts
                mapped.forEach { (i, metric) ->
                    val v = cells.getOrNull(i)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: return@forEach
                    // Sleep is stamped with the WAKE time so it lands on the morning it belongs to;
                    // everything else stays on the cycle it was measured in.
                    val at = if (metric == VitalsStore.M.SLEEP && wakeAt >= 0)
                        cells.getOrNull(wakeAt)?.let { parseDate(it) } ?: ts else ts
                    samples.add(VitalsStore.Sample(metric, v, at, at, "whoop"))
                }
            }
        }

        if (samples.isEmpty()) return Result(rows, 0, 0, 0,
            "Found the file but no readings in it — is this the Whoop export?")

        VitalsStore.put(ctx, samples)
        // The WHOLE imported history into the brain, one memory per day — that is the point of the
        // import. Writing only today would leave months of readings in a database the assistant
        // cannot search.
        try { VitalsInsight.rememberDays(ctx, 400) } catch (e: Exception) {}
        return Result(rows, samples.size, from, to)
    }

    // MARK: - Reading

    /** Every CSV in the zip, or the file itself when it already is one. */
    private fun readCsvs(ctx: Context, uri: Uri): List<Pair<String, String>> {
        val name = (uri.lastPathSegment ?: "").lowercase()
        val looksZip = name.endsWith(".zip") ||
            (ctx.contentResolver.getType(uri)?.contains("zip") == true)

        if (!looksZip) {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            return listOfNotNull(text?.takeIf { it.contains(',') }?.let { name to it })
        }

        val out = ArrayList<Pair<String, String>>()
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".csv")) {
                        // Read without closing the stream — closing the reader would close the zip
                        // and cost every entry after this one.
                        out.add(entry.name to zip.readBytes().toString(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    /** A CSV line, respecting quotes — Whoop quotes any field containing a comma. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        line.forEach { c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /**
     * Whoop's timestamps, in the shapes it actually writes them.
     *
     * Tried in order and the first that parses wins. A row whose date cannot be read is skipped
     * rather than filed under today — a year of history stacked onto one day would look like data
     * and be worthless.
     */
    private val FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss",
        "yyyy-MM-dd",
        "MM/dd/yyyy"
    )

    private fun parseDate(s: String): Long? {
        val t = s.trim().removeSuffix("Z").trim()
        if (t.isBlank()) return null
        FORMATS.forEach { f ->
            try {
                val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US)
                sdf.isLenient = false
                return sdf.parse(t)?.time ?: return@forEach
            } catch (e: Exception) {}
        }
        return null
    }

    /** What to tell someone who has not got the export yet. */
    fun howToGetIt(): String =
        "In the WHOOP app: More → Export WHOOP Data → request it. Whoop emails you a zip within a " +
        "few minutes. Save it to this phone, then come back and pick it here — it has your whole " +
        "history, including HRV, recovery and strain, which the live connection can't send at all."
}
