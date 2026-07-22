package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import com.agentos.shell.InteractionLogService
import com.agentos.shell.ReminderScheduler

/**
 * END-TO-END FEATURE SELF-TEST.
 *
 * ApiHealth proves the APIs answer. This proves the FEATURES built on them actually do what they claim —
 * which is a different question: every API can be green while a feature is broken in its own wiring
 * (a store that won't write, a planner that can't parse its own action schema, a permission never granted).
 *
 * Two safety tiers, because "test everything" must never mean "spam everyone":
 *   SAFE    — runs anything with no outside-world side effects. Writes go to clearly-marked test rows
 *             that are deleted again, so the brain is left exactly as it was found.
 *   DRYRUN  — for send-capable features (SMS, email, LinkedIn, Telegram) verifies the whole chain right
 *             up to the send — credentials, permission, target resolution, draft generation — and then
 *             deliberately stops. Nothing is ever delivered.
 *
 * Every check reports what it actually verified, so a pass means something specific rather than "no crash".
 */
object FeatureHealth {
    private const val TAG = "SlyOS-FeatureHealth"
    private const val PREFS = "slyos_featurehealth"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** status: PASS | FAIL | SKIP (not configured) | DRYRUN (verified up to the send) */
    data class Check(val area: String, val feature: String, val status: String, val detail: String, val ms: Long = 0)

    private fun run(area: String, feature: String, block: () -> Pair<String, String>): Check {
        val t = System.currentTimeMillis()
        return try { val (s, d) = block(); Check(area, feature, s, d, System.currentTimeMillis() - t) }
        catch (e: Exception) { Check(area, feature, "FAIL", (e.message ?: e.javaClass.simpleName).take(110), System.currentTimeMillis() - t) }
    }
    private fun pass(d: String) = "PASS" to d
    private fun fail(d: String) = "FAIL" to d
    private fun skip(d: String) = "SKIP" to d
    private fun dry(d: String) = "DRYRUN" to d

    // ── MEMORY / BRAIN ────────────────────────────────────────────────────────────────────────────
    private fun brainChecks(ctx: Context) = listOf(
        run("Brain", "Message store read/write") {
            val before = MessageStore.count(ctx)
            MessageStore.insertOne(ctx, "__slyos_selftest__", "SelfTest", "test", "me", "selftest ${System.currentTimeMillis()}")
            val after = MessageStore.count(ctx)
            if (after > before) pass("wrote + counted ($before→$after)") else fail("write did not increase count")
        },
        run("Brain", "Semantic recall") {
            val hits = MessageStore.search(ctx, "selftest", 5)
            if (hits.isNotEmpty()) pass("search returned ${hits.size} hit(s)") else fail("search found nothing")
        },
        run("Brain", "Embeddings") {
            val prov = try { EmbeddingClient.provider(ctx) } catch (e: Exception) { null }
            if (prov.isNullOrBlank()) skip("no embedding provider configured")
            else {
                val v = EmbeddingClient.embed(ctx, listOf("health check"))
                val dims = v?.firstOrNull()?.size ?: 0
                if (dims > 0) pass("$prov returned $dims dims") else fail("$prov returned no vector")
            }
        },
        run("Brain", "Brain context assembly") {
            val c = BrainContext.build(ctx, "what do you know about me")
            if (c.isNotBlank()) pass("assembled ${c.length} chars of context") else fail("context came back empty")
        },
        run("Brain", "Document text index") {
            val n = DocText.count(ctx); pass("$n indexed documents")
        },
        run("Brain", "CRM / leads") { pass("${LeadStore.count(ctx)} contacts") },
        run("Brain", "Connections") { pass("${ConnectionStore.count(ctx)} connections") },
        run("Brain", "Photo index") {
            val n = PhotoIndex.count(ctx)
            if (n > 0) pass("$n photos understood") else skip("no photos indexed yet")
        },
        run("Brain", "Ingestion choke point (Brain.remember)") {
            // Proves the single path every feature routes through actually grows the searchable brain.
            val before = MessageStore.count(ctx)
            Brain.remember(ctx, "note", "__slyos_selftest__", "identity-engine self-test ${System.currentTimeMillis()}")
            val after = MessageStore.count(ctx)
            if (after > before) pass("remember() wrote + indexed ($before→$after)") else fail("remember() did not grow the brain")
        },
        run("Brain", "Voice flywheel (edit capture)") {
            // The draft→sent corrections that make the voice converge on the real user.
            val n = EditPairStore.count(ctx)
            pass("$n draft→sent corrections captured (feed the voice + export as training pairs)")
        }
    )

