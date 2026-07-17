package com.agentos.shell.tools

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Ships a site LIVE to Netlify and returns a RENDERED public URL. Personal Netlify tokens can create sites with
 * no team-role wall (unlike Vercel), so this is our reliable free host. Uses Netlify's canonical file-digest
 * deploy: create site → declare files by SHA1 → upload the ones Netlify wants. Netlify serves each file by its
 * extension, so index.html renders as text/html (unlike a raw upload that shows source).
 */
object NetlifyClient {
    private const val TAG = "SlyOS-Netlify"
    data class Result(val ok: Boolean, val url: String, val error: String)

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun req(method: String, url: String, token: String, contentType: String?, body: ByteArray?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) doOutput = true
        }
        if (body != null) conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return code to raw
    }

    /** Deploy files to a NEW Netlify site via the file-digest API; returns the live rendered URL. */
    fun deploy(token: String, files: Map<String, String>): Result {
        if (token.isBlank()) return Result(false, "", "No Netlify token set.")
        if (files.isEmpty()) return Result(false, "", "Nothing to deploy.")
        return try {
            // 1) create a site (auto-named subdomain).
            val (sc, sraw) = req("POST", "https://api.netlify.com/api/v1/sites", token, "application/json", "{}".toByteArray())
            if (sc !in 200..299) return Result(false, "", "Netlify create-site failed ($sc): ${errMsg(sraw)}")
            val site = JSONObject(sraw)
            val siteId = site.optString("id")
            val siteUrl = site.optString("ssl_url").ifBlank { site.optString("url") }
            if (siteId.isBlank()) return Result(false, "", "Netlify didn't return a site id.")
            // 2) declare the deploy as a file→sha1 manifest (paths must start with /).
            val shaByPath = files.mapKeys { if (it.key.startsWith("/")) it.key else "/${it.key}" }.mapValues { sha1(it.value) }
            val manifest = JSONObject().apply { shaByPath.forEach { (p, sha) -> put(p, sha) } }
            val (dc, draw) = req("POST", "https://api.netlify.com/api/v1/sites/$siteId/deploys", token, "application/json",
                JSONObject().put("files", manifest).toString().toByteArray())
            if (dc !in 200..299) return Result(false, "", "Netlify deploy failed ($dc): ${errMsg(draw)}")
            val deploy = JSONObject(draw)
            val deployId = deploy.optString("id")
            val required = deploy.optJSONArray("required")
            val requiredShas = (0 until (required?.length() ?: 0)).map { required!!.optString(it) }.toSet()
            // 3) upload each file Netlify still needs (by sha). Served content-type comes from the file extension.
            shaByPath.forEach { (p, sha) ->
                if (requiredShas.isEmpty() || sha in requiredShas) {
                    val content = files[p.removePrefix("/")] ?: files[p] ?: return@forEach
                    val (uc, uraw) = req("PUT", "https://api.netlify.com/api/v1/deploys/$deployId/files${p}", token,
                        "application/octet-stream", content.toByteArray(Charsets.UTF_8))
                    if (uc !in 200..299) return Result(false, "", "Netlify upload failed ($uc): ${errMsg(uraw)}")
                }
            }
            Result(true, deploy.optString("ssl_url").ifBlank { siteUrl }, "")
        } catch (e: Exception) { Log.w(TAG, "deploy: ${e.message}"); Result(false, "", "Couldn't reach Netlify: ${e.message}") }
    }

    fun deployHtml(token: String, html: String): Result = deploy(token, mapOf("index.html" to html))

    private fun errMsg(raw: String): String = try { JSONObject(raw).optString("message").ifBlank { raw.take(140) } } catch (e: Exception) { raw.take(140) }
}
