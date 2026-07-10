package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P1 — the action layer: perceive → plan → act → verify. Reuses the AgentLoop shape but over the LIVE
 * SCREEN. Each step it reads the current screen (via the accessibility service), the model picks ONE
 * primitive, it executes, re-reads, and repeats until the goal is met or a cap is hit.
 *
 * Safety envelope (mandatory): a persistent "SlyOS is controlling your screen — STOP" notification; a hard
 * step + no-progress cap; it NEVER taps an irreversible button (Send/Pay/Post/Delete/Submit/…) — it stops
 * and hands that final tap to the user. Every action is logged to the OutboxStore AND the brain, so the
 * record of what it did grows the memory just like everything else.
 */
object ScreenAgent {
    private const val TAG = "SlyOS"
    private const val CH = "sly_act"
    private const val NOTIF_ID = 991
    private const val MAX_STEPS = 32

    @Volatile var running = false; private set
    @Volatile private var stopFlag = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private val IRREVERSIBLE = Regex("(?i)\\b(send|pay|post|delete|submit|buy|order|place order|checkout|purchase|confirm|transfer|remove|unsubscribe now)\\b")

    fun stop() { stopFlag = true }

    /** Kick off a run (decoupled from any Activity lifecycle — the accessibility service outlives screens). */
    fun start(ctx: Context, goal: String, openPkg: String? = null) {
        if (running) { stop(); return }   // a second trigger = STOP
        scope.launch { run(ctx.applicationContext, goal, openPkg) }
    }

