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

    private fun guessMime(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".doc") || n.endsWith(".docx") -> "application/msword"
            n.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Send an email WITH file attachments (multipart/mixed MIME) as the user. This is what lets SlyOS
     * send documents itself — résumés, PDFs, images — not just text. Records it to the brain.
     */
    fun sendWithAttachments(ctx: Context, to: String, subject: String, body: String, files: List<java.io.File>): Pair<Boolean, String> {
        val real = files.filter { it.exists() }
        if (real.isEmpty()) return send(ctx, to, subject, body)   // nothing to attach → plain send
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return false to "Google isn't connected."
        val boundary = "slyos_" + System.currentTimeMillis()
        val mime = buildString {
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(encodeHeader(subject)).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n").append(body).append("\r\n")
            for (f in real) {
                val enc = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP).chunked(76).joinToString("\r\n")
                append("--").append(boundary).append("\r\n")
                append("Content-Type: ").append(guessMime(f.name)).append("; name=\"").append(f.name).append("\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n")
                append("Content-Disposition: attachment; filename=\"").append(f.name).append("\"\r\n\r\n")
                append(enc).append("\r\n")
            }
            append("--").append(boundary).append("--")
        }
        val raw = Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val (code, resp) = post("$BASE/messages/send", token, JSONObject().put("raw", raw).toString())
        if (code !in 200..299) {
            Log.e(TAG, "gmail send(attach) $code: ${resp.take(300)}")
            val reason = try { JSONObject(resp).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
            return false to ((reason ?: "send failed") + " ($code)")
        }
        try { MessageStore.insertOne(ctx, to, "Email", to, "me", "Subject: $subject\n$body\n[${real.size} attachment(s)]") } catch (e: Exception) {}
        return true to "Sent to $to with ${real.size} attachment(s) ✓"
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
        val prefs = ctx.getSharedPreferences("slyos_gmail", Context.MODE_PRIVATE)
        val seen = LinkedHashSet(prefs.getStringSet("seen", emptySet()) ?: emptySet())
        // Sync BOTH received AND SENT mail — so "who did I email last?" works. Sent mail is filed as role
        // "me" against the recipient (the To header), received mail as role "them" against the sender.
        var added = 0
        added += syncQuery(ctx, token, "in:inbox newer_than:60d", "them", "From", maxMessages, seen)
        added += syncQuery(ctx, token, "in:sent newer_than:60d", "me", "To", maxMessages, seen)
        if (added > 0) {
            val capped = if (seen.size > 1200) seen.toList().takeLast(1200).toSet() else seen
            prefs.edit().putStringSet("seen", capped).apply()
            Log.i(TAG, "gmail synced $added new emails to brain")
        }
        return added
    }

    /**
     * END-TO-END SIGN-UP SUPPORT: find the newest verification/confirmation email (optionally matching a
     * [hint] like the service name) and return its verification URL, so the screen agent can open it and
     * finish an account sign-up automatically. Read-only, on the user's token. Returns null if none yet.
     */
    fun verificationLink(ctx: Context, hint: String = ""): String? {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return null
        val kw = "(subject:(verify OR confirm OR verification OR activate OR \"confirm your email\") OR verify OR confirm OR activate)"
        val q = (if (hint.isNotBlank()) "($hint) " else "") + "newer_than:1d " + kw
        val (lc, lb) = get("$BASE/messages?maxResults=6&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token)
        if (lc !in 200..299) return null
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return null
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return null }
        for (id in ids) {
            if (id.isBlank()) continue
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            val payload = try { JSONObject(mb).optJSONObject("payload") } catch (e: Exception) { null } ?: continue
            val raw = StringBuilder(); collectRawBodies(payload, raw)
            pickVerifyLink(raw.toString())?.let { return it }
        }
        return null
    }

    private fun collectRawBodies(part: JSONObject, sb: StringBuilder) {
        if (sb.length > 200_000) return
        try {
            val data = part.optJSONObject("body")?.optString("data").orEmpty()
            if (data.isNotEmpty()) sb.append(String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).append("\n")
            val parts = part.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) collectRawBodies(parts.getJSONObject(i), sb)
        } catch (e: Exception) {}
    }

    /** Newest one-time / 2FA CODE from recent mail (for auto-filling verification screens). */
    fun latestCode(ctx: Context, hint: String = ""): String? {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return null
        val q = (if (hint.isNotBlank()) "($hint) " else "") +
            "newer_than:1d (subject:(code OR verification OR OTP OR \"security code\" OR \"one-time\" OR confirm OR sign-in) OR code OR verification OR OTP)"
        val (lc, lb) = get("$BASE/messages?maxResults=6&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token)
        if (lc !in 200..299) return null
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return null
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return null }
        for (id in ids) {
            if (id.isBlank()) continue
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            val payload = try { JSONObject(mb).optJSONObject("payload") } catch (e: Exception) { null } ?: continue
            val raw = StringBuilder(header(payload, "Subject")).append(" ")
            collectRawBodies(payload, raw)
            pickCode(raw.toString())?.let { return it }
        }
        return null
    }

    /** Pull a 4-8 digit one-time code out of text, preferring one near a code/OTP keyword. */
    fun pickCode(text: String): String? {
        val plain = text.replace(Regex("<[^>]+>"), " ")
        Regex("(?i)(?:code|otp|verification|passcode|pin|password)[^0-9]{0,24}(\\d{4,8})").find(plain)?.let { return it.groupValues[1] }
        Regex("(?<![0-9])(\\d{6})(?![0-9])").find(plain)?.let { return it.value }
        Regex("(?<![0-9])(\\d{4,8})(?![0-9])").find(plain)?.let { return it.value }
        return null
    }

    private fun pickVerifyLink(text: String): String? {
        val urls = Regex("https?://[^\\s\"'<>)\\]]+").findAll(text).map { it.value.trimEnd('.', ',', ';', '"', '\'') }.distinct().toList()
        return urls.firstOrNull { Regex("(?i)verif|confirm|activ|validate|/token|/auth|/e/|magic|onelink|action").containsMatchIn(it) }
            ?: urls.firstOrNull { it.length in 25..400 }
    }

    /**
     * P3: scan recent receipt/order/invoice emails and turn the real ones into expense rows (deduped by
     * ExpenseStore hash). Runs each candidate through the vision-schema text extractor; non-receipts (a
     * newsletter that merely says "order") return receipt:false and are skipped. Bounded per run for the
     * free tier. Returns how many NEW expenses were added.
     */
    fun syncReceipts(ctx: Context, maxMessages: Int = 15): Int {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank() || !AgentClient.hasKey()) return 0
        val q = "newer_than:90d (subject:(receipt OR invoice OR \"order confirmation\" OR \"your order\") OR from:(no-reply OR orders OR receipts))"
        val (lc, lb) = get("$BASE/messages?maxResults=$maxMessages&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token)
        if (lc !in 200..299) return 0
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return 0
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return 0 }
        val prefs = ctx.getSharedPreferences("slyos_gmail", Context.MODE_PRIVATE)
        val seen = LinkedHashSet(prefs.getStringSet("seen_receipts", emptySet()) ?: emptySet())
        var added = 0
        for (id in ids) {
            if (id.isBlank() || seen.contains(id)) continue
            seen.add(id)
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            try {
                val payload = JSONObject(mb).optJSONObject("payload") ?: continue
                val subject = header(payload, "Subject")
                val sb = StringBuilder(); extract(ctx, id, token, payload, sb, 0)
                val text = ("Subject: $subject\n" + sb.toString()).trim()
                val r = AgentClient.extractReceiptText(text) ?: continue   // not a real receipt → skip
                // Only save confident, priced receipts — never a garbage $0 / unknown row.
                if (r.total <= 0.0 || r.confidence < 0.55) continue
                val rid = ExpenseStore.record(ctx, r.merchant, r.dateIso, r.total, r.currency, r.tax,
                    r.category, r.itemsJson, "email", "", text.take(1500), r.confidence)
                if (rid > 0) added++
            } catch (e: Exception) {}
        }
        val capped = if (seen.size > 800) seen.toList().takeLast(800).toSet() else seen
        prefs.edit().putStringSet("seen_receipts", capped).apply()
        if (added > 0) Log.i(TAG, "gmail synced $added receipts to expenses")
        return added
    }

    /**
     * Scan recent mail that likely carries a DOCUMENT (attachments, invoices, statements, forms, contracts)
     * and auto-file the real ones into Documents (which also logs to the brain). Text-based (email body +
     * PDF-attachment text). Deduped per run. Returns how many NEW documents were filed.
     */
    fun syncDocs(ctx: Context, maxMessages: Int = 12): Int {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank() || !AgentClient.hasKey()) return 0
        val q = "newer_than:120d (has:attachment OR subject:(invoice OR statement OR form OR contract OR ticket OR policy OR agreement OR document))"
        val (lc, lb) = get("$BASE/messages?maxResults=$maxMessages&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token)
        if (lc !in 200..299) return 0
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return 0
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return 0 }
        val prefs = ctx.getSharedPreferences("slyos_gmail", Context.MODE_PRIVATE)
        val seen = LinkedHashSet(prefs.getStringSet("seen_docs", emptySet()) ?: emptySet())
        var added = 0
        for (id in ids) {
            if (id.isBlank() || seen.contains(id)) continue
            seen.add(id)
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            try {
                val payload = JSONObject(mb).optJSONObject("payload") ?: continue
                val subject = header(payload, "Subject")
                val sb = StringBuilder(); extract(ctx, id, token, payload, sb, 0)
                val text = ("Subject: $subject\n" + sb.toString()).trim()
                if (text.length < 40) continue
                val j = AgentClient.extractFormText(text) ?: continue   // not a real document → skip
                val title = j.optString("title", subject.ifBlank { "Document" })
                DocStore.addText(ctx, j.optString("category", "other"), title,
                    j.optString("summary", ""), j.optJSONObject("fields") ?: JSONObject(), "email")
                try { DocText.add(ctx, title, "email", text) } catch (e: Exception) {}   // full text → readable by agents
                added++
            } catch (e: Exception) {}
        }
        val capped = if (seen.size > 800) seen.toList().takeLast(800).toSet() else seen
        prefs.edit().putStringSet("seen_docs", capped).apply()
        if (added > 0) Log.i(TAG, "gmail filed $added documents")
        return added
    }

    /** Ingest up to [maxMessages] messages matching [q]. [role] = "me"/"them"; [whoHeader] = From/To. */
    private fun syncQuery(ctx: Context, token: String, q: String, role: String, whoHeader: String, maxMessages: Int, seen: LinkedHashSet<String>): Int {
        val (lc, lb) = get("$BASE/messages?maxResults=$maxMessages&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token)
        if (lc !in 200..299) { Log.w(TAG, "gmail list $lc: ${lb.take(160)}"); return 0 }
        val ids = try {
            val arr = JSONObject(lb).optJSONArray("messages") ?: return 0
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (e: Exception) { return 0 }
        var added = 0
        for (id in ids) {
            if (id.isBlank() || seen.contains(id)) continue
            val (mc, mb) = get("$BASE/messages/$id?format=full", token)
            if (mc !in 200..299) continue
            try {
                val msg = JSONObject(mb)
                val payload = msg.optJSONObject("payload") ?: continue
                val subject = header(payload, "Subject").ifBlank { "(no subject)" }
                val who = senderName(header(payload, whoHeader))
                val sb = StringBuilder()
                extract(ctx, id, token, payload, sb, 0)
                val bodyText = sb.toString().replace(Regex("\\s+\n"), "\n").trim().take(8000)
                val prefix = if (role == "me") "Sent to $who — " else ""
                MessageStore.insertOne(ctx, who, "Email", who, role, prefix + "Subject: $subject\n$bodyText")
                seen.add(id); added++
            } catch (e: Exception) { Log.w(TAG, "gmail parse $id failed", e) }
        }
        return added
    }

    // ── INCOMING ATTACHMENTS ──────────────────────────────────────────────────────────────────────
    // What people actually sent you. HomeAI lists these so a contract, invoice or form that landed in
    // your inbox is already there — one tap to fill it, read it, or send it on.

    data class MailAttachment(
        val msgId: String, val attId: String, val name: String, val mime: String,
        val from: String, val subject: String, val ts: Long
    ) {
        val sender: String get() = Regex("^\\s*\"?([^\"<]+?)\"?\\s*<").find(from)?.groupValues?.get(1)?.trim()
            ?: from.substringBefore("@").trim().ifBlank { "someone" }
        /** The bare reply-to address — this is what lets the AI actually answer the email. */
        val email: String get() = Regex("<([^>]+)>").find(from)?.groupValues?.get(1)?.trim()
            ?: Regex("[\\w.+-]+@[\\w.-]+\\.\\w+").find(from)?.value.orEmpty()
        val isPdf: Boolean get() = mime.contains("pdf") || name.endsWith(".pdf", true)
    }

    // A short-lived cache so the nudge, the attach sheet, and every email auto-reply share ONE fetch
    // instead of each firing a dozen Gmail calls. 60s is plenty for a single screen session.
    @Volatile private var attCache: List<MailAttachment> = emptyList()
    @Volatile private var attCacheAt = 0L
    fun recentAttachmentsCached(ctx: Context, max: Int = 10): List<MailAttachment> {
        val now = System.currentTimeMillis()
        if (attCache.isNotEmpty() && now - attCacheAt < 60_000L) return attCache.take(max)
        val fresh = recentAttachments(ctx, maxOf(max, 12))
        if (fresh.isNotEmpty()) { attCache = fresh; attCacheAt = now }
        return fresh.take(max)
    }

    /** The most recent attachments people emailed you. */
    fun recentAttachments(ctx: Context, max: Int = 10): List<MailAttachment> {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return emptyList()
        val out = mutableListOf<MailAttachment>()
        try {
            val (c, body) = get("$BASE/messages?q=has:attachment&maxResults=$max", token)
            if (c != 200) return emptyList()
            val ids = JSONObject(body).optJSONArray("messages") ?: return emptyList()
            for (i in 0 until ids.length()) {
                val id = ids.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isBlank()) continue
                val (mc, mb) = get("$BASE/messages/$id?format=full", token)
                if (mc != 200) continue
                val msg = JSONObject(mb)
                val ts = msg.optString("internalDate").toLongOrNull() ?: 0L
                var from = ""; var subject = ""
                msg.optJSONObject("payload")?.optJSONArray("headers")?.let { hs ->
                    for (h in 0 until hs.length()) {
                        val ho = hs.optJSONObject(h) ?: continue
                        when (ho.optString("name").lowercase()) {
                            "from" -> from = ho.optString("value")
                            "subject" -> subject = ho.optString("value")
                        }
                    }
                }
                collectAttachments(msg.optJSONObject("payload"), id, from, subject, ts, out)
            }
        } catch (e: Exception) { Log.w(TAG, "recentAttachments: ${e.message}") }
        return out.sortedByDescending { it.ts }
    }

    private fun collectAttachments(
        part: JSONObject?, msgId: String, from: String, subject: String, ts: Long, out: MutableList<MailAttachment>
    ) {
        if (part == null) return
        val fn = part.optString("filename")
        val bodyObj = part.optJSONObject("body")
        val aid = bodyObj?.optString("attachmentId").orEmpty()
        if (fn.isNotBlank() && aid.isNotBlank() && !isInlineJunk(part, fn, bodyObj))
            out.add(MailAttachment(msgId, aid, fn, part.optString("mimeType"), from, subject, ts))
        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) collectAttachments(parts.optJSONObject(i), msgId, from, subject, ts, out)
    }

    /** Reject inline/signature clutter — Content-ID embeds, "inline" disposition, tiny logo images. */
    private fun isInlineJunk(part: JSONObject, filename: String, body: JSONObject?): Boolean {
        val mime = part.optString("mimeType").lowercase()
        val size = body?.optInt("size", 0) ?: 0
        var inline = false
        part.optJSONArray("headers")?.let { hs ->
            for (h in 0 until hs.length()) {
                val ho = hs.optJSONObject(h) ?: continue
                when (ho.optString("name").lowercase()) {
                    "content-disposition" -> if (ho.optString("value").trim().lowercase().startsWith("inline")) inline = true
                    "content-id", "x-attachment-id" -> inline = true   // embedded in the body, not a real attachment
                }
            }
        }
        val looksLikeLogo = mime.startsWith("image/") &&
            (size in 1 until 25_000 || Regex("(?i)(image0\\d\\d|logo|signature|icon|footer)").containsMatchIn(filename))
        return inline || looksLikeLogo
    }

    /** Pull one attachment down to cache and hand back a Uri HomeAI can act on. */
    fun downloadAttachment(ctx: Context, a: MailAttachment): android.net.Uri? {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return null
        return try {
            val (c, body) = get("$BASE/messages/${a.msgId}/attachments/${a.attId}", token)
            if (c != 200) return null
            val data = JSONObject(body).optString("data")
            if (data.isBlank()) return null
            val bytes = Base64.decode(data.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            val safe = a.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
            val f = java.io.File(ctx.cacheDir, safe)
            f.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(ctx, "com.agentos.shell.fileprovider", f)
        } catch (e: Exception) { Log.w(TAG, "downloadAttachment: ${e.message}"); null }
    }

    /**
     * The text of the most recent PDF a given person emailed you — so an auto-reply is grounded in the
     * ACTUAL attachment, not a vague retrieval. Returns "" if there's none (then the reply just stays general).
     */
    fun attachmentTextFromSender(ctx: Context, sender: String): String = try {
        if (sender.isBlank()) "" else {
            recentAttachmentsCached(ctx, 12)
                .filter { it.isPdf && (it.from.contains(sender, true) || it.sender.contains(sender, true)) }
                .take(1)
                .mapNotNull { att -> downloadAttachment(ctx, att)?.let { uri -> FileOps.pdfText(ctx, uri).takeIf { t -> t.isNotBlank() } } }
                .joinToString("\n").take(8000)
        }
    } catch (e: Exception) { Log.w(TAG, "attachmentTextFromSender: ${e.message}"); "" }
}
