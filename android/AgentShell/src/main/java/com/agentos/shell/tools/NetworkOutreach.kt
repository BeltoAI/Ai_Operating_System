package com.agentos.shell.tools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AUTONOMOUS RECONNECT — "message N people from my network for me today." Pulls never-reached LinkedIn
 * connections, drafts a personal opener for each, and sends it via accessibility TAP-SEND (opens their profile
 * → Message → type → Send), human-paced with a gap between each. Requires the SlyOS accessibility service ON.
 *
 * SAFETY: capped at [count] (≤ your daily cap), a hard Stop, per-send failure is skipped (never spams the wrong
 * person), every send logged to the brain + "Sent for you" with recall. Runs in its own scope so it survives
 * navigating away, but stops if the app process is killed.
 */
object NetworkOutreach {
    @Volatile var running = false
        private set
    @Volatile var sent = 0
    @Volatile var failed = 0
    @Volatile var total = 0
    @Volatile var lastMsg = ""

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun stop() {
        running = false
        job?.cancel()
        try { ScreenAgent.stop() } catch (e: Exception) {}   // also kill any screen-agent run it spawned
        lastMsg = "Stopped — $sent sent."
    }

    fun start(ctx: Context, goal: String, count: Int, onUpdate: () -> Unit) {
        if (running) return
        if (!TapSend.available()) { lastMsg = "Turn on SlyOS accessibility (Settings → Total Recall) first."; onUpdate(); return }
        running = true; sent = 0; failed = 0; lastMsg = "Finding people…"; onUpdate()
        job = scope.launch {
            try {
                // Feed the user's LinkedIn persona/voice into every draft so it sounds like them on LinkedIn.
                val liStyle = try { MemoryStore.styleFor(ctx, "LinkedIn") } catch (e: Exception) { "" }
                val profile = (if (liStyle.isNotBlank()) "Your LinkedIn voice/persona: $liStyle\n\n" else "") +
                    withContext(Dispatchers.IO) { MemoryStore.fullProfile(ctx) }
                val cap = count.coerceIn(1, MissionStore.dailyCap(ctx))
                val targets = withContext(Dispatchers.IO) {
                    ConnectionStore.neverReachedOut(ctx).filter { it.url.isNotBlank() }.take(cap)
                }
                total = targets.size
                if (total == 0) { lastMsg = "No never-reached connections with a profile link found. Import your LinkedIn network first."; running = false; onUpdate(); return@launch }
                for (c in targets) {
                    if (!running) break
                    lastMsg = "Messaging ${c.name}… (${sent + failed + 1}/$total)"; onUpdate()
                    // Anything we've already said to / heard from this person, so the opener continues the
                    // relationship instead of reading like a cold intro to someone you've talked to before.
                    val hist = withContext(Dispatchers.IO) { priorWith(ctx, c.name) }
                    val msg = withContext(Dispatchers.IO) {
                        AgentClient.tailoredOutreach(goal, c.name, c.role, c.company, profile, hist).take(600)
                    }
                    if (msg.length < 8 || msg.startsWith("[")) { failed++; lastMsg = "Skipped ${c.name}: couldn't draft."; onUpdate(); continue }
                    val (ok, detail) = TapSend.sendViaProfile(ctx, c.url, msg, c.name)
                    if (ok) {
                        sent++
                        withContext(Dispatchers.IO) {
                            ConnectionStore.markReachedOut(ctx, c.name)
                            MissionStore.addContacted(ctx, c.name)
                            OutboxStore.record(ctx, "LinkedIn", c.name, "reconnect", msg, "Autonomous reconnect: $goal", "sent")
                            MessageStore.insertOne(ctx, c.name, "LinkedIn", "me", "me", "Reconnected: $msg")
                            HealthStore.recordLlm(ctx, "tapsend", true)
                        }
                        lastMsg = "Sent to ${c.name} ✓  ($sent sent, $failed skipped)"
                    } else {
                        failed++; lastMsg = "Skipped ${c.name}: $detail"
                        HealthStore.note("reconnect_skip", false, "${c.name}: $detail")
                    }
                    onUpdate()
                    if (running) delay((25_000..45_000).random().toLong())   // human-paced gap
                }
                running = false
                lastMsg = "Done — $sent sent, $failed skipped."
                onUpdate()
            } catch (e: Exception) {
                running = false; lastMsg = "Stopped on an error: ${e.message}"; onUpdate()
            }
        }
    }

