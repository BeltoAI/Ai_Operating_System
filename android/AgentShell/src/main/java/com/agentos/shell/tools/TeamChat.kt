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
        // Mark this agent as the one you're "with", so your reply reaches them WITHOUT an @mention.
        try { EmployeeStore.all(ctx).firstOrNull { it.name.equals(agentName, true) }?.let { setLastAgent(ctx, it.id) } } catch (e: Exception) {}
    }

    /** An agent shares a generated file (a designed PDF) into the group for review. Returns whether it sent. */
    fun postDocument(ctx: Context, file: java.io.File, caption: String): Boolean {
        if (!enabled(ctx)) return false
        val gid = groupId(ctx); if (gid == 0L) return false
        return try { TelegramClient.sendDocument(gid, file, caption) } catch (e: Exception) { false }
    }

    /**
     * Called from the Telegram service for every update. Returns true if it consumed the update (a message in
     * the team group), so the service skips its private-owner brain flow. Auto-captures the group the first
     * time the bot sees a group message while enabled.
     */
    private fun greeted(ctx: Context, chatId: Long) = p(ctx).getBoolean("greeted_$chatId", false)
    private fun markGreeted(ctx: Context, chatId: Long) = p(ctx).edit().putBoolean("greeted_$chatId", true).apply()

    fun handleUpdate(ctx: Context, u: TelegramClient.Update): Boolean {
        if (!enabled(ctx)) return false
        val isGroup = u.chatId < 0   // Telegram group/supergroup chat ids are negative
        if (!isGroup) return false   // a DM → let the private-owner flow handle it, not the team chat

        // ANY group the bot is in (while team chat is on) IS a team chat — so you can spin up new test groups
        // freely. Follow the active group for agent posts, and greet each new one exactly once.
        val gid = u.chatId
        if (groupId(ctx) != gid) setGroupId(ctx, gid)
        if (!greeted(ctx, gid)) {
            markGreeted(ctx, gid)
            try { TelegramClient.sendMessage(gid,
                "SlyOS team chat connected ✓ — your agents will post here. Address one by name, e.g. \"Kai, reschedule my 3pm\".") } catch (e: Exception) {}
        }

        // Someone joined → the whole team introduces itself.
        if (u.newMembers.isNotEmpty()) { introduceAll(ctx, u.newMembers.first()); return true }

        val staff = try { EmployeeStore.all(ctx) } catch (e: Exception) { emptyList() }

        // A PDF/photo dropped in the group → read it and feed it to the right agent (or the brain).
        if (u.isPdf || u.photoFileId != null) { ingestAttachment(ctx, gid, u, staff); return true }

        val text = u.text.trim()
        if (text.isBlank()) return true
        if (staff.isEmpty()) { safeSend(gid, "You haven't hired any agents yet — open SlyOS → Team to add one."); return true }

        // In a real group with humans, stay QUIET unless summoned: the bot (@handle / its name) or an agent
        // by name, or a team question. Humans chatting to each other never trigger a response.
        val named = staff.firstOrNull { e ->
            e.name.isNotBlank() && Regex("(?i)(^|[^\\p{L}])@?" + Regex.escape(e.name) + "\\b").containsMatchIn(text)
        }
        // Summoned if: an agent named, a team question, the bot @mentioned, you REPLIED to an agent's message,
        // or you're mid-exchange (an agent asked/posted in the last ~5 min) — so answering a "needs you" needs no @.
        val engaged = System.currentTimeMillis() - p(ctx).getLong("last_agent_ts", 0L) < 5 * 60 * 1000L
        val summoned = named != null || isTeamQuestion(text) || mentionsBot(text) || u.replyToBot || engaged
        if (!summoned) return true   // consumed, but we don't butt into human conversation

        // "Who's here / introduce yourselves / what can you do" → one authoritative roster answer.
        if (named == null && isTeamQuestion(text)) { safeSend(gid, rosterText(staff)); return true }

        // The prior conversation (before this message) — so follow-ups have context and stay coherent.
        val history = try {
            ConversationStore.thread(ctx, "Team", gid.toString()).takeLast(8).joinToString("\n") { it.text }
        } catch (e: Exception) { "" }

        // WHO answers: explicit @name → that agent. Else if a clear role fits → that agent. Else if we're mid-
        // conversation (an agent just replied) → keep it with THAT agent so "just me" reaches whoever asked.
        val emp = named ?: bestFit(ctx, staff, text) ?: recentAgent(ctx, staff) ?: staff.first()
        val instruction = if (named != null)
            text.replaceFirst(Regex("(?i)@?" + Regex.escape(emp.name) + "\\s*[,:]?\\s*"), "").trim().ifBlank { text } else text

        val fromWho = u.senderName.ifBlank { "You" }
        try {
            EmployeeStore.clearAsked(ctx, emp.id)   // you replied → let this agent surface a fresh ask later
            MemoryLog.add(ctx, "note", "Team chat → ${emp.name}", "$fromWho: $instruction", "Team")
            ConversationStore.add(ctx, "Team", gid.toString(), "them", "$fromWho: $instruction")
        } catch (e: Exception) {}

        // Actually answer/act NOW (grounded in the brain + the thread) and reply with the real result.
        try { TelegramClient.sendTyping(gid) } catch (e: Exception) {}
        val reply = try { EmployeeRunner.answer(ctx, emp, instruction, history, fromWho) } catch (e: Exception) { "Couldn't get to that just now." }
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

    /** Pick the agent that best fits the message (intent-aware + knowledge-aware), or null if nothing fits. */
    private fun bestFit(ctx: Context, staff: List<EmployeeStore.Employee>, text: String): EmployeeStore.Employee? {
        val t = text.lowercase()
        // A question about a DOCUMENT/paper/chapter → the agent that actually HAS documents fed to it (Bastardi).
        val docQ = Regex("(?i)\\b(chapter|section|page \\d|figure \\d|the (paper|pdf|doc|document|report|whitepaper|white paper)|" +
            "in (the|my|this|your) (paper|doc|pdf|report|document)|according to|what does .* say|summari|explain (the|this))\\b").containsMatchIn(t)
        if (docQ) {
            val withDocs = staff.map { it to (try { AgentKnowledge.count(ctx, it.id) } catch (e: Exception) { 0 }) }.filter { it.second > 0 }
            withDocs.maxByOrNull { it.second }?.let { return it.first }
            staff.firstOrNull { it.role.contains("expert", true) }?.let { return it }
        }
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
            boost("explain|what is|how does|why|know|expert|technical|deep", "expert|deep")
            // an agent that's been fed documents is the natural home for knowledge questions
            if (try { AgentKnowledge.count(ctx, e.id) } catch (ex: Exception) { 0 } > 0) s += 1
            return s
        }
        val best = staff.maxByOrNull { score(it) } ?: return null
        return if (score(best) > 0) best else null
    }

    /** Was the bot itself summoned — by its @handle or the DISTINCTIVE last word of its name (e.g. "bastard")? */
    private fun mentionsBot(text: String): Boolean {
        val t = text.lowercase()
        val user = try { TelegramClient.botUsername() } catch (e: Exception) { "" }.lowercase()
        if (user.isNotBlank() && t.contains("@$user")) return true
        val name = try { TelegramClient.botName() } catch (e: Exception) { "" }.lowercase()
        // Only the last distinctive word (surname-like), so the owner's first name in the name doesn't over-trigger.
        val last = name.split(Regex("[^a-z0-9]+")).filter { it.length > 4 }.lastOrNull() ?: return false
        return Regex("(^|[^a-z0-9])@?" + Regex.escape(last) + "($|[^a-z0-9])").containsMatchIn(t)
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

    /** Read a PDF/photo dropped in the group and feed it to the named agent (or the deep-expert), + the brain. */
    private fun ingestAttachment(ctx: Context, gid: Long, u: TelegramClient.Update, staff: List<EmployeeStore.Employee>) {
        val caption = u.caption.trim()
        // Which agent should learn it? The one named in the caption, else a "deep expert" (Bastardi), else none.
        val target = staff.firstOrNull { it.name.isNotBlank() && caption.contains(it.name, true) }
            ?: staff.firstOrNull { it.role.contains("expert", true) || it.name.equals("Bastardi", true) }
        try {
            if (u.isPdf) {
                val bytes = u.docFileId?.let { TelegramClient.downloadFile(it) }
                if (bytes == null) { safeSend(gid, "Couldn't download that PDF (Telegram caps bot downloads at 20 MB)."); return }
                val name = u.docName.ifBlank { "document.pdf" }
                val tmp = java.io.File(ctx.cacheDir, "tg_${System.currentTimeMillis()}.pdf")
                val text = try {
                    tmp.writeBytes(bytes)
                    var t = FileOps.pdfText(ctx, android.net.Uri.fromFile(tmp))
                    if (t.length < 200) { val ocr = PdfOcr.fromFile(tmp); if (ocr.length > t.length) t = ocr }   // slides/scans → OCR
                    t
                } catch (e: Exception) { "" } finally { try { tmp.delete() } catch (e: Exception) {} }
                if (text.length < 40) { safeSend(gid, "Got “$name” but couldn't read any text from it, even with OCR."); return }
                try { DocText.add(ctx, name, "telegram", text) } catch (e: Exception) {}   // into the brain for everyone
                if (target != null) { AgentKnowledge.add(ctx, target.id, name, text); safeSend(gid, "${target.name} learned “$name” ✓ — it's now part of what they know.") }
                else safeSend(gid, "Read “$name” and saved it to your brain ✓.")
            } else if (u.photoFileId != null) {
                val bytes = TelegramClient.downloadFile(u.photoFileId)
                val b64 = bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                val desc = if (b64 != null) AgentClient.askVision(caption.ifBlank { "Describe this image in detail for later search and knowledge." }, listOf(b64), "") else ""
                if (desc.isBlank() || desc.startsWith("Couldn't")) { safeSend(gid, "Couldn't read that image."); return }
                try { MemoryLog.add(ctx, "note", "Team chat image", desc.take(500), "Team") } catch (e: Exception) {}
                if (target != null) { AgentKnowledge.add(ctx, target.id, "shared image", desc); safeSend(gid, "${target.name} took a look ✓ — noted it.") }
                else safeSend(gid, "Had a look — noted what's in it ✓.")
            }
        } catch (e: Exception) { safeSend(gid, "Couldn't process that attachment.") }
    }
}
