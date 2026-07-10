package com.agentos.shell.tools

import android.util.Log
import com.agentos.shell.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Supabase REST client — Auth (GoTrue) + Data (PostgREST). Matches ACCOUNT_AND_SYNC.md exactly, so
 * other clients can mirror it. No SDK; plain HTTPS so the app stays light. All calls are blocking (run on
 * a background thread by callers).
 */
object SupabaseClient {
    private const val TAG = "SlyOS-Supabase"
    private val URL_BASE = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val ANON = BuildConfig.SUPABASE_ANON_KEY

    fun configured(): Boolean = URL_BASE.isNotBlank() && ANON.isNotBlank()

    /** Last REST error (HTTP code + body), for surfacing a real reason in the UI. */
    @Volatile var lastError: String = ""

    data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
    data class AuthResult(val ok: Boolean, val session: Session? = null, val error: String = "")

    private fun open(path: String, method: String, bearer: String? = null): HttpURLConnection {
        val c = (URL(URL_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("apikey", ANON)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + (bearer ?: ANON))
        }
        return c
    }

    private fun send(c: HttpURLConnection, body: String?): Pair<Int, String> {
        return try {
            if (body != null) { c.doOutput = true; OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) } }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to txt
        } catch (e: Exception) { Log.w(TAG, "http fail", e); -1 to (e.message ?: "network error") } finally { c.disconnect() }
    }

    private fun parseSession(json: String): Session? = try {
        val o = JSONObject(json)
        val user = o.optJSONObject("user")
        val id = user?.optString("id").orEmpty()
        val email = user?.optString("email").orEmpty()
        val at = o.optString("access_token"); val rt = o.optString("refresh_token")
        if (at.isBlank() || id.isBlank()) null else Session(at, rt, id, email)
    } catch (e: Exception) { null }

    private fun errOf(json: String): String = try {
        val o = JSONObject(json)
        o.optString("msg").ifBlank { o.optString("error_description").ifBlank { o.optString("error").ifBlank { o.optString("message") } } }
    } catch (e: Exception) { json.take(140) }.ifBlank { "Something went wrong." }

    fun signUp(email: String, password: String): AuthResult {
        if (!configured()) return AuthResult(false, error = "Account backend not configured.")
        val (code, txt) = send(open("/auth/v1/signup", "POST"), JSONObject().put("email", email).put("password", password).toString())
        val s = parseSession(txt)
        // With email confirmation ON, signup returns no session — that's still success (pending confirm).
        return if (code in 200..299) AuthResult(true, s) else AuthResult(false, error = errOf(txt))
    }

    fun signIn(email: String, password: String): AuthResult {
        if (!configured()) return AuthResult(false, error = "Account backend not configured.")
        val (code, txt) = send(open("/auth/v1/token?grant_type=password", "POST"),
            JSONObject().put("email", email).put("password", password).toString())
        val s = parseSession(txt)
        return if (code in 200..299 && s != null) AuthResult(true, s) else AuthResult(false, error = errOf(txt))
    }

    fun refresh(refreshToken: String): Session? {
        if (!configured()) return null
        val (code, txt) = send(open("/auth/v1/token?grant_type=refresh_token", "POST"),
            JSONObject().put("refresh_token", refreshToken).toString())
        return if (code in 200..299) parseSession(txt) else null
    }

    fun signOut(accessToken: String) {
        try { send(open("/auth/v1/logout", "POST", accessToken), "{}") } catch (e: Exception) {}
    }

    // ---- Data (PostgREST) --------------------------------------------------------------------------
    /** Upsert rows (last-write-wins on the table's unique key). [rows] is a JSON array of objects. */
    fun upsert(table: String, accessToken: String, rows: JSONArray): Boolean {
        if (!configured() || rows.length() == 0) return false
        val c = open("/rest/v1/$table", "POST", accessToken)
        c.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        val (code, txt) = send(c, rows.toString())
        if (code !in 200..299) lastError = "HTTP $code ${txt.take(160)}"
        return code in 200..299
    }

    /** Pull rows for the user changed since [sinceMs]. Returns the parsed array (empty on failure). */
    fun pull(table: String, accessToken: String, userId: String, sinceMs: Long): JSONArray {
        if (!configured()) return JSONArray()
        val path = "/rest/v1/$table?user_id=eq.$userId&updated_at=gt.$sinceMs&order=updated_at.asc"
        val (code, txt) = send(open(path, "GET", accessToken), null)
        return try { if (code in 200..299) JSONArray(txt) else JSONArray() } catch (e: Exception) { JSONArray() }
    }
}
