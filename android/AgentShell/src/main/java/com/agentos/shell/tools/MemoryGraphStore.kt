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

        // Each conversation = ONE clean person node (sized by message count). The messages
        // themselves are revealed in the detail panel when you tap the person — keeping the
        // graph calm and Obsidian-like instead of an explosion of message dots.
        // People come from the message DB (imported + live) — top contacts by volume, capped so the
        // O(n²) force-layout never freezes. Everyone else still lives in the DB (Ask finds them).
        val peopleByApp = HashMap<String, MutableList<Int>>()
        MessageStore.topContacts(ctx, 45)
            .filter { "person:${it.first}" !in forg && it.first.isNotBlank() }
            .forEach { (contact, cnt) ->
                val content = "$cnt message" + (if (cnt != 1) "s" else "")
                e(hub, n("person:$contact", "person", contact, content, "Chats", (0.5f + 0.02f * cnt).coerceAtMost(0.95f), 0.9f, false))
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
        // Research papers you've generated.
        PaperStore.list(ctx).forEach { p ->
            val key = "paper:${p.id}"
            if (key in forg) return@forEach
            e(hub, n(key, "paper", p.title, "Research paper", "Research", 0.7f, 0.6f, false))
        }
        // On-screen recall, grouped into ONE node per app, and WIRED to the people you talk to in
        // that app — so messaging, social and screen activity all connect instead of floating apart.
        if (MemoryStore.recallEnabled(ctx)) {
            InteractionStore.appCounts(ctx).take(16).forEach { (app, cnt) ->
                val key = "recall:$app"
                if (key in forg) return@forEach
                val rid = n(key, "recall", app, "$cnt on-screen capture" + (if (cnt != 1) "s" else ""),
                    "Total recall", (0.5f + 0.03f * cnt).coerceAtMost(0.9f), 0.8f, false)
                e(hub, rid)
                peopleByApp[app]?.forEach { pid -> e(pid, rid) }   // connect app ↔ its conversations
            }
        }
        // Your imported network as ONE node (20k individual dots would bury the brain + freeze layout).
        val connCount = ConnectionStore.count(ctx)
        if (connCount > 0 && "network:linkedin" !in forg) {
            val msg = ConnectionStore.messagedCount(ctx)
            val never = ConnectionStore.neverReachedOut(ctx).size
            e(hub, n("network:linkedin", "network", "LinkedIn network",
                "$connCount connections · messaged $msg · $never to reach", "Network", 0.95f, 0.75f, false))
        }
        // Every captured prompt, response and moment becomes a memory, chained to its parent so the
        // whole history stays connected (not just the last few).
        val idByKey = HashMap<String, Int>()
        MemoryLog.load(ctx).takeLast(50).forEach { ev ->
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
        nodes.forEach { it.x = (rnd.nextFloat() - .5f) * 460; it.y = (rnd.nextFloat() - .5f) * 460 }
        val minGap = 64f      // keep nodes from overlapping
        repeat(600) {
            for (i in nodes.indices) {
                val a = nodes[i]
                for (j in i + 1 until nodes.size) {
                    val b = nodes[j]
                    val dx = a.x - b.x; val dy = a.y - b.y; val d2 = dx * dx + dy * dy + .01f
                    val d = sqrt(d2); val rep = 5200f / d2; val ux = dx / d; val uy = dy / d
                    a.x += ux * rep * .5f; a.y += uy * rep * .5f; b.x -= ux * rep * .5f; b.y -= uy * rep * .5f
                    if (d < minGap) {
                        val push = (minGap - d) * .5f
                        a.x += ux * push; a.y += uy * push; b.x -= ux * push; b.y -= uy * push
                    }
                }
            }
            edges.forEach {
                val a = nodes[it.a]; val b = nodes[it.b]
                val dx = b.x - a.x; val dy = b.y - a.y; val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val f = (d - 150f) * .015f; val ux = dx / d; val uy = dy / d
                a.x += ux * f; a.y += uy * f; b.x -= ux * f; b.y -= uy * f
            }
        }
        if (nodes.isNotEmpty()) {
            val cx = nodes.map { it.x }.average().toFloat(); val cy = nodes.map { it.y }.average().toFloat()
            nodes.forEach { it.x -= cx; it.y -= cy }
        }
    }
}