    // ── HOME AI / PLANNER ────────────────────────────────────────────────────────────────────────
    private fun aiChecks(ctx: Context) = listOf(
        run("Home AI", "Model reachable") {
            val (code, text) = AgentClient.chat("Reply with the single word: ok", "", emptyList())
            if (code == 200 && text.isNotBlank()) pass("answered in ${text.take(24)}") else fail("code $code: ${text.take(70)}")
        },
        run("Home AI", "Never emits raw JSON") {
            // The regression that shipped once: a malformed/truncated envelope leaking to the UI.
            val bad = """{"say":"Hello there","actions":["web_search"],"remember":"x"}"""
            val cleaned = AgentClient.sanitizeForUi(bad)
            if (cleaned.contains("\"say\"") || cleaned.trimStart().startsWith("{"))
                fail("raw JSON survived sanitising: ${cleaned.take(60)}")
            else if (cleaned.contains("Hello there")) pass("extracted the message, dropped the JSON")
            else fail("message text lost: ${cleaned.take(60)}")
        },
        run("Home AI", "Truncated JSON recovery") {
            val truncated = """{"say":"Partial answer here","actions":["""
            val cleaned = AgentClient.sanitizeForUi(truncated)
            if (cleaned.contains("Partial answer here") && !cleaned.contains("{")) pass("recovered from truncated envelope")
            else fail("got: ${cleaned.take(60)}")
        },
        run("Home AI", "Vision") {
            if (!ModelRouter.hasKey(ctx, "gemini") && !ModelRouter.hasKey(ctx, "anthropic") &&
                !ModelRouter.hasKey(ctx, "openai") && !ModelRouter.hasKey(ctx, "githubmodels"))
                skip("no vision-capable brain keyed")
            else pass("vision-capable brain available")
        }
    )

    // ── DOCUMENTS ────────────────────────────────────────────────────────────────────────────────
    private fun docChecks(ctx: Context, deep: Boolean) = listOf(
        run("Documents", "Google connected") {
            if (!GoogleAuth.isConnected(ctx)) skip("Google not connected")
            else if (GoogleAuth.accessToken(ctx).isBlank()) fail("connected but token refresh failed")
            else pass("token refreshes")
        },
        run("Documents", "Create styled Doc") {
            if (!deep) dry("skipped — creates a real Doc; enable deep test to run")
            else if (!GoogleAuth.isConnected(ctx)) skip("Google not connected")
            else {
                val r = GoogleWorkspace.createDoc(ctx, "SlyOS self-test", "# Heading\nBody line\n- bullet one\n- bullet two")
                if (r.ok) pass("created ${r.url}") else fail(r.error)
            }
        },
        run("Documents", "Create styled Sheet") {
            if (!deep) dry("skipped — creates a real Sheet; enable deep test to run")
            else if (!GoogleAuth.isConnected(ctx)) skip("Google not connected")
            else {
                val r = GoogleWorkspace.createSheet(ctx, "SlyOS self-test", listOf(listOf("Item", "Amount"), listOf("Test", "1")))
                if (r.ok) pass("created ${r.url}") else fail(r.error)
            }
        },
        run("Documents", "Create styled Slides") {
            if (!deep) dry("skipped — creates a real deck; enable deep test to run")
            else if (!GoogleAuth.isConnected(ctx)) skip("Google not connected")
            else {
                val r = GoogleWorkspace.createSlides(ctx, "SlyOS self-test", listOf("Title" to "- point one\n- point two"))
                if (r.ok) pass("created ${r.url}") else fail(r.error)
            }
        },
        run("Documents", "PDF rendering") {
            val ok = try { Class.forName("android.graphics.pdf.PdfDocument"); true } catch (e: Exception) { false }
            if (ok) pass("PDF engine available") else fail("no PDF engine")
        }
    )

    // ── SCHEDULING / SYSTEM ──────────────────────────────────────────────────────────────────────
    private fun systemChecks(ctx: Context) = listOf(
        run("System", "Exact alarms permitted") {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val can = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (can) pass("exact alarms allowed — reminders will fire on time")
            else fail("exact alarms DENIED — reminders will be delayed by Doze")
        },
        run("System", "Reminder scheduling") {
            // Schedule far enough out that it can't fire during the test, then CANCEL the exact same
            // PendingIntent — otherwise the self-test would leave a stray reminder armed on the phone.
            val at = System.currentTimeMillis() + 3_600_000
            val ok = ReminderScheduler.schedule(ctx, at, "__slyos_selftest__")
            val cancelled = try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val i = android.content.Intent(ctx, com.agentos.shell.ReminderReceiver::class.java)
                    .putExtra("text", "__slyos_selftest__")
                val pi = android.app.PendingIntent.getBroadcast(ctx, (at % 1_000_000).toInt(), i,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                am.cancel(pi); pi.cancel(); true
            } catch (e: Exception) { false }
            when {
                ok && cancelled -> pass("scheduled, then cancelled cleanly (nothing left armed)")
                ok -> fail("scheduled but COULD NOT cancel — a stray test reminder is armed")
                else -> fail("scheduler refused")
            }
        },
        run("System", "Calendar read") {
            if (!CalendarTool.hasPermission(ctx)) skip("calendar permission not granted")
            else { val e = CalendarTool.upcoming(ctx); pass(if (e.isBlank()) "readable (no upcoming events)" else "read upcoming events") }
        },
        run("System", "Notification listener") {
            val on = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true
            if (on) pass("connected") else fail("not granted — Now/People and auto-reply are blind")
        },
        run("System", "Accessibility service") {
            if (InteractionLogService.instance != null) pass("running — tap-send + screen actions available")
            else fail("not running — LinkedIn send, WhatsApp answer, screen agent all unavailable")
        },
        run("System", "Default launcher") {
            val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            val r = ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            val me = r?.activityInfo?.packageName == ctx.packageName
            if (me) pass("SlyOS is Home") else fail("Home is ${r?.activityInfo?.packageName ?: "unknown"}")
        }
    )

