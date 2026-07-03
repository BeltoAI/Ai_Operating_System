package com.agentos.shell.tools

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports exported chat logs from ANY platform so the brain gets real conversation history with each
 * contact AND samples of how YOU write. Auto-detects the format:
 *   • WhatsApp        → "Export chat" .txt
 *   • LinkedIn        → messages.csv (Data export)
 *   • Instagram/Msgr  → Meta JSON (Download your information → Messages)
 *   • Telegram        → Telegram Desktop "Export chat history" JSON
 */
object ChatImport {
    data class Result(val contacts: Int, val messages: Int, val mySamples: List<String>, val source: String)
    // P2.1: carry the REAL export timestamp (epoch ms) when we can parse it; 0 = unknown.
    private data class Line(val contact: String, val sender: String, val body: String, val ts: Long = 0L)

    // Group 1 now captures the leading date+time so we can preserve real recency (was un-captured).
    private val WA_LINE = Regex("""^\[?(\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4},?\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AaPp][Mm])?)\]?\s*[-–]?\s*([^:]{1,40}):\s?(.*)$""")

    // WhatsApp's date/time varies by locale; try the common patterns, lenient. 0 on failure.
    private val WA_FORMATS = listOf(
        "M/d/yy, h:mm:ss a", "M/d/yy, h:mm a", "M/d/yy, HH:mm:ss", "M/d/yy, HH:mm",
        "M/d/yyyy, h:mm:ss a", "M/d/yyyy, h:mm a", "M/d/yyyy, HH:mm:ss", "M/d/yyyy, HH:mm",
        "d/M/yyyy, HH:mm:ss", "d/M/yyyy, HH:mm", "dd/MM/yyyy, HH:mm:ss", "dd/MM/yyyy, HH:mm",
        "d.M.yyyy, HH:mm:ss", "d.M.yy, HH:mm", "dd.MM.yy, HH:mm", "dd/MM/yy, HH:mm"
    )
    private fun parseWhen(s: String, formats: List<String>, utc: Boolean = false): Long {
        val t = s.replace("[", "").replace("]", "").replace(" UTC", "").trim()
        for (f in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US)
                sdf.isLenient = true
                if (utc) sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val d = sdf.parse(t); if (d != null) return d.time
            } catch (e: Exception) {}
        }
        return 0L
    }

    fun importAny(ctx: Context, uri: Uri, owner: String): Result {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return empty()
        } catch (e: Exception) { return empty() }
        // A zip (e.g. one archive of all your chat exports) → unpack and import everything inside.
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            return importZip(ctx, bytes, owner)
        return dispatchText(ctx, String(bytes, Charsets.UTF_8), owner)
    }

    private fun dispatchText(ctx: Context, text: String, owner: String): Result {
        if (text.isBlank()) return empty()
        val head = text.trimStart()
        return try {
            when {
                text.contains("CONVERSATION ID", true) -> linkedIn(ctx, text, owner)
                head.startsWith("{") || head.startsWith("[") -> json(ctx, text, owner)
                else -> whatsApp(ctx, text, owner)
            }
        } catch (e: Exception) { empty() }
    }

    /** Unpack a .zip and import every .txt/.csv/.json inside (handles nested folders too). */
    private fun importZip(ctx: Context, bytes: ByteArray, owner: String): Result {
        var contacts = 0; var messages = 0; val samples = ArrayList<String>(); var sources = HashSet<String>()
        try {
            val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.lowercase()
                if (entry.isDirectory || name.startsWith("__macosx") ||
                    !(name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".json"))) { zis.closeEntry(); continue }
                val bos = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = zis.read(buf); if (n < 0) break; bos.write(buf, 0, n) }
                zis.closeEntry()
                val r = dispatchText(ctx, bos.toString("UTF-8"), owner)
                contacts += r.contacts; messages += r.messages; samples.addAll(r.mySamples)
                if (r.source.isNotBlank()) sources.add(r.source)
            }
            zis.close()
        } catch (e: Exception) { /* return whatever we got */ }
        return Result(contacts, messages, samples, sources.joinToString("+").ifBlank { "zip" })
    }

    private fun empty() = Result(0, 0, emptyList(), "")

    /** Common write path: bulk-insert ALL messages into the scalable DB, keep a recent slice per
     *  contact in the live store for replies, and collect the owner's own messages as samples. */
    private fun ingest(ctx: Context, platform: String, lines: List<Line>, ownerName: String?): Result {
        val clean = lines.filter { it.body.isNotBlank() && it.contact.isNotBlank() }
        if (clean.isEmpty()) return Result(0, 0, emptyList(), platform)
        // P2.1: use the REAL timestamp where the export carried one; only messages with an unknown time
        // are spread deterministically backwards from import time (1 min apart, in order) — never clustered
        // at "now", so ORDER BY ts still sorts imported threads in true chronological order.
        val base = System.currentTimeMillis(); val n = clean.size
        val rows = clean.mapIndexed { i, l ->
            val role = if (ownerName != null && l.sender.equals(ownerName, true)) "me" else "them"
            val ts = if (l.ts > 0L) l.ts else base - (n - i) * 60_000L
            MessageStore.Row(l.contact, platform, l.sender, role, l.body, ts)
        }
        // Dedupe against what's already in the brain so re-importing the same export doesn't
        // double-count. Report the number of NEW messages actually added.
        val added = MessageStore.insertBatchDedupe(ctx, rows)
        // NOTE: we deliberately do NOT write imported history into the old per-key JSON store — that
        // rewrites the whole blob per message (O(n²)) and was the cause of multi-minute imports.
        // Replies + the graph read history straight from the DB now.
        val mine = if (ownerName != null) clean.filter { it.sender.equals(ownerName, true) }.map { it.body } else emptyList()
        return Result(clean.map { it.contact }.toSet().size, added, mine, platform)
    }

    private fun whatsApp(ctx: Context, text: String, owner: String): Result {
        // (sender, body, ts) — ts parsed from each line's real WhatsApp date/time when possible.
        val msgs = ArrayList<Triple<String, String, Long>>()
        for (line in text.split(Regex("\r?\n"))) {
            // Strip WhatsApp's invisible bidi/format marks + odd spaces that break the regex.
            val raw = line
                .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]"), "")
                .replace(Regex("[\\u00a0\\u202f\\u2007\\u2009]"), " ").trim()
            val m = WA_LINE.find(raw)
            if (m != null) {
                val body = m.groupValues[3].trim()
                if (body.isNotBlank() && !body.contains("end-to-end encrypted"))
                    msgs.add(Triple(m.groupValues[2].trim(), body, parseWhen(m.groupValues[1], WA_FORMATS)))
            } else if (raw.isNotBlank() && msgs.isNotEmpty()) {
                val last = msgs.removeAt(msgs.size - 1); msgs.add(Triple(last.first, last.second + " " + raw.trim(), last.third))
            }
        }
        if (msgs.isEmpty()) return empty()
        val freq = msgs.groupingBy { it.first }.eachCount()
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) } else null
        val contact = freq.entries.filter { it.key != ownerName }.maxByOrNull { it.value }?.key
            ?: freq.entries.maxByOrNull { it.value }!!.key
        return ingest(ctx, "WhatsApp", msgs.map { Line(contact, it.first, it.second, it.third) }, ownerName)
    }

    private fun linkedIn(ctx: Context, text: String, owner: String): Result {
        val rows = ConnectionStore.parseCsv(text)
        val header = rows.firstOrNull { r -> r.any { it.contains("CONVERSATION ID", true) } } ?: return empty()
        val h = header.map { it.trim().uppercase() }
        val iFrom = h.indexOf("FROM"); val iTo = h.indexOf("TO"); val iBody = h.indexOf("CONTENT")
        val iDate = h.indexOf("DATE")   // LinkedIn's messages.csv carries a real "yyyy-MM-dd HH:mm:ss UTC" date
        if (iFrom < 0 || iBody < 0) return empty()
        val start = rows.indexOf(header) + 1
        val rowsData = rows.drop(start)
        val freq = HashMap<String, Int>()
        rowsData.forEach { if (iFrom in it.indices) it[iFrom].trim().let { f -> if (f.isNotBlank()) freq[f] = (freq[f] ?: 0) + 1 } }
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) }
            else freq.maxByOrNull { it.value }?.key
        val lines = rowsData.mapNotNull { c ->
            fun g(i: Int) = if (i >= 0 && i in c.indices) c[i].trim() else ""
            val from = g(iFrom); val to = g(iTo); val body = g(iBody)
            if (from.isBlank() || body.isBlank()) return@mapNotNull null
            val contact = if (from.equals(ownerName, true)) to.substringBefore(",").trim() else from
            val ts = if (iDate >= 0) parseWhen(g(iDate), listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss"), utc = true) else 0L
            Line(contact.ifBlank { "LinkedIn" }, from, body, ts)
        }
        return ingest(ctx, "LinkedIn", lines, ownerName)
    }

    private fun json(ctx: Context, text: String, owner: String): Result {
        // Telegram desktop export
        val root = JSONObject(text.substring(text.indexOf(if (text.trimStart().startsWith("[")) "[" else "{")))
        if (root.has("messages") && (root.has("name") || root.has("id"))) return telegramChat(ctx, root, owner)
        if (root.optJSONObject("chats")?.optJSONArray("list") != null) {
            val list = root.getJSONObject("chats").getJSONArray("list")
            var c = 0; var n = 0; val samples = ArrayList<String>()
            for (i in 0 until list.length()) {
                val r = telegramChat(ctx, list.getJSONObject(i), owner)
                c += r.contacts; n += r.messages; samples.addAll(r.mySamples)
            }
            return Result(c, n, samples, "Telegram")
        }
        // Meta (Instagram / Messenger)
        if (root.has("participants") && root.has("messages")) return meta(ctx, root, owner)
        return empty()
    }

    private fun telegramChat(ctx: Context, chat: JSONObject, owner: String): Result {
        val contact = chat.optString("name").ifBlank { "Telegram" }
        val arr = chat.optJSONArray("messages") ?: return empty()
        val lines = ArrayList<Line>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("type") != "message") continue
            val sender = m.optString("from")
            val body = flattenText(m.opt("text"))
            // Telegram exports carry a real unix time — preserve it (seconds → ms).
            val ts = m.optString("date_unixtime").toLongOrNull()?.times(1000) ?: 0L
            if (sender.isNotBlank() && body.isNotBlank()) lines.add(Line(contact, sender, body, ts))
        }
        val freq = lines.groupingBy { it.sender }.eachCount()
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) }
            else freq.maxByOrNull { it.value }?.key
        return ingest(ctx, "Telegram", lines, ownerName)
    }

    private fun meta(ctx: Context, root: JSONObject, owner: String): Result {
        val parts = root.optJSONArray("participants")
        val title = root.optString("title").ifBlank {
            (0 until (parts?.length() ?: 0)).map { parts!!.getJSONObject(it).optString("name") }
                .firstOrNull { it.isNotBlank() && !it.equals(owner, true) } ?: "Instagram"
        }
        val arr = root.optJSONArray("messages") ?: return empty()
        val lines = ArrayList<Line>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val sender = m.optString("sender_name")
            val body = m.optString("content")
            // Meta (Instagram/Messenger) exports carry a real timestamp_ms — preserve it.
            val ts = m.optLong("timestamp_ms", 0L)
            if (sender.isNotBlank() && body.isNotBlank() && !body.endsWith("to your message")) lines.add(Line(title, sender, body, ts))
        }
        val freq = lines.groupingBy { it.sender }.eachCount()
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) }
            else freq.minByOrNull { it.value }?.key   // your own side is often the less-frequent in a thread you received
        return ingest(ctx, "Instagram", lines, ownerName)
    }

    /** Telegram text can be a String, or an array of strings / {type,text} entities. */
    private fun flattenText(t: Any?): String = when (t) {
        is String -> t
        is JSONArray -> (0 until t.length()).joinToString("") { i ->
            when (val e = t.opt(i)) { is String -> e; is JSONObject -> e.optString("text"); else -> "" }
        }
        else -> ""
    }
}
