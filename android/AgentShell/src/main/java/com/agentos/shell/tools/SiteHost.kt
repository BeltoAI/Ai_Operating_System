package com.agentos.shell.tools

import android.util.Log
import com.agentos.shell.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-config, free website hosting for non-technical users. Uploads a self-contained HTML page to SlyOS's OWN
 * Supabase Storage (the app already ships with that connection — no user account, no Vercel token, nothing to
 * set up) and returns a real public URL that renders in any browser. This is the default "ship it" target so
 * agents can hand back an actual live link in the response. Vercel stays available for power users who want a
 * custom domain or their own project.
 */
object SiteHost {
    private const val TAG = "SlyOS-SiteHost"
    private const val BUCKET = "sites"

    fun available(): Boolean = try { BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() } catch (e: Exception) { false }

    data class Result(val ok: Boolean, val url: String, val error: String)

    private fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "site" }

    /** Publish an HTML page and return its live public URL. [name] just makes the path readable. */
    fun publish(html: String, name: String = "site"): Result {
        if (!available()) return Result(false, "", "hosting isn't configured in this build")
        if (html.length < 40) return Result(false, "", "nothing to publish")
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val path = "${slug(name)}-${System.currentTimeMillis().toString(36)}.html"
        return try {
            val conn = (URL("$base/storage/v1/object/$BUCKET/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 40000
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "text/html; charset=utf-8")
                setRequestProperty("x-upsert", "true")
                setRequestProperty("cache-control", "3600")
            }
            conn.outputStream.use { it.write(html.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code in 200..299) Result(true, "$base/storage/v1/object/public/$BUCKET/$path", "")
            else { Log.w(TAG, "publish $code: ${raw.take(160)}"); Result(false, "", "couldn't publish ($code)") }
        } catch (e: Exception) { Log.w(TAG, "publish: ${e.message}"); Result(false, "", "couldn't publish: ${e.message}") }
    }
}
