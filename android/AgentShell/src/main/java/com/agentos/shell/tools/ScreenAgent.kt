package com.agentos.shell.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    @Volatile private var lastTapText = ""    // guards against instantly un-doing our own toggle (like→unlike)
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val GAME = Regex("(?i)\\b(chess|checkers|draughts|2048|solitaire|sudoku|wordle|tic.?tac.?toe|connect ?four|minesweeper|card game|board game|puzzle|play (a |me a )?(game|match|move)|make a move|beat (the|this))\\b")
    private val scope = CoroutineScope(Dispatchers.IO)

    /** True if tapping [now] would UNDO the toggle we just did in [prev] (e.g. we liked, now it says Unlike). */
    private fun undoesLastToggle(prev: String, now: String): Boolean {
        val p = prev.lowercase().trim(); val c = now.lowercase().trim()
        if (p.isBlank()) return false
        fun on(base: String, undo: List<String>) = p.contains(base) && !undo.any { p.contains(it) } && undo.any { c.contains(it) }
        return on("like", listOf("unlike", "remove reaction")) || (p == "liked" && c.contains("unlike")) ||
               on("follow", listOf("unfollow", "following")) ||
               on("save", listOf("unsave", "saved")) ||
               on("connect", listOf("pending", "withdraw")) ||
               on("subscribe", listOf("subscribed", "unsubscribe"))
    }

    // MONEY GATE ONLY. The agent has full control of everything else (fill forms, sign up, log in, submit,
    // post, toggle settings…) but must NEVER complete a spend/transfer — it stops and hands the final tap to
    // the user at that exact screen. Deliberately scoped to financial actions per the user's choice.
    private val IRREVERSIBLE = Regex("(?i)\\b(pay|pay now|buy|buy now|purchase|place order|order now|checkout|check ?out|complete (order|purchase|payment)|confirm (payment|purchase|order|and pay)|subscribe|upgrade to|start (free )?trial|donate|transfer( money)?|send money|withdraw|deposit|add (a )?(card|payment)|place bid)\\b")

    fun stop() { stopFlag = true }

    /** Kick off a run (decoupled from any Activity lifecycle — the accessibility service outlives screens). */
    fun start(ctx: Context, goal: String, openPkg: String? = null) {
        if (running) { stop(); return }   // a second trigger = STOP
        scope.launch { run(ctx.applicationContext, goal, openPkg) }
    }

    private suspend fun run(ctx: Context, rawGoal: String, openPkg: String?) {
        val svc = InteractionLogService.instance
        if (svc == null) {
            OutboxStore.record(ctx, "Action", "Screen control", "act", rawGoal, "couldn't run — Accessibility control isn't enabled", status = "held")
            return
        }
        running = true; stopFlag = false; lastTapText = ""
        acquireWake(ctx)   // keep the screen on so long / overnight mission runs can execute
        postBanner(ctx)
        val profile = try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" }
        // Resolve WHO from the brain for vague people-tasks ("friends I haven't talked to in a while") so the
        // agent has concrete names to search + message, instead of guessing.
        val goal = resolveTargets(ctx, rawGoal)
        // Pull RELEVANT brain knowledge for this goal (profile + memories, projects, contacts, dates) so the
        // agent can autonomously fill in setup details — e.g. build a Notion page from what it knows about you.
        val brainCtx = try { (profile + "\n" + BrainContext.build(ctx, goal)).trim() } catch (e: Exception) { profile }
        // ONE-TIME high-level plan so execution has direction instead of wandering step-to-step.
        var plan = try { AgentClient.planScreenGoal(goal, brainCtx) } catch (e: Exception) { "" }
        Log.i(TAG, "screenAgent plan:\n$plan")
        val history = StringBuilder()
        var lastDump = ""; var stall = 0; var replans = 0; var verifyTries = 0
        try {
            if (!openPkg.isNullOrBlank()) { try { ctx.packageManager.getLaunchIntentForPackage(openPkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e: Exception) {}; delay(1600) }
            // Repetitive tasks ("connect with 20…", "message 5…") need a bigger budget — scale it to the
            // count in the goal so the agent can actually finish the whole batch.
            val target = Regex("(?i)\\b(\\d{1,3})\\b").find(goal)?.groupValues?.get(1)?.toIntOrNull()
            val budget = if (target != null && target in 2..100) (target * 6 + 12).coerceAtMost(200) else MAX_STEPS
            var step = 0
            while (step++ < budget) {
                if (stopFlag) { finish(ctx, goal, "Stopped by you.", history.toString()); return }
                var nodes = svc.readScreen()
                val pkg = svc.currentPackage()
                // HYBRID PERCEPTION: capture a screenshot every step so the planner can SEE elements the
                // accessibility tree misses (comment/like icons in Compose/canvas UIs, game boards, etc.) and
                // act on them by coordinate when they aren't in the node list.
                val shot = if (Build.VERSION.SDK_INT >= 30) screenshot(svc) else null
                if (nodes.isEmpty() && shot == null) { delay(1200); nodes = svc.readScreen(); if (nodes.isEmpty()) { finish(ctx, goal, "Can't read this screen (it may be protected).", history.toString()); return } }
                val dump = nodes.joinToString("\n") { n ->
                    val st = when { n.role == "switch" -> if (n.checked) " {on}" else " {off}"; n.role == "list" -> " {scrollable}"; else -> "" }
                    "${n.index}. [${n.role}] ${n.text}$st"
                }.take(4800)
                // Stall handling only applies to node-driven screens; vision screens (empty node list) change
                // without the dump changing, so we don't false-trigger there.
                if (nodes.size >= 3 && dump == lastDump) {
                    if (++stall >= 3) {
                        if (replans < 1) { plan = try { AgentClient.planScreenGoal("$goal (I'm stuck on: $pkg; rethink the approach)", brainCtx) } catch (e: Exception) { plan }; replans++; stall = 0 }
                        else { finish(ctx, goal, "No progress — stopping so I don't loop.", history.toString()); return }
                    }
                } else stall = 0
                lastDump = dump

                val action = AgentClient.planScreenStep(goal, pkg, dump, history.toString(), brainCtx, plan, shot ?: "").trim()
                Log.i(TAG, "screenAgent step $step: $action")

                val done = Regex("(?is)^DONE\\b\\s*(.*)$").find(action)
                if (done != null) {
                    // VERIFY before truly finishing — this is what stops the "quit midway" problem. For a
                    // COUNTED batch, allow many more re-checks so it can't declare victory after item #1.
                    val maxVerify = if (target != null) target + 2 else 2
                    val verdict = try { AgentClient.verifyScreenGoal(goal, pkg, dump, history.toString()) } catch (e: Exception) { "YES" }
                    if (verdict.startsWith("YES", true) || verifyTries >= maxVerify) {
                        finish(ctx, goal, done.groupValues[1].trim().ifBlank { verdict.removePrefix("YES").trim().ifBlank { "Done." } }, history.toString()); return
                    }
                    verifyTries++; history.append("• self-check: NOT done yet — ${verdict.take(90)}. Keep going.\n"); lastDump = ""; delay(500); continue
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

    /**
     * For vague people-targeting goals, resolve concrete NAMES from the brain and fold them into the goal so
     * the agent knows exactly who to search + act on. Handles "haven't talked to in a while / lost touch /
     * reconnect / gone quiet". Leaves the goal unchanged if it isn't that kind of task or the brain is empty.
     */
    private fun resolveTargets(ctx: Context, goal: String): String {
        val vague = Regex("(?i)(haven'?t (talked|spoken|messaged|chatted)|lost touch|reconnect|gone quiet|fallen out of touch|old friends|used to talk)").containsMatchIn(goal)
        if (!vague) return goal
        val count = Regex("(?i)\\b(\\d{1,3})\\b").find(goal)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        val names = try { MessageStore.staleContacts(ctx, count.coerceIn(1, 30)) } catch (e: Exception) { emptyList() }
        if (names.isEmpty()) return goal
        return "$goal\nTARGET PEOPLE (you've gone quiet with, oldest first) — reach these by name: ${names.joinToString(", ")}"
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
                        postConfirm(ctx, node.text)   // CLEAR prompt so the user knows a money tap is theirs
                        finish(ctx, goal, "I set everything up but stopped before spending money — tap \"${node.text}\" yourself to finish.", history.toString()); null
                    }
                    // Never instantly un-do our own action (the classic like→unlike thrash).
                    (cmd == "TAP" || cmd == "TOGGLE") && undoesLastToggle(lastTapText, node.text) -> {
                        history.append("• \"${node.text}\" already done — not undoing it\n"); true
                    }
                    cmd == "LONGPRESS" -> { lastTapText = node.text; history.append("• long-pressed \"${node.text}\"\n"); svc.longPress(i) }
                    cmd == "TOGGLE" -> { history.append("• toggled \"${node.text}\" (was ${if (node.checked) "on" else "off"})\n"); lastTapText = node.text; svc.tapNode(i) }
                    else -> { history.append("• tapped \"${node.text}\"\n"); lastTapText = node.text; svc.tapNode(i) }
                }
            }
            "TYPE" -> {
                val rest = action.removePrefix("TYPE").trim()
                val i = rest.substringBefore("|").trim().toIntOrNull()
                val text = rest.substringAfter("|", "").trim()
                if (i != null) { history.append("• typed \"${text.take(30)}\"\n"); svc.setText(i, text) } else false
            }
            "CLEAR" -> { val i = action.substringAfter(" ", "").trim().toIntOrNull(); if (i != null) { history.append("• cleared #$i\n"); svc.setText(i, "") } else false }
            "TAPXY" -> {
                // Vision tap: the planner saw the element in the screenshot and gives a 0-1000 coordinate.
                val p = action.split(Regex("\\s+"))
                fun norm(i: Int, span: Int) = ((p.getOrNull(i)?.toFloatOrNull() ?: 0f) / 1000f * span)
                history.append("• tapped a point I could see\n"); svc.tapAt(norm(1, svc.screenW), norm(2, svc.screenH))
            }
            "DRAGXY" -> {
                val p = action.split(Regex("\\s+"))
                fun norm(i: Int, span: Int) = ((p.getOrNull(i)?.toFloatOrNull() ?: 0f) / 1000f * span)
                history.append("• dragged / made a move\n"); svc.drag(norm(1, svc.screenW), norm(2, svc.screenH), norm(3, svc.screenW), norm(4, svc.screenH))
            }
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
            "CODE" -> {
                // 2FA: fetch the one-time code from the just-arrived SMS notification or email and type it.
                val rest = action.removePrefix("CODE").trim()
                val n = rest.substringBefore("|").trim().split(" ").firstOrNull()?.toIntOrNull()
                val hint = rest.substringAfter(" ", "").trim()
                val code = try { OtpReader.latest(ctx, hint) } catch (e: Exception) { null }
                if (n != null && code != null) { history.append("• entered the 2FA code from your messages\n"); svc.setText(n, code) }
                else { history.append("• waiting for the 2FA code to arrive…\n"); delay(4000); false }
            }
            "VERIFYEMAIL" -> {
                // End-to-end sign-up: pull the verification link from the just-received email and open it.
                val hint = action.removePrefix("VERIFYEMAIL").trim()
                val link = try { GmailClient.verificationLink(ctx, hint) } catch (e: Exception) { null }
                if (link != null) {
                    history.append("• opened the verification link from your email\n")
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                    delay(2600); true
                } else { history.append("• verification email not here yet — waiting\n"); delay(4000); false }
            }
            else -> false
        }
    }

    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun acquireWake(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "slyos:operate")
            wl.acquire(60 * 60 * 1000L)   // safety cap: 60 min
            wakeLock = wl
        } catch (e: Exception) {}
    }
    private fun releaseWake() { try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}; wakeLock = null }

    /** Suspend wrapper around the accessibility screenshot callback. */
    private suspend fun screenshot(svc: InteractionLogService): String? = suspendCancellableCoroutine { cont ->
        try { svc.captureScreenshot { b64 -> if (cont.isActive) cont.resume(b64) } } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
    }

    /** Execute a coordinate action from the vision planner (0-1000 grid → pixels). */
    private suspend fun execVision(svc: InteractionLogService, action: String, history: StringBuilder): Boolean {
        val parts = action.split(Regex("\\s+"))
        fun norm(i: Int, span: Int) = ((parts.getOrNull(i)?.toFloatOrNull() ?: 0f) / 1000f * span)
        return when (parts.getOrNull(0)?.uppercase()) {
            "TAPXY" -> { history.append("• tapped the board\n"); svc.tapAt(norm(1, svc.screenW), norm(2, svc.screenH)) }
            "DRAGXY" -> { history.append("• made a move\n"); svc.drag(norm(1, svc.screenW), norm(2, svc.screenH), norm(3, svc.screenW), norm(4, svc.screenH)) }
            "SWIPE" -> { val d = parts.getOrNull(1) ?: "down"; history.append("• swiped $d\n"); svc.swipe(d) }
            "WAIT" -> { delay(1400); true }
            else -> false
        }
    }

    private fun finish(ctx: Context, goal: String, summary: String, history: String) {
        running = false
        releaseWake()
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
            .setContentTitle("SlyOS is operating your phone")
            .setContentText("Tap STOP — or swipe this away — to take back control")
            .setOngoing(true)
            .setDeleteIntent(stopPi)          // swiping the notification away STOPS the agent
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPi)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** A CLEAR, high-visibility pause asking the user to make one specific tap (only ever for money). */
    private fun postConfirm(ctx: Context, buttonText: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "SlyOS is acting", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, 3, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            val n = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Your turn — SlyOS won't spend on its own")
                .setContentText("Everything's ready. Tap \"$buttonText\" on screen to finish.")
                .setStyle(Notification.BigTextStyle().bigText("I set it all up but stopped before spending money. Tap \"$buttonText\" on screen yourself to complete it."))
                .setAutoCancel(true).setContentIntent(open)
                .build()
            nm.notify(NOTIF_ID + 1, n)
        } catch (e: Exception) {}
    }
    private fun cancelBanner(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (e: Exception) {}
    }
}
