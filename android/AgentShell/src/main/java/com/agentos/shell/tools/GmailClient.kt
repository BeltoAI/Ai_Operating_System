package com.agentos.shell.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads recent Gmail — subject, sender, body, and the text of PDF attachments — straight into the
 * brain's searchable store, so the agent actually knows what's in your inbox and what people sent
 * you (including meeting notes and attached documents). Read-only, incremental, on the user's token.
 */
object GmailClient {
    private const val TAG = "SlyOS"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    private fun get(url: String, token: String): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    private fun post(url: String, token: String, json: String): Pair<Int, String> {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    /**
     * RFC 2047 encoded-word for a header value. Email HEADERS are ASCII-only on the wire, so a raw
     * UTF-8 character (an em-dash "—", curly quotes, accents, emoji) gets mis-decoded into mojibake
     * like "Ã¢ÂÂ". Encode any non-ASCII header as =?UTF-8?B?…?=, chunked so no encoded-word exceeds
     * the 75-char limit and multibyte characters never split across a chunk. The body is unaffected
     * (its Content-Type already declares UTF-8).
     */
    private fun encodeHeader(s: String): String {
        if (s.all { it.code in 0..127 }) return s
        val out = StringBuilder()
        val chunk = StringBuilder()
        fun flush() {
            if (chunk.isEmpty()) return
            val enc = Base64.encodeToString(chunk.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            if (out.isNotEmpty()) out.append("\r\n ")   // continuation: CRLF + space
            out.append("=?UTF-8?B?").append(enc).append("?=")
            chunk.setLength(0)
        }
        for (ch in s) {
            chunk.append(ch)
            if (chunk.toString().toByteArray(Charsets.UTF_8).size >= 39) flush()   // ~52 base64 chars → under 75
        }
        flush()
        return out.toString()
    }

    /** Send an email as the user (gmail.send). Returns ok + a message. Records it to the brain. */
    fun send(ctx: Context, to: String, subject: String, body: String): Pair<Boolean, String> {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return false to "Google isn't connected."
        // Do NOT set a From header — Gmail uses the authenticated account automatically. A mismatched
        // From (stale/wrong account) triggers a 403 "Delegation denied," which is likely what happened.
        val mime = buildString {
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(encodeHeader(subject)).append("\r\n")
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n")
            append(body)
        }
        val raw = Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val (code, resp) = post("$BASE/messages/send", token, JSONObject().put("raw", raw).toString())
        if (code !in 200..299) {
            Log.e(TAG, "gmail send $code: ${resp.take(300)}")
            val reason = try { JSONObject(resp).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
            val hint = if (code == 403) " — reconnect Google (Disconnect→Connect) to grant Send, and make sure the Gmail API is enabled." else ""
            return false to ((reason ?: "send failed") + " ($code)" + hint)
        }
        try {
            MessageStore.insertOne(ctx, to, "Email", to, "me", "Subject: $subject\n$body")
        } catch (e: Exception) {}
        return true to "Sent to $to ✓"
    }

    private fun b64(data: String): ByteArray = try {
        Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
    } catch (e: Exception) { ByteArray(0) }

    private fun header(payload: JSONObject, name: String): String {
        val hs = payload.optJSONArray("headers") ?: return ""
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            if (h.optString("name").equals(name, true)) return h.optString("value")
        }
        return ""
    }

    /** Walk the MIME tree, collecting plain text and PDF-attachment text. */
    private fun extract(ctx: Context, msgId: String, token: String, part: JSONObject, sb: StringBuilder, depth: Int) {
        if (depth > 8) return
        val mime = part.optString("mimeType")
        val filename = part.optString("filename")
        val body = part.optJSONObject("body")
        if (filename.isNotBlank() && body?.optString("attachmentId").orEmpty().isNotBlank()) {
            // An attachment. Only PDFs are read for text; others are noted by name.
            if (mime.contains("pdf", true) || filename.endsWith(".pdf", true)) {
                val (code, raw) = get("$BASE/messages/$msgId/attachments/${body!!.optString("attachmentId")}", token)
                if (code in 200..299) {
                    val data = JSONObject(raw).optString("data")
                    val text = pdfText(ctx, b64(data))
                    if (text.isNotBlank()) sb.append("\n[Attachment “$filename”]: ").append(text.take(4000))
                }
            } else sb.append("\n[Attachment: $filename]")
            return
        }
        if (mime == "text/plain") {
            val data = body?.optString("data").orEmpty()
            if (data.isNotBlank()) sb.append(String(b64(data)))
        } else if (mime == "text/html" && sb.isEmpty()) {
            val data = body?.optString("data").orEmpty()
            if (data.isNotBlank()) sb.append(String(b64(data)).replace(Regex("(?is)<[^>]+>"), " ").replace(Regex("\\s+"), " "))
        }
        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) extract(ctx, msgId, token, parts.getJSONObject(i), sb, depth + 1)
    }

    private fun pdfText(ctx: Context, bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val doc = PDDocument.load(bytes)
            val t = PDFTextStripper().getText(doc); doc.close()
            t.replace(Regex("\\s+"), " ").trim()
        } catch (e: Exception) { "" }
    }

    private fun senderName(from: String): String {
        // "Jane Doe <jane@x.com>" → "Jane Doe"; "jane@x.com" → "jane@x.com"
        val m = Regex("^\\s*\"?([^\"<]+?)\"?\\s*<").find(from)
        return (m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: from.trim()).take(80)
    }

    /**
     * Pull recent inbox mail into the brain, incrementally (only messages not seen before). Safe to
     * call on app start, off the main thread. Returns the number of new emails ingested.
     */
    fun syncToBrain(ctx: Context, maxMessages: Int = 30): Int {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return 0
        val (lc, lb) = get("$BASE/messages?maxResults=$maxMessages&q=" + java.net.URLEncoder.encode("in:inbox newer_than:60d", "UTF-8"), token)
        if (lc !in 200..299) { Log.w(TAG, "gmail list $lc: ${lb.take(160)}"); return 0 }
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return 0
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return 0 }
        val prefs = ctx.getSharedPreferences("slyos_gmail", Context.MODE_PRIVATE)
        // Insertion-ordered so the 600-cap below evicts the OLDEST ids, not arbitrary ones. With a plain
        // HashSet, takeLast(600) kept a random subset — recently-seen ids could be dropped and their
        // emails re-ingested as duplicates once the inbox history passed 600.
        val seen = LinkedHashSet(prefs.getStringSet("seen", emptySet()) ?: emptySet())
        var added = 0
        for (id in ids) {
            if (id.isBlank() || seen.contains(id)) continue
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            try {
                val msg = JSONObject(mb)
                val payload = msg.optJSONObject("payload") ?: continue
                val from = header(payload, "From")
                val subject = header(payload, "Subject").ifBlank { "(no subject)" }
                val who = senderName(from)
                val sb = StringBuilder()
                extract(ctx, id, token, payload, sb, 0)
                val bodyText = sb.toString().replace(Regex("\\s+\n"), "\n").trim().take(8000)
                val full = "Subject: $subject\n$bodyText"
                MessageStore.insertOne(ctx, who, "Email", who, "them", full)
                seen.add(id); added++
            } catch (e: Exception) { Log.w(TAG, "gmail parse $id failed", e) }
        }
        if (added > 0) {
            val capped = if (seen.size > 600) seen.toList().takeLast(600).toSet() else seen
            prefs.edit().putStringSet("seen", capped).apply()
            Log.i(TAG, "gmail synced $added new emails to brain")
        }
        return added
    }
}
