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
        val embProvider = try { EmbeddingClient.provider(ctx) } catch (e: Exception) { null }
        val semantic = when {
            embProvider == null -> "OFF — needs a free Gemini (or OpenAI) key to embed"
            else -> (if (embedded < 0) "—" else "$embedded") + (if (pending > 0) " (+$pending queued via $embProvider)" else " (all embedded)")
        }
        val agents = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        val agentDocs = try { agents.sumOf { AgentKnowledge.count(ctx, it.id) } } catch (e: Exception) { -1 }
        return listOf(
            Line("Messages in brain", n { MessageStore.count(ctx) }, "chats/emails/notes — grows as you talk & import"),
            Line("Photos understood", if (photos < 0) "—" else "$photos", "on-device gallery indexing (fills over hours)"),
            Line("Semantic memory", semantic, "embeddings for meaning-based recall"),
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

    /** Wiring/health checks — which capabilities are actually connected right now (not counts). */
    fun health(ctx: Context): List<Line> {
        fun ok(b: Boolean) = if (b) "connected" else "not set up"
        val notif = try {
            (android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)
        } catch (e: Exception) { false }
        return listOf(
            Line("Model key", ok(try { AgentClient.hasKey() } catch (e: Exception) { false }), "the brain's LLM"),
            Line("Embeddings", try { EmbeddingClient.provider(ctx) ?: "off — add Gemini/OpenAI key" } catch (e: Exception) { "—" }, "semantic memory needs this"),
            Line("Google / Gmail", ok(try { GoogleAuth.isConnected(ctx) } catch (e: Exception) { false }), "inbox + calendar + contacts"),
            Line("Calendar access", ok(try { CalendarTool.hasPermission(ctx) } catch (e: Exception) { false }), "events + wake-up planner"),
            Line("Mic access", ok(try { SongId.hasMic(ctx) } catch (e: Exception) { false }), "song ID"),
            Line("Notification access", ok(notif), "media widget + notif reading"),
            Line("Telegram", ok(try { TelegramClient.configured() } catch (e: Exception) { false }), "team chat + bot"),
            Line("Account sync", ok(try { AccountStore.signedIn(ctx) } catch (e: Exception) { false }), "cross-device brain")
        )
    }

    /** current value + change since yesterday / last snapshot, e.g. "209 (+42 today)". */
    private fun withDelta(ctx: Context, l: Line): String {
        if (!l.value.trim().matches(Regex("[0-9,]+"))) return l.value   // skip mixed-text values (e.g. semantic, health)
        val cur = l.value.trim().replace(",", "").toIntOrNull() ?: return l.value
        val prev = try { StatsHistory.past(ctx, l.label, 1) } catch (e: Exception) { null }
        val d = if (prev != null) cur - prev else null
        val tag = when { d == null -> ""; d > 0 -> " (+$d today)"; d < 0 -> " ($d today)"; else -> "" }
        return l.value + tag
    }

    /** Plain-text report for the Home AI reply, with day-over-day deltas + wiring health. */
    fun report(ctx: Context): String {
        val counts = lines(ctx).joinToString("\n") { "• ${it.label}: ${withDelta(ctx, it)}  —  ${it.hint}" }
        val wiring = health(ctx).joinToString("\n") { "• ${it.label}: ${it.value}  —  ${it.hint}" }
        val since = try { StatsHistory.days(ctx) } catch (e: Exception) { 0 }
        val trend = if (since > 1) "\n(tracking $since days — deltas are vs. yesterday)" else "\n(first snapshot today — deltas appear tomorrow)"
        return "Brain health check\n\nWHAT'S IN THE BRAIN:\n$counts\n\nWIRING:\n$wiring$trend"
    }

    /** Dump to logcat so it can be pulled with: adb logcat -s SlyOS-Stats:I */
    fun log(ctx: Context) {
        try { lines(ctx).forEach { Log.i(TAG, "${it.label}: ${withDelta(ctx, it)}") } } catch (e: Exception) {}
        try { health(ctx).forEach { Log.i(TAG, "[wiring] ${it.label}: ${it.value}") } } catch (e: Exception) {}
    }
}
