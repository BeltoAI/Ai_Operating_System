package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * The current working design per agent — the HTML source (the editable master), its rendered PDF path, and
 * metadata. Because the HTML is kept, "make the title bigger / add a pricing slide" edits the SAME document
 * and re-renders, so you iterate in chat until it's perfect. The PDFs are also filed to the SlyOS folder + brain.
 */
object DesignStore {
    private const val PREFS = "slyos_designs"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Doc(val title: String, val kind: String, val html: String, val pdfPath: String, val ts: Long)

    fun set(ctx: Context, empId: String, title: String, kind: String, html: String, pdfPath: String) {
        val o = JSONObject().put("title", title).put("kind", kind).put("html", html).put("pdf", pdfPath).put("ts", System.currentTimeMillis())
        p(ctx).edit().putString(empId, o.toString()).apply()
    }

    fun get(ctx: Context, empId: String): Doc? = try {
        val s = p(ctx).getString(empId, null) ?: return null
        val o = JSONObject(s)
        Doc(o.optString("title"), o.optString("kind"), o.optString("html"), o.optString("pdf"), o.optLong("ts"))
    } catch (e: Exception) { null }

    fun clear(ctx: Context, empId: String) = p(ctx).edit().remove(empId).apply()
}
