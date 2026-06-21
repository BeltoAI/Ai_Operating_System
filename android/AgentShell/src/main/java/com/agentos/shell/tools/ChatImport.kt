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
    private data class Line(val contact: String, val sender: String, val body: String)

    private val WA_LINE = Regex("""^\[?\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4},?\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AaPp][Mm])?\]?\s*[-–]?\s*([^:]{1,40}):\s?(.*)$""")

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

    /** Common write path: store per-contact history + collect the owner's own messages as samples. */
    private fun ingest(ctx: Context, platform: String, lines: List<Line>, ownerName: String?): Result {
        val clean = lines.filter { it.body.isNotBlank() && it.contact.isNotBlank() }
        if (clean.isEmpty()) return Result(0, 0, emptyList(), platform)
        clean.takeLast(2000).forEach { l ->
            val role = if (ownerName != null && l.sender.equals(ownerName, true)) "me" else "them"
            ConversationStore.add(ctx, platform, l.contact, role, l.body)
        }
        val mine = if (ownerName != null) clean.filter { it.sender.equals(ownerName, true) }.map { it.body } else emptyList()
        return Result(clean.map { it.contact }.toSet().size, clean.size, mine, platform)
    }

    private fun whatsApp(ctx: Context, text: String, owner: String): Result {
        val msgs = ArrayList<Pair<String, String>>()
        for (line in text.split(Regex("\r?\n"))) {
            // Strip WhatsApp's invisible bidi/format marks + odd spaces that break the regex.
            val raw = line
                .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]"), "")
                .replace(Regex("[\\u00a0\\u202f\\u2007\\u2009]"), " ").trim()
            val m = WA_LINE.find(raw)
            if (m != null) {
                val body = m.groupValues[2].trim()
                if (body.isNotBlank() && !body.contains("end-to-end encrypted"))
                    msgs.add(m.groupValues[1].trim() to body)
            } else if (raw.isNotBlank() && msgs.isNotEmpty()) {
                val last = msgs.removeAt(msgs.size - 1); msgs.add(last.first to (last.second + " " + raw.trim()))
            }
        }
        if (msgs.isEmpty()) return empty()
        val freq = msgs.groupingBy { it.first }.eachCount()
        val ownerName = if (owner.isNotBlank()) freq.keys.firstOrNull { it.equals(owner, true) || it.startsWith(owner, true) } else null
        val contact = freq.entries.filter { it.key != ownerName }.maxByOrNull { it.value }?.key
            ?: freq.entries.maxByOrNull { it.value }!!.key
        return ingest(ctx, "WhatsApp", msgs.map { Line(contact, it.first, it.second) }, ownerName)
    }

    private fun linkedIn(ctx: Context, text: String, owner: String): Result {
        val rows = ConnectionStore.parseCsv(text)
        val header = rows.firstOrNull { r -> r.any { it.contains("CONVERSATION ID", true) } } ?: return empty()
        val h = header.map { it.trim().uppercase() }
        val iFrom = h.indexOf("FROM"); val iTo = h.indexOf("TO"); val iBody = h.indexOf("CONTENT")
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
            Line(contact.ifBlank { "LinkedIn" }, from, body)
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
            if (sender.isNotBlank() && body.isNotBlank()) lines.add(Line(contact, sender, body))
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
            if (sender.isNotBlank() && body.isNotBlank() && !body.endsWith("to your message")) lines.add(Line(title, sender, body))
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