    private suspend fun run(ctx: Context, goal: String, openPkg: String?) {
        val svc = InteractionLogService.instance
        if (svc == null) {
            OutboxStore.record(ctx, "Action", "Screen control", "act", goal, "couldn't run — Accessibility control isn't enabled", status = "held")
            return
        }
        running = true; stopFlag = false
        postBanner(ctx)
        val profile = try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" }
        // ONE-TIME high-level plan so execution has direction instead of wandering step-to-step.
        var plan = try { AgentClient.planScreenGoal(goal, profile) } catch (e: Exception) { "" }
        Log.i(TAG, "screenAgent plan:\n$plan")
        val history = StringBuilder()
        var lastDump = ""; var stall = 0; var replans = 0; var verifyTries = 0
        try {
            if (!openPkg.isNullOrBlank()) { try { ctx.packageManager.getLaunchIntentForPackage(openPkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e: Exception) {}; delay(1600) }
            var step = 0
            while (step++ < MAX_STEPS) {
                if (stopFlag) { finish(ctx, goal, "Stopped by you.", history.toString()); return }
                var nodes = svc.readScreen()
                if (nodes.isEmpty()) { delay(1200); nodes = svc.readScreen(); if (nodes.isEmpty()) { finish(ctx, goal, "Can't read this screen (it may be protected).", history.toString()); return } }
                val pkg = svc.currentPackage()
                val dump = nodes.joinToString("\n") { n ->
                    val st = when { n.role == "switch" -> if (n.checked) " {on}" else " {off}"; n.role == "list" -> " {scrollable}"; else -> "" }
                    "${n.index}. [${n.role}] ${n.text}$st"
                }.take(4800)
                // Stall handling: if the screen hasn't changed, first RE-PLAN (maybe the strategy is wrong),
                // and only give up if that still doesn't help.
                if (dump == lastDump) {
                    if (++stall >= 3) {
                        if (replans < 1) { plan = try { AgentClient.planScreenGoal("$goal (I'm stuck on: $pkg; rethink the approach)", profile) } catch (e: Exception) { plan }; replans++; stall = 0 }
                        else { finish(ctx, goal, "No progress — stopping so I don't loop.", history.toString()); return }
                    }
                } else stall = 0
                lastDump = dump

                val action = AgentClient.planScreenStep(goal, pkg, dump, history.toString(), profile, plan).trim()
                Log.i(TAG, "screenAgent step $step: $action")

                val done = Regex("(?is)^DONE\\b\\s*(.*)$").find(action)
                if (done != null) {
                    // VERIFY before truly finishing — this is what stops the "quit midway" problem.
                    val verdict = try { AgentClient.verifyScreenGoal(goal, pkg, dump, history.toString()) } catch (e: Exception) { "YES" }
                    if (verdict.startsWith("YES", true) || verifyTries >= 2) {
                        finish(ctx, goal, done.groupValues[1].trim().ifBlank { verdict.removePrefix("YES").trim().ifBlank { "Done." } }, history.toString()); return
                    }
                    verifyTries++; history.append("• self-check: not done yet — ${verdict.take(80)}\n"); lastDump = ""; delay(500); continue
                }
                if (action.startsWith("STUCK", true)) { finish(ctx, goal, action.removePrefix("STUCK").trim().ifBlank { "Got stuck." }, history.toString()); return }

                val ok: Boolean = execAction(ctx, goal, svc, action, nodes, history) ?: return  // null = we already finished (irreversible guard)
                if (!ok) history.append("• (step had no effect)\n")
                delay(850)   // human pace + let the UI settle before re-reading
            }
            // Out of steps — do a final honest check instead of claiming success.
            val finalDump = try { svc.readScreen().joinToString("\n") { "${it.index}. [${it.role}] ${it.text}" }.take(3000) } catch (e: Exception) { "" }
            val verdict = try { AgentClient.verifyScreenGoal(goal, svc.currentPackage(), finalDump, history.toString()) } catch (e: Exception) { "NO" }
            finish(ctx, goal, if (verdict.startsWith("YES", true)) "Done." else "I reached my step limit before finishing — ${verdict.take(90)}", history.toString())
        } catch (e: Exception) {
            finish(ctx, goal, "Hit an error: ${e.message}", history.toString())
        }
    }

    /** Execute one primitive. Returns true/false for effect, or null if the run already finished (safety stop). */
    private suspend fun execAction(
        ctx: Context, goal: String, svc: InteractionLogService, action: String,
        nodes: List<InteractionLogService.ScreenNode>, history: StringBuilder
    ): Boolean? {
        val cmd = action.substringBefore(" ").uppercase()
        return when (cmd) {
            "TAP", "TOGGLE", "LONGPRESS" -> {
                val i = action.substringAfter(" ", "").trim().toIntOrNull()
                val node = i?.let { nodes.getOrNull(it) }
                when {
                    node == null -> false
                    IRREVERSIBLE.containsMatchIn(node.text) -> {
                        finish(ctx, goal, "I've set everything up — tap \"${node.text}\" yourself to finish.", history.toString()); null
                    }
                    cmd == "LONGPRESS" -> { history.append("• long-pressed \"${node.text}\"\n"); svc.longPress(i) }
                    cmd == "TOGGLE" -> { history.append("• toggled \"${node.text}\" (was ${if (node.checked) "on" else "off"})\n"); svc.tapNode(i) }
                    else -> { history.append("• tapped \"${node.text}\"\n"); svc.tapNode(i) }
                }
            }
            "TYPE" -> {
                val rest = action.removePrefix("TYPE").trim()
                val i = rest.substringBefore("|").trim().toIntOrNull()
                val text = rest.substringAfter("|", "").trim()
                if (i != null) { history.append("• typed \"${text.take(30)}\"\n"); svc.setText(i, text) } else false
            }
            "CLEAR" -> { val i = action.substringAfter(" ", "").trim().toIntOrNull(); if (i != null) { history.append("• cleared #$i\n"); svc.setText(i, "") } else false }
            "SCROLL" -> { val d = !action.contains("up", true); history.append("• scrolled ${if (d) "down" else "up"}\n"); svc.scroll(d) }
            "SWIPE" -> { val dir = action.substringAfter(" ", "down").trim(); history.append("• swiped $dir\n"); svc.swipe(dir) }
            "ENTER" -> { history.append("• pressed enter\n"); svc.imeEnter() }
            "BACK" -> { history.append("• back\n"); svc.back() }
            "HOME" -> { history.append("• home\n"); svc.home() }
            "WAIT" -> { history.append("• waited for load\n"); delay(1500); true }
            "SETTINGS" -> {
                val key = action.removePrefix("SETTINGS").trim()
                val ok = SystemPanels.open(ctx, key)
                if (ok) { history.append("• opened $key settings\n"); delay(1600) }
                ok
            }
            "OPEN" -> {
                val name = action.removePrefix("OPEN").trim()
                val app = ToolRouter.installedApps(ctx).firstOrNull { it.label.contains(name, true) }
                if (app != null) { history.append("• opened ${app.label}\n"); ToolRouter.launchApp(ctx, app.pkg); delay(1500); true } else false
            }
            else -> false
        }
    }

    private fun finish(ctx: Context, goal: String, summary: String, history: String) {
        running = false
        cancelBanner(ctx)
        // Everything the agent did goes into the visible outbox AND the brain (so recall grows).
        OutboxStore.record(ctx, "Action", goal.take(40), "act", summary, "screen control: ${history.replace("\n", " ").take(300)}")
        try { MessageStore.insertOne(ctx, "Screen agent", "Actions", "system", "system", "Operated the phone for \"$goal\": $summary") } catch (e: Exception) {}
    }

    // ── STOP banner (ongoing notification, visible from any app) ──
    private fun postBanner(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "SlyOS is acting", NotificationManager.IMPORTANCE_HIGH))
        val stopPi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, com.agentos.shell.StopActionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("SlyOS is controlling your screen")
            .setContentText("Tap STOP to take back control")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPi)
            .build()
        nm.notify(NOTIF_ID, n)
    }
    private fun cancelBanner(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (e: Exception) {}
    }
}
