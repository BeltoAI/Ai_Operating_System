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

        // In a real group with humans, stay QUIET unless summoned: the bot (@handle / its name) or an agent
        // by name, or a team question. Humans chatting to each other never trigger a response.
        val named = staff.firstOrNull { e ->
            e.name.isNotBlank() && Regex("(?i)(^|[^\\p{L}])@?" + Regex.escape(e.name) + "\\b").containsMatchIn(text)
        }
        val summoned = named != null || isTeamQuestion(text) || mentionsBot(text)
        if (!summoned) return true   // consumed, but we don't butt into human conversation

        // "Who's here / introduce yourselves / what can you do" → one authoritative roster answer.
        if (named == null && isTeamQuestion(text)) { safeSend(gid, rosterText(staff)); return true }

        // The prior conversation (before this message) — so follow-ups have context and stay coherent.
        val history = try {
            ConversationStore.thread(ctx, "Team", gid.toString()).takeLast(8).joinToString("\n") { it.text }
        } catch (e: Exception) { "" }

        // WHO answers: explicit @name → that agent. Else if a clear role fits → that agent. Else if we're mid-
        // conversation (an agent just replied) → keep it with THAT agent so "just me" reaches whoever asked.
        val emp = named ?: bestFit(staff, text) ?: recentAgent(ctx, staff) ?: staff.first()
        val instruction = if (named != null)
            text.replaceFirst(Regex("(?i)@?" + Regex.escape(emp.name) + "\\s*[,:]?\\s*"), "").trim().ifBlank { text } else text

        val fromWho = u.senderName.ifBlank { "You" }
        try {
            MemoryLog.add(ctx, "note", "Team chat → ${emp.name}", "$fromWho: $instruction", "Team")
            ConversationStore.add(ctx, "Team", gid.toString(), "them", "$fromWho: $instruction")
        } catch (e: Exception) {}

        // Actually answer/act NOW (grounded in the brain + the thread) and reply with the real result.
        try { TelegramClient.sendTyping(gid) } catch (e: Exception) {}
        val reply = try { EmployeeRunner.answer(ctx, emp, instruction, history) } catch (e: Exception) { "Couldn't get to that just now." }
        try { ConversationStore.add(ctx, "Team", gid.toString(), "me", "${emp.name}: $reply") } catch (e: Exception) {}
        setLastAgent(ctx, emp.id)
        safeSend(gid, "${emp.name} · $reply")
        return true
    }

    // The agent who last replied — used to keep a back-and-forth coherent instead of hopping agents.
    private fun setLastAgent(ctx: Context, id: String) = p(ctx).edit().putString("last_agent", id).putLong("last_agent_ts", System.currentTimeMillis()).apply()
    private fun recentAgent(ctx: Context, staff: List<EmployeeStore.Employee>): EmployeeStore.Employee? {
        val ts = p(ctx).getLong("last_agent_ts", 0L)
        if (System.currentTimeMillis() - ts > 12 * 60 * 1000L) return null   // stale → new topic
        val id = p(ctx).getString("last_agent", null) ?: return null
        return staff.firstOrNull { it.id == id }
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

    /** Was the bot itself summoned — by its @handle or a distinctive word of its name (e.g. "bastard")? */
    private fun mentionsBot(text: String): Boolean {
        val t = text.lowercase()
        val user = try { TelegramClient.botUsername() } catch (e: Exception) { "" }.lowercase()
        if (user.isNotBlank() && t.contains("@$user")) return true
        val name = try { TelegramClient.botName() } catch (e: Exception) { "" }.lowercase()
        val words = name.split(Regex("[^a-z0-9]+")).filter { it.length > 3 }
        return words.any { t.contains(it) }
    }

    /** Is this a question ABOUT the team itself (who's here, introductions, capabilities)? */
    private fun isTeamQuestion(text: String): Boolean = Regex(
        "(?i)\\b(who('?s| is| are)?\\s+(here|else|this|you|on the team|in (this|the) (chat|group))|" +
        "introduce (yoursel(f|ves)|the team)|meet the team|the (whole )?team\\??$|list (the )?(team|agents|bots)|" +
        "what can (you|the team|they) do|who do i have|everyone here)\\b").containsMatchIn(text.trim())

    /** The one clean roster message — who's on the team and what each does. */
    private fun rosterText(staff: List<EmployeeStore.Employee>): String =
        "Your team — just talk to us naturally, no need to tag anyone:\n" +
        staff.joinToString("\n") { "• ${it.name} — ${it.role}: ${it.goal.take(80)}" }

    /** When someone joins the group, introduce the whole team in one quick message. */
    fun introduceAll(ctx: Context, newcomer: String) {
        if (!isConnected(ctx)) return
        val staff = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }
        if (staff.isEmpty()) return
        val hi = if (newcomer.isNotBlank()) "Welcome, $newcomer! " else "Welcome! "
        safeSend(groupId(ctx), hi + rosterText(staff))
    }

    private fun safeSend(gid: Long, text: String) { try { TelegramClient.sendMessage(gid, text) } catch (e: Exception) {} }
}
