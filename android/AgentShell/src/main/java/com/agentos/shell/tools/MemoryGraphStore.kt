package com.agentos.shell.tools

import android.content.Context
import java.util.Random
import kotlin.math.sqrt

/** Nodes + edges for the Memory graph, built from REAL data, with a one-time force layout. */
object MemoryGraphStore {
    class Node(
        val id: Int, val key: String, val type: String, val label: String, val content: String,
        val source: String, val strength: Float, val recency: Float, var pinned: Boolean,
        var x: Float = 0f, var y: Float = 0f
    )
    data class Edge(val a: Int, val b: Int)

    val nodes = ArrayList<Node>()
    val edges = ArrayList<Edge>()

    private const val PREF = "slyos_memgraph"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun forgotten(ctx: Context): Set<String> = prefs(ctx).getStringSet("forgotten", emptySet()) ?: emptySet()
    fun forget(ctx: Context, key: String) {
        prefs(ctx).edit().putStringSet("forgotten", forgotten(ctx) + key).apply()
        rebuild(ctx)
    }

    private fun n(key: String, type: String, label: String, content: String, source: String, s: Float, r: Float, pin: Boolean): Int {
        nodes.add(Node(nodes.size, key, type, label, content, source, s, r, pin)); return nodes.size - 1
    }
    private fun e(a: Int, b: Int) { edges.add(Edge(a, b)) }

    /** Memories as plain lines, for AI search (excludes the hub). */
    fun memoryLines(): List<String> =
        nodes.filter { it.type != "hub" }.map { "${it.label}: ${it.content} (${it.source})" }

    fun connections(id: Int): List<Node> =
        edges.filter { it.a == id || it.b == id }.map { nodes[if (it.a == id) it.b else it.a] }

    /** True if there are no real memories yet (only the hub, or nothing). */
    fun isEmpty(): Boolean = nodes.count { it.type != "hub" } == 0

    fun rebuild(ctx: Context) {
        nodes.clear(); edges.clear()
        val forg = forgotten(ctx)
        val hub = n("hub", "hub", "SlyOS", "Your second brain.", "Core", 1f, 1f, true)

        // People + messages, grouped by sender (newest first in the store).
        NotificationStore.notes.groupBy { it.title.ifBlank { it.app } }.forEach { (sender, list) ->
            val key = "msg:$sender"
            if (sender.isBlank() || key in forg) return@forEach
            val latest = list.first()
            val id = n(key, "person", sender, latest.text, latest.app,
                (0.45f + 0.08f * list.size).coerceAtMost(0.9f), 0.92f, false)
            e(hub, id)
        }
        // Facts the agent learned about you.
        MemoryStore.about(ctx).split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { i, line ->
            val key = "fact:$i"
            if (key in forg) return@forEachIndexed
            e(hub, n(key, "idea", if (line.length > 34) line.take(33) + "…" else line, line, "About you", 0.6f, 0.5f, false))
        }
        // Checklist items.
        ChecklistStore.load(ctx).forEach {
            val key = "task:${it.id}"
            if (key in forg) return@forEach
            e(hub, n(key, "task", it.text, if (it.done) "Completed" else "To do", "Checklist", 0.55f, 0.6f, false))
        }
        // Captured prompts, responses and moments — link children to their parent if present.
        val idByKey = HashMap<String, Int>()
        MemoryLog.load(ctx).forEach { ev ->
            val key = "log:${ev.id}"
            if (key in forg) return@forEach
            val nid = n(key, ev.type, ev.label, ev.content, ev.source, 0.5f, 0.7f, false)
            idByKey[key] = nid
            val parentNid = ev.parent?.let { idByKey[it] }
            e(parentNid ?: hub, nid)
        }
        layout()
    }

    private fun layout() {
        val rnd = Random(7)
        nodes.forEach { it.x = (rnd.nextFloat() - .5f) * 280; it.y = (rnd.nextFloat() - .5f) * 280 }
        repeat(400) {
            for (i in nodes.indices) {
                val a = nodes[i]
                for (j in i + 1 until nodes.size) {
                    val b = nodes[j]
                    val dx = a.x - b.x; val dy = a.y - b.y; val d2 = dx * dx + dy * dy + .01f
                    val d = sqrt(d2); val rep = 2600f / d2; val ux = dx / d; val uy = dy / d
                    a.x += ux * rep * .5f; a.y += uy * rep * .5f; b.x -= ux * rep * .5f; b.y -= uy * rep * .5f
                }
            }
            edges.forEach {
                val a = nodes[it.a]; val b = nodes[it.b]
                val dx = b.x - a.x; val dy = b.y - a.y; val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val f = (d - 95f) * .02f; val ux = dx / d; val uy = dy / d
                a.x += ux * f; a.y += uy * f; b.x -= ux * f; b.y -= uy * f
            }
        }
        if (nodes.isNotEmpty()) {
            val cx = nodes.map { it.x }.average().toFloat(); val cy = nodes.map { it.y }.average().toFloat()
            nodes.forEach { it.x -= cx; it.y -= cy }
        }
    }
}
