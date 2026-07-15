package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * A work SHIFT for one employee. It reads its goal, its own recent log and the owner's brain context, decides
 * the single most useful next step, does the thinking/drafting, and reports back in one line — flagging when it
 * needs something from the owner. Supervised by default (it proposes; the owner approves), so nothing fires
 * unattended. Everything is written to the employee's log AND the brain, so the whole team's work is tracked.
 */
object EmployeeRunner {
    private const val TAG = "SlyOS-Shift"

    /** Run one shift. Returns the short "what I did" line for immediate UI feedback. */
    fun runShift(ctx: Context, emp: EmployeeStore.Employee): String {
        EmployeeStore.setStatus(ctx, emp.id, "working")
        return try {
            val owner = MemoryStore.ownerName(ctx).ifBlank { "the owner" }
            val recent = EmployeeStore.logFor(ctx, emp.id, 10).joinToString("\n") { "• ${it.line}" }
            val brain = try { BrainContext.build(ctx, emp.goal) } catch (e: Exception) { "" }
            val sys = "You are ${emp.name}, the ${emp.role} on $owner's personal AI team. Your standing goal: " +
                "\"${emp.goal}\". Tools you may use: ${emp.tools.ifBlank { "your general knowledge" }}. " +
                "You are doing a short work shift. Pick the SINGLE most useful next step toward your goal, do the " +
                "thinking/drafting for it now, and report. Output ONLY compact JSON: " +
                "{\"did\":\"one short past-tense line of what you did, under 14 words\"," +
                "\"needs\":\"what you need from the owner to continue, or empty string\"," +
                "\"detail\":\"the actual draft/result/finding, or empty string\"}. No prose, no code fences."
            val user = "Your recent log:\n${recent.ifBlank { "(nothing yet)" }}\n\nWhat you know about $owner:\n" +
                "${brain.take(4000)}\n\nDo your next step now."
            val raw = AgentClient.complete(sys, user, 500)
            val o = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (e: Exception) { null }
            val did = o?.optString("did")?.trim().orEmpty().ifBlank { "Worked on: ${emp.goal.take(40)}" }
            val needs = o?.optString("needs")?.trim().orEmpty()
            val detail = o?.optString("detail")?.trim().orEmpty()

            EmployeeStore.log(ctx, emp.id, did, false)
            if (detail.isNotBlank()) EmployeeStore.log(ctx, emp.id, detail.take(600), false)
            if (needs.isNotBlank()) EmployeeStore.log(ctx, emp.id, "Needs you: $needs", true)
            // Track the team's work in the brain too.
            try { MemoryLog.add(ctx, "action", "${emp.name} (${emp.role})", (did + (if (detail.isNotBlank()) "\n$detail" else "")).take(800), "Team") } catch (e: Exception) {}

            EmployeeStore.setStatus(ctx, emp.id, if (needs.isNotBlank()) "needs_you" else "idle", touchRun = true)
            did
        } catch (e: Exception) {
            Log.w(TAG, "runShift: ${e.message}")
            EmployeeStore.setStatus(ctx, emp.id, "idle", touchRun = true)
            "Couldn't finish this shift — I'll try again."
        }
    }

    /** Draft an employee config from a plain "build me an employee that…" request. Returns name/role/goal/tools. */
    fun draftFromRequest(request: String): JSONObject {
        val sys = "Turn a request to hire an AI employee into a config. Output ONLY compact JSON " +
            "{\"name\":\"a short human first name\",\"role\":\"2-4 word job title\",\"goal\":\"one clear standing " +
            "instruction in second person, e.g. 'Keep my inbox triaged and draft replies I can approve'\"," +
            "\"tools\":\"comma-separated from: email, calendar, contacts, web, files, expenses, notes\"," +
            "\"interval_min\":<how often in minutes it should run itself, 0 if only on demand>}. No prose."
        val raw = AgentClient.complete(sys, "Request: $request", 300)
        return try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (e: Exception) { JSONObject() }
    }
}
