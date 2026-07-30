package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent catalogue — what replaces the Power store.
 *
 * The Power store pointed at GitHub repos and tried to make them run: a curated list of 33, a live
 * search, distillation into skills, and for anything that was a real program, Termux. Every layer of
 * that was a negotiation with somebody else's build system, and the honest verdict after a day of
 * fixing it was that the advanced half works and cannot be watched, while the simple half installs
 * a paragraph of prose.
 *
 * An agent is a different thing entirely, and the design was already written down in AGENT_STORE.md
 * before I started rebuilding it from scratch: **one self-contained HTML file** that runs in the
 * sandboxed WebView SlyOS already has, through the `SlyOS` bridge it already exposes — save, load,
 * memory, remember, ask. No build step, no dependencies, no shell, nothing to compile. If you can
 * write a web page you can ship an agent, and it arrives at the same quality as a built-in screen
 * because it runs in the same runtime as the Architect's own output.
 *
 * That solves the three things the Power store never could:
 *
 *  - **It always works.** No repo to clone, no wheel that has no aarch64 build, no five-minute
 *    install to stare at. An agent is text; installing it is a write to local storage.
 *  - **It can be reviewed.** `approved` gates the listing, so a human reads the HTML before anyone
 *    sees it. You cannot meaningfully review a dependency tree.
 *  - **It can be sold.** Versions, ratings and an install count are rows in a table rather than
 *    properties of a stranger's repository.
 *
 * Supabase holds the catalogue; the phone holds what it installed. Reading is anonymous, because a
 * store nobody can browse before signing up is a store nobody browses.
 */
object AgentCatalogue {

    data class Listing(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val category: String,
        val icon: String,
        val installs: Int,
        val rating: Double,
        val ratingsCount: Int,
        val version: Int,
        /** The whole agent. Fetched with the listing — they are a few kilobytes of text. */
        val code: String
    )

    val CATEGORIES = listOf("All", "Work", "Money", "Health", "Social", "Fun", "Other")

    /**
     * Browse approved agents.
     *
     * Anonymous on purpose: the RLS policy in AGENT_STORE.md already allows reading approved rows
     * without a session, and requiring a login to look at a shelf is how a store gets abandoned
     * before anybody sees what is on it.
     */
    fun browse(ctx: Context, category: String = "All", limit: Int = 50): List<Listing> {
        if (!SupabaseClient.configured()) return emptyList()
        return try {
            val q = buildString {
                append("select=id,name,author,description,category,icon,installs,rating,")
                append("ratings_count,version,code&approved=eq.true")
                if (category != "All") append("&category=eq.").append(category)
                append("&order=installs.desc&limit=").append(limit)
            }
            parse(SupabaseClient.getAnon("agents", q))
        } catch (e: Exception) { emptyList() }
    }

    fun search(ctx: Context, query: String, limit: Int = 30): List<Listing> {
        val t = query.trim()
        if (t.length < 2) return emptyList()
        if (!SupabaseClient.configured()) return emptyList()
        return try {
            val enc = java.net.URLEncoder.encode("%$t%", "UTF-8")
            val q = "select=id,name,author,description,category,icon,installs,rating," +
                "ratings_count,version,code&approved=eq.true" +
                "&or=(name.ilike.$enc,description.ilike.$enc)&limit=$limit"
            parse(SupabaseClient.getAnon("agents", q))
        } catch (e: Exception) { emptyList() }
    }

    private fun parse(json: String): List<Listing> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Listing(
                    o.optString("id"), o.optString("name"), o.optString("author"),
                    o.optString("description"), o.optString("category").ifBlank { "Other" },
                    o.optString("icon").ifBlank { "◆" },
                    o.optInt("installs"), o.optDouble("rating", 0.0),
                    o.optInt("ratings_count"), o.optInt("version", 1),
                    o.optString("code"))
            }
        }.filter { it.name.isNotBlank() && it.code.length > 40 }
    } catch (e: Exception) { emptyList() }

    /**
     * Install: write the HTML locally and bump the public counter.
     *
     * Nothing can fail halfway. The agent is already in hand when this is called — it came down with
     * the listing — so installing is a local write, and the count is a courtesy to the author that
     * is allowed to fail silently. Compare the Power store, where "install" meant cloning a repo and
     * hoping pip agreed.
     */
    fun install(ctx: Context, l: Listing): Long {
        val id = AppStore.add(ctx, l.name, l.code)
        try { SupabaseClient.rpc("bump_installs", JSONObject().put("agent_id", l.id)) } catch (e: Exception) {}
        // Into the brain, so "what can my phone do now" is answerable and the assistant knows what
        // it has been given — the same wiring Powers had, which was the one part of it worth keeping.
        try {
            Brain.remember(ctx, "note", "Installed agent: ${l.name}",
                "${l.name} by ${l.author.ifBlank { "unknown" }} — ${l.description}", role = "system")
        } catch (e: Exception) {}
        return id
    }

    /** Already on this phone? Matched on name, since a local app carries no catalogue id. */
    fun installed(ctx: Context, l: Listing): Boolean =
        try { AppStore.load(ctx).any { it.name.equals(l.name, true) } } catch (e: Exception) { false }

    /**
     * Publish one of your own.
     *
     * Lands as `approved = false`, so it is yours to test and invisible to everybody else until a
     * human has read the HTML. That review step is the whole reason this can be opened to strangers
     * at all — a self-contained page is something a person can actually read in two minutes.
     */
    fun publish(ctx: Context, name: String, description: String, category: String,
                html: String): Pair<Boolean, String> {
        if (!AccountStore.signedIn(ctx)) return false to "Sign in to publish."
        val token = AccountStore.freshAccessToken(ctx)
        val uid = AccountStore.userId(ctx)
        if (token.isBlank() || uid.isBlank()) return false to "Session expired — sign in again."
        if (html.length < 60) return false to "That agent has no code in it."
        return try {
            val row = JSONObject()
                .put("user_id", uid).put("name", name.trim())
                .put("author", try { MemoryStore.ownerName(ctx) } catch (e: Exception) { "" })
                .put("description", description.trim())
                .put("category", category).put("code", html)
            val ok = SupabaseClient.upsert("agents", token, JSONArray().put(row))
            if (ok) true to "Submitted for review ✓ — it's on your phone now, and public once approved."
            else false to "Couldn't submit — check your connection."
        } catch (e: Exception) { false to (e.message ?: "couldn't submit") }
    }
}
