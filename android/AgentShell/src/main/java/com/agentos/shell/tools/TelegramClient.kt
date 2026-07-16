package com.agentos.shell.tools

import com.agentos.shell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Telegram Bot API client: long-poll updates, send messages, download files. */
object TelegramClient {
    private val token get() = BuildConfig.TELEGRAM_BOT_TOKEN
    fun configured(): Boolean = token.isNotBlank()
    private fun api(method: String) = "https://api.telegram.org/bot$token/$method"

    data class Update(
        val updateId: Long, val chatId: Long, val text: String, val caption: String,
        val photoFileId: String?, val docFileId: String?, val docName: String,
        val docMime: String, val voiceFileId: String?, val senderName: String = "",
        val newMembers: List<String> = emptyList(), val replyToBot: Boolean = false
    ) {
        val isPdf: Boolean get() = docFileId != null &&
            (docName.endsWith(".pdf", true) || docMime.equals("application/pdf", true))
    }

    private fun get(urlStr: String, readMs: Int = 60000): String? = try {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = readMs
        }
        val code = c.responseCode
        (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }

    /** Long-poll for updates starting at [offset]. Blocks up to ~50s. */
    fun getUpdates(offset: Long): List<Update> {
        val raw = get(api("getUpdates") + "?timeout=50&offset=$offset") ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("result") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val u = arr.getJSONObject(i)
                val msg = u.optJSONObject("message") ?: return@mapNotNull null
                val chat = msg.optJSONObject("chat") ?: return@mapNotNull null
                val photo = msg.optJSONArray("photo")?.let { it.optJSONObject(it.length() - 1)?.optString("file_id") }
                val doc = msg.optJSONObject("document")
                // Who sent it — so the message can be filed in the brain under a real name, not a number.
                val from = msg.optJSONObject("from")
                val name = listOfNotNull(from?.optString("first_name")?.takeIf { it.isNotBlank() },
                        from?.optString("last_name")?.takeIf { it.isNotBlank() })
                    .joinToString(" ").ifBlank {
                        from?.optString("username")?.takeIf { it.isNotBlank() }
                            ?: chat.optString("title").takeIf { it.isNotBlank() } ?: ""
                    }
                val joined = msg.optJSONArray("new_chat_members")?.let { arr ->
                    (0 until arr.length()).mapNotNull { j ->
                        val m2 = arr.optJSONObject(j) ?: return@mapNotNull null
                        if (m2.optBoolean("is_bot", false)) null   // ignore the bot itself joining
                        else listOfNotNull(m2.optString("first_name").takeIf { it.isNotBlank() },
                            m2.optString("last_name").takeIf { it.isNotBlank() }).joinToString(" ")
                            .ifBlank { m2.optString("username") }.takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
                val replyToBot = msg.optJSONObject("reply_to_message")?.optJSONObject("from")?.optBoolean("is_bot", false) ?: false
                Update(
                    u.getLong("update_id"), chat.getLong("id"),
                    msg.optString("text"), msg.optString("caption"),
                    photo, doc?.optString("file_id"), doc?.optString("file_name").orEmpty(),
                    doc?.optString("mime_type").orEmpty(),
                    msg.optJSONObject("voice")?.optString("file_id"), name, joined, replyToBot
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun sendMessage(chatId: Long, text: String) {
        val t = URLEncoder.encode(text.take(4000), "UTF-8")
        get(api("sendMessage") + "?chat_id=$chatId&text=$t", 20000)
    }

    /** Show the native "typing…" indicator (clears itself after ~5s) — cleaner than a placeholder message. */
    fun sendTyping(chatId: Long) { try { get(api("sendChatAction") + "?chat_id=$chatId&action=typing", 10000) } catch (e: Exception) {} }

    // The bot's own @username + display name (for detecting when it's summoned in a group). Cached.
    @Volatile private var meUser: String? = null
    @Volatile private var meName: String? = null
    private fun ensureMe() {
        if (meUser != null) return
        try {
            val raw = get(api("getMe"), 15000) ?: return
            val r = JSONObject(raw).optJSONObject("result") ?: return
            meUser = r.optString("username"); meName = r.optString("first_name")
        } catch (e: Exception) {}
    }
    fun botUsername(): String { ensureMe(); return meUser.orEmpty() }
    fun botName(): String { ensureMe(); return meName.orEmpty() }

    /** Upload a document (PDF etc.) into a chat via multipart/form-data. */
    fun sendDocument(chatId: Long, file: java.io.File, caption: String = ""): Boolean {
        if (!file.exists()) return false
        return try {
            val boundary = "----slyos${System.currentTimeMillis()}"
            val conn = (URL(api("sendDocument")).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 90000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            java.io.DataOutputStream(conn.outputStream.buffered()).use { dos ->
                fun field(name: String, value: String) {
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    dos.write(value.toByteArray(Charsets.UTF_8)); dos.writeBytes("\r\n")
                }
                field("chat_id", chatId.toString())
                if (caption.isNotBlank()) field("caption", caption.take(1000))
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n")
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(dos) }
                dos.writeBytes("\r\n--$boundary--\r\n"); dos.flush()
            }
            conn.responseCode in 200..299
        } catch (e: Exception) { false }
    }

    /** Download a file by file_id; returns its bytes or null. */
    fun downloadFile(fileId: String): ByteArray? {
        val info = get(api("getFile") + "?file_id=$fileId", 20000) ?: return null
        val path = try { JSONObject(info).optJSONObject("result")?.optString("file_path") } catch (e: Exception) { null }
            ?: return null
        return try {
            (URL("https://api.telegram.org/file/bot$token/$path").openConnection() as HttpURLConnection)
                .inputStream.use { it.readBytes() }
        } catch (e: Exception) { null }
    }
}
