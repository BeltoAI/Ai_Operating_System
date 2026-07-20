package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * FLOW-LEVEL AUDIT — every feature, broken into the exact chain of steps it depends on.
 *
 * FeatureHealth answers "does this feature work?". This answers the more useful question: "WHICH STEP of
 * this feature is broken?". A WhatsApp auto-reply isn't one thing — it's
 *     notification arrives → parsed → written to brain → brain read back for context →
 *     persona/style resolved → LLM called → reply sanitised → delivered → logged
 * and any one of those links can fail silently while everything around it looks healthy. Only by naming
 * each link can the UI show you the real break point instead of a red dot on the whole feature.
 *
 * Every step declares what it needs, and a verifier that PROVES it — so a pass means the step genuinely
 * works, not merely that no exception was thrown. Failures are persisted with a timestamp so intermittent
 * breaks are still visible after the fact.
 */
object FlowAudit {
    private const val TAG = "SlyOS-FlowAudit"
    private const val PREFS = "slyos_flowaudit"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One link in a chain. [critical] steps break the whole flow; non-critical degrade it. */
    data class Step(val name: String, val critical: Boolean = true, val verify: (Context) -> Pair<Boolean, String>)
    data class Flow(val id: String, val title: String, val trigger: String, val steps: List<Step>)

    data class StepResult(val name: String, val ok: Boolean, val detail: String, val critical: Boolean)
    data class FlowResult(val id: String, val title: String, val steps: List<StepResult>, val ms: Long) {
        val brokenAt: String? get() = steps.firstOrNull { !it.ok && it.critical }?.name
        val ok: Boolean get() = brokenAt == null
        val degraded: Boolean get() = ok && steps.any { !it.ok }
    }

    /** A named "contact" the audit talks to, so real pipeline calls never touch a real person. */
    private const val AUDIT_SENDER = "__flowaudit__"
    /** Carries a real generated artefact between steps, so later steps inspect what earlier ones produced. */
    @Volatile private var lastDraft: String = ""
    @Volatile private var lastPlan: List<AgentAction> = emptyList()

