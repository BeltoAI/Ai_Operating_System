package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The installed Powers on this device. Persists locally, and — crucially — feeds every AI: installed SKILLs
 * are injected into the brain's system prompt, and CONNECT/TOOL powers are surfaced as available capabilities
 * (with their endpoint) so HomeAI and every other surface can use them. One install, live everywhere.
 */
object PowerRegistry {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("slyos_powers", Context.MODE_PRIVATE)

    private fun toJson(p: Power, endpoint: String) = JSONObject()
        .put("id", p.id).put("name", p.name).put("tagline", p.tagline).put("type", p.type.name)
        .put("category", p.category).put("icon", p.icon).put("repo", p.repo).put("stars", p.stars)
        .put("description", p.description).put("instructions", p.instructions).put("endpoint", endpoint)

    private fun fromJson(o: JSONObject): Pair<Power, String> = Power(
        o.optString("id"), o.optString("name"), o.optString("tagline"),
        PowerType.from(o.optString("type")), o.optString("category"), o.optString("icon").ifBlank { "⚡" },
        o.optString("repo"), o.optString("stars"), o.optString("description"), o.optString("instructions")
    ) to o.optString("endpoint")

    private fun load(ctx: Context): MutableList<JSONObject> {
        val raw = prefs(ctx).getString("installed", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw); MutableList(a.length()) { a.getJSONObject(it) }
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(ctx: Context, list: List<JSONObject>) {
        val a = JSONArray(); list.forEach { a.put(it) }
        prefs(ctx).edit().putString("installed", a.toString()).apply()
    }

    fun isInstalled(ctx: Context, id: String) = load(ctx).any { it.optString("id") == id }

    fun install(ctx: Context, power: Power, endpoint: String = "") {
        val list = load(ctx).filterNot { it.optString("id") == power.id }.toMutableList()
        list.add(toJson(power, endpoint))
        save(ctx, list)
    }

    fun setEndpoint(ctx: Context, id: String, endpoint: String) {
        val list = load(ctx)
        list.firstOrNull { it.optString("id") == id }?.put("endpoint", endpoint)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.optString("id") == id })

    /** KILL SWITCH: forget every installed power (skills, endpoints, awareness) in one shot. */
    fun clear(ctx: Context) = prefs(ctx).edit().remove("installed").apply()

    fun installed(ctx: Context): List<Power> = load(ctx).map { fromJson(it).first }

    fun endpointOf(ctx: Context, id: String): String =
        load(ctx).firstOrNull { it.optString("id") == id }?.optString("endpoint") ?: ""

    fun count(ctx: Context): Int = load(ctx).size

    /**
     * What the brain is told about the user's installed Powers. SKILLs contribute their guidance directly;
     * CONNECT/TOOL powers contribute an awareness line (+ endpoint) so the AI knows it can use them.
     * Empty string when nothing is installed — zero prompt cost by default.
     */
    fun brainInstructions(ctx: Context): String {
        val items = load(ctx)
        if (items.isEmpty()) return ""
        val sb = StringBuilder("INSTALLED POWERS (abilities the user added — use them when relevant):\n")
        for (o in items) {
            val (p, endpoint) = fromJson(o)
            when (p.type) {
                PowerType.SKILL -> sb.append("• ${p.name} — ").append(p.instructions.ifBlank { p.description }).append("\n")
                else -> {
                    sb.append("• ${p.name} (${p.type.label}) — power to ${p.tagline}. ${p.description}")
                    if (endpoint.isNotBlank()) sb.append(" Endpoint: $endpoint.")
                    sb.append("\n")
                }
            }
        }
        return sb.toString()
    }
}
