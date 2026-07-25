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
