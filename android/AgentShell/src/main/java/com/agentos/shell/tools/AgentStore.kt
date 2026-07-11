package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import com.agentos.shell.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * THE AGENT STORE — a global catalogue of SlyOS agents.
 *
 * An "agent" is a self-contained HTML/JS mini-app that runs in SlyOS's sandboxed WebView with the SlyOS
 * bridge (the same runtime the Architect builds into). Anyone can publish one: it's just HTML + a manifest.
 * Users browse, install (download the HTML into their local [AppStore]) and run it. Backed by Supabase
 * (free tier): a public `agents` table with row-level security — everyone can READ approved agents, signed-in
 * developers can PUBLISH. See AGENT_STORE.md for the schema + REST contract so any client/dev can build on it.
 */
object AgentStore {
    private const val TAG = "SlyOS-Store"
    private val URL_BASE = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val ANON = BuildConfig.SUPABASE_ANON_KEY

    fun configured(): Boolean = URL_BASE.isNotBlank() && ANON.isNotBlank()

    data class Agent(
        val id: String, val name: String, val author: String, val description: String,
        val category: String, val icon: String, val installs: Int,
        val rating: Double = 0.0, val ratingsCount: Int = 0, val version: Int = 1
    )

    /** A single user review. */
    data class Review(val author: String, val stars: Int, val body: String, val date: String)
    /** A published release in an agent's history. */
    data class Release(val version: Int, val notes: String, val date: String)

    private fun conn(path: String, method: String, bearer: String? = null): HttpURLConnection =
        (URL(URL_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("apikey", ANON)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + (bearer ?: ANON))
        }

