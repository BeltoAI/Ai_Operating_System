package com.agentos.shell.tools

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Add any repo" — searches GitHub live and maps repositories into [Power]s, guessing the integration type
 * from the repo's language/topics. This is the infinite tail beneath the curated shelves: any of GitHub's
 * millions of tools can become a Power your phone gains.
 */
object GitHubSearch {
    private const val TAG = "SlyOS-GH"

    /** Search GitHub repositories, most-starred first. Returns Powers ready to preview/install. */
    fun search(query: String, limit: Int = 25): List<Power> {
        if (query.isBlank()) return emptyList()
        Busy.start()   // drive the global edge-loader while we wait on GitHub
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.github.com/search/repositories?q=$q&sort=stars&order=desc&per_page=$limit"
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 12000; readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SlyOS-PowerStore")
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) { Log.w(TAG, "search $code"); return emptyList() }
            val items = JSONObject(txt).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { fromRepo(it) } }
        } catch (e: Exception) { Log.w(TAG, "search failed: ${e.message}"); emptyList() }
        finally { Busy.end() }
    }

    private fun fromRepo(o: JSONObject): Power {
        val full = o.optString("full_name")
        val name = o.optString("name").ifBlank { full.substringAfterLast('/') }
        val desc = o.optString("description").ifBlank { "An open-source project on GitHub." }
        val lang = o.optString("language")
        val topics = o.optJSONArray("topics")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } } ?: emptyList()
        val stars = human(o.optInt("stargazers_count"))
        val type = guessType(lang, topics, desc)
        val tagline = desc.take(60).trim().removeSuffix(".").lowercase()
        return Power("gh:$full", name, tagline, type, "GitHub", type.glyph, full, stars, desc)
    }

    /** Heuristic: skills/agents → SKILL, hostable web apps → CONNECT, libraries/CLIs → TOOL. */
    private fun guessType(lang: String, topics: List<String>, desc: String): PowerType {
        val t = (topics + lang.lowercase() + desc.lowercase())
        fun has(vararg k: String) = k.any { key -> t.any { it.contains(key) } }
        return when {
            has("skill", "agent", "prompt", "claude", "mcp", "llm-agent") -> PowerType.SKILL
            has("self-host", "selfhosted", "docker", "saas", "dashboard", "webapp", "nextjs", "server") -> PowerType.CONNECT
            has("cli", "library", "converter", "ffmpeg", "image", "audio", "pdf", "video") -> PowerType.TOOL
            lang.equals("typescript", true) || lang.equals("go", true) -> PowerType.CONNECT
            else -> PowerType.TOOL
        }
    }

    private fun human(n: Int): String = when {
        n >= 1000 -> "%.0fk".format(n / 1000.0)
        else -> n.toString()
    }
}
