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

        // Someone joined → the whole team introduces itself.
        if (u.newMembers.isNotEmpty()) { introduceAll(ctx, u.newMembers.first()); return true }

        val text = u.text.trim()
        if (text.isBlank()) return true
        val staff = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        if (staff.isEmpty()) { safeSend(gid, "You haven't hired any agents yet — open SlyOS → Team to add one."); return true }

        // Figure out WHO should answer — by explicit @name if given, else whoever fits the message best.
        val named = staff.firstOrNull { e ->
            e.name.isNotBlank() && Regex("(?i)(^|[^\\p{L}])@?" + Regex.escape(e.name) + "\\b").containsMatchIn(text)
        }
        val emp = named ?: bestFit(staff, text) ?: staff.first()
        val instruction = if (named != null)
            text.replaceFirst(Regex("(?i)@?" + Regex.escape(emp.name) + "\\s*[,:]?\\s*"), "").trim().ifBlank { text } else text

        // Remember what was asked, in the brain (so it's searchable and every AI in SlyOS sees it).
        val fromWho = u.senderName.ifBlank { "You" }
        try {
            MemoryLog.add(ctx, "note", "Team chat → ${emp.name}", "$fromWho: $instruction", "Team")
            ConversationStore.add(ctx, "Team", gid.toString(), "them", "$fromWho: $instruction")
        } catch (e: Exception) {}

        // Actually answer/act NOW (grounded in the brain) and reply with the real result — not "next shift".
        safeSend(gid, "${emp.name} is on it…")
        val reply = try { EmployeeRunner.answer(ctx, emp, instruction) } catch (e: Exception) { "Couldn't get to that just now." }
        try { ConversationStore.add(ctx, "Team", gid.toString(), "me", "${emp.name}: $reply") } catch (e: Exception) {}
        safeSend(gid, "${emp.name} · $reply")
        return true
    }

    /** Pick the agent whose role/goal best matches the message (intent-aware), or null if nothing fits. */
    private fun bestFit(staff: List<EmployeeStore.Employee>, text: String): EmployeeStore.Employee? {
        val t = text.lowercase()
        val words = t.split(Regex("[^a-z0-9]+")).filter { it.length > 3 }
        fun score(e: EmployeeStore.Employee): Int {
            val hay = "${e.role} ${e.goal}".lowercase()
            var s = words.count { hay.contains(it) }
            fun boost(intent: String, role: String) { if (Regex(intent).containsMatchIn(t) && Regex(role).containsMatchIn(hay)) s += 4 }
            boost("calendar|schedul|plan|meeting|appointment|agenda|today|tomorrow|free time", "calendar|schedul")
            boost("email|inbox|reply|mail|draft", "inbox|email|mail")
            boost("research|news|competitor|market|trend|look up|find out", "research|analyst|intelligence|news")
            boost("expense|receipt|spend|budget|invoice|money|cost", "book|expense|financ|account")
            boost("reddit|post|comment|tweet|social|audience", "reddit|growth|social|market")
            return s
        }
        val best = staff.maxByOrNull { score(it) } ?: return null
        return if (score(best) > 0) best else null
    }

    /** When someone joins the group, have every agent introduce itself in one quick message. */
    fun introduceAll(ctx: Context, newcomer: String) {
        if (!isConnected(ctx)) return
        val staff = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        if (staff.isEmpty()) return
        val gid = groupId(ctx)
        val hi = if (newcomer.isNotBlank()) "Welcome, $newcomer! " else "Welcome! "
        val intros = staff.joinToString("\n") { "• ${it.name} — ${it.role}. ${it.goal.take(90)}" }
        safeSend(gid, hi + "Here's the team — just talk to us naturally, no need to tag anyone:\n$intros")
    }

    private fun safeSend(gid: Long, text: String) { try { TelegramClient.sendMessage(gid, text) } catch (e: Exception) {} }
}
