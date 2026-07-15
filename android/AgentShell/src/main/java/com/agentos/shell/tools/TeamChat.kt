package com.agentos.shell.tools

import android.content.Context

/**
 * Your team as a Telegram GROUP CHAT — you, your AI agents, and any real humans you add. Agents post their
 * updates and asks here (as "Kai · …"); you or a teammate reply and address an agent by name to dispatch it.
 *
 * This rides the existing Telegram bot service, but is deliberately SEPARATE from the private owner-DM brain:
 * in a group there are other humans, so it NEVER speaks as the owner or exposes the brain — it only routes
 * instructions to the named agent (which acts on its next supervised shift). The owner controls who's in the
 * group, so any member may address the agents.
 */
object TeamChat {
    private const val PREFS = "slyos_teamchat"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()
    fun groupId(ctx: Context): Long = p(ctx).getLong("group_id", 0L)
    private fun setGroupId(ctx: Context, id: Long) = p(ctx).edit().putLong("group_id", id).apply()
    fun isConnected(ctx: Context): Boolean = enabled(ctx) && groupId(ctx) != 0L

    /** An agent posts an update/ask into the group. Prefixed with its name so identities are clear. */
    fun post(ctx: Context, agentName: String, text: String) {
        if (!enabled(ctx)) return
        val gid = groupId(ctx); if (gid == 0L || text.isBlank()) return
        try { TelegramClient.sendMessage(gid, "$agentName · $text") } catch (e: Exception) {}
    }

    /**
     * Called from the Telegram service for every update. Returns true if it consumed the update (a message in
     * the team group), so the service skips its private-owner brain flow. Auto-captures the group the first
     * time the bot sees a group message while enabled.
     */
    fun handleUpdate(ctx: Context, u: TelegramClient.Update): Boolean {
        if (!enabled(ctx)) return false
        val gid = groupId(ctx)
        val isGroup = u.chatId < 0   // Telegram group/supergroup chat ids are negative
        if (gid == 0L) {
            if (!isGroup) return false   // haven't captured a group yet; let the normal DM flow handle DMs
            setGroupId(ctx, u.chatId)
            try { TelegramClient.sendMessage(u.chatId,
                "SlyOS team chat connected ✓ — your agents will post here. Address one by name, e.g. \"Kai, reschedule my 3pm\".") } catch (e: Exception) {}
            return true
        }
        if (u.chatId != gid) return false   // some other chat → not ours

        val text = u.text.trim()
        if (text.isBlank()) return true
        val staff = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        if (staff.isEmpty()) { safeSend(gid, "You haven't hired any agents yet — open SlyOS → Team to add one."); return true }

        val emp = staff.firstOrNull { e ->
            e.name.isNotBlank() && Regex("(?i)(^|[^\\p{L}])@?" + Regex.escape(e.name) + "\\b").containsMatchIn(text)
        }
        if (emp == null) {
            safeSend(gid, "Address an agent by name to give them something. You have: ${staff.joinToString(", ") { it.name }}.")
            return true
        }
        val instruction = text.replaceFirst(Regex("(?i)@?" + Regex.escape(emp.name) + "\\s*[,:]?\\s*"), "").trim().ifBlank { text }
        val fromWho = u.senderName.ifBlank { "A teammate" }
        try {
            EmployeeStore.log(ctx, emp.id, "$fromWho (team chat): $instruction", false)
            EmployeeStore.setStatus(ctx, emp.id, "idle")
            MemoryLog.add(ctx, "note", "Team chat → ${emp.name}", "$fromWho: $instruction", "Team")
        } catch (e: Exception) {}
        safeSend(gid, "${emp.name} · got it 👍 — I'll handle \"${instruction.take(70)}\" on my next shift.")
        return true
    }

    private fun safeSend(gid: Long, text: String) { try { TelegramClient.sendMessage(gid, text) } catch (e: Exception) {} }
}
