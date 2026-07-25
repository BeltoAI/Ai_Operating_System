package com.agentos.shell.tools

import android.content.Context
import android.util.Log

/**
 * PER-CHANNEL / PER-PERSON QUALITY AUDIT. Response quality is the product, so it needs to be measurable, not
 * a vibe. This drafts a reply to the SAME incoming message as if it arrived on each platform, from a real
 * person, and reports what the drafter actually saw and produced.
 *
 * Run it from a shell with:
 *   adb shell am broadcast -a com.agentos.shell.AUDIT_VOICE
 * and read the results with `adb logcat -s SlyOS-Audit`. It writes nothing and sends nothing.
 */
object VoiceAudit {
    private const val TAG = "SlyOS-Audit"

    private val CHANNELS = listOf("LinkedIn", "Email", "Instagram", "X", "WhatsApp", "Telegram", "Slack", "SMS")

    /**
     * PLANNER PROBE: run the real action planner over phone-operation prompts and log exactly what actions it
     * emits. "Operate my phone" failing silently (planner runs, zero actions) is invisible without this.
     */
    fun planner(ctx: Context) {
        val prompts = listOf(
            "open instagram",
            "open instagram and search for anduril",
            "turn on the flashlight",
            "text Anna that I'm running late",
            "post on linkedin about on-device AI",
            "connect with 10 people in my network on linkedin",
            "what's on my calendar tomorrow"
        )
        Log.i(TAG, "══════ PLANNER PROBE ══════")
        val apps = try { ToolRouter.installedApps(ctx).map { it.label } } catch (t: Throwable) { emptyList() }
        for (p in prompts) {
            try {
                val brain = try { BrainContext.build(ctx, p) } catch (t: Throwable) { "" }
                val t0 = System.currentTimeMillis()
                val r = AgentClient.ask(p, apps, brain, emptyList())
                val acts = r.actions.filter { it.type.isNotBlank() && it.type != "none" }
                Log.i(TAG, "\"$p\" (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "   say    : ${r.say.take(120)}")
                Log.i(TAG, "   ACTIONS: ${if (acts.isEmpty()) "*** NONE ***" else acts.joinToString(", ") { it.type + "(" + it.arg.take(60) + ")" }}")
            } catch (t: Throwable) { Log.w(TAG, "\"$p\" failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END PLANNER PROBE ══════")
    }

    /**
     * OUTREACH PROBE: draft the intro message for the first N LinkedIn connections the owner has never
     * reached out to, and log each one with timing. DRAFTS ONLY — nothing is sent.
     */
    /** Preview what the REAL sender (tailoredOutreach + the owner's template) would say. Nothing is sent. */
    fun outreachPreview(ctx: Context, n: Int, template: String) {
        Log.i(TAG, "══════ OUTREACH PREVIEW — template-based, NOTHING SENT ══════")
        val targets = try { ConnectionStore.neverReachedOut(ctx).filter { it.url.isNotBlank() }.take(n) } catch (t: Throwable) { emptyList() }
        Log.i(TAG, "targets with profile links: ${targets.size}")
        val liStyle = try { MemoryStore.styleFor(ctx, "LinkedIn") } catch (t: Throwable) { "" }
        val profile = (if (liStyle.isNotBlank()) "Your LinkedIn voice/persona: $liStyle\n\n" else "") +
            (try { BrainDigest.getOrFull(ctx) } catch (t: Throwable) { "" })
        targets.forEachIndexed { i, c ->
            try {
                val t0 = System.currentTimeMillis()
                val msg = AgentClient.tailoredOutreach(
                    "invite them to test SlyOS as an early developer tester",
                    c.name, c.role, c.company, profile, "", template)
                Log.i(TAG, "${i + 1}. ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") +
                    (if (c.company.isNotBlank()) " @ ${c.company}" else "") + " (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "     ${msg.replace("\n", " ⏎ ")}")
            } catch (t: Throwable) { Log.w(TAG, "${c.name}: ${t.message}") }
        }
        Log.i(TAG, "══════ END PREVIEW ══════")
    }

    fun outreach(ctx: Context, n: Int = 10) {
        Log.i(TAG, "══════ OUTREACH PROBE (drafts only, nothing sent) ══════")
        val never = try { ConnectionStore.neverReachedOut(ctx) } catch (t: Throwable) { emptyList() }
        Log.i(TAG, "never-reached-out pool: ${never.size}")
        val started = System.currentTimeMillis()
        never.take(n).forEachIndexed { i, c ->
            try {
                val t0 = System.currentTimeMillis()
                val mem = ReplyContext.forSender(ctx, c.source, c.name)
                val msg = AgentClient.introMessage(c.name, c.company, c.role, c.source, mem)
                Log.i(TAG, "${i + 1}. ${c.name}" + (if (c.role.isNotBlank()) " — ${c.role}" else "") +
                    (if (c.company.isNotBlank()) " @ ${c.company}" else "") + "  (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "     ${msg.replace("\n", " ⏎ ").take(320)}")
            } catch (t: Throwable) { Log.w(TAG, "${c.name} failed: ${t.message}") }
        }
        Log.i(TAG, "TOTAL for ${minOf(n, never.size)} drafts: ${(System.currentTimeMillis() - started) / 1000}s")
        Log.i(TAG, "══════ END OUTREACH PROBE ══════")
    }

    /**
     * THE MATRIX AUDIT — the real test of "does it sound right everywhere". Same engine, but varied across the
     * three axes that actually determine whether a reply is appropriate: WHO is writing (wife vs co-founder vs
     * investor vs stranger), WHICH channel (register), and WHAT the topic is (personal vs technical vs money vs
     * emotional). A single-channel test can't catch a warm-to-your-wife tone leaking into an investor thread.
     */
    /** What's ACTUALLY in the brain, per platform — the honest coverage picture. */
    /** Import a chat archive from a path and report the honest per-file outcome. */
    fun importFile(ctx: Context, path: String) {
        Log.i(TAG, "══════ IMPORT: $path ══════")
        val before = try { MessageStore.count(ctx) } catch (t: Throwable) { 0 }
        val t0 = System.currentTimeMillis()
        try {
            val uri = android.net.Uri.fromFile(java.io.File(path))
            val owner = try { MemoryStore.ownerName(ctx).ifBlank { MemoryStore.profileName(ctx) } } catch (t: Throwable) { "" }
            val r = ChatImport.importAny(ctx, uri, owner)
            val after = try { MessageStore.count(ctx) } catch (t: Throwable) { 0 }
            Log.i(TAG, "RESULT: ${r.messages} added · ${r.contacts} contacts · ${(System.currentTimeMillis() - t0) / 1000}s")
            Log.i(TAG, "brain: $before -> $after (+${after - before})")
            r.files.sortedByDescending { it.parsed }.forEach {
                Log.i(TAG, "  ${if (it.ok) "OK  " else "FAIL"} ${it.name.take(46).padEnd(48)} parsed=${it.parsed} added=${it.added} ${it.error}")
            }
            Log.i(TAG, "files: ${r.files.count { it.ok }} ok, ${r.files.count { !it.ok }} failed")
        } catch (t: Throwable) { Log.e(TAG, "import blew up: ${t.message}", t) }
        Log.i(TAG, "══════ END IMPORT ══════")
    }

    /** Does search actually find the newly imported history? Keyword + FTS health + semantic. */
    fun searchProbe(ctx: Context, q: String) {
        Log.i(TAG, "══════ SEARCH PROBE: \"$q\" ══════")
        try { Log.i(TAG, "messages=${MessageStore.count(ctx)} ftsRows=${MessageStore.ftsCount(ctx)}") } catch (t: Throwable) { Log.w(TAG, "counts: ${t.message}") }
        try {
            val hits = MessageStore.search(ctx, q, 8)
            Log.i(TAG, "keyword/FTS hits: ${hits.size}")
            hits.take(5).forEach { Log.i(TAG, "   [${it.contact}] ${it.body.take(120)}") }
        } catch (t: Throwable) { Log.w(TAG, "search failed: ${t.message}") }
        try {
            val sem = VectorStore.search(ctx, q, 5)
            Log.i(TAG, "semantic hits: ${sem.size} (embedded=${VectorStore.embeddedCount(ctx)})")
        } catch (t: Throwable) { Log.w(TAG, "semantic: ${t.message}") }
        Log.i(TAG, "══════ END SEARCH PROBE ══════")
    }

    fun brainStats(ctx: Context) {
        Log.i(TAG, "══════ BRAIN COVERAGE ══════")
        try { Log.i(TAG, "total messages: ${MessageStore.count(ctx)}") } catch (t: Throwable) {}
        try {
            // TRUE totals from SQL. The earlier top-400 sample badly understated platforms with many
            // small threads and led me to a wrong "96% lost" conclusion.
            Log.i(TAG, "── messages by platform (TRUE counts) ──")
            MessageStore.countsByPlatform(ctx).forEach { (plat, c) ->
                Log.i(TAG, "  ${plat.padEnd(14)} $c msgs across ${MessageStore.contactCount(ctx, plat)} contacts")
            }
            val top = MessageStore.topContacts(ctx, 400)
            Log.i(TAG, "── top 15 contacts ──")
            top.take(15).forEach { (n, c, p) -> Log.i(TAG, "  ${n.take(28).padEnd(30)} $c  [$p]") }
        } catch (t: Throwable) { Log.w(TAG, "stats: ${t.message}") }
        try { Log.i(TAG, "LinkedIn connections: ${ConnectionStore.count(ctx)}") } catch (t: Throwable) {}
        try { Log.i(TAG, "vectors embedded: ${VectorStore.embeddedCount(ctx)}") } catch (t: Throwable) {}
        Log.i(TAG, "══════ END COVERAGE ══════")
    }

    fun matrix(ctx: Context) {
        data class Case(val who: String, val channel: String, val topic: String, val msg: String)
        val cases = listOf(
            Case("Joslyn Barragan", "WhatsApp", "personal/spouse", "can you take Bello to the vet tomorrow? i cant make it"),
            Case("Joslyn Barragan", "WhatsApp", "emotional", "rough day today. feeling really low"),
            Case("Anna A", "Telegram", "work/co-founder", "the satlyt deck needs the pricing slide before monday. can you do it?"),
            Case("Anna A", "Telegram", "disagreement", "i think we should drop the satellite angle and focus purely on consumer"),
            Case("Rama", "LinkedIn", "deal/professional", "Great meeting today. Can you send over pricing for the onboard inference pilot?"),
            Case("Nabeel Khan", "LinkedIn", "investor", "Interesting. What's your current traction and round size?"),
            Case("Elon Musk", "X", "technical/banter", "on-device inference is the only way this scales"),
            Case("Carlos XOG", "Instagram", "friend/casual", "yo that dnd session was insane last night 😂"),
            Case("Tamer Elsawaf", "Email", "technical peer", "How are you handling thermal constraints for onboard inference?"),
            Case("Unknown Recruiter", "LinkedIn", "cold/irrelevant", "Hi! Are you open to a Senior Data Analyst role in Ohio?")
        )
        Log.i(TAG, "══════ MATRIX AUDIT — person × channel × topic ══════")
        for (c in cases) {
            try {
                val t0 = System.currentTimeMillis()
                val mem = ReplyContext.forSender(ctx, c.channel, c.who, c.msg)
                val draft = AgentClient.draftReplyThread(c.who, listOf("them" to c.msg), mem, null, c.msg)
                val ident = try { PersonResolver.identityLine(ctx, c.who).take(120) } catch (t: Throwable) { "" }
                Log.i(TAG, "── ${c.who} | ${c.channel} | ${c.topic} (${System.currentTimeMillis() - t0}ms)")
                Log.i(TAG, "   THEY: ${c.msg}")
                Log.i(TAG, "   ID  : ${ident.ifBlank { "(unresolved)" }}")
                Log.i(TAG, "   YOU : ${draft.replace("\n", " ⏎ ").take(300)}")
            } catch (t: Throwable) { Log.w(TAG, "${c.who}/${c.channel} failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END MATRIX ══════")
    }

    /** Draft a reply per channel to [incoming] from [sender] and log what each one produced. */
    fun run(ctx: Context, sender: String = "Anna Schmidt",
            incoming: String = "Hey! Loved what you're building. Any chance you're free this week for a quick call?") {
        Log.i(TAG, "══════ PER-CHANNEL DRAFT AUDIT ══════")
        Log.i(TAG, "from=\"$sender\"  message=\"$incoming\"")

        // Identity resolution first — the same human should be recognised across every platform.
        try {
            val p = PersonResolver.resolve(ctx, sender)
            Log.i(TAG, "IDENTITY: name=${p.name} aliases=${p.aliases} email=${p.email.ifBlank { "-" }} company=${p.company.ifBlank { "-" }}")
            val hist = PersonResolver.historyFor(ctx, sender, 12)
            Log.i(TAG, "CROSS-PLATFORM HISTORY: ${if (hist.isBlank()) "(none found)" else hist.replace("\n", " ").take(400)}")
        } catch (t: Throwable) { Log.w(TAG, "identity failed: ${t.message}") }

        for (ch in CHANNELS) {
            try {
                val persona = MemoryStore.styleFor(ctx, ch)
                val voice = Voice.voiceFor(ctx, ch)
                val exemplars = Regex("Real examples of how you actually write[^\n]*").find(voice)?.value.orEmpty()
                val t0 = System.currentTimeMillis()
                val draft = AgentClient.draftReplyThread(sender, listOf("them" to incoming),
                    ReplyContext.forSender(ctx, ch, sender, incoming), null, incoming)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "───── $ch ─────")
                Log.i(TAG, "  persona   : ${persona.ifBlank { "*** NONE SET ***" }}")
                Log.i(TAG, "  exemplars : ${if (exemplars.isBlank()) "(none — writing to character)" else exemplars.take(180)}")
                Log.i(TAG, "  ctxChars  : ${voice.length}")
                Log.i(TAG, "  DRAFT(${ms}ms): ${draft.replace("\n", " ⏎ ").take(400)}")
            } catch (t: Throwable) { Log.w(TAG, "$ch failed: ${t.message}") }
        }
        Log.i(TAG, "══════ END AUDIT ══════")
    }
}