    /**
     * Whatever we've already exchanged with this person, oldest-first, as "You: …" / "Them: …" lines.
     * Empty when it's a genuinely first contact — which is what tells the drafter to write an intro.
     */
    private fun priorWith(ctx: Context, name: String): String = try {
        MessageStore.threadFor(ctx, name, 12)
            .filter { it.body.isNotBlank() }
            .joinToString("\n") { (if (it.role == "me") "You: " else "Them: ") + it.body.take(220) }
            .take(1800)
    } catch (e: Exception) { "" }

    /** One mission prospect to reach on LinkedIn. [url] is a direct profile if we have one, else blank. */
    data class Target(val name: String, val role: String, val company: String, val url: String)

    /**
     * MISSION outreach over LinkedIn instead of guessed email. Web-found prospects rarely carry a profile URL,
     * so for those we open a LinkedIn people-search for "Name Company" and tap the matching result before
     * messaging — the same deterministic single-send loop as reconnect (message → back out → next person).
     * This replaces the first.last@domain guessing that was bouncing on nearly every send.
     */
    fun startMission(ctx: Context, goal: String, targets: List<Target>, onUpdate: () -> Unit) {
        if (running) return
        if (!TapSend.available()) { lastMsg = "Turn on SlyOS accessibility (Settings → Total Recall) first."; onUpdate(); return }
        val list = targets.filter { it.name.isNotBlank() }.take(MissionStore.dailyCap(ctx))
        if (list.isEmpty()) { lastMsg = "No named people to reach — run a mission first."; onUpdate(); return }
        running = true; sent = 0; failed = 0; total = list.size; lastMsg = "Starting…"; onUpdate()
        job = scope.launch {
            try {
                val liStyle = try { MemoryStore.styleFor(ctx, "LinkedIn") } catch (e: Exception) { "" }
                val profile = (if (liStyle.isNotBlank()) "Your LinkedIn voice/persona: $liStyle\n\n" else "") +
                    withContext(Dispatchers.IO) { MemoryStore.fullProfile(ctx) }
                for (t in list) {
                    if (!running) break
                    lastMsg = "Messaging ${t.name}… (${sent + failed + 1}/$total)"; onUpdate()
                    val hist = withContext(Dispatchers.IO) { priorWith(ctx, t.name) }
                    val msg = withContext(Dispatchers.IO) {
                        AgentClient.tailoredOutreach(goal, t.name, t.role, t.company, profile, hist).take(600)
                    }
                    if (msg.length < 8 || msg.startsWith("[")) { failed++; lastMsg = "Skipped ${t.name}: couldn't draft."; onUpdate(); continue }
                    val (ok, detail) = if (t.url.contains("/in/")) TapSend.sendViaProfile(ctx, t.url, msg, t.name)
                        else TapSend.sendViaSearch(ctx, searchUrlFor(t), t.name, msg)
                    if (ok) {
                        sent++
                        withContext(Dispatchers.IO) {
                            MissionStore.addContacted(ctx, (t.name.trim() + "@" + t.company.trim()).trim('@'))
                            OutboxStore.record(ctx, "LinkedIn", t.name, "mission", msg, "Mission outreach: $goal", "sent")
                            MessageStore.insertOne(ctx, t.name, "LinkedIn", "me", "me", "Mission outreach: $msg")
                            HealthStore.recordLlm(ctx, "tapsend", true)
                        }
                        lastMsg = "Sent to ${t.name} ✓  ($sent sent, $failed skipped)"
                    } else {
                        failed++; lastMsg = "Skipped ${t.name}: $detail"
                        HealthStore.note("mission_skip", false, "${t.name}: $detail")
                    }
                    onUpdate()
                    if (running) delay((25_000..45_000).random().toLong())
                }
                running = false
                lastMsg = "Done — $sent sent, $failed skipped."
                onUpdate()
            } catch (e: Exception) {
                running = false; lastMsg = "Stopped on an error: ${e.message}"; onUpdate()
            }
        }
    }

    /** LinkedIn people-search deep link aimed at this specific person (name + company narrows it hard). */
    private fun searchUrlFor(t: Target): String {
        val kw = (t.name + " " + t.company).trim()
        return "https://www.linkedin.com/search/results/people/?keywords=" +
            java.net.URLEncoder.encode(kw, "UTF-8")
    }
}
