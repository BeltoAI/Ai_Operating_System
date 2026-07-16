package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * A one-shot health check across every brain store, so you can see at a glance whether each part is populating
 * like it should — instead of guessing. Surfaced via the Home AI ("brain status") and logged to logcat on launch
 * (adb logcat -s SlyOS-Stats:I) for a quick pull. Every lookup is defensive: a broken store reports "—", never
 * crashes the report.
 */
object BrainStats {
    private const val TAG = "SlyOS-Stats"

    private fun n(block: () -> Int): String = try { block().toString() } catch (e: Exception) { "—" }

    data class Line(val label: String, val value: String, val hint: String)

    fun lines(ctx: Context): List<Line> {
        val photos = try { PhotoIndex.count(ctx) } catch (e: Exception) { -1 }
        val embedded = try { VectorStore.embeddedCount(ctx) } catch (e: Exception) { -1 }
        val pending = try { VectorStore.pendingCount(ctx) } catch (e: Exception) { -1 }
        val agents = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        val agentDocs = try { agents.sumOf { AgentKnowledge.count(ctx, it.id) } } catch (e: Exception) { -1 }
        return listOf(
            Line("Messages in brain", n { MessageStore.count(ctx) }, "chats/emails/notes — grows as you talk & import"),
            Line("Photos understood", if (photos < 0) "—" else "$photos", "on-device gallery indexing (fills over hours)"),
            Line("Semantic memory", (if (embedded < 0) "—" else "$embedded") + (if (pending > 0) " (+$pending queued)" else ""), "embeddings for meaning-based recall"),
            Line("Memory-graph nodes", n { MemoryGraphStore.nodes.size }, "the map you see on the Memory tab"),
            Line("Filed documents", n { DocStore.list(ctx).size }, "receipts, invoices, decks, scans"),
            Line("Indexed doc text", n { DocText.count(ctx) }, "full-text of docs, searchable by the AI"),
            Line("CRM contacts", n { LeadStore.count(ctx) }, "people your team saved"),
            Line("Expenses logged", n { ExpenseStore.count(ctx) }, "from receipts/invoices"),
            Line("Network / connections", n { ConnectionStore.count(ctx) }, "your relationship graph"),
            Line("Powers installed", n { PowerRegistry.count(ctx) }, "skills added to the brain"),
            Line("AI teammates", agents.size.toString(), "agents on your team"),
            Line("Docs fed to agents", if (agentDocs < 0) "—" else "$agentDocs", "PDFs/knowledge per agent (e.g. Bastardi)")
        )
    }

    /** Plain-text report for the Home AI reply. */
    fun report(ctx: Context): String {
        val ls = lines(ctx)
        val body = ls.joinToString("\n") { "• ${it.label}: ${it.value}  —  ${it.hint}" }
        return "Brain health check:\n$body"
    }

    /** Dump to logcat so it can be pulled with: adb logcat -s SlyOS-Stats:I */
    fun log(ctx: Context) {
        try { lines(ctx).forEach { Log.i(TAG, "${it.label}: ${it.value}") } } catch (e: Exception) {}
    }
}
