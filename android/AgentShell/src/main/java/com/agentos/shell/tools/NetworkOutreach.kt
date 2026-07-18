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
                    val msg = withContext(Dispatchers.IO) {
                        AgentClient.tailoredOutreach(goal, c.name, c.role, c.company, profile).take(600)
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
}
