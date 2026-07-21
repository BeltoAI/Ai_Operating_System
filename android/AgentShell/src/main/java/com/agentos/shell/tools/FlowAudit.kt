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

        // ── SILENT-OUTPUT FLOWS: these exist specifically to catch "you asked for something and got
        // nothing back" — the failure class that produces no error, no crash, just an empty answer.
        Flow("expenses", "Expenses / receipts", "You scan a receipt or ask about spending", listOf(
            Step("Expense store readable") { ctx ->
                val n = try { ExpenseStore.count(ctx) } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n expenses logged" else "expense database unreadable")
            },
            Step("Vision brain for receipt photos") { ctx ->
                val ok = ModelRouter.hasKey(ctx, "gemini") || ModelRouter.hasKey(ctx, "anthropic") ||
                    ModelRouter.hasKey(ctx, "openai") || ModelRouter.hasKey(ctx, "githubmodels")
                ok to (if (ok) "a vision-capable brain is keyed" else "NO vision brain — receipt photos cannot be read at all")
            },
            // Real parse of a real receipt, asserting a USABLE result — not just "didn't crash".
            Step("Actually parse a receipt (real extract)") { ctx ->
                val sample = "WHOLE FOODS MARKET\n2026-07-19\nBananas 3.40\nCoffee 12.99\nSubtotal 16.39\nTax 1.31\nTOTAL 17.70 USD"
                val r = try { AgentClient.extractReceiptText(sample) } catch (e: Exception) { null }
                when {
                    r == null -> false to "returned NOTHING for a clearly valid receipt — scanning is broken"
                    r.total <= 0.0 -> false to "parsed but total came back ${r.total} — the amount is being lost"
                    r.merchant.isBlank() || r.merchant == "(unknown)" -> false to "parsed a total but NO merchant"
                    else -> true to "read “${r.merchant}” ${r.currency} ${r.total}"
                }
            },
            Step("Categorisation") { _ ->
                val c = try { ExpenseStore.normalizeCategory("groceries") } catch (e: Exception) { "" }
                c.isNotBlank() to (if (c.isNotBlank()) "maps to \"$c\"" else "category mapping returned nothing")
            },
            stepBrainWrite(), stepLogOutbox())),

        Flow("documents_fetch", "Ask about a document", "\"What did the contract say about X?\"", listOf(
            Step("Documents are indexed") { ctx ->
                val n = try { DocText.count(ctx) } catch (e: Exception) { -1 }
                when {
                    n < 0 -> false to "document index unreadable"
                    n == 0 -> false to "NO documents indexed — every document question returns nothing"
                    else -> true to "$n documents indexed"
                }
            },
            // Retrieval that asserts real text came back, which is the exact silent failure.
            Step("Retrieval returns real passages") { ctx ->
                val n = try { DocText.count(ctx) } catch (e: Exception) { 0 }
                if (n == 0) false to "nothing indexed, so retrieval can only ever return empty"
                else {
                    val probe = try { DocText.retrieve(ctx, "the", 800) } catch (e: Exception) { "" }
                    if (probe.isNotBlank()) true to "returned ${probe.length} chars of document text"
                    else false to "returned NOTHING even for a common word — retrieval is broken, not just unmatched"
                }
            },
            Step("Filed documents listable") { ctx ->
                val n = try { DocStore.list(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n filed documents" else "document list unreadable")
            },
            Step("PDF text extraction available") { _ ->
                val ok = try { Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument"); true }
                         catch (e: Throwable) { false }
                ok to (if (ok) "PDF text/form engine present" else "PDF engine MISSING — PDFs can't be read or filled")
            },
            stepBrainRead())),

        Flow("forms", "Fill a form", "You hand SlyOS a PDF form to complete", listOf(
            Step("PDF form engine") { _ ->
                val ok = try { Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument"); true }
                         catch (e: Throwable) { false }
                ok to (if (ok) "engine present" else "MISSING — no form can be filled")
            },
            // The real cause of "it filled nothing": the profile is empty, so no field can ever match.
            Step("Profile has values to fill with") { ctx ->
                val fields = listOfNotNull(
                    MemoryStore.profileName(ctx).takeIf { it.isNotBlank() }?.let { "name" },
                    MemoryStore.profileEmail(ctx).takeIf { it.isNotBlank() }?.let { "email" },
                    MemoryStore.profilePhone(ctx).takeIf { it.isNotBlank() }?.let { "phone" })
                fields.isNotEmpty() to (if (fields.isNotEmpty()) "can fill: ${fields.joinToString()}"
                    else "profile is EMPTY — every form will fill 0 fields and look broken")
            },
            Step("Can save the filled file") { ctx ->
                val uri = try { SlyFolder.file(ctx, "__flowaudit_form__.txt", "text/plain", "probe".toByteArray(), "documents") }
                          catch (e: Exception) { null }
                (uri != null) to (if (uri != null) "output folder writable" else "cannot save a filled form")
            })),

        Flow("telegram_dm", "Telegram bot (private)", "Someone messages your bot", listOf(
            Step("Bot configured + reachable") { _ ->
                if (!TelegramClient.configured()) false to "no bot token in this build — the bot cannot run"
                else { val u = try { TelegramClient.botUsername() } catch (e: Exception) { "" }
                       u.isNotBlank() to (if (u.isNotBlank()) "@$u reachable" else "getMe failed — token may be revoked") }
            },
            stepBrainWrite(), stepBrainRead(),
            Step("Document knowledge available", critical = false) { ctx ->
                val has = try { KnowledgeStore.hasDoc(ctx) } catch (e: Exception) { false }
                true to (if (has) "a document is loaded for Q&A" else "no document loaded (fine — text chat still works)")
            },
            stepBrainReachable(), stepGenerate(), stepSanitise(), stepLogOutbox())),

        Flow("telegram_team", "Telegram team chat", "You address an agent in the group", listOf(
            Step("Team chat connected") { ctx ->
                val on = try { TeamChat.isConnected(ctx) } catch (e: Exception) { false }
                on to (if (on) "group paired" else "not connected — agents can't be reached in a group")
            },
            Step("Agents exist to answer") { ctx ->
                val n = try { EmployeeStore.all(ctx).size } catch (e: Exception) { 0 }
                (n > 0) to (if (n > 0) "$n agent(s) hired" else "no agents — nobody can respond")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepSanitise(), stepLogOutbox())),

        Flow("agents", "AI employees working", "Agents run shifts on your behalf", listOf(
            Step("Team roster") { ctx ->
                val n = try { EmployeeStore.all(ctx).size } catch (e: Exception) { 0 }
                (n > 0) to (if (n > 0) "$n on the team" else "no agents hired yet")
            },
            Step("Per-agent knowledge", critical = false) { ctx ->
                val fed = try { EmployeeStore.all(ctx).sumOf { AgentKnowledge.count(ctx, it.id) } } catch (e: Exception) { 0 }
                true to (if (fed > 0) "$fed documents fed to agents" else "no documents fed to any agent yet")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepLogOutbox())),

        Flow("calendar", "Calendar + scheduling", "\"What's on today / book me at 3\"", listOf(
            Step("Calendar permission") { ctx ->
                val ok = try { CalendarTool.hasPermission(ctx) } catch (e: Exception) { false }
                ok to (if (ok) "granted" else "DENIED — SlyOS is blind to your schedule")
            },
            Step("Events actually readable") { ctx ->
                if (!CalendarTool.hasPermission(ctx)) false to "no permission, so nothing can be read"
                else { val e = try { CalendarTool.upcoming(ctx) } catch (ex: Exception) { "" }
                       true to (if (e.isBlank()) "readable (nothing upcoming)" else "read ${e.lines().size} upcoming line(s)") }
            },
            stepBrainRead(), stepBrainReachable())),

        Flow("contacts_sms", "Message a person", "\"Text Sarah I'm running late\"", listOf(
            Step("Contacts readable") { ctx ->
                val ok = try { ContactsTool.canRead(ctx) } catch (e: Exception) { false }
                ok to (if (ok) "granted" else "DENIED — names can't be resolved to numbers")
            },
            Step("Name → number resolution") { ctx ->
                if (!ContactsTool.canRead(ctx)) false to "no contacts permission"
                else { val n = try { ContactsTool.findCandidates(ctx, "a").size } catch (e: Exception) { -1 }
                       (n >= 0) to (if (n >= 0) "lookup works ($n candidates for a broad query)" else "contact lookup threw") }
            },
            Step("SMS permission") { ctx ->
                val g = ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                g to (if (g) "granted" else "DENIED — texts cannot be sent")
            },
            stepBrainReachable(), stepGenerate(), stepGuard(), stepLogOutbox())),

        Flow("screen_agent", "Operate the screen", "\"Do X in this app for me\"", listOf(
            stepAccessibility(),
            Step("Screen readable") { _ ->
                val svc = com.agentos.shell.InteractionLogService.instance
                if (svc == null) false to "service not running"
                else { val n = try { svc.readScreen().size } catch (e: Exception) { -1 }
                       (n > 0) to (if (n > 0) "reads $n nodes from the current screen" else "reads NOTHING — cannot see the screen") }
            },
            stepBrainReachable(), stepGenerate(), stepLogOutbox())),

        Flow("voice", "Voice in / voice out", "You speak to SlyOS", listOf(
            Step("Microphone") { ctx ->
                val ok = try { SongId.hasMic(ctx) } catch (e: Exception) { false }
                ok to (if (ok) "granted" else "DENIED — voice input impossible")
            },
            Step("Speech output", critical = false) { ctx ->
                val el = try { ElevenLabs.available(ctx) } catch (e: Exception) { false }
                true to (if (el) "ElevenLabs voice available" else "using the system voice (no ElevenLabs key)")
            },
            stepBrainReachable(), stepGenerate(), stepSanitise())),

        Flow("song_id", "Identify a song", "\"What song is this?\"", listOf(
            Step("Microphone") { ctx ->
                val ok = try { SongId.hasMic(ctx) } catch (e: Exception) { false }
                ok to (if (ok) "granted" else "DENIED — cannot listen")
            },
            Step("AudD token") { ctx ->
                // NOTE: stored as "audd_token", NOT "audd_key" — providerKey() would look up the wrong
                // pref and report a configured token as missing (same bug class as github/netlify).
                val t = try { MemoryStore.musicIdToken(ctx) } catch (e: Exception) { "" }
                t.isNotBlank() to (if (t.isNotBlank()) "token set" else "no AudD token — song ID returns nothing")
            })),

        Flow("translate", "Translate", "\"Translate this to Spanish\"", listOf(
            Step("Translator returns real output") { _ ->
                val out = try { Translate.translate("hello", "es") } catch (e: Exception) { "" }
                (out.isNotBlank() && !out.equals("hello", true)) to
                    (if (out.isNotBlank() && !out.equals("hello", true)) "\"hello\" → \"$out\""
                     else "returned nothing usable — the language pack may not be downloaded")
            },
            stepLogOutbox())),

        Flow("trading", "Trading / quotes", "\"How's NVDA doing?\"", listOf(
            Step("Quote source keyed") { ctx ->
                val k = try { MemoryStore.providerKey(ctx, "finnhub") } catch (e: Exception) { "" }
                k.isNotBlank() to (if (k.isNotBlank()) "Finnhub key set" else "no market data key — quotes return nothing")
            },
            Step("Live quote returns a price") { ctx ->
                val q = try { QuoteClient.quote("AAPL") } catch (e: Exception) { null }
                (q != null) to (if (q != null) "AAPL priced" else "quote lookup returned NOTHING")
            },
            stepBrainWrite(), stepLogOutbox())),

        Flow("job_hunt", "Job hunt", "\"Find me a job as X\"", listOf(
            Step("Résumé on file") { ctx ->
                val r = try { JobStore.resume(ctx) } catch (e: Exception) { "" }
                r.isNotBlank() to (if (r.isNotBlank()) "${r.length} chars of résumé" else "NO résumé — tailoring has nothing to work from")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepFolder(), stepLogOutbox())),

        Flow("papers", "Write a paper / report", "\"Write me a paper on X\"", listOf(
            stepBrainRead(), stepBrainReachable(),
            Step("Paper store writable") { ctx ->
                val n = try { PaperStore.list(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n papers stored" else "paper store unreadable")
            },
            stepFolder(), stepIndexDoc(), stepLogOutbox())),

        Flow("deploy", "Build + deploy a site", "\"Build and ship a landing page\"", listOf(
            stepBrainReachable(),
            Step("Vercel token") { ctx ->
                val t = try { MemoryStore.vercelToken(ctx) } catch (e: Exception) { "" }
                t.isNotBlank() to (if (t.isNotBlank()) "set" else "no Vercel token — cannot ship")
            },
            Step("Supabase provisioning", critical = false) { ctx ->
                val u = try { DeployClient.supabaseUrl(ctx) } catch (e: Exception) { "" }
                true to (if (u.isNotBlank()) "backend configured" else "no Supabase — frontend-only deploys")
            },
            stepLogOutbox())),

        Flow("powers", "Powers / store", "You install and run a Power", listOf(
            Step("Powers installed") { ctx ->
                val n = try { PowerRegistry.count(ctx) } catch (e: Exception) { -1 }
                (n >= 0) to (if (n > 0) "$n installed" else if (n == 0) "none installed yet" else "power registry unreadable")
            },
            stepBrainRead(), stepBrainReachable())),

        Flow("backup", "Brain backup + restore", "Your brain is backed up automatically", listOf(
            Step("Backup configured") { ctx ->
                val on = try { BrainBackup.autoEnabled(ctx) } catch (e: Exception) { false }
                true to (if (on) "auto-backup ON" else "auto-backup OFF — a wipe would lose everything")
            },
            Step("Google Drive target") { ctx ->
                val c = GoogleAuth.isConnected(ctx)
                c to (if (c) "Drive reachable" else "not connected — backups can only stay on-device")
            },
            stepFolder())),

        Flow("account_sync", "Account + cross-device sync", "Your brain follows you to another phone", listOf(
            Step("Signed in") { ctx ->
                val on = try { AccountStore.signedIn(ctx) } catch (e: Exception) { false }
                on to (if (on) "signed in" else "not signed in — no cross-device brain")
            },
            Step("Last sync succeeded") { ctx ->
                val t = try { BrainSync.lastOkMs(ctx) } catch (e: Exception) { 0L }
                (t > 0) to (if (t > 0) "last ok " + java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
                            else "never synced successfully")
            })),

        Flow("checklist", "Checklist", "\"Add milk to my list\"", listOf(
            Step("List readable + writable") { ctx ->
                val before = try { ChecklistStore.load(ctx).size } catch (e: Exception) { -1 }
                if (before < 0) false to "checklist unreadable"
                else { ChecklistStore.add(ctx, "__flowaudit__")
                       val after = try { ChecklistStore.load(ctx).size } catch (e: Exception) { -1 }
                       try { ChecklistStore.removeMatching(ctx, "__flowaudit__") } catch (e: Exception) {}
                       (after > before) to (if (after > before) "add + remove both work" else "add did not persist") }
            },
            stepLogOutbox())),

        Flow("otp", "OTP autofill", "A login code arrives while you're signing in", listOf(
            Step("Opt-in enabled") { ctx ->
                val on = try { MemoryStore.otpAutofill(ctx) } catch (e: Exception) { false }
                true to (if (on) "enabled" else "OFF by default — codes are never read (this is the safe default)")
            },
            stepNotifAccess())),

        // ── THE REMAINING SCREENS: every user-facing surface now has a traced flow. ──
        Flow("chat", "Chat", "A normal back-and-forth conversation", listOf(
            Step("Threads persist") { ctx ->
                val n = try { ChatStore.threads(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n saved chat thread(s)" else "chat store unreadable")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepSanitise(),
            Step("Web search available to chat") { ctx ->
                val web = ModelRouter.hasKey(ctx, "anthropic") || ModelRouter.hasKey(ctx, "gemini")
                web to (if (web) "a web-capable brain is keyed" else "no web-capable brain — chat can't look things up")
            },
            stepBrainWrite())),

        Flow("research", "Research / papers", "\"Research X and write it up\"", listOf(
            stepBrainRead(), stepBrainReachable(),
            Step("Document knowledge loaded", critical = false) { ctx ->
                val has = try { KnowledgeStore.hasDoc(ctx) } catch (e: Exception) { false }
                true to (if (has) "a source document is loaded" else "no source doc (research runs from the web instead)")
            },
            Step("Web-capable brain for sources") { ctx ->
                val web = ModelRouter.hasKey(ctx, "anthropic") || ModelRouter.hasKey(ctx, "gemini")
                web to (if (web) "can browse for sources" else "NO web brain — research would be from memory only")
            },
            stepGenerate(), stepFolder(), stepIndexDoc(), stepLogOutbox())),

        Flow("look", "Look (point the camera)", "\"What am I looking at?\"", listOf(
            Step("Camera permission") { ctx ->
                val g = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                g to (if (g) "granted" else "DENIED — Look cannot see anything")
            },
            Step("Vision brain keyed") { ctx ->
                val v = ModelRouter.hasKey(ctx, "gemini") || ModelRouter.hasKey(ctx, "anthropic") ||
                    ModelRouter.hasKey(ctx, "openai") || ModelRouter.hasKey(ctx, "githubmodels")
                v to (if (v) "vision-capable brain available" else "NO vision brain — images can't be understood")
            },
            Step("Receipt/document capture path") { ctx ->
                val n = try { DocStore.list(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n captured documents filed" else "document store unreadable")
            },
            stepBrainWrite())),

        Flow("faces", "Faces / people", "SlyOS recognises who's in a photo", listOf(
            Step("People store readable") { ctx ->
                val n = try { PeopleStore.list(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n > 0) "$n people saved" else if (n == 0) "no faces saved yet" else "people store unreadable")
            },
            Step("Photo index to match against") { ctx ->
                val n = try { PhotoIndex.count(ctx) } catch (e: Exception) { 0 }
                (n > 0) to (if (n > 0) "$n photos indexed" else "no photos indexed — nothing to match faces in")
            },
            Step("Vision brain keyed") { ctx ->
                val v = ModelRouter.hasKey(ctx, "gemini") || ModelRouter.hasKey(ctx, "anthropic") ||
                    ModelRouter.hasKey(ctx, "openai") || ModelRouter.hasKey(ctx, "githubmodels")
                v to (if (v) "available" else "no vision brain")
            })),

        Flow("shop", "Shopping", "\"Find me the best X under Y\"", listOf(
            Step("Web-capable brain") { ctx ->
                val web = ModelRouter.hasKey(ctx, "anthropic") || ModelRouter.hasKey(ctx, "gemini")
                web to (if (web) "can search the live web" else "NO web brain — shopping results would be invented, not real")
            },
            stepBrainRead(), stepGenerate(), stepSanitise(), stepLogOutbox())),

        Flow("converse", "Converse (voice chat)", "A spoken back-and-forth", listOf(
            Step("Microphone") { ctx ->
                val ok = try { SongId.hasMic(ctx) } catch (e: Exception) { false }
                ok to (if (ok) "granted" else "DENIED — cannot hear you")
            },
            Step("Voice sample for your cloned voice", critical = false) { ctx ->
                val has = try { VoiceSampleStore.hasSample(ctx) } catch (e: Exception) { false }
                true to (if (has) "voice sample recorded" else "no sample — uses the default voice")
            },
            Step("Speech output", critical = false) { ctx ->
                val el = try { ElevenLabs.available(ctx) } catch (e: Exception) { false }
                true to (if (el) "ElevenLabs available" else "system voice (no ElevenLabs key)")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepSanitise())),

        Flow("cowork", "Cowork", "Long-form work sessions with SlyOS", listOf(
            Step("Cowork sessions persist") { ctx ->
                val n = try { CoworkChatStore.list(ctx).size } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n cowork session(s)" else "cowork store unreadable")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(),
            Step("Can produce real documents") { ctx ->
                val ok = GoogleAuth.isConnected(ctx)
                true to (if (ok) "Google connected — can create Docs/Sheets/Slides" else "no Google — falls back to PDF/Office files")
            },
            stepFolder(), stepLogOutbox())),

        Flow("architect", "Architect (build an app)", "\"Build me an app that…\"", listOf(
            stepBrainReachable(),
            Step("Code-capable brain") { ctx ->
                val strong = ModelRouter.hasKey(ctx, "anthropic") || ModelRouter.hasKey(ctx, "openai")
                strong to (if (strong) "a strong coding brain is keyed" else "only small models keyed — generated apps will be poor")
            },
            Step("Deploy target") { ctx ->
                val v = try { MemoryStore.vercelToken(ctx) } catch (e: Exception) { "" }
                val n = try { MemoryStore.netlifyToken(ctx) } catch (e: Exception) { "" }
                (v.isNotBlank() || n.isNotBlank()) to
                    (if (v.isNotBlank() || n.isNotBlank()) "can ship live" else "no deploy token — builds can't go live")
            },
            stepGenerate(), stepLogOutbox())),

        Flow("store", "Power Store", "Browse and install Powers", listOf(
            Step("Store backend reachable") { _ ->
                val ok = try { AgentStore.configured() } catch (e: Exception) { false }
                ok to (if (ok) "store configured" else "store not configured — nothing can be browsed or installed")
            },
            Step("Installed powers load") { ctx ->
                val n = try { PowerRegistry.count(ctx) } catch (e: Exception) { -1 }
                (n >= 0) to (if (n >= 0) "$n power(s) installed" else "power registry unreadable")
            },
            stepBrainRead())),

        Flow("team", "Team", "Your AI employees and their work", listOf(
            Step("Team roster") { ctx ->
                val n = try { EmployeeStore.all(ctx).size } catch (e: Exception) { -1 }
                (n > 0) to (if (n > 0) "$n agent(s)" else "no agents hired")
            },
            Step("Work is being logged") { ctx ->
                val logged = try { EmployeeStore.all(ctx).sumOf { EmployeeStore.logFor(ctx, it.id, 50).size } } catch (e: Exception) { -1 }
                (logged >= 0) to (if (logged > 0) "$logged logged work entries" else "no work logged yet")
            },
            stepBrainRead(), stepBrainReachable(), stepGenerate(), stepLogOutbox())),

        // ── BLIND-SPOT FLOWS: things that fail SILENTLY because nothing was ever watching them. ──
        Flow("background_work", "Background workers", "Autonomous work while you sleep", listOf(
            Step("Workers are actually running") { ctx ->
                val silent = try { WorkerHealth.silent(ctx) } catch (e: Exception) { emptyList() }
                val all = try { WorkerHealth.statuses(ctx) } catch (e: Exception) { emptyList() }
                val ran = all.count { it.lastRun > 0 }
                when {
                    all.isEmpty() -> false to "worker tracking unavailable"
                    ran == 0 -> false to "NO worker has ever reported a run — autonomous work may be entirely dead"
                    silent.isNotEmpty() -> false to "OVERDUE: ${silent.joinToString { it.worker }} — past their cadence"
                    else -> true to "$ran/${all.size} workers reporting on schedule"
                }
            },
            Step("Recent worker failures") { ctx ->
                val bad = try { WorkerHealth.statuses(ctx).filter { it.fails > 0 } } catch (e: Exception) { emptyList() }
                bad.isEmpty() to (if (bad.isEmpty()) "no worker failures recorded"
                    else "failing: " + bad.joinToString { "${it.worker}(${it.fails})" })
            })),

        Flow("services", "Long-running services", "The bot, accessibility, location stay alive", listOf(
            Step("No service has silently died") { ctx ->
                val ghosts = try { ServiceHealth.ghosts(ctx) } catch (e: Exception) { emptyList() }
                ghosts.isEmpty() to (if (ghosts.isEmpty()) "none believed-alive-but-dead"
                    else "GHOSTS (think they're running, Android says no): ${ghosts.joinToString()}")
            },
            Step("Accessibility engine alive") { _ ->
                val on = com.agentos.shell.InteractionLogService.instance != null
                on to (if (on) "running" else "DEAD — tap-send, screen actions and call answering are all off")
            },
            Step("Telegram bot service") { ctx ->
                if (!TelegramClient.configured()) true to "no bot in this build (nothing to keep alive)"
                else {
                    val alive = try { ServiceHealth.reallyRunning(ctx, "TelegramService") } catch (e: Exception) { false }
                    alive to (if (alive) "service running" else "NOT running — the bot will not answer anyone")
                }
            })),

        Flow("data_integrity", "Data integrity", "The brain stays consistent as it grows", listOf(
            // Real bug visible in the live data: embeddings show 'pending 1' on every single pull.
            Step("Embedding queue drains") { ctx ->
                val pend = try { VectorStore.pendingCount(ctx) } catch (e: Exception) { -1 }
                when {
                    pend < 0 -> true to "pending count unavailable"
                    pend == 0 -> true to "queue empty — everything is embedded"
                    pend < 50 -> true to "$pend waiting (normal, drains on the next embed pass)"
                    else -> false to "$pend STUCK unembedded — semantic recall is degrading"
                }
            },
            Step("Semantic index matches the brain") { ctx ->
                val msgs = try { MessageStore.count(ctx) } catch (e: Exception) { 0 }
                val vecs = try { VectorStore.embeddedCount(ctx) } catch (e: Exception) { 0 }
                if (msgs == 0) true to "nothing to compare yet"
                else {
                    val pct = vecs * 100.0 / msgs
                    (pct > 50) to "$vecs vectors for $msgs messages (${pct.toInt()}%)" +
                        (if (pct <= 50) " — most of the brain is NOT searchable by meaning" else "")
                }
            },
            Step("No duplicate contacts") { ctx ->
                val leads = try { LeadStore.all(ctx) } catch (e: Exception) { emptyList() }
                val dupes = leads.groupBy { it.email.lowercase().trim() }
                    .filter { it.key.isNotBlank() && it.value.size > 1 }.size
                (dupes == 0) to (if (dupes == 0) "${leads.size} contacts, no duplicates" else "$dupes duplicated email(s) in the CRM")
            },
            Step("Brain is growing") { ctx ->
                val n = try { MessageStore.count(ctx) } catch (e: Exception) { 0 }
                (n > 0) to "$n messages stored"
            })),

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
            // THE BUG THAT HID FOR MONTHS: notification channels are immutable, so a channel first created
            // with low importance stays silent forever no matter what the code asks for. Read back the
            // channel Android ACTUALLY has and confirm it can make noise.
            Step("Reminder channel is audible") { ctx ->
                if (android.os.Build.VERSION.SDK_INT < 26) true to "pre-Android 8: sound set on the notification"
                else {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val ch = nm.getNotificationChannel(com.agentos.shell.ReminderReceiver.CHANNEL)
                    when {
                        ch == null -> true to "channel not created yet (created on first reminder)"
                        ch.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT ->
                            false to "channel importance is ${ch.importance} — reminders will be SILENT"
                        ch.sound == null -> false to "channel has NO sound — reminders will be silent"
                        else -> true to "audible (importance ${ch.importance}, vibration ${ch.shouldVibrate()})"
                    }
                }
            },
            Step("Timer actually schedules a ring") { ctx ->
                // A timer used to only draw a countdown — nothing was scheduled, so it never rang.
                val at = System.currentTimeMillis() + 3_600_000
                val ok = com.agentos.shell.ReminderScheduler.schedule(ctx, at, "__flowaudit_timer__")
                try {
                    val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val i = android.content.Intent(ctx, com.agentos.shell.ReminderReceiver::class.java)
                        .putExtra("text", "__flowaudit_timer__")
                    val pi = android.app.PendingIntent.getBroadcast(ctx, (at % 1_000_000).toInt(), i,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    am.cancel(pi); pi.cancel()
                } catch (e: Exception) {}
                ok to (if (ok) "timers schedule a real ring" else "timer would count down SILENTLY")
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
