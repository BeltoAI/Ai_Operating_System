package com.agentos.shell.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ships a site LIVE to Vercel end-to-end, straight from a chat. Uses Vercel's inline-files deploy API (no git,
 * no CLI): POST the file contents, get back a public URL. The owner brings a Vercel token (Brain → API keys).
 * Supabase (the app's backend) is wired client-side into the generated HTML using the project's URL + anon key,
 * so the deployed site has a real database/auth without any server to run.
 */
object DeployClient {
    private const val TAG = "SlyOS-Deploy"

    data class Result(val ok: Boolean, val url: String, val error: String)

    /** Supabase creds handed to the generated app so it has a working backend. */
    fun supabaseUrl(ctx: Context): String = MemoryStore.supabaseUrl(ctx).ifBlank { try { com.agentos.shell.BuildConfig.SUPABASE_URL } catch (e: Exception) { "" } }
    fun supabaseAnon(ctx: Context): String = MemoryStore.supabaseAnon(ctx).ifBlank { try { com.agentos.shell.BuildConfig.SUPABASE_ANON_KEY } catch (e: Exception) { "" } }

    private fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "slyos-site" }

    /** Deploy a set of files (path → content) to Vercel production. Returns the live https URL or an error. */
    fun deploy(ctx: Context, name: String, files: Map<String, String>): Result {
        val token = MemoryStore.vercelToken(ctx)
        if (token.isBlank()) return Result(false, "", "No Vercel token — add one in Brain → API keys to deploy.")
        if (files.isEmpty()) return Result(false, "", "Nothing to deploy.")
        return try {
            val body = JSONObject().apply {
                put("name", slug(name))
                put("target", "production")
                put("projectSettings", JSONObject().put("framework", JSONObject.NULL))
                put("files", JSONArray().apply {
                    files.forEach { (path, content) ->
                        put(JSONObject()
                            .put("file", path)
                            .put("data", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                            .put("encoding", "base64"))
                    }
                })
            }.toString()
            val conn = (URL("https://api.vercel.com/v13/deployments?forceNew=1").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (e: Exception) { null } ?: raw.take(160)
                Log.w(TAG, "vercel $code: $msg"); return Result(false, "", "Vercel error ($code): $msg")
            }
            val obj = JSONObject(raw)
            // Prefer a stable production alias (same URL across redeploys) over the per-deploy url.
            val alias = obj.optJSONArray("alias")?.let { if (it.length() > 0) it.optString(0) else "" }.orEmpty()
            val url = alias.ifBlank { obj.optString("url") }
            if (url.isBlank()) Result(false, "", "Vercel didn't return a URL.") else Result(true, "https://$url", "")
        } catch (e: Exception) { Log.w(TAG, "deploy: ${e.message}"); Result(false, "", "Couldn't reach Vercel: ${e.message}") }
    }

    /** Convenience: deploy a single HTML page as index.html. */
    fun deployHtml(ctx: Context, name: String, html: String): Result = deploy(ctx, name, mapOf("index.html" to html))
}
