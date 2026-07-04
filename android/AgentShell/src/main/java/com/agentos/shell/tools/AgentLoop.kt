package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1 — the real agentic tool-use loop for the MAIN path (Home + voice), promoted from the Cowork pattern.
 *
 * The model may either ANSWER, or call ONE tool; SlyOS executes it, feeds the result back as the next
 * turn, and repeats until the model answers or [MAX_TURNS] is hit. It's provider-agnostic (plain-text
 * protocol, works on Claude / GPT / Gemini — no native tool-use API needed).
 *
 * Side-effect tools route through [ToolRouter.executeActions] with the caller's [userInitiated] flag, so
 * the code-level action gate + OutboundGuard + confirm/trust gates still apply — autonomous/bot callers
 * can never auto-fire gated actions through the loop.
 */
object AgentLoop {
    private const val MAX_TURNS = 5
    private const val TAG = "SlyOS"

    /** [answer] is what to say to the user; [actions] are the side-effect tools actually executed. */
    data class Result(val answer: String, val actions: List<AgentAction>)

    private val READ_TOOLS = setOf("web_search", "memory_search", "find_contact", "calendar_lookup", "read_url", "expense_lookup")

    private fun system(memory: String): String {
        val now = java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        return """
You are SlyOS — the user's on-phone agent, acting AS them. Solve the request step by step. On each turn reply
with EXACTLY ONE block and nothing else:

Call a tool:
TOOL <name>
ARG: <one line, or JSON>

Or finish:
ANSWER
<final reply to the user, in their voice, short and natural>

READ tools (I return text for you to use):
- web_search      ARG: search query        → current facts from the live web (use for anything time-sensitive)
- memory_search   ARG: what to recall       → matching memories from the user's brain (people, emails, facts)
- find_contact    ARG: a person's name      → their phone number if in contacts
- calendar_lookup ARG: (empty)              → the user's upcoming events
- expense_lookup  ARG: {"range":"this month","category":"Food"} → real spending totals from their receipts
                    (range: "this month"|"last month"|"this year"|"last 30 days"|"all"; category optional)

ACTION tools (these DO things; I return a confirmation):
- send_sms   ARG: {"name":"Alex","body":"..."}
- message    ARG: {"name":"Alex","body":"...","app":"whatsapp"}
- send_email ARG: {"to":"a@x.com","subject":"...","body":"..."}
- add_event  ARG: {"title":"...","start":"2026-07-05T16:00","end":"2026-07-05T16:30","attendees":["a@x.com"]}
- remind     ARG: {"text":"call mom","in":1200}  OR  {"text":"...","at":"2026-07-05T15:00"}
- create_sheet ARG: {"title":"…","rows":[["Merchant","Category","Total"],["Costco","Food","82.10"]]} (real Google Sheet)
- create_pdf   ARG: {"title":"…","content":"the full report text"} (real PDF saved to Downloads)

RULES: prefer web_search over guessing on facts. Chain steps (e.g. find_contact or memory_search to get an
email, THEN send_email). For any SPENDING question ("how much did I spend on food this month", "where's my
money going", "give me a spending review") use expense_lookup for the real numbers — never guess. For a
"spending review/report", narrate the biggest categories + notable changes + any subscriptions, then offer
(or, if they asked for a sheet/PDF, create) create_sheet or create_pdf with the itemized totals.
If a tool result says it can't (no contact, nothing found), do NOT invent — put a
short clarifying question in ANSWER. Never send a message/email or add an event unless the user clearly asked
for it. Keep ANSWER concise and human.
Current time: $now.
${if (memory.isNotBlank()) "About the user (draw on this): ${memory.take(1600)}" else ""}
""".trim()
    }

