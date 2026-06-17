package com.agentos.shell.tools

import android.util.Base64
import com.agentos.shell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Posts to X/Twitter via API v2 with OAuth 1.0a user-context signing. Requires four keys in
 * apikey.properties: TWITTER_API_KEY / TWITTER_API_SECRET / TWITTER_ACCESS_TOKEN / TWITTER_ACCESS_SECRET.
 * If they're absent, configured() is false and callers fall back to the share sheet.
 */
object TwitterClient {
    private const val URL_TWEET = "https://api.twitter.com/2/tweets"

    fun configured(): Boolean = BuildConfig.TWITTER_API_KEY.isNotBlank() &&
        BuildConfig.TWITTER_ACCESS_TOKEN.isNotBlank()

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun hmacSha1(base: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(base.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Returns Pair(success, message). */
    fun postTweet(text: String): Pair<Boolean, String> {
        if (!configured()) return false to "X API keys not set."
        val ck = BuildConfig.TWITTER_API_KEY; val cs = BuildConfig.TWITTER_API_SECRET
        val tk = BuildConfig.TWITTER_ACCESS_TOKEN; val ts = BuildConfig.TWITTER_ACCESS_SECRET

        val oauth = sortedMapOf(
            "oauth_consumer_key" to ck,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_token" to tk,
            "oauth_version" to "1.0"
        )
        val paramStr = oauth.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val base = "POST&${enc(URL_TWEET)}&${enc(paramStr)}"
        val signingKey = "${enc(cs)}&${enc(ts)}"
        val signature = hmacSha1(base, signingKey)
        val authParams = (oauth + ("oauth_signature" to signature)).toSortedMap()
        val header = "OAuth " + authParams.entries.joinToString(", ") { "${enc(it.key)}=\"${enc(it.value)}\"" }

        return try {
            val conn = (URL(URL_TWEET).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Authorization", header)
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            if (code in 200..299) true to "Posted to X." else false to explain(code, body)
        } catch (e: Exception) {
            false to "Couldn't reach X: ${e.message}"
        }
    }

    /** Turn X's raw error into something actionable instead of a cryptic code. */
    private fun explain(code: Int, body: String): String {
        val low = body.lowercase()
        return when {
            low.contains("duplicate") ->
                "X blocked this as a duplicate — it won't post the same text twice. Tweak the wording and try again."
            code == 401 ->
                "X rejected the credentials (401). Regenerate your Access Token & Secret AFTER setting the app to Read+Write, then update apikey.properties."
            code == 403 ->
                "X forbade this post (403) — usually the app is Read-only or your access tier can't write. In the developer portal enable OAuth 1.0a Read+Write and regenerate tokens."
            code == 429 ->
                "Rate-limited (429) — the free tier has a small posting cap. Wait a bit and try again."
            else -> "X error $code: ${body.take(160)}"
        }
    }
}
