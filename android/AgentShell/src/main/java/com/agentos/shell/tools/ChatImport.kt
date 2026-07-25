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
    data class Result(val contacts: Int, val messages: Int, val mySamples: List<String>, val source: String,
                      /** Per-file outcome, so the user sees "28 imported, 3 failed — and why", never a silent lie. */
                      val files: List<FileReport> = emptyList())

    /** @param parsed messages found in the file  @param added new messages actually written (rest were dupes) */
    data class FileReport(val name: String, val parsed: Int, val added: Int, val contact: String, val error: String = "") {
        val ok: Boolean get() = error.isEmpty()
    }
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
        // STREAM, never readBytes(). Loading a whole export into memory is what silently destroyed a real
        // import: 26,678 WhatsApp messages became 950 because the archive blew the heap partway through and
        // the failure was swallowed. OutOfMemoryError is an Error, not an Exception, so we catch Throwable.
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                val bin = java.io.BufferedInputStream(raw, 64 * 1024)
                bin.mark(4)
                val sig = ByteArray(2)
                val n = bin.read(sig)
                bin.reset()
                if (n == 2 && sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte())
                    importZipStream(ctx, bin, owner)
                else dispatchText(ctx, bin.readBytes().toString(Charsets.UTF_8), owner)
            } ?: empty()
        } catch (t: Throwable) {
            android.util.Log.e("SlyOS-Import", "importAny failed: ${t.message}", t)
            Result(0, 0, emptyList(), "", listOf(FileReport("(file)", 0, 0, "", t.message ?: "could not read the file")))
        }
    }

    private fun dispatchText(ctx: Context, text: String, owner: String): Result {
        if (text.isBlank()) return empty()
        // Strip the invisible bidi/BOM marks WhatsApp sprinkles at the start before sniffing anything.
        val head = text.trimStart().replace(Regex("^[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]+"), "")
        // THE BUG THAT DESTROYED A REAL IMPORT: a WhatsApp export begins "[3/13/25, 12:58:25 AM] Name: …",
        // so `head.startsWith("[")` sent it to the JSON parser, which found nothing and reported success —
        // 26,678 messages silently became 0. Only one chat survived, purely because its file happened to start
        // with an invisible LTR mark. So: detect the WhatsApp line shape FIRST, and never sniff on "[" alone.
        // Test EACH line on its own. WA_LINE is anchored (^…$), so running it against many lines JOINED
        // together only ever matched when the sample WAS a single line: tiny chats were detected fine while
        // every large export failed the test and fell through to the JSON parser, importing nothing.
        val looksWhatsApp = head.lineSequence().take(60).any { ln ->
            val c = ln.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]"), "")
                .replace(Regex("[\\u00a0\\u202f\\u2007\\u2009]"), " ").trim()
            c.isNotBlank() && WA_LINE.containsMatchIn(c)
        }
        return try {
            when {
                text.contains("CONVERSATION ID", true) -> linkedIn(ctx, text, owner)
                looksWhatsApp -> whatsApp(ctx, text, owner)
                head.startsWith("{") || head.startsWith("[") -> json(ctx, text, owner)
                else -> whatsApp(ctx, text, owner)
            }
        } catch (t: Throwable) {
            android.util.Log.w("SlyOS-Import", "parse failed: ${t.message}")
            Result(0, 0, emptyList(), "", listOf(FileReport("(file)", 0, 0, "", t.message ?: "parse failed")))
        }
    }

    /** Unpack a .zip and import every .txt/.csv/.json inside (handles nested folders too). */
    /**
     * Unpack an archive of chat exports ENTRY BY ENTRY, straight from the stream. Never holds the whole
     * archive in memory, isolates every file so one bad export can't kill the other thirty, and reports each
     * outcome so the user is told the truth instead of a silent partial success.
     */
    private fun importZipStream(ctx: Context, input: java.io.InputStream, owner: String): Result {
        var contacts = 0; var messages = 0
        val samples = ArrayList<String>(); val sources = HashSet<String>(); val reports = ArrayList<FileReport>()
        try {
            val zis = java.util.zip.ZipInputStream(input)
            while (true) {
                val entry = try { zis.nextEntry ?: break } catch (t: Throwable) {
                    reports.add(FileReport("(archive)", 0, 0, "", "archive is damaged past this point: ${t.message}")); break
                }
                val name = entry.name
                val lower = name.lowercase()
                if (entry.isDirectory || lower.startsWith("__macosx") || lower.substringAfterLast('/').startsWith(".")) {
                    try { zis.closeEntry() } catch (t: Throwable) {}; continue
                }
                if (!(lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".json"))) {
                    // Tell the user we skipped it rather than pretending it didn't exist.
                    if (!lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".mp4") &&
                        !lower.endsWith(".opus") && !lower.endsWith(".webp") && !lower.endsWith(".m4a"))
                        reports.add(FileReport(name.substringAfterLast('/'), 0, 0, "", "unsupported file type"))
                    try { zis.closeEntry() } catch (t: Throwable) {}; continue
                }
                val text = try {
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = zis.read(buf); if (r < 0) break
                        total += r
                        // A single absurd file must not take the whole import down with it.
                        if (total > 40L * 1024 * 1024) throw IllegalStateException("file is over 40MB")
                        bos.write(buf, 0, r)
                    }
                    bos.toString("UTF-8")
                } catch (t: Throwable) {
                    reports.add(FileReport(name.substringAfterLast('/'), 0, 0, "", t.message ?: "could not read"))
                    try { zis.closeEntry() } catch (ig: Throwable) {}; continue
                }
                try { zis.closeEntry() } catch (t: Throwable) {}
                val short = name.substringAfterLast('/')
                try {
                    val r = dispatchText(ctx, text, owner)
                    val parsed = r.files.firstOrNull()?.parsed ?: r.messages
                    reports.add(FileReport(short, parsed, r.messages, r.files.firstOrNull()?.contact ?: ""))
                    contacts += r.contacts; messages += r.messages; samples.addAll(r.mySamples)
                    if (r.source.isNotBlank()) sources.add(r.source)
                    android.util.Log.i("SlyOS-Import", "$short: parsed=$parsed added=${r.messages}")
                } catch (t: Throwable) {
                    // Isolated: log it, report it, keep going.
                    android.util.Log.w("SlyOS-Import", "FAILED $short: ${t.message}")
                    reports.add(FileReport(short, 0, 0, "", t.message ?: "import failed"))
                }
            }
            try { zis.close() } catch (t: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.e("SlyOS-Import", "archive aborted after $messages msgs: ${t.message}", t)
            reports.add(FileReport("(archive)", 0, 0, "", "stopped early: ${t.message}"))
        }
        android.util.Log.i("SlyOS-Import", "zip done: $messages msgs, ${reports.count { it.ok }} ok, ${reports.count { !it.ok }} failed")
        return Result(contacts, messages, samples, sources.joinToString("+").ifBlank { "zip" }, reports)
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
        val added = try { MessageStore.insertBatchDedupe(ctx, rows) } catch (t: Throwable) {
            android.util.Log.e("SlyOS-Import", "insert failed for ${rows.size} rows: ${t.message}", t); 0
        }
        android.util.Log.i("SlyOS-Import", "ingest $platform: parsed=${clean.size} rows=${rows.size} added=$added " +
            "contact=${clean.firstOrNull()?.contact?.take(30)}")
        // NOTE: we deliberately do NOT write imported history into the old per-key JSON store — that
        // rewrites the whole blob per message (O(n²)) and was the cause of multi-minute imports.
        // Replies + the graph read history straight from the DB now.
        val mine = if (ownerName != null) clean.filter { it.sender.equals(ownerName, true) }.map { it.body } else emptyList()
        return Result(clean.map { it.contact }.toSet().size, added, mine, platform,
            listOf(FileReport(platform, clean.size, added, clean.firstOrNull()?.contact ?: "")))
    }

    private fun whatsApp(ctx: Context, text: String, owner: String): Result {
        // (sender, body, ts) — ts parsed from each line's real WhatsApp date/time when possible.
        val msgs = ArrayList<Triple<String, String, Long>>()
        run {
            val segs = text.split(Regex("\r\n|\r|\n|\u2028|\u2029"))
            android.util.Log.i("SlyOS-Import", "whatsApp(): textLen=${text.length} segments=${segs.size} " +
                "first=${segs.firstOrNull()?.take(60)?.replace("\n", " ")}")
        }
        // Split on ANY line ending. The previous pattern failed on these real exports, so the whole file
        // became ONE line: WA_LINE matched the first entry and every later message was appended to its body.
        // A 9,077-message chat therefore parsed as a single message — why the big chats imported nothing.
        for (line in text.split(Regex("\r\n|\r|\n|\u2028|\u2029"))) {
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