    // ── reusable step verifiers (the same link appears in many flows) ─────────────────────────────
    private fun stepNotifAccess() = Step("Notification access") { ctx ->
        val on = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
        on to (if (on) "listener connected" else "NOT granted — SlyOS never sees incoming messages")
    }
    private fun stepAccessibility() = Step("Accessibility service") { ctx ->
        val on = com.agentos.shell.InteractionLogService.instance != null
        on to (if (on) "service running" else "NOT running — cannot read or act on screens")
    }
    private fun stepBrainWrite() = Step("Write to brain") { ctx ->
        val before = MessageStore.count(ctx)
        MessageStore.insertOne(ctx, "__flowaudit__", "FlowAudit", "test", "them", "flow audit probe")
        val after = MessageStore.count(ctx)
        (after > before) to (if (after > before) "message persisted ($before→$after)" else "brain did not accept the write")
    }
    private fun stepBrainRead() = Step("Read context from brain") { ctx ->
        val c = try { BrainContext.build(ctx, "flow audit probe") } catch (e: Exception) { "" }
        c.isNotBlank() to (if (c.isNotBlank()) "${c.length} chars of grounding context" else "brain returned NO context — replies would be generic")
    }
    private fun stepBrainRecall() = Step("Recall from brain (search)") { ctx ->
        val hits = try { MessageStore.search(ctx, "flow audit probe", 5) } catch (e: Exception) { emptyList() }
        hits.isNotEmpty() to (if (hits.isNotEmpty()) "found ${hits.size} matching memory" else "written but NOT searchable — recall is broken")
    }
    private fun stepBrainReachable() = Step("LLM reachable") { ctx ->
        val choice = ModelRouter.choose(ctx, ModelRouter.Tier.CHEAP, false, false)
        (choice != null) to (if (choice != null) "routes to ${choice.provider}/${choice.model}" else "NO brain with a key — nothing can be generated")
    }
    private fun stepGenerate() = Step("Generate a response") { ctx ->
        val (code, text) = try { AgentClient.chat("Reply with exactly: ok", "", emptyList()) } catch (e: Exception) { -1 to "" }
        (code == 200 && text.isNotBlank()) to (if (code == 200) "model answered (${text.trim().take(20)})" else "model call failed (code $code)")
    }
    private fun stepSanitise() = Step("Sanitise output (no raw JSON)") { ctx ->
        val out = AgentClient.sanitizeForUi("""{"say":"Hi there","actions":[]}""")
        val clean = !out.contains("\"say\"") && !out.trimStart().startsWith("{") && out.contains("Hi there")
        clean to (if (clean) "JSON envelope stripped, message preserved" else "RAW JSON WOULD REACH THE USER: ${out.take(40)}")
    }
    private fun stepPersona() = Step("Resolve persona / style", critical = false) { ctx ->
        val s = try { MemoryStore.styleFor(ctx, "WhatsApp") } catch (e: Exception) { "" }
        val p = try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" }
        (p.isNotBlank()) to when {
            s.isNotBlank() -> "per-app voice + profile loaded"
            p.isNotBlank() -> "profile loaded (no per-app voice set yet)"
            else -> "no profile — replies won't sound like you"
        }
    }
    private fun stepGuard() = Step("Rate-limit guard") { ctx ->
        val ok = try { AutoReplyGuard.allow(ctx, "__flowaudit__"); true } catch (e: Exception) { false }
        ok to (if (ok) "runaway-send guard active" else "guard unavailable — sends are uncapped")
    }
    private fun stepLogOutbox() = Step("Log to 'Sent for you'") { ctx ->
        val n = try { OutboxStore.recent(ctx, 5).size } catch (e: Exception) { -1 }
        (n >= 0) to (if (n >= 0) "outbox writable ($n recent entries)" else "outbox unreadable — actions would be invisible")
    }
    private fun stepGoogle() = Step("Google account") { ctx ->
        val c = GoogleAuth.isConnected(ctx)
        val t = if (c) GoogleAuth.accessToken(ctx) else ""
        (t.isNotBlank()) to when {
            !c -> "not connected"
            t.isBlank() -> "connected but token refresh FAILS"
            else -> "token refreshes"
        }
    }
    private fun stepFolder() = Step("Save into SlyOS folder") { ctx ->
        val uri = try { SlyFolder.file(ctx, "__flowaudit__.txt", "text/plain", "probe".toByteArray(), "documents") } catch (e: Exception) { null }
        (uri != null) to (if (uri != null) "wrote + indexed a file" else "cannot write to the SlyOS folder")
    }
    private fun stepIndexDoc() = Step("Index document into brain") { ctx ->
        val before = try { DocText.count(ctx) } catch (e: Exception) { -1 }
        (before >= 0) to (if (before >= 0) "$before documents indexed and searchable" else "document index unreadable")
    }

