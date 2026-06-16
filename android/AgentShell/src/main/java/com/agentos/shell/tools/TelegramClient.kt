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
        val photoFileId: String?, val docFileId: String?, val docName: String, val voiceFileId: String?
    )

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
                Update(
                    u.getLong("update_id"), chat.getLong("id"),
                    msg.optString("text"), msg.optString("caption"),
                    photo, doc?.optString("file_id"), doc?.optString("file_name").orEmpty(),
                    msg.optJSONObject("voice")?.optString("file_id")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun sendMessage(chatId: Long, text: String) {
        val t = URLEncoder.encode(text.take(4000), "UTF-8")
        get(api("sendMessage") + "?chat_id=$chatId&text=$t", 20000)
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
