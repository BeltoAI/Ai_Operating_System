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
    private const val MAX_STEPS = 18

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
        val history = StringBuilder()
        var lastDump = ""; var stall = 0
        try {
            if (!openPkg.isNullOrBlank()) { try { ctx.packageManager.getLaunchIntentForPackage(openPkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e: Exception) {}; delay(1600) }
            var step = 0
            while (step++ < MAX_STEPS) {
                if (stopFlag) { finish(ctx, goal, "Stopped by you.", history.toString()); return }
                val nodes = svc.readScreen()
                if (nodes.isEmpty()) { finish(ctx, goal, "Can't read this screen (it may be protected).", history.toString()); return }
                val dump = nodes.joinToString("\n") { "${it.index}. [${it.role}] ${it.text}" }.take(3500)
                if (dump == lastDump) { if (++stall >= 3) { finish(ctx, goal, "No progress — stopping.", history.toString()); return } } else stall = 0
                lastDump = dump
                val plan = AgentClient.planScreenStep(goal, svc.currentPackage(), dump, history.toString(), profile).trim()
                Log.i(TAG, "screenAgent step $step: $plan")

                val done = Regex("(?is)^DONE\\b\\s*(.*)$").find(plan)
                if (done != null) { finish(ctx, goal, done.groupValues[1].trim().ifBlank { "Done." }, history.toString()); return }
                if (plan.startsWith("STUCK", true)) { finish(ctx, goal, plan.removePrefix("STUCK").trim().ifBlank { "Got stuck." }, history.toString()); return }

                val ok: Boolean = when {
                    plan.startsWith("TAP", true) -> {
                        val i = plan.removePrefix("TAP").trim().toIntOrNull()
                        val node = i?.let { nodes.getOrNull(it) }
                        when {
                            node == null -> false
                            IRREVERSIBLE.containsMatchIn(node.text) -> {   // safety: never auto-tap a send/pay/submit
                                finish(ctx, goal, "I've set everything up — tap \"${node.text}\" yourself to finish.", history.toString())
                                return
                            }
                            else -> { history.append("• tapped \"${node.text}\"\n"); svc.tapNode(i) }
                        }
                    }
                    plan.startsWith("TYPE", true) -> {
                        val rest = plan.removePrefix("TYPE").trim()
                        val i = rest.substringBefore("|").trim().toIntOrNull()
                        val text = rest.substringAfter("|", "").trim()
                        if (i != null) { history.append("• typed into #$i\n"); svc.setText(i, text) } else false
                    }
                    plan.startsWith("SCROLL", true) -> { val d = !plan.contains("up", true); history.append("• scrolled\n"); svc.scroll(d) }
                    plan.startsWith("BACK", true) -> { history.append("• back\n"); svc.back() }
                    plan.startsWith("OPEN", true) -> {
                        val name = plan.removePrefix("OPEN").trim()
                        val app = ToolRouter.installedApps(ctx).firstOrNull { it.label.contains(name, true) }
                        if (app != null) { history.append("• opened ${app.label}\n"); ToolRouter.launchApp(ctx, app.pkg); delay(1500); true } else false
                    }
                    else -> false
                }
                if (!ok) history.append("• (step had no effect)\n")
                delay(900)   // human pace + let the UI settle before re-reading
            }
            finish(ctx, goal, "Reached the step limit.", history.toString())
        } catch (e: Exception) {
            finish(ctx, goal, "Hit an error: ${e.message}", history.toString())
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