    fun run(
        ctx: Context, prompt: String, memory: String,
        history: List<Pair<String, String>> = emptyList(),
        userInitiated: Boolean = true
    ): Result {
        val sys = system(memory)
        val messages = JSONArray()
        history.takeLast(10).forEach { (u, a) ->   // P4: longer memory of the session
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val done = ArrayList<AgentAction>()
        var turn = 0
        while (turn++ < MAX_TURNS) {
            val raw = AgentClient.loopTurn(sys, messages)
            val call = parseTool(raw)
            if (call == null) return Result(stripAnswer(raw), done)   // model answered
            val (name, arg) = call
            val result = execTool(ctx, name, arg, userInitiated, done)
            Log.i(TAG, "agentLoop turn $turn: $name(${arg.take(60)}) → ${result.take(80)}")
            messages.put(JSONObject().put("role", "assistant").put("content", raw))
            messages.put(JSONObject().put("role", "user").put("content", "TOOL RESULT [$name]:\n$result"))
        }
        // Out of tool budget — force a direct answer with whatever we have.
        messages.put(JSONObject().put("role", "user")
            .put("content", "You've used all tool steps. Reply now with ANSWER and the best answer you can give."))
        return Result(stripAnswer(AgentClient.loopTurn(sys, messages, /* VOICE-quality final */ "claude-sonnet-4-6")), done)
    }

    // Returns (toolName, arg) if the reply is a tool call, else null (it's an answer).
    private fun parseTool(raw: String): Pair<String, String>? {
        val t = raw.trim()
        val idx = Regex("(?im)^\\s*TOOL\\s+([a-z_]+)\\s*$").find(t) ?: return null
        val name = idx.groupValues[1].trim()
        // If the model also wrote an ANSWER before the TOOL, treat the earliest directive as intent.
        val ansIdx = Regex("(?im)^\\s*ANSWER\\s*$").find(t)
        if (ansIdx != null && ansIdx.range.first < idx.range.first) return null
        val after = t.substring(idx.range.last + 1)
        val arg = Regex("(?is)ARG:\\s*(.*)").find(after)?.groupValues?.get(1)?.trim()
            ?.substringBefore("\nTOOL")?.trim().orEmpty()
        return name to arg
    }

    private fun stripAnswer(raw: String): String {
        val t = raw.trim()
        val m = Regex("(?is)^\\s*ANSWER\\s*\\n(.*)$").find(t) ?: Regex("(?is)ANSWER\\s*\\n(.*)$").find(t)
        return (m?.groupValues?.get(1) ?: t).trim().ifBlank { "Done." }
    }

    private fun execTool(ctx: Context, name: String, arg: String, userInitiated: Boolean, done: ArrayList<AgentAction>): String {
        return try {
            when (name) {
                "web_search" -> AgentClient.webSearchText(arg.ifBlank { "" }).ifBlank { "No results." }
                "read_url" -> AgentClient.webSearchText("Read and summarize this page: $arg")
                "calendar_lookup" -> CalendarTool.upcoming(ctx).ifBlank { "No upcoming events." }
                "expense_lookup" -> {
                    val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
                    val (from, to) = ExpenseStore.rangeFor(o.optString("range", "this month"))
                    val cat = o.optString("category").takeIf { it.isNotBlank() }
                    "Spending (${o.optString("range", "this month")}${if (cat != null) ", $cat" else ""}):\n" +
                        ExpenseStore.summaryText(ctx, from, to, cat)
                }
                "find_contact" -> when (val r = ContactsTool.resolve(ctx, arg)) {
                    is ContactsTool.Resolution.Found -> "Contact: ${r.contact.name} — ${r.contact.number}"
                    is ContactsTool.Resolution.Ambiguous -> "Several match \"$arg\": ${r.options.joinToString(", ") { it.name }}. Ask which one."
                    ContactsTool.Resolution.None -> "No contact named \"$arg\"."
                }
                "memory_search" -> {
                    val hits = ArrayList<String>()
                    try { hits += MessageStore.search(ctx, arg, 6).map { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body } } catch (e: Exception) {}
                    try { hits += VectorStore.search(ctx, arg, 4).map { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body } } catch (e: Exception) {}
                    hits.distinct().take(8).joinToString("\n").ifBlank { "Nothing in memory about that." }
                }
                // Side-effect tools → the ONE gate. Gated types are blocked for non-user-initiated callers.
                else -> {
                    val res = ToolRouter.executeActions(ctx, listOf(AgentAction(name, arg)), userInitiated)
                    if (name !in READ_TOOLS) done.add(AgentAction(name, arg))
                    res.ifBlank { "Done." }
                }
            }
        } catch (e: Exception) { "That tool failed: ${e.message}" }
    }
}
