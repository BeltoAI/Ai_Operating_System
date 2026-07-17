package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provisions the backend for a deployed app: runs the agent's generated SQL (create tables, RLS policies, seed
 * data) against the owner's Supabase project via the Management API. This is what makes a shipped site actually
 * WORK on first load instead of hitting empty/missing tables. Needs a Supabase Personal Access Token (the
 * Management API doesn't accept the anon/service key), plus the project ref parsed from the project URL.
 */
object SupabaseAdmin {
    private const val TAG = "SlyOS-Supabase"

    data class Result(val ok: Boolean, val message: String)

    /** Parse the project ref (subdomain) from a Supabase URL, e.g. https://abcd1234.supabase.co → abcd1234. */
    fun projectRef(ctx: Context): String {
        val url = DeployClient.supabaseUrl(ctx)
        return Regex("https?://([a-z0-9]+)\\.supabase\\.co").find(url.lowercase())?.groupValues?.get(1).orEmpty()
    }

    fun configured(ctx: Context): Boolean = MemoryStore.supabasePat(ctx).isNotBlank() && projectRef(ctx).isNotBlank()

    /** Execute arbitrary SQL (DDL + DML) against the project's Postgres via the Supabase Management API. */
    fun runSql(ctx: Context, sql: String): Result {
        val pat = MemoryStore.supabasePat(ctx)
        val ref = projectRef(ctx)
        if (pat.isBlank()) return Result(false, "No Supabase access token — add a Personal Access Token in Brain → API keys to let agents set up the database.")
        if (ref.isBlank()) return Result(false, "No Supabase project URL set — add it in Brain → API keys.")
        if (sql.isBlank()) return Result(false, "No SQL to run.")
        return try {
            val body = JSONObject().put("query", sql).toString()
            val conn = (URL("https://api.supabase.com/v1/projects/$ref/database/query").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $pat")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code in 200..299) Result(true, "Database schema applied ✓")
            else {
                val msg = try { JSONObject(raw).optString("message").ifBlank { raw.take(160) } } catch (e: Exception) { raw.take(160) }
                Log.w(TAG, "runSql $code: $msg"); Result(false, "Supabase error ($code): $msg")
            }
        } catch (e: Exception) { Log.w(TAG, "runSql: ${e.message}"); Result(false, "Couldn't reach Supabase: ${e.message}") }
    }
}