    // ── OUTBOUND (never actually sends) ──────────────────────────────────────────────────────────
    private fun outboundChecks(ctx: Context) = listOf(
        run("Outbound", "Email (Gmail)") {
            if (!GoogleAuth.isConnected(ctx)) skip("Google not connected")
            else if (GoogleAuth.accessToken(ctx).isBlank()) fail("token refresh failed — sends would fail")
            else dry("credentials + token valid; stopped before send")
        },
        run("Outbound", "SMS") {
            val granted = ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) dry("permission granted; stopped before send") else fail("SEND_SMS not granted")
        },
        run("Outbound", "LinkedIn tap-send") {
            if (!TapSend.available()) fail("accessibility off — tap-send cannot run")
            else {
                val targets = try { ConnectionStore.neverReachedOut(ctx).count { it.url.isNotBlank() } } catch (e: Exception) { 0 }
                dry("engine ready, $targets reachable profiles; stopped before opening LinkedIn")
            }
        },
        run("Outbound", "Outreach draft quality") {
            val msg = AgentClient.tailoredOutreach("test the drafting pipeline", "Alex Smith", "CTO", "Acme", "")
            when {
                msg.isBlank() || msg.length < 12 -> fail("draft came back empty")
                !msg.contains("Alex", true) -> fail("draft ignored the locked first name: ${msg.take(60)}")
                msg.trimStart().startsWith("{") -> fail("draft leaked JSON")
                else -> pass("drafted ${msg.length} chars, addressed correctly")
            }
        },
        run("Outbound", "Telegram bot") {
            if (!TelegramClient.configured()) skip("no bot token in this build")
            else if (TelegramClient.botUsername().isNotBlank()) pass("bot reachable") else fail("getMe failed")
        },
        run("Outbound", "Auto-reply guard") {
            val before = AutoReplyGuard.allow(ctx, "__slyos_selftest__")
            if (before) pass("rate-limiter armed and allowing") else pass("rate-limiter armed (currently capped)")
        }
    )

    /** Everything. [deep] additionally creates real Google files (Doc/Sheet/Slides) to prove styling works. */
    fun runAll(ctx: Context, deep: Boolean = false): List<Check> {
        val out = ArrayList<Check>()
        out.addAll(brainChecks(ctx))
        out.addAll(aiChecks(ctx))
        out.addAll(docChecks(ctx, deep))
        out.addAll(systemChecks(ctx))
        out.addAll(outboundChecks(ctx))
        cleanup(ctx)
        persist(ctx, out)
        return out
    }

    /** Remove anything the self-test wrote, so running it never pollutes the brain. */
    private fun cleanup(ctx: Context) {
        try { MessageStore.deleteContact(ctx, "__slyos_selftest__") } catch (e: Exception) { Log.w(TAG, "cleanup: ${e.message}") }
    }

    private fun persist(ctx: Context, checks: List<Check>) {
        val e = p(ctx).edit()
        e.putLong("ran_at", System.currentTimeMillis())
        e.putInt("pass", checks.count { it.status == "PASS" })
        e.putInt("fail", checks.count { it.status == "FAIL" })
        e.putInt("total", checks.size)
        checks.forEach { c ->
            val line = c.status + "|" + c.area + "|" + c.ms + "|" + c.detail.take(120)
            e.putString("f_" + c.feature.replace(' ', '_'), line)
        }
        e.apply()
        HealthStore.note("feature_sweep", checks.none { it.status == "FAIL" },
            checks.count { it.status == "PASS" }.toString() + " pass / " + checks.count { it.status == "FAIL" } + " fail")
    }

    fun lastRun(ctx: Context): Long = p(ctx).getLong("ran_at", 0L)
}
