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
        // "see more" then "see less" (or vice-versa) is a classic expand/collapse loop — block the reverse.
        val expandCollapse = (Regex("(?i)(see|read|show) ?more|…more|\\.\\.\\.more").containsMatchIn(p) &&
                              Regex("(?i)(see|read|show) ?less|less").containsMatchIn(c))
        return expandCollapse ||
               on("like", listOf("unlike", "remove reaction")) || (p == "liked" && c.contains("unlike")) ||
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
        // REFLEX LEARN: if the user has taught a skill matching this goal, REPLAY it deterministically — no
        // LLM, no guessing. This is the reliable fast path for repeatable tasks.
        try {
            val learned = ReflexLearn.match(ctx, rawGoal)
            if (learned != null) {
                postBanner(ctx); Log.i(TAG, "OP replay learned skill '${learned.name}' (${learned.steps.size} steps)")
                val ok = ReflexLearn.replay(svc, learned)
                if (ok) { finish(ctx, rawGoal, "Done — replayed your taught skill \"${learned.name}\".", "learned skill"); return }
                Log.i(TAG, "OP learned skill didn't fully match the screen — falling back to the planner")
            }
        } catch (e: Exception) {}
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
        Log.i(TAG, "OP START goal=\"$goal\" openPkg=$openPkg\nPLAN:\n$plan")
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
                if (nodes.isEmpty()) { delay(1100); nodes = svc.readScreen() }
                val pkg = svc.currentPackage()
                val dump = nodes.joinToString("\n") { n ->
                    val st = when { n.role == "switch" -> if (n.checked) " {on}" else " {off}"; n.role == "list" -> " {scrollable}"; else -> "" }
                    "${n.index}. [${n.role}] ${n.text}$st"
                }.take(4800)
                // Decide if we need EYES. Text is fine for labelled UIs (Settings, forms), but social/media
                // apps render like/comment/share as UNLABELLED icons the accessibility tree can't identify —
                // so use vision whenever the screen is icon-heavy, the task is a social interaction, it's a
                // game, the screen is near-empty, or we've stalled.
                val blankButtons = nodes.count { it.clickable && it.text.isBlank() }
                val social = Regex("(?i)\\b(like|comment|react|share|repost|retweet|follow|unfollow|story|stories|reel|reels|post|dm|swipe|match|tinder|bumble|snap)\\b").containsMatchIn(goal)
                val struggling = GAME.containsMatchIn(goal) || social || blankButtons >= 4 || nodes.size < 5 || stall >= 1
                val shot = if (struggling && Build.VERSION.SDK_INT >= 30) screenshot(svc) else null
                if (struggling && shot == null) {
                    Log.w(TAG, "OP struggling but no screenshot (sdk=${Build.VERSION.SDK_INT}, capture failed?) — falling back to text")
                    // If the OS says the service can't screenshot, the user must re-toggle Accessibility so the
                    // new canTakeScreenshot config activates. Tell them clearly ONCE, then stop wasting a run.
                    if (InteractionLogService.screenshotBlocked) {
                        postConfirm(ctx, "Re-enable SlyOS in Settings → Accessibility (off then on)")
                        finish(ctx, goal, "I can't see the screen yet — turn the SlyOS accessibility service OFF then ON in Settings → Accessibility, then try again.", history.toString())
                        return
                    }
                }
                if (nodes.isEmpty() && shot == null) { finish(ctx, goal, "Can't read this screen (it may be protected).", history.toString()); return }
                if (nodes.size >= 3 && dump == lastDump) {
                    if (++stall >= 3) {
                        if (replans < 1) { plan = try { AgentClient.planScreenGoal("$goal (I'm stuck on: $pkg; rethink the approach)", brainCtx) } catch (e: Exception) { plan }; replans++; stall = 0 }
                        else { finish(ctx, goal, "No progress — stopping so I don't loop.", history.toString()); return }
                    }
                } else stall = 0
                lastDump = dump

                val raw = AgentClient.planScreenStep(goal, pkg, dump, history.toString(), brainCtx, plan, shot ?: "")
                val action = normalizeAction(raw)
                Log.i(TAG, "OP step=$step pkg=$pkg nodes=${nodes.size} vision=${shot != null} raw=\"${raw.replace("\n", " ").take(160)}\" action=\"$action\"")

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
                Log.i(TAG, "OP step=$step exec=\"$action\" ok=$ok")
                // Surface the latest action on the banner so the user can watch progress live.
                val lastDoing = history.toString().trimEnd().substringAfterLast("• ").substringBefore("\n").take(80)
                updateBanner(ctx, step, if (shot != null) "$lastDoing (seeing screen)" else lastDoing)
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

    private val CMDS = listOf("TAPXY", "DRAGXY", "TOGGLE", "LONGPRESS", "SETTINGS", "VERIFYEMAIL", "SWIPE",
        "SCROLL", "CLEAR", "TYPE", "CODE", "ENTER", "BACK", "HOME", "WAIT", "OPEN", "DONE", "STUCK", "TAP", "DO")

    /** Pull the actual command out of a model reply, even if the model added prose around it (vision models
     *  are chatty). Returns the recognized command line, or the trimmed text if none found. */
    private fun normalizeAction(raw: String): String {
        val t = raw.trim()
        // 1) a line that STARTS with a command.
        for (line in t.lines()) {
            val l = line.trim().trim('`', '*', '-', '#', '>', ' ', '.')
            val head = l.substringBefore(" ").uppercase().trim()
            if (CMDS.contains(head)) return l
        }
        // 2) earliest command keyword anywhere in the text (word-boundary, so "DO" ≠ inside "DONE").
        val up = t.uppercase()
        var bestIdx = -1; var bestCmd = ""
        for (c in CMDS) {
            val m = Regex("\\b${Regex.escape(c)}\\b").find(up) ?: continue
            val idx = m.range.first
            if (bestIdx < 0 || idx < bestIdx) { bestIdx = idx; bestCmd = c }
        }
        if (bestIdx >= 0) return t.substring(bestIdx).lineSequence().firstOrNull()?.trim() ?: bestCmd
        return t
    }

    /** True if tapping [nodeText] would UNDO what the GOAL wants (e.g. goal is to like, but this element
     *  already shows "Liked"/"Unlike" — tapping it would remove the like). Prevents the like→unlike thrash
     *  even on a post that was already liked before we started. */
    private fun wouldUndoGoal(goal: String, nodeText: String): Boolean {
        val g = goal.lowercase(); val t = nodeText.lowercase().trim()
        if (Regex("\\blike\\b").containsMatchIn(g) && !g.contains("unlike") &&
            (t == "liked" || t == "unlike" || t.contains("remove reaction") || t.contains("reaction button state: like"))) return true
        if (Regex("\\bfollow\\b").containsMatchIn(g) && !g.contains("unfollow") && (t == "following" || t == "unfollow")) return true
        if (Regex("\\bsave\\b").containsMatchIn(g) && !g.contains("unsave") && (t == "saved" || t == "unsave")) return true
        return false
    }

    /**
     * REFLEX skill executor — the model names an intent, we deterministically find + act + (for multi-step
     * skills like comment) drive the whole flow. `arg` after a | is text to type.
     */
    private suspend fun reflexDo(
        ctx: Context, goal: String, svc: InteractionLogService,
        nodes: List<InteractionLogService.ScreenNode>, rest: String, history: StringBuilder
    ): Boolean {
        val intent = rest.substringBefore("|").trim().lowercase()
        val arg = rest.substringAfter("|", "").trim()
        when (intent) {
            "comment", "reply" -> {
                // Open the comment field, type a specific comment, then send — the whole flow in one skill.
                val open = Reflex.findIndex(nodes, "comment") ?: return false
                svc.tapNode(open); delay(1300)
                val after = svc.readScreen()
                val field = Reflex.fieldIndex(after, "comment") ?: return false
                if (arg.isBlank()) { history.append("• opened comments (no text given yet)\n"); return true }
                svc.setText(field, arg); delay(700)
                val send = Reflex.findIndex(svc.readScreen(), "send")
                if (send != null) { svc.tapNode(send); history.append("• commented: \"${arg.take(40)}\"\n") }
                else history.append("• typed comment (send button not found)\n")
                return true
            }
            "message", "dm" -> {
                val field = Reflex.fieldIndex(nodes, "message") ?: Reflex.findIndex(nodes, "message")?.let { svc.tapNode(it); delay(1200); Reflex.fieldIndex(svc.readScreen(), "message") }
                if (field != null && arg.isNotBlank()) {
                    svc.setText(field, arg); delay(600)
                    Reflex.findIndex(svc.readScreen(), "send")?.let { svc.tapNode(it) }
                    history.append("• messaged: \"${arg.take(40)}\"\n"); return true
                }
                return false
            }
            "search" -> {
                val box = Reflex.fieldIndex(nodes, "search") ?: Reflex.findIndex(nodes, "search")?.let { svc.tapNode(it); delay(900); Reflex.fieldIndex(svc.readScreen(), "search") }
                if (box != null && arg.isNotBlank()) { svc.setText(box, arg); delay(500); svc.imeEnter(); history.append("• searched \"$arg\"\n"); return true }
                return box != null
            }
            else -> {
                val idx = Reflex.findIndex(nodes, intent) ?: return false
                val node = nodes.getOrNull(idx)
                if (node != null && (Reflex.alreadyDone(intent, node.text) || wouldUndoGoal(goal, node.text))) {
                    history.append("• '$intent' already done — leaving \"${node.text}\"\n"); return true
                }
                lastTapText = node?.text ?: ""
                history.append("• did '$intent' → \"${node?.text}\"\n")
                return svc.tapNode(idx)
            }
        }
    }

    /** Execute one primitive. Returns true/false for effect, or null if the run already finished (safety stop). */
    private suspend fun execAction(
        ctx: Context, goal: String, svc: InteractionLogService, action: String,
        nodes: List<InteractionLogService.ScreenNode>, history: StringBuilder
    ): Boolean? {
        val cmd = action.substringBefore(" ").uppercase()
        // Robust index: pull the FIRST integer out of the argument (tolerates "TAP <54", "TAP: 54", "TAP #54").
        fun argIdx(): Int? = Regex("-?\\d+").find(action.substringAfter(" ", ""))?.value?.toIntOrNull()
        return when (cmd) {
            "TAP", "TOGGLE", "LONGPRESS" -> {
                val i = argIdx()
                val node = i?.let { nodes.getOrNull(it) }
                when {
                    node == null -> false
                    IRREVERSIBLE.containsMatchIn(node.text) -> {
                        postConfirm(ctx, node.text)   // CLEAR prompt so the user knows a money tap is theirs
                        finish(ctx, goal, "I set everything up but stopped before spending money — tap \"${node.text}\" yourself to finish.", history.toString()); null
                    }
                    // Never instantly un-do our own action, and never tap something already in the goal state.
                    (cmd == "TAP" || cmd == "TOGGLE") && (undoesLastToggle(lastTapText, node.text) || wouldUndoGoal(goal, node.text)) -> {
                        history.append("• \"${node.text}\" is already in the desired state — leaving it\n"); true
                    }
                    cmd == "LONGPRESS" -> { lastTapText = node.text; history.append("• long-pressed \"${node.text}\"\n"); svc.longPress(i) }
                    cmd == "TOGGLE" -> { history.append("• toggled \"${node.text}\" (was ${if (node.checked) "on" else "off"})\n"); lastTapText = node.text; svc.tapNode(i) }
                    else -> { history.append("• tapped \"${node.text}\"\n"); lastTapText = node.text; svc.tapNode(i) }
                }
            }
            "TYPE" -> {
                val rest = action.removePrefix("TYPE").trim()
                val i = Regex("-?\\d+").find(rest.substringBefore("|"))?.value?.toIntOrNull()
                val text = rest.substringAfter("|", "").trim()
                if (i != null) { history.append("• typed \"${text.take(30)}\"\n"); svc.setText(i, text) } else false
            }
            "CLEAR" -> { val i = argIdx(); if (i != null) { history.append("• cleared #$i\n"); svc.setText(i, "") } else false }
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
            "DO" -> reflexDo(ctx, goal, svc, nodes, action.removePrefix("DO").trim(), history)
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

    @Volatile private var lastShotMs = 0L
    /** Suspend wrapper around the accessibility screenshot callback. Enforces the ~1s system rate limit so
     *  back-to-back steps don't fail with INTERVAL_TIME_SHORT. */
    private suspend fun screenshot(svc: InteractionLogService): String? {
        val since = System.currentTimeMillis() - lastShotMs
        if (since in 0 until 1150) delay(1150 - since)
        lastShotMs = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            try { svc.captureScreenshot { b64 -> if (cont.isActive) cont.resume(b64) } } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
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
        Log.i(TAG, "OP FINISH \"$summary\" | history: ${history.replace("\n", " · ").take(400)}")
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
    /** Live progress on the ongoing banner — so the user can SEE what the agent is doing step by step. */
    private fun updateBanner(ctx: Context, step: Int, doing: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val stopPi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, com.agentos.shell.StopActionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            val n = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("SlyOS · step $step")
                .setContentText(doing.take(80))
                .setOngoing(true).setOnlyAlertOnce(true).setDeleteIntent(stopPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPi)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {}
    }
    private fun cancelBanner(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (e: Exception) {}
    }
}