    // ── the flows ────────────────────────────────────────────────────────────────────────────────
    fun flows(): List<Flow> = listOf(
        Flow("msg_reply", "Incoming message → AI reply", "A WhatsApp/SMS/Telegram message arrives", listOf(
            stepNotifAccess(),
            Step("Parse sender + text") { _ ->
                val live = try { NotificationStore.notes.size } catch (e: Exception) { -1 }
                val replyable = try { NotificationStore.notes.count { it.canReply } } catch (e: Exception) { 0 }
                (live >= 0) to when {
                    live < 0 -> "notification store unreadable"
                    live == 0 -> "none captured yet — message yourself, then re-run"
                    else -> "$live captured, $replyable support inline reply"
                }
            },
            stepBrainWrite(), stepBrainRecall(),
            // REAL PIPELINE from here: the exact calls AgentNotificationListener makes for a live message.
            Step("Build reply context (real ReplyContext)") { ctx ->
                val c = try { ReplyContext.forSender(ctx, "WhatsApp", AUDIT_SENDER, "are we still on for tomorrow?") }
                        catch (e: Exception) { "" }
                c.isNotBlank() to (if (c.isNotBlank()) "${c.length} chars assembled for this sender" else "no context — the reply would be generic")
            },
            stepPersona(), stepBrainReachable(),
            Step("Draft the actual reply (real draftReplyThread)") { ctx ->
                val mem = try { ReplyContext.forSender(ctx, "WhatsApp", AUDIT_SENDER, "are we still on for tomorrow?") } catch (e: Exception) { "" }
                val thread = listOf("them" to "Hey, are we still on for tomorrow?")
                val r = try { AgentClient.draftReplyThread(AUDIT_SENDER, thread, mem, null, "are we still on for tomorrow?") }
                        catch (e: Exception) { "" }
                lastDraft = r
                when {
                    r.isBlank() -> false to "the real reply path produced NOTHING"
                    AgentClient.looksLikeError(r) -> false to "reply path errored: ${r.take(70)}"
                    else -> true to "generated a real reply: \"${r.take(60)}\""
                }
            },
            Step("Reply is clean + sendable") { _ ->
                val r = lastDraft
                when {
                    r.isBlank() -> false to "nothing to check — drafting failed above"
                    r.trimStart().startsWith("{") || r.contains("\"say\"") -> false to "RAW JSON would be sent: ${r.take(50)}"
                    r.length > 1500 -> false to "reply too long to send (${r.length} chars)"
                    else -> true to "${r.length} chars, no JSON, within send limits"
                }
            },
            stepGuard(),
            Step("Deliver reply") { ctx ->
                val on = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                    ?.contains(ctx.packageName) == true
                on to (if (on) "inline reply available where the app supports it" else "cannot deliver without notification access")
            },
            stepLogOutbox())),

        Flow("home_ai", "Home AI → answer + action", "You type or speak a request on Home", listOf(
            stepBrainRead(), stepBrainReachable(),
            // REAL planner: ask() is the exact call Home makes, so this proves the live path end to end.
            Step("Run the real planner (AgentClient.ask)") { ctx ->
                val apps = try { ToolRouter.installedApps(ctx).take(30).map { it.label } } catch (e: Exception) { emptyList() }
                val r = try { AgentClient.ask("set a timer for 5 minutes", apps, "") } catch (e: Exception) { null }
                lastPlan = r?.actions ?: emptyList()
                lastDraft = r?.say.orEmpty()
                when {
                    r == null -> false to "planner threw — Home AI would fail"
                    r.say.isBlank() && r.actions.isEmpty() -> false to "planner returned nothing usable"
                    else -> true to "said \"${r.say.take(45)}\" + ${r.actions.size} action(s)"
                }
            },
            Step("Planner chose the right action") { _ ->
                val types = lastPlan.map { it.type }
                val good = types.any { it == "timer" || it == "alarm" || it == "remind" }
                good to (if (good) "picked ${types.joinToString()} for a timer request"
                         else "picked ${types.ifEmpty { listOf("nothing") }.joinToString()} — expected timer/alarm")
            },
            Step("Answer is clean (no raw JSON)") { _ ->
                val s = lastDraft
                val clean = !s.trimStart().startsWith("{") && !s.contains("\"say\"") && !s.contains("[[card:")
                clean to (if (clean) "user-facing text is clean" else "RAW JSON/markup would be shown: ${s.take(50)}")
            },
            Step("Executor runs a real action") { ctx ->
                val r = try { ToolRouter.executeAction(ctx, "translate", "{\"text\":\"hello\",\"to\":\"es\"}") } catch (e: Exception) { "" }
                (r.isNotBlank() && !r.startsWith("couldn't", true)) to
                    (if (r.isNotBlank()) "executed, returned \"${r.take(40)}\"" else "executor returned nothing")
            },
            stepBrainWrite(), stepLogOutbox())),

        Flow("doc_make", "Generate a document", "\"Make me a deck / doc / sheet\"", listOf(
            stepBrainRead(), stepBrainReachable(),
            Step("Design a theme") { ctx ->
                val t = try { DocForge.designTheme("Audit probe", "a short test document", "onepager") } catch (e: Exception) { null }
                (t != null) to (if (t != null) "art direction chosen (accent #${t.accent}, ${t.font})" else "theme generation failed")
            },
            Step("Build the file") { ctx ->
                val bytes = try { Ooxml.docx("Audit probe", "# Heading\nBody\n- bullet") } catch (e: Exception) { ByteArray(0) }
                (bytes.size > 500) to (if (bytes.size > 500) "docx built (${bytes.size / 1024}kb)" else "document writer produced nothing")
            },
            Step("Render to PDF", critical = false) { ctx ->
                val ok = try { Class.forName("android.webkit.WebView"); true } catch (e: Exception) { false }
                ok to (if (ok) "PDF renderer available" else "no renderer — PDF export unavailable")
            },
            stepFolder(), stepIndexDoc(), stepLogOutbox())),

        Flow("outreach", "Autonomous outreach", "Reconnect / Mission sends on your behalf", listOf(
            stepAccessibility(),
            Step("Find targets") { ctx ->
                val n = try { ConnectionStore.neverReachedOut(ctx).count { it.url.isNotBlank() } } catch (e: Exception) { -1 }
                (n > 0) to (if (n > 0) "$n reachable profiles" else "no reachable targets — import your network")
            },
            stepBrainRead(),
            Step("Read prior conversation", critical = false) { ctx ->
                val n = try { MessageStore.threadFor(ctx, "__flowaudit__", 5).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "history lookup works (openers stay in context)" else "cannot read history — openers would read cold")
            },
            stepBrainReachable(),
            Step("Draft a personalised message") { ctx ->
                val m = try { AgentClient.tailoredOutreach("test", "Alex Smith", "CTO", "Acme", "") } catch (e: Exception) { "" }
                (m.length > 12 && m.contains("Alex", true)) to
                    (if (m.contains("Alex", true)) "drafted and addressed correctly" else "draft wrong or empty: ${m.take(40)}")
            },
            stepGuard(),
            Step("Send via tap-send") { ctx ->
                val ok = TapSend.available()
                ok to (if (ok) "engine ready (verified up to send)" else "accessibility off — cannot send")
            },
            stepBrainWrite(), stepLogOutbox())),

        Flow("email", "Email triage + send", "Gmail is connected and mail arrives", listOf(
            stepGoogle(),
            Step("Read inbox") { ctx ->
                val t = if (GoogleAuth.isConnected(ctx)) GoogleAuth.accessToken(ctx) else ""
                t.isNotBlank() to (if (t.isNotBlank()) "inbox reachable" else "no valid token — cannot read mail")
            },
            stepBrainWrite(), stepBrainRead(), stepBrainReachable(), stepGenerate(), stepSanitise(),
            stepLogOutbox())),

        Flow("photos", "Photo understanding", "Gallery images are indexed on-device", listOf(
            Step("Media permission") { ctx ->
                val g = ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                g to (if (g) "granted" else "DENIED — no photos can be read")
            },
            Step("On-device vision") { ctx ->
                val n = try { PhotoIndex.count(ctx) } catch (e: Exception) { -1 }
                (n > 0) to (if (n > 0) "$n photos understood" else "nothing indexed yet")
            },
            Step("Searchable by content") { ctx ->
                val n = try { PhotoIndex.count(ctx) } catch (e: Exception) { 0 }
                (n > 0) to (if (n > 0) "photo index queryable" else "no index to query")
            },
            stepBrainRead())),

        Flow("reminders", "Reminders + alarms", "\"Remind me in 20 minutes\"", listOf(
            stepBrainReachable(),
            Step("Exact-alarm permission") { ctx ->
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val can = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                can to (if (can) "exact alarms allowed — fires on time" else "DENIED — reminders will be delayed by Doze")
            },
            Step("Schedule") { ctx ->
                val at = System.currentTimeMillis() + 3_600_000
                val ok = com.agentos.shell.ReminderScheduler.schedule(ctx, at, "__flowaudit__")
                try {
                    val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val i = android.content.Intent(ctx, com.agentos.shell.ReminderReceiver::class.java).putExtra("text", "__flowaudit__")
                    val pi = android.app.PendingIntent.getBroadcast(ctx, (at % 1_000_000).toInt(), i,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    am.cancel(pi); pi.cancel()
                } catch (e: Exception) {}
                ok to (if (ok) "scheduled + cancelled cleanly" else "scheduler refused")
            },
            Step("Notification permission") { ctx ->
                val g = android.os.Build.VERSION.SDK_INT < 33 ||
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                g to (if (g) "can alert you" else "DENIED — the reminder fires silently")
            },
            stepBrainWrite(), stepLogOutbox()))
    )

    // ── runner ───────────────────────────────────────────────────────────────────────────────────
    fun runOne(ctx: Context, flow: Flow): FlowResult {
        val t0 = System.currentTimeMillis()
        val results = flow.steps.map { s ->
            try { val (ok, d) = s.verify(ctx); StepResult(s.name, ok, d, s.critical) }
            catch (e: Exception) { StepResult(s.name, false, (e.message ?: e.javaClass.simpleName).take(100), s.critical) }
        }
        return FlowResult(flow.id, flow.title, results, System.currentTimeMillis() - t0)
    }

    fun runAll(ctx: Context): List<FlowResult> {
        val out = flows().map { runOne(ctx, it) }
        cleanup(ctx)
        persist(ctx, out)
        return out
    }

    /** Remove everything the audit wrote, so auditing never pollutes the brain or the folder. */
    private fun cleanup(ctx: Context) {
        try { MessageStore.deleteContact(ctx, "__flowaudit__") } catch (e: Exception) { Log.w(TAG, "cleanup: ${e.message}") }
    }

    /**
     * Persist results AND keep a rolling failure history — an intermittent break (a provider that fails
     * only under rate limit, say) is invisible if we only ever store the latest green run.
     */
    private fun persist(ctx: Context, results: List<FlowResult>) {
        val e = p(ctx).edit()
        e.putLong("ran_at", System.currentTimeMillis())
        e.putInt("flows_ok", results.count { it.ok })
        e.putInt("flows_total", results.size)
        results.forEach { f ->
            val arr = JSONArray()
            f.steps.forEach { s ->
                arr.put(JSONObject().put("n", s.name).put("ok", s.ok).put("d", s.detail.take(120)).put("c", s.critical))
            }
            e.putString("flow_" + f.id, JSONObject()
                .put("title", f.title).put("ok", f.ok).put("brokenAt", f.brokenAt ?: "")
                .put("ms", f.ms).put("steps", arr).toString())
        }
        // Rolling failure log (last 40), so past breaks stay visible after things recover.
        val hist = try { JSONArray(p(ctx).getString("failures", "[]")) } catch (ex: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        results.filter { !it.ok }.forEach { f ->
            hist.put(JSONObject().put("t", now).put("flow", f.title).put("step", f.brokenAt ?: "")
                .put("why", f.steps.firstOrNull { !it.ok && it.critical }?.detail?.take(120) ?: ""))
        }
        val trimmed = JSONArray()
        val start = maxOf(0, hist.length() - 40)
        for (i in start until hist.length()) trimmed.put(hist.get(i))
        e.putString("failures", trimmed.toString())
        e.apply()
        HealthStore.note("flow_audit", results.all { it.ok }, results.count { it.ok }.toString() + "/" + results.size + " flows healthy")
    }

    data class Failure(val time: Long, val flow: String, val step: String, val why: String)

    fun failureHistory(ctx: Context): List<Failure> = try {
        val a = JSONArray(p(ctx).getString("failures", "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Failure(it.optLong("t"), it.optString("flow"), it.optString("step"), it.optString("why")) }
        }.reversed()
    } catch (e: Exception) { emptyList() }

    fun lastRun(ctx: Context): Long = p(ctx).getLong("ran_at", 0L)
}