    private fun read(c: HttpURLConnection, body: String? = null): Pair<Int, String> = try {
        if (body != null) { c.doOutput = true; OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        code to txt
    } catch (e: Exception) { -1 to (e.message ?: "network error") } finally { c.disconnect() }

    private fun agentFrom(o: JSONObject) = Agent(
        o.optString("id"), o.optString("name").ifBlank { "Untitled" }, o.optString("author").ifBlank { "anon" },
        o.optString("description"), o.optString("category").ifBlank { "Other" },
        o.optString("icon").ifBlank { "🤖" }, o.optInt("installs"),
        o.optDouble("rating", 0.0), o.optInt("ratings_count"), o.optInt("version", 1))

    /** Browse the store. [query] full-text-ish filter, [category] optional. Newest/most-installed first. */
    fun list(query: String = "", category: String = "", limit: Int = 60): List<Agent> {
        if (!configured()) return emptyList()
        val params = StringBuilder("select=id,name,author,description,category,icon,installs,rating,ratings_count,version&approved=eq.true&order=installs.desc,created_at.desc&limit=$limit")
        if (category.isNotBlank()) params.append("&category=eq.").append(URLEncoder.encode(category, "UTF-8"))
        if (query.isNotBlank()) {
            val q = URLEncoder.encode("%${query}%", "UTF-8")
            params.append("&or=(name.ilike.$q,description.ilike.$q,author.ilike.$q)")
        }
        val (code, txt) = read(conn("/rest/v1/agents?$params", "GET"))
        if (code !in 200..299) { Log.w(TAG, "list $code: ${txt.take(120)}"); return emptyList() }
        return try { JSONArray(txt).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::agentFrom) } } } catch (e: Exception) { emptyList() }
    }

    val CATEGORIES = listOf("Productivity", "Fun", "Finance", "Health", "Social", "Utilities", "Games", "Creative", "Other")

    /** Fetch an agent's HTML (only when installing — keeps browsing light). */
    fun code(id: String): String? {
        if (!configured()) return null
        val (c, t) = read(conn("/rest/v1/agents?id=eq.$id&select=code", "GET"))
        if (c !in 200..299) return null
        return try { JSONArray(t).optJSONObject(0)?.optString("code")?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
    }

    /** Install: download the HTML and add it to the local mini-app store; bump the public counter. */
    fun install(ctx: Context, agent: Agent): Long? {
        val html = code(agent.id) ?: return null
        val localId = AppStore.add(ctx, agent.name, html)
        try { read(conn("/rest/v1/rpc/bump_installs", "POST"), JSONObject().put("agent_id", agent.id).toString()) } catch (e: Exception) {}
        return localId
    }

    /** Publish a local mini-app to the store (signed-in developers). Returns (ok, message). */
    fun publish(ctx: Context, name: String, description: String, category: String, icon: String, html: String): Pair<Boolean, String> {
        if (!configured()) return false to "Store backend not set up."
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return false to "Sign in (Settings → Account) to publish."
        val row = JSONObject()
            .put("user_id", AccountStore.userId(ctx))
            .put("name", name).put("author", MemoryStore.ownerName(ctx).ifBlank { AccountStore.email(ctx).substringBefore("@") }.ifBlank { "anon" })
            .put("description", description).put("category", category).put("icon", icon.ifBlank { "🤖" })
            .put("code", html).put("version", 1).put("approved", false)   // pending review by default
        val (code, txt) = read(conn("/rest/v1/agents", "POST").apply { setRequestProperty("Prefer", "return=minimal") }, JSONArray().put(row).toString())
        return if (code in 200..299) true to "Submitted ✓ — it goes live once approved."
        else false to "Publish failed: HTTP $code ${txt.take(80)}"
    }

    // ── Ratings & reviews ────────────────────────────────────────────────────────────────────────

    private fun shortDate(iso: String) = iso.take(10)   // yyyy-MM-dd

    /** All reviews for an agent, newest first. */
    fun reviews(agentId: String, limit: Int = 50): List<Review> {
        if (!configured()) return emptyList()
        val (c, t) = read(conn("/rest/v1/reviews?agent_id=eq.$agentId&select=author,stars,body,created_at&order=created_at.desc&limit=$limit", "GET"))
        if (c !in 200..299) return emptyList()
        return try {
            JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o ->
                Review(o.optString("author").ifBlank { "anon" }, o.optInt("stars"), o.optString("body"), shortDate(o.optString("created_at")))
            } } }
        } catch (e: Exception) { emptyList() }
    }

    /** Post (or update) the signed-in user's review. One review per user per agent (upsert). */
    fun postReview(ctx: Context, agentId: String, stars: Int, body: String): Pair<Boolean, String> {
        if (!configured()) return false to "Store backend not set up."
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return false to "Sign in (Settings → Account) to review."
        val uid = AccountStore.userId(ctx)
        if (uid.isBlank()) return false to "Sign in to review."
        val author = MemoryStore.ownerName(ctx).ifBlank { AccountStore.email(ctx).substringBefore("@") }.ifBlank { "anon" }
        val row = JSONObject().put("agent_id", agentId).put("user_id", uid).put("author", author)
            .put("stars", stars.coerceIn(1, 5)).put("body", body.trim())
        val c = conn("/rest/v1/reviews?on_conflict=agent_id,user_id", "POST", token)
            .apply { setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal") }
        val (code, txt) = read(c, JSONArray().put(row).toString())
        return if (code in 200..299) true to "Thanks for the review!" else false to "Couldn't post review: HTTP $code ${txt.take(60)}"
    }

    // ── Versioned releases ───────────────────────────────────────────────────────────────────────

    /** An agent's release history, newest version first. */
    fun releases(agentId: String, limit: Int = 30): List<Release> {
        if (!configured()) return emptyList()
        val (c, t) = read(conn("/rest/v1/agent_versions?agent_id=eq.$agentId&select=version,notes,created_at&order=version.desc&limit=$limit", "GET"))
        if (c !in 200..299) return emptyList()
        return try {
            JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o ->
                Release(o.optInt("version"), o.optString("notes"), shortDate(o.optString("created_at")))
            } } }
        } catch (e: Exception) { emptyList() }
    }

    /** Ship a new release: atomically bumps the agent's code+version and logs the changelog. Owner only. */
    fun publishRelease(ctx: Context, agentId: String, html: String, notes: String): Pair<Boolean, String> {
        if (!configured()) return false to "Store backend not set up."
        val token = AccountStore.freshAccessToken(ctx)
        if (token.isBlank()) return false to "Sign in to publish a release."
        val body = JSONObject().put("p_agent", agentId).put("p_code", html).put("p_notes", notes.trim())
        val (code, txt) = read(conn("/rest/v1/rpc/publish_release", "POST", token), body.toString())
        return if (code in 200..299) true to "Release v${txt.trim().ifBlank { "?" }} is live ✓"
        else false to "Release failed: HTTP $code ${txt.take(60)}"
    }
}
