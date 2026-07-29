package com.agentos.shell.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** A launchable app the user has installed. */
data class AppEntry(val label: String, val pkg: String)

/**
 * SlyOS Tool Registry + router (M1.5).
 *
 * Turns a typed command into a real Android action via intents. This is the
 * deterministic layer; the LLM intent layer (free-form understanding) plugs in
 * on top of this in a later phase. Everything here is a normal app intent —
 * no privilege, no system modification.
 */
object ToolRouter {

    fun installedApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun launchApp(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }

    private fun start(ctx: Context, intent: Intent) {
        // NEVER let a missing handler crash the app (e.g. a device with no clock app for ACTION_SET_ALARM, or a
        // blocked background activity launch). Fail quietly instead.
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) { Log.w("SlyOS", "start intent failed: ${e.message}") }
    }

    private fun webSearch(ctx: Context, q: String) =
        start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(intent); true
    } catch (e: Exception) { false }

    /** The six fixed Manual Mode tools — each tries the default app, then a fallback. */
    fun openTool(ctx: Context, name: String): String {
        val ok = when (name) {
            "Phone" -> tryStart(ctx, Intent(Intent.ACTION_DIAL))
            "Messages" ->
                tryStart(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)) ||
                tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
            "Camera" ->
                tryStart(ctx, Intent("android.media.action.STILL_IMAGE_CAMERA")) ||
                tryStart(ctx, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            "Browser" ->
                tryStart(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)) ||
                tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            "Files" ->
                tryStart(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES)) ||
                tryStart(ctx, Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE))
            "Settings" -> tryStart(ctx, Intent(Settings.ACTION_SETTINGS))
            else -> false
        }
        return if (ok) "Opening $name…" else "No app found for $name"
    }

    /** Execute a structured action chosen by the agent. Returns a feedback line, or "". */
    fun executeAction(ctx: Context, action: String, arg: String): String {
        return try {
            when (action) {
                "open_app" -> {
                    val app = installedApps(ctx)
                        .firstOrNull { it.label.lowercase().contains(arg.lowercase()) }
                    if (app != null) launchApp(ctx, app.pkg) else webSearch(ctx, arg)
                    ""
                }
                "web_search" -> { webSearch(ctx, arg); "" }
                "dial" -> {
                    start(ctx, Intent(Intent.ACTION_DIAL).apply { if (arg.isNotBlank()) data = Uri.parse("tel:$arg") })
                    ""
                }
                "sms" -> {
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (arg.isNotBlank()) "sms:$arg" else "sms:")))
                    ""
                }
                "camera" -> { start(ctx, Intent(MediaStore.ACTION_IMAGE_CAPTURE)); "" }
                "settings" -> { start(ctx, Intent(Settings.ACTION_SETTINGS)); "" }
                "torch", "flashlight" -> {
                    val a = arg.trim().lowercase()
                    // NOTE: don't match bare "light" as ON — the word "flash-LIGHT" contains it, which would
                    // force ON when the user says "flashlight" to toggle OFF. Require explicit on/off words.
                    val want: Boolean? = when {
                        Regex("\\b(off|out|kill|stop|disable|dark|0|false)\\b").containsMatchIn(a) -> false
                        Regex("\\b(on|enable|bright|1|true)\\b").containsMatchIn(a) -> true
                        else -> null   // toggle
                    }
                    val r = Torch.set(ctx, want)
                    try { MemoryLog.add(ctx, "action", "Flashlight", r, "SlyOS") } catch (e: Exception) {}
                    r
                }
                "media", "music_control" -> {
                    val a = arg.trim().lowercase()
                    val r = when {
                        Regex("pause|stop|play|resume|toggle").containsMatchIn(a) -> MediaControls.playPause(ctx)
                        Regex("next|skip|forward|ahead").containsMatchIn(a) -> MediaControls.next(ctx)
                        Regex("prev|previous|back|last|restart").containsMatchIn(a) -> MediaControls.previous(ctx)
                        Regex("open|launch|show").containsMatchIn(a) -> MediaControls.open(ctx)
                        else -> MediaControls.playPause(ctx)
                    }
                    try { MemoryLog.add(ctx, "action", "Media", r, "SlyOS") } catch (e: Exception) {}
                    r
                }
                "identify_song", "song", "shazam" -> SongId.identify(ctx)
                "add_event" -> addEvent(ctx, arg)
                "update_event" -> updateEventRoute(ctx, arg)
                "event_followup" -> eventFollowupRoute(ctx, arg)
                "move_event" -> moveEventRoute(ctx, arg)
                "cancel_event" -> cancelEventRoute(ctx, arg)
                "send_sms" -> sendSms(ctx, arg)
                "message" -> sendMessage(ctx, arg)
                "send_photo" -> sendPhoto(ctx, arg)
                "translate" -> {
                    val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("text", arg) }
                    val text = o.optString("text").ifBlank { arg }
                    val to = o.optString("to").ifBlank { "en" }
                    val out = com.agentos.shell.tools.Translate.translate(text, to)
                    if (out == text) "That's already in the target language (or I couldn't translate offline)." else out
                }
                "navigate" -> navigate(ctx, arg)
                "share_location" -> shareLocation(ctx, arg)
                "send_email" -> sendEmail(ctx, arg)
                "outreach" -> outreachRoute(ctx, arg)
                // ONE action for every document format — the model picks pdf/docx/pptx/xlsx/html.
                "create_document" -> createDocument(ctx, arg)
                "refine_document" -> refineDocument(ctx, arg)
                "open_document" -> openDocument(ctx, arg)
                "send_document" -> sendDocument(ctx, arg)
                "create_doc" -> createDoc(ctx, arg)
                "create_sheet" -> createSheet(ctx, arg)
                "create_slides" -> createSlides(ctx, arg)
                "create_pdf" -> createPdf(ctx, arg)
                "open_url" -> openUrl(ctx, arg)
                "play_music" -> playMusic(ctx, arg)
                "timer" -> setTimer(ctx, arg)
                "alarm" -> setAlarm(ctx, arg)
                "remind" -> remind(ctx, arg)
                "trade" -> executeTrade(ctx, arg)
                "checklist_add" -> { ChecklistStore.add(ctx, arg); "Added to checklist: \"$arg\"" }
                "checklist_clear" -> {
                    // Actually clear (all, or just completed) AND record it in the brain, so what the AI says
                    // matches reality — the executed task always goes through the brain.
                    val doneOnly = arg.contains("done", true) || arg.contains("complet", true) || arg.contains("finish", true)
                    val msg = if (doneOnly) { ChecklistStore.clearDone(ctx); "Cleared completed checklist items." }
                              else { val n = ChecklistStore.clearAll(ctx); "Cleared your checklist ($n item${if (n == 1) "" else "s"})." }
                    try { MessageStore.insertOne(ctx, "Checklist", "Checklist", "system", "system", msg) } catch (e: Exception) {}
                    msg
                }
                "checklist_remove" -> {
                    // Remove specific item(s) by text AND record the truth in the brain.
                    val removed = ChecklistStore.removeMatching(ctx, arg)
                    val msg = if (removed.isEmpty()) "No checklist item matched: " + arg
                              else "Removed from checklist: " + removed.joinToString("; ")
                    if (removed.isNotEmpty()) try { MessageStore.insertOne(ctx, "Checklist", "Checklist", "system", "system", msg) } catch (e: Exception) {}
                    msg
                }
                "pin_app" -> {
                    val app = installedApps(ctx).firstOrNull { it.label.lowercase().contains(arg.lowercase()) }
                    if (app != null) { ShortcutStore.add(ctx, "app", app.label, app.pkg); "Pinned ${app.label} to Home." }
                    else "No app named \"$arg\"."
                }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    /**
     * P2.4: consequential / navigation actions that must NEVER fire straight from model output on a
     * non-user-initiated path (autonomous reply, worker, bot) — an injected message could otherwise
     * auto-open an attacker URL, place a call, spend, or send. These only run when [userInitiated].
     */
    private val GATED = setOf(
        "open_url", "open_app", "web_search", "dial", "sms", "navigate", "play_music", "camera",
        "settings", "send_sms", "message", "send_photo", "send_email", "outreach", "add_event", "move_event", "cancel_event", "share_location",
        "create_doc", "create_sheet", "create_slides", "create_pdf", "trade",
        "create_document", "refine_document", "open_document", "send_document"
    )

    /**
     * Run an ordered list of actions, recording metrics; returns combined feedback. Every entry point
     * routes through here, so the [userInitiated] gate is enforced in ONE place. Autonomous/worker/bot
     * callers MUST pass userInitiated=false so gated actions are skipped instead of auto-executed.
     */
    /** Map a concrete action to the "what for" bucket, so the analytics stream doubles as intent data. */
    fun categoryFor(type: String): String = when (type) {
        "remind", "add_event", "move_event", "cancel_event" -> "remember"
        "timer", "alarm" -> "schedule"
        "send_sms", "sms", "message", "send_email", "outreach", "send_photo", "share_location" -> "communicate"
        "create_doc", "create_sheet", "create_slides", "create_pdf",
        "create_document", "refine_document" -> "create"
        "open_document", "send_document" -> "communicate"
        "trade" -> "finance"
        "identify_song", "song", "shazam", "play_music", "media", "music_control" -> "music"
        "translate" -> "translate"
        "web_search", "open_url" -> "research"
        "navigate" -> "device_control"
        "torch", "flashlight", "open_app", "dial", "camera", "settings", "pin_app" -> "device_control"
        "checklist_add", "checklist_remove", "checklist_clear" -> "remember"
        else -> "other"
    }

    fun executeActions(ctx: Context, actions: List<AgentAction>, userInitiated: Boolean = true): String {
        Log.i("SlyOS", "actions(${actions.size}, user=$userInitiated): " + actions.joinToString { "${it.type}=${it.arg}" })
        val msgs = mutableListOf<String>()
        // WHAT EACH STEP PRODUCED, FOR THE STEPS AFTER IT.
        //
        // Every argument here was written by the planner before anything ran, so step 2 referred to
        // a document or a Meet link that did not exist yet. "Make the deck and email it to Carlos"
        // sent Carlos an empty email and reported both steps as done.
        val bus = ArrayList<ActionChain.Produced>()
        /** Kinds a step was supposed to produce and didn't — the reason to skip what depended on it. */
        val failedKinds = HashSet<String>()
        for (a in actions) {
            if (a.type.isBlank() || a.type == "none") continue
            if (!userInitiated && a.type in GATED) {   // code-level gate: never auto-fire these unattended
                Log.w("SlyOS", "action gated (non-user-initiated): ${a.type}")
                try { Analytics.track(ctx, "action_gated", a.type.take(30), categoryFor(a.type)) } catch (e: Exception) {}
                continue
            }
            // A DUPLICATE OF SOMETHING JUST DONE IS ASKED ABOUT, NOT REPEATED.
            //
            // Nothing guarded this. Say "send it" twice, or tap retry after a slow reply, and two
            // emails went out, two events were created, two invitations arrived. The second one is
            // never what anyone wanted, and it cannot be taken back.
            if (a.type in GATED && ActionGuard.isRepeat(ctx, a.type, a.arg)) {
                msgs.add(ActionGuard.repeatNotice(a.type))
                continue
            }

            // ONE FAILING STEP MUST NOT KILL THE REST.
            //
            // This re-threw, so a chain — "make the doc and email it to Carlos" — lost everything
            // after the first failure, and the caller reported an error for a request that was
            // half done. The owner then had no idea a document had been created. Each step now
            // reports its own outcome and the chain continues.
            // Fill in what earlier steps produced, then decide whether this step still makes sense.
            val arg = ActionChain.resolve(ctx, a.type, a.arg, bus)
            val blocked = ActionChain.blockedBy(a.type, arg, bus, failedKinds)
            if (blocked != null) {
                // Running it anyway would put a message in front of a real person with the important
                // part missing, and tell the owner it worked.
                Log.i("SlyOS", "action ${a.type} skipped — its subject was never created")
                msgs.add(blocked)
                continue
            }

            val m = try { executeAction(ctx, a.type, arg) }
                    catch (e: Exception) {
                        try { Analytics.track(ctx, "action_failed", a.type.take(30), categoryFor(a.type)) } catch (ig: Exception) {}
                        Log.w("SlyOS", "action ${a.type} failed, continuing", e)
                        ActionChain.producesKind(a.type)?.let { k -> failedKinds.add(k) }
                        msgs.add("**${channelFor(a.type)} didn't happen** — ${e.message ?: "it failed"}.")
                        continue
                    }
            // A step that returned without throwing can still have produced nothing — "Couldn't build
            // that document" is a normal return value here, and the next step must know.
            val made = ActionChain.capture(ctx, a.type, arg, m)
            if (made != null) bus.add(made)
            else ActionChain.producesKind(a.type)?.let { k -> failedKinds.add(k) }

            if (a.type in GATED) ActionGuard.remember(ctx, a.type, a.arg)
            MetricsStore.record(ctx, MetricsStore.secondsFor(a.type))
            // WIN: an action actually ran. Tag it with the feature and the what-for bucket so you can see
            // both "which features get used" and "what people use SlyOS for" from the same stream.
            try { Analytics.track(ctx, "action", a.type.take(30), categoryFor(a.type)) } catch (e: Exception) {}
            // EVERY action taken on your behalf lands in "Sent for you" AND in the brain — recorded HERE,
            // at the one choke point all actions pass through, rather than at individual call sites (which
            // is why most actions were previously invisible: only ~8 places ever called OutboxStore).
            try { recordAction(ctx, a.type, a.arg, m, userInitiated) } catch (e: Exception) {}
            if (m.isNotEmpty()) msgs.add(m)
        }
        // Hand what this batch made to whatever runs next — the confirmation card executes in its
        // own call, after this one has returned and taken the bus with it.
        ActionChain.publishBatch(bus)
        return msgs.joinToString("  ")
    }

    /**
     * Make a document in ANY format. arg = {"title":…,"brief":…,"format":"pdf|docx|pptx|xlsx|html"}.
     * If no format is given we do NOT guess — we ask, because that's a real preference, and a deck
     * delivered as a Word file is a wasted minute for everyone.
     */
    private fun createDocument(ctx: Context, arg: String): String {
        val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("brief", arg) }
        // "…and email it to eshir010@ucr.edu" is who it goes to, not what it is about. Left in, it
        // became the title, so the file was named after the recipient's address and that name then
        // travelled onward as the subject line and the attachment.
        val brief = ActionChain.stripDelivery(o.optString("brief").ifBlank { arg })
        val title = o.optString("title").ifBlank {
            brief.split(Regex("[.\\n]")).firstOrNull()?.take(60)?.trim().orEmpty().ifBlank { "Document" }
        }
        val fmt = o.optString("format").lowercase().ifBlank { DocForge.formatFrom(brief) }
        if (fmt.isBlank())
            return "What format would you like — a PDF, a Word doc (.docx), a slide deck (.pptx), or a spreadsheet (.xlsx)?"
        val m = DocForge.create(ctx, title, brief, fmt, o.optString("kind"))
        return if (m.ok)
            "Made “${m.name}” ✓ — it's in your SlyOS folder. Say “open it”, “send it”, or tell me what to change."
        else "Couldn't build that document — ${m.error}"
    }

    /** "make it shorter", "add a pricing slide", "turn it into a PDF" — edits the doc we just made. */
    private fun refineDocument(ctx: Context, arg: String): String {
        val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("instruction", arg) }
        val instruction = o.optString("instruction").ifBlank { arg }
        if (!DocForge.hasDraft(ctx)) return "There's no document to refine yet — ask me to make one first."
        val m = DocForge.refine(ctx, instruction, o.optString("format").lowercase())
        return if (m.ok) "Updated “${m.name}” ✓ — ${instruction.take(60)}. Say “open it” or “send it”."
               else "Couldn't revise it — ${m.error}"
    }

    private fun latestDoc(ctx: Context, arg: String): SlyFolder.Doc? =
        (if (arg.isNotBlank()) DocForge.find(ctx, arg) else null) ?: DocForge.library(ctx).firstOrNull()

    private fun openDocument(ctx: Context, arg: String): String {
        val d = latestDoc(ctx, arg) ?: return "I haven't made any documents yet."
        return if (DocForge.open(ctx, d.uri, d.name)) "Opening “${d.name}”."
               else "Couldn't open “${d.name}” — no app on this phone handles that file type."
    }

    /**
     * Send the document — actually send it, when the owner said who to.
     *
     * This ignored the recipient completely. "Make a one-pager and email it to eshir010@ucr.edu"
     * planned `send_document {"name":…,"to":"eshir010@ucr.edu"}` and this opened a share sheet,
     * dropping the address on the floor and handing the job back to the person who had just asked
     * for it to be done. The document was made; the sending — the half that involves another human
     * being — quietly became homework.
     *
     * The share sheet stays as the fallback for when nobody was named, which is a genuine case
     * ("send it" with no recipient), not a failure.
     */
    private fun sendDocument(ctx: Context, arg: String): String {
        val o = try { JSONObject(arg) } catch (e: Exception) { null }
        // A JSON argument must not be passed to the name matcher whole — it would score the doc
        // against "to", "name" and the address itself.
        val name = o?.optString("name").orEmpty().ifBlank { if (o == null) arg else "" }
        val d = latestDoc(ctx, name) ?: return "I haven't made any documents yet."

        var to = o?.optString("to").orEmpty().trim()
        if (to.isNotBlank() && !to.contains("@")) {
            val p = try { PersonResolver.resolve(ctx, to) } catch (e: Exception) { null }
            if (p != null && p.candidates.size > 1 && p.email.isBlank())
                return "Which $to? " + p.candidates.joinToString(", ") + " — tell me which and I'll send it."
            if (p != null && p.email.isNotBlank()) to = p.email
        }

        if (to.contains("@") && to.contains(".") && GoogleAuth.isConnected(ctx)) {
            val file = ActionChain.asFile(ctx, d.uri, d.name)
            if (file != null) {
                val subject = d.name.substringBeforeLast('.').replace('_', ' ')
                val (ok, msg) = GmailClient.sendWithAttachments(
                    ctx, to, subject, "Here's the one you asked for — it's attached.", listOf(file))
                if (ok) {
                    MemoryLog.add(ctx, "response", "Email: $subject", "Sent “${d.name}” to $to", "Email")
                    return "Emailed “${d.name}” to $to ✓"
                }
                return "Couldn't email “${d.name}” to $to — $msg"
            }
        }

        return if (DocForge.share(ctx, d.uri, d.name)) "Pick where to send “${d.name}”."
               else "Couldn't share “${d.name}”."
    }

    /** Actions that are pure reads/navigation — logging every one would drown the real activity. */
    private val NOT_WORTH_LOGGING = setOf("none", "web_search", "open_app", "settings", "camera", "look")

    /** Human label for the outbox row, so "Sent for you" reads like a story, not a debug log. */
    private fun channelFor(type: String): String = when (type) {
        "send_email" -> "Email"
        "outreach" -> "Outreach"
        "send_sms", "sms", "message" -> "Message"
        "send_photo" -> "Photo"
        "create_doc" -> "Doc"; "create_sheet" -> "Sheet"; "create_slides" -> "Deck"; "create_pdf" -> "PDF"
        "create_document", "refine_document", "open_document", "send_document" -> "Document"
        "add_event", "move_event", "cancel_event" -> "Calendar"
        "remind", "timer", "alarm" -> "Reminder"
        "trade" -> "Trading"
        "share_location" -> "Location"
        "navigate" -> "Navigation"
        "translate" -> "Translation"
        "checklist_add", "checklist_remove", "checklist_clear" -> "Checklist"
        "identify_song", "song", "shazam" -> "Music"
        "play_music", "media", "music_control" -> "Media"
        "torch", "flashlight" -> "Device"
        "dial" -> "Call"
        "pin_app" -> "Home"
        else -> "Action"
    }

    /**
     * Log ONE executed action to the outbox and the brain.
     *
     * Two guarantees this gives us: (1) "Sent for you" shows everything SlyOS did on your behalf, not just
     * the handful of flows that remembered to log; (2) the action becomes recallable — asking "what did you
     * do today" or "did you email Sarah" hits the brain and finds it, because every path writes here.
     */
    /** Actions where the user is WAITING for something back — silence here is a failure, not a no-op. */
    private val MUST_PRODUCE_OUTPUT = setOf(
        "create_document", "refine_document", "create_doc", "create_sheet", "create_slides", "create_pdf",
        "expenses", "documents", "translate", "identify_song", "song", "shazam", "write_paper",
        "find_job", "network_search", "shop", "look", "invest", "faces", "compose_post", "spicy_post")

    private fun recordAction(ctx: Context, type: String, arg: String, result: String, userInitiated: Boolean) {
        if (type in NOT_WORTH_LOGGING) return
        if (result.isBlank()) {
            // SILENT EMPTY RESULT — no exception, no "couldn't…", just nothing came back. This is the
            // failure mode that used to be completely invisible: you ask for expenses, a filled form, a
            // fetched document, and simply get no answer. If output was expected, that IS a failure.
            if (type in MUST_PRODUCE_OUTPUT)
                Fail.log(ctx, channelFor(type), "$type → ${arg.take(50)}",
                    "produced NO output — you asked for something and got nothing back")
            return
        }
        val channel = channelFor(type)
        // Who/what it concerned — a name, title, or the first meaningful part of the argument.
        val subject = try {
            val o = JSONObject(arg)
            listOf("to", "name", "contact", "title", "symbol", "text", "query")
                .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } } ?: arg
        } catch (e: Exception) { arg }.trim().take(60).ifBlank { type }
        val failed = Fail.looksFailed(result)
        val why = if (userInitiated) "you asked" else "SlyOS did this autonomously"
        try {
            OutboxStore.record(ctx, channel, subject, type, result.take(400), why,
                if (failed) "failed" else "sent")
        } catch (e: Exception) {}
        // ANY action that didn't work is recorded centrally — an email address that couldn't be found, a
        // message that didn't send, a doc that wouldn't build. This is the one place every action passes
        // through, so nothing can fail silently.
        if (failed) Fail.log(ctx, channel, "$type → $subject", result.take(200))
        // Into the searchable brain too, so the action is recallable later like any other memory.
        try {
            MessageStore.insertOne(ctx, channel, "SlyOS", "system", "system",
                "$channel · $subject — ${result.take(300)}")
        } catch (e: Exception) {}
        try { MemoryLog.add(ctx, "action", "$channel: $subject", result.take(400), "SlyOS") } catch (e: Exception) {}
    }

    /** Execute a PRACTICE buy/sell at the live price and log it to the brain. arg = {symbol,action,shares,name?}. */
    private fun executeTrade(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val symbol = o.optString("symbol").trim().uppercase()
            val action = o.optString("action").trim().lowercase()
            val shares = o.optDouble("shares", 0.0)
            if (symbol.isBlank() || shares <= 0) return "Which stock and how many shares?"
            val price = QuoteClient.quotes(listOf(symbol))[symbol]?.price
                ?: return "Couldn't get a live price for $symbol right now."
            val ok = if (action == "sell") TradeStore.sell(ctx, symbol, shares, price)
                     else TradeStore.buy(ctx, symbol, o.optString("name", symbol), shares, price)
            if (ok) {
                MessageStore.insertOne(ctx, "Trading", "Trade", "system", "system",
                    "${action.replaceFirstChar { it.uppercase() }} ${"%.4f".format(shares)} $symbol @ $${"%.2f".format(price)} (practice)")
                "${action.replaceFirstChar { it.uppercase() }} ${"%.4f".format(shares)} $symbol at $${"%.2f".format(price)} — done (practice account)."
            } else if (action == "sell") "You don't hold that many $symbol." else "Not enough practice cash for that."
        } catch (e: Exception) { "I couldn't place that trade." }
    }

    private fun sendSms(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val name = o.optString("name")
            val body = o.optString("body")
            if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find ${name.ifBlank { "them" }}."
            val contact = when (val r = ContactsTool.resolve(ctx, name)) {
                is ContactsTool.Resolution.Found -> r.contact
                is ContactsTool.Resolution.Ambiguous ->
                    return "I know a few people like “$name”: ${r.options.joinToString(", ") { it.name }}. Which one should I text? (tell me the full name)"
                ContactsTool.Resolution.None ->
                    return "I couldn't find a contact called “$name”. What's their full name or number?"
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED) return "SMS permission is off."
            val sms = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendTextMessage(contact.number, null, body, null, null)
            android.util.Log.i("SlyOS", "sms -> ${contact.name} (${contact.number})")
            // Record what you sent so it feeds the brain (searchable + reply context with this person).
            MessageStore.insertOne(ctx, contact.name, "SMS", contact.name, "me", body)
            ConversationStore.add(ctx, "SMS", contact.name, "me", body)
            "Texted ${contact.name}: \"$body\""
        } catch (e: Exception) {
            android.util.Log.e("SlyOS", "sendSms failed", e); "Couldn't send the text."
        }
    }

    private fun setTimer(ctx: Context, arg: String): String {
        val secs = parseDuration(arg)
        if (secs <= 0) return "How long should the timer be?"
        // In-app timer so the Home countdown widget can show it live (a system-clock timer can't be read back).
        val label = arg.replace(Regex("(?i)\\b(timer|set|for|a|an|minutes?|mins?|seconds?|secs?|hours?|hrs?|\\d+)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        TimerStore.start(ctx, secs, label)
        val pretty = if (secs >= 3600) "${secs / 3600}h ${(secs % 3600) / 60}m" else if (secs >= 60) "${secs / 60} min" else "$secs sec"
        // THE TIMER BUG: this only started an on-screen countdown — NOTHING was ever scheduled to fire, so
        // a timer could never ring. It just silently reached zero. Schedule a real alarm-toned reminder at
        // the end so the timer actually goes off, whether or not you're looking at the Home screen.
        val fired = try {
            com.agentos.shell.ReminderScheduler.schedule(ctx, System.currentTimeMillis() + secs * 1000L,
                (label.ifBlank { "Timer" }) + " — $pretty is up")
        } catch (e: Exception) { false }
        if (!fired) Fail.log(ctx, "Reminder", "timer for $pretty", "could not schedule the ring — it will count down silently")
        try { MessageStore.insertOne(ctx, "Timers", "Timer", "me", "me", "Timer set for $pretty") } catch (e: Exception) {}
        return "Timer set for $pretty — counting down on your Home screen, and it'll ring when it's up."
    }

    /**
     * CHANGE AN EXISTING EVENT AND TELL THE PEOPLE ON IT.
     * arg = {"title":"date night","addMeet":true,"start":"2026-07-26T19:00","end":"...",
     *        "addAttendees":["a@b.com"],"notify":true}
     * Finds the event by title among upcoming ones — that is how the owner refers to them ("the date
     * night invite"), never by id. Everything goes out with sendUpdates=all, because a change nobody is
     * told about leaves everyone believing the old details.
     */
    private fun updateEventRoute(ctx: Context, arg: String): String {
        if (!GoogleAuth.isConnected(ctx)) return "Connect Google in Settings and I can update the event and notify everyone."
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title").ifBlank { return "Which event should I update?" }
            val target = GoogleCalendarClient.findEvents(ctx, title).firstOrNull()
                ?: return "I couldn't find an upcoming event matching \u201c$title\u201d."
            val addAttendees = ArrayList<String>()
            o.optJSONArray("addAttendees")?.let { for (i in 0 until it.length()) addAttendees.add(it.optString(i)) }
            val startMs = o.optString("start").takeIf { it.isNotBlank() }?.let { isoToMs(it) }
            val endMs = o.optString("end").takeIf { it.isNotBlank() }?.let { isoToMs(it) }
            val wantMeet = o.optBoolean("addMeet", false) && target.meetLink.isBlank()
            val r = GoogleCalendarClient.patchEvent(ctx, target.id,
                startMs = startMs, endMs = endMs, addAttendees = addAttendees,
                addMeet = wantMeet, notify = o.optBoolean("notify", true))
            if (!r.ok) return "I couldn't update \u201c${target.title}\u201d: ${r.error}"
            // Record what CHANGED, not what was intended — this is the row a later "did that go out?" reads.
            MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                "Updated: ${r.title}" + (if (startMs != null) " · moved to ${o.optString("start")}" else "") +
                    (if (wantMeet) " · Meet link added" else "") +
                    (if (addAttendees.isNotEmpty()) " · invited ${addAttendees.joinToString(", ")}" else "") +
                    " · everyone on it was notified")
            buildString {
                append("Updated \u201c${r.title}\u201d")
                if (startMs != null) append(", moved to ${o.optString("start")}")
                if (wantMeet && r.meetLink.isNotBlank()) append(", Meet link added: ${r.meetLink}")
                if (addAttendees.isNotEmpty()) append(", invited ${addAttendees.joinToString(", ")}")
                append(". Everyone on it has been emailed the update")
                val pending = r.attendees.filter { it.responseStatus != "accepted" && !it.organizer }
                if (pending.isNotEmpty()) append(" \u00b7 still waiting on ${pending.joinToString(", ") { it.email }}")
                append(".")
            }
        } catch (e: Exception) { Log.w("SlyOS", "updateEvent: ${e.message}"); "I couldn't read that update." }
    }

    /**
     * CHASE THE PEOPLE WHO HAVEN'T REPLIED. arg = {"title":"date night","message":"optional note"}
     * Emails only those whose RSVP is not "accepted" — never the whole list, so nobody who already
     * confirmed gets nagged. Declines are reported back rather than emailed: a declined invite needs the
     * owner's judgement (reschedule? drop them?), not an automatic reminder.
     */
    private fun eventFollowupRoute(ctx: Context, arg: String): String {
        if (!GoogleAuth.isConnected(ctx)) return "Connect Google in Settings and I can follow up with them."
        return try {
            val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("title", arg) }
            val title = o.optString("title").ifBlank { return "Which event should I follow up about?" }
            val ev = GoogleCalendarClient.findEvents(ctx, title).firstOrNull()
                ?: return "I couldn't find an upcoming event matching \u201c$title\u201d."
            val declined = ev.attendees.filter { it.responseStatus == "declined" }
            val waiting = ev.attendees.filter { it.responseStatus != "accepted" && it.responseStatus != "declined" && !it.organizer }
            if (ev.attendees.isEmpty()) return "\u201c${ev.title}\u201d has nobody invited yet \u2014 want me to add them?"
            if (waiting.isEmpty() && declined.isEmpty()) return "Everyone on \u201c${ev.title}\u201d has already accepted \u2014 nothing to chase."
            var sent = 0
            val note = o.optString("message").ifBlank {
                "Just checking you saw the invite for \u201c${ev.title}\u201d" +
                    (if (ev.startIso.isNotBlank()) " on ${ev.startIso.take(16).replace('T', ' ')}" else "") +
                    (if (ev.meetLink.isNotBlank()) "\n\nGoogle Meet: ${ev.meetLink}" else "") +
                    "\n\nLet me know if that time doesn't work."
            }
            waiting.forEach { a ->
                val (ok, _) = try { GmailClient.send(ctx, a.email, "Re: ${ev.title}", note) } catch (e: Exception) { false to "" }
                if (ok) sent++
            }
            MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                "Followed up on ${ev.title} \u00b7 emailed ${waiting.take(5).joinToString(", ") { it.email }}" +
                    (if (declined.isNotEmpty()) " \u00b7 declined: ${declined.joinToString(", ") { it.email }}" else ""))
            buildString {
                if (sent > 0) append("Followed up with ${waiting.joinToString(", ") { it.email }} about \u201c${ev.title}\u201d.")
                else if (waiting.isNotEmpty()) append("I couldn't send the follow-up emails.")
                if (declined.isNotEmpty())
                    append(" ${declined.joinToString(" and ") { it.email }} declined \u2014 want me to find another time?")
            }
        } catch (e: Exception) { Log.w("SlyOS", "eventFollowup: ${e.message}"); "I couldn't read that." }
    }

    /** "2026-07-26T16:00" -> epoch millis in the device's zone. */
    private fun isoToMs(iso: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(iso)?.time
    } catch (e: Exception) { null }

    /** Public entry so UI (e.g. the wake-up suggestion chip) can set an alarm directly. */
    fun quickAlarm(ctx: Context, timeArg: String): String = setAlarm(ctx, timeArg)

    private fun setAlarm(ctx: Context, arg: String): String {
        val hm = parseClockTime(arg) ?: return "What time should the alarm go off? (e.g. “7am”, “18:30”, “in 20 minutes”)"
        val (h, m) = hm
        val label = Regex("(?i)\\b(for|at|to)\\b").split(arg).lastOrNull()?.let {
            Regex("(?i)(alarm|am|pm|\\d|:|in|minutes?|mins?|hours?|hrs?|noon|midnight|half|quarter|past)").replace(it, "").trim()
        }.orEmpty().take(40)
        // ACTION_SET_ALARM sets a REAL system alarm — it rings through Doze, silent mode, and reboots, which a
        // WorkManager/handler alarm can't guarantee. That's the "actually works" part.
        start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
            .putExtra(AlarmClock.EXTRA_VIBRATE, true)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val pretty = prettyTime(h, m)
        // BACKUP RING: handing the alarm to the system clock app is the right primary path, but we cannot
        // verify it actually landed — some OEM clock apps (Samsung especially) create an alarm from
        // EXTRA_SKIP_UI and leave it DISABLED, so nothing ever rings and nothing reports a problem.
        // SlyOS therefore schedules its own alarm-toned reminder for the same moment as a safety net.
        try {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            com.agentos.shell.ReminderScheduler.schedule(ctx, cal.timeInMillis,
                label.ifBlank { "Alarm" } + " — $pretty")
        } catch (e: Exception) { Fail.log(ctx, "Reminder", "backup alarm for $pretty", e.message ?: "failed") }
        val note = "Alarm set for $pretty" + (if (label.isNotBlank()) " — “$label”" else "")
        try { MessageStore.insertOne(ctx, "Alarms", "Alarm", "me", "me", note) } catch (e: Exception) {}
        try { MemoryLog.add(ctx, "action", "Alarm", note, "SlyOS") } catch (e: Exception) {}
        return "$note. It'll ring even on silent or in Doze."
    }

    /** Parse an alarm time from natural language → 24h (hour, minute). Handles am/pm, bare hours (soonest
     *  future), noon/midnight, HH:MM, and relative "in 20 min / in 2 hours". Returns null if unparseable. */
    private fun parseClockTime(raw: String): Pair<Int, Int>? {
        val t = raw.trim().lowercase()
        if (t.isBlank()) return null
        // Relative: "in 20 minutes", "in 2 hours", "in 90 min"
        if (Regex("\\bin\\b|from now|after").containsMatchIn(t)) {
            val secs = parseDuration(t)
            if (secs > 0) {
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, secs) }
                return cal.get(java.util.Calendar.HOUR_OF_DAY) to cal.get(java.util.Calendar.MINUTE)
            }
        }
        if (Regex("\\bnoon\\b").containsMatchIn(t)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return 0 to 0
        // "7 30 am" / "7 30" — space between hour and minutes (speech-to-text writes times this way).
        Regex("\\b(\\d{1,2})\\s+(\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\b").find(t)?.let { g ->
            var hh = g.groupValues[1].toIntOrNull() ?: return@let
            val mm = g.groupValues[2].toIntOrNull() ?: return@let
            val ap2 = g.groupValues[3].replace(".", "")
            if (ap2 == "pm" && hh < 12) hh += 12
            if (ap2 == "am" && hh == 12) hh = 0
            if (hh in 0..23 && mm in 0..59) return hh to mm
        }
        val mtch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t) ?: return null
        var h = mtch.groupValues[1].toIntOrNull() ?: return null
        val m = mtch.groupValues[2].toIntOrNull() ?: 0
        val ap = mtch.groupValues[3].replace(".", "")
        if (h > 23 || m > 59) return null
        when {
            ap == "pm" && h < 12 -> h += 12
            ap == "am" && h == 12 -> h = 0
            ap.isBlank() && h in 1..11 -> {
                // Ambiguous bare hour → pick whichever of AM/PM comes SOONEST in the future.
                val now = java.util.Calendar.getInstance()
                fun next(hour: Int): Long {
                    val c = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, m)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (c.before(now)) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    return c.timeInMillis
                }
                h = if (next(h) <= next(h + 12)) h else h + 12
            }
        }
        return h to m
    }

    private fun prettyTime(h: Int, m: Int): String {
        val ap = if (h < 12) "AM" else "PM"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "%d:%02d %s".format(h12, m, ap)
    }

    /** Schedule a timed reminder that pops a notification with a message.
     *  arg = {"text":"call mom","in":1200}  (relative seconds)  or  {"text":"leave","at":"2026-07-02T15:00"}. */
    private fun remind(ctx: Context, arg: String): String {
        return try {
            val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("text", arg) }
            val text = o.optString("text").ifBlank { return "What should I remind you about?" }
            val now = System.currentTimeMillis()
            val at = when {
                o.has("in") -> now + o.optLong("in", 0L) * 1000L
                o.optString("at").isNotBlank() -> parseLocal(o.optString("at"))
                else -> 0L
            }
            if (at <= now + 1000) return "When should I remind you?"
            com.agentos.shell.ReminderScheduler.schedule(ctx, at, text)
            MessageStore.insertOne(ctx, "Reminders", "Reminder", "me", "me", "Reminder set: $text")
            val whenStr = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
            "Reminder set for $whenStr — “$text”"
        } catch (e: Exception) { "I couldn't set that reminder." }
    }

    /**
     * Seconds from a duration argument.
     *
     * The old version stripped every digit out of the string and glued them together, so "1h30"
     * became 130 — then multiplied by 3600, giving a 32-hour timer. "2 minutes 30 seconds" became
     * 230 minutes. Any compound duration was silently, wildly wrong.
     *
     * Now: a bare number is seconds (what ScreenIntent emits); anything else goes through the same
     * unit-aware parser the intent layer uses, so both paths agree.
     */
    private fun parseDuration(s: String): Int {
        val t = s.trim().lowercase()
        if (Regex("^\\d+$").matches(t)) return t.toIntOrNull() ?: 0
        ScreenIntent.durationSeconds(t)?.let { return it }
        val num = t.filter { it.isDigit() }.toIntOrNull() ?: return 0
        return when {
            t.contains('h') -> num * 3600
            t.contains('m') && !t.contains("ms") -> num * 60
            else -> num
        }
    }

    private fun addEvent(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title", "Busy")
            val startMs = parseLocal(o.optString("start"))
            val endMs = parseLocal(o.optString("end"))
            if (startMs <= 0 || endMs <= 0) return "I couldn't read those times."
            val named = ArrayList<String>()
            o.optJSONArray("attendees")?.let { for (i in 0 until it.length()) named.add(it.optString(i)) }
            val wantsMeet = o.optBoolean("meet", false) || o.optString("location").contains("meet", true)

            // TURN NAMES INTO ADDRESSES BEFORE DECIDING ANYTHING.
            //
            // This read the model's array verbatim, so "invite Joslyn" arrived as the string "Joslyn",
            // hasEmails was false, the Google path was skipped, and the event was written to a local
            // calendar that emails nobody. The owner was told an event existed; Joslyn was never asked.
            // PersonResolver already does contacts → messages → network and even reports genuine
            // ambiguity — it simply was never called from here.
            val attendees = ArrayList<String>()
            val unresolved = ArrayList<String>()
            var ambiguity = ""
            for (who in named) {
                if (who.contains("@") && who.contains(".")) { attendees.add(who.trim()); continue }
                val p = try { PersonResolver.resolve(ctx, who) } catch (e: Exception) { null }
                when {
                    // Two people you actually message share this name. Picking one silently is how the
                    // wrong Anna gets invited to something.
                    p != null && p.candidates.size > 1 && p.email.isBlank() -> {
                        ambiguity = "Which $who? " + p.candidates.joinToString(", ") + " — tell me which and I'll send it."
                    }
                    p != null && p.email.isNotBlank() -> attendees.add(p.email)
                    else -> unresolved.add(who)
                }
            }
            if (ambiguity.isNotBlank()) return ambiguity

            // An invitation with nobody on it is not a partial success. When inviting WAS the request,
            // create nothing and ask; when the owner wanted the slot anyway, make it and say plainly
            // who is missing.
            val invitingWasThePoint = named.isNotEmpty() && attendees.isEmpty() &&
                !Regex("(?i)\\b(block|busy|hold|focus|reminder)\\b").containsMatchIn(title + " " + arg)
            if (invitingWasThePoint && unresolved.isNotEmpty()) {
                return "I don't have an email address for ${unresolved.joinToString(" or ")}. " +
                    "Give me one and I'll create it and send the invite."
            }

            val hasEmails = attendees.isNotEmpty()

            // Real Google path: if connected, create the event via the Calendar API so we get an actual
            // Google Meet link and email invites — something CalendarContract simply can't do.
            if (GoogleAuth.isConnected(ctx) && (wantsMeet || hasEmails)) {
                // Location, agenda and recurrence travel with it now. They were parsed, shown back
                // to the owner, and then dropped at the point of creation — a guest opening that
                // invitation saw a title and a time and no idea where to go.
                val r = GoogleCalendarClient.createEvent(
                    ctx, title, startMs, endMs, attendees, wantsMeet,
                    location = o.optString("location"),
                    description = o.optString("description"),
                    recurrence = o.optString("recurrence").takeIf { it.isNotBlank() },
                    timeZone = o.optString("tz").takeIf { it.isNotBlank() })
                if (r.ok) {
                    val link = r.meetLink.ifBlank { r.htmlLink }
                    MemoryLog.add(ctx, "response", "Calendar: $title",
                        "Created “$title” on Google Calendar (${o.optString("start")}–${o.optString("end")})" +
                            (if (attendees.isNotEmpty()) " with ${attendees.joinToString(", ")}" else "") +
                            (if (r.meetLink.isNotBlank()) " · Meet: ${r.meetLink}" else ""), "Calendar")
                    MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                        "Created: $title · ${o.optString("start")} to ${o.optString("end")}" +
                            (if (attendees.isNotEmpty()) " · with ${attendees.joinToString(", ")}" else "") +
                            (if (r.meetLink.isNotBlank()) " · Meet ${r.meetLink}" else ""))
                    val who = if (attendees.isNotEmpty()) ", invited ${attendees.joinToString(", ")}" else ""
                    val missed = if (unresolved.isEmpty()) "" else
                        " ${unresolved.joinToString(" and ")} " +
                        (if (unresolved.size == 1) "was NOT invited" else "were NOT invited") +
                        " — I don't have an address."
                    return if (r.meetLink.isNotBlank())
                        "Created “$title” on your Google Calendar$who. Google Meet link: ${r.meetLink}"
                    else "Created “$title” on your Google Calendar$who."
                }
                if (r.error == "not-connected") { /* token expired/revoked — fall through to local */ }
                else Log.w("SlyOS", "Google Calendar failed (${r.error}); falling back to local")
            }
            if (CalendarTool.canWrite(ctx)) {
                val r = CalendarTool.addEvent(ctx, title, startMs, endMs, attendees)
                if (r.startsWith("OK::")) {
                    val where = r.removePrefix("OK::")
                    // Feed the brain so the agent knows about the block when it answers later.
                    MemoryLog.add(ctx, "response", "Calendar: $title", "Blocked “$title” in $where (${o.optString("start")}–${o.optString("end")})", "Calendar")
                    // THIS PATH INVITES NOBODY, AND USED TO SAY IT DID.
                    // A CalendarContract write puts a row in a local calendar; it sends no email to anyone.
                    // Only the Google API path above, with sendUpdates=all, actually invites people. This
                    // branch nonetheless returned "and invited Joslyn" and wrote "· with Joslyn" into the
                    // brain. So the owner was told the invite went out, the brain recorded that it went out,
                    // and when he later asked "was it sent to Joslyn?" the answer was a confident yes —
                    // sourced from a memory that was false the moment it was written. She never got it.
                    // A false confirmation is worse than a failure: a failure gets noticed and retried.
                    // Say plainly that the block exists and the invite did NOT go out, and record it that way.
                    val notInvited = attendees.isNotEmpty()
                    MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                        "Blocked: $title · ${o.optString("start")} to ${o.optString("end")}" +
                            (if (notInvited) " · NO invite sent to ${attendees.joinToString(", ")} " +
                                "(local calendar only — nothing was emailed)" else ""))
                    val who = if (notInvited)
                        " — but ${attendees.joinToString(" and ")} " +
                        (if (attendees.size == 1) "was NOT invited" else "were NOT invited") +
                        ": this went into a local calendar, which can't email anyone" else ""
                    val meetHint = if (wantsMeet || notInvited)
                        (if (!GoogleAuth.isConnected(ctx))
                            " Connect Google in Settings and I can send the real invite" +
                                (if (wantsMeet) " with a Meet link." else ".")
                         else " Google is connected but the invite call failed — try again and I'll send it.")
                        else ""
                    return "Added “$title” to your $where$who.$meetHint"
                }
            }
            // Fallback: open the calendar's new-event screen pre-filled (always works).
            start(ctx, Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                .putExtra(CalendarContract.Events.TITLE, title))
            // "It just shows me the calendar" — this is that. It is the last resort when nothing could be
            // written directly, and it is silent about what got dropped on the way: a prefilled new-event
            // screen carries no attendees and no Meet link, so anyone the owner named is quietly not invited.
            // Name the loss instead of leaving them to discover it when the other person never shows up.
            "Opened your calendar to confirm “$title” — I couldn't write it directly (no synced calendar found)." +
                (if (attendees.isNotEmpty())
                    " ${attendees.joinToString(" and ")} " +
                    (if (attendees.size == 1) "is NOT invited" else "are NOT invited") +
                    " — tap Save and add them yourself, or connect Google in Settings and I'll send it properly."
                 else "") +
                (if (wantsMeet) " There's no Meet link on it either." else "")
        } catch (e: Exception) {
            Log.e("SlyOS", "addEvent failed", e); "I couldn't read those times."
        }
    }

    /** Enqueue a spam-safe outreach drip the agent resolved (arg = {recipients:[{name,email}], subject, body,
     *  attach, everyMin, campaign}). One email per recipient, paced. */
    private fun outreachRoute(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val body = o.optString("body")
            if (body.isBlank()) return "There's nothing to send."
            val recips = ArrayList<OutreachQueue.Recipient>()
            o.optJSONArray("recipients")?.let { a ->
                for (i in 0 until a.length()) { val r = a.optJSONObject(i) ?: continue; recips.add(OutreachQueue.Recipient(r.optString("name"), r.optString("email"))) }
            }
            if (recips.isEmpty()) return "No recipients to send to."
            val everyMin = o.optInt("everyMin", 60).coerceIn(1, 1440)
            val n = OutreachQueue.enqueue(ctx, recips, o.optString("subject").ifBlank { "Hello" }, body, o.optString("attach"), everyMin, o.optString("campaign"))
            if (n > 0) "Queued outreach to $n ${if (n == 1) "person" else "people"} — sending ≈1 every ${everyMin}m ✓"
            else "Those contacts are already queued."
        } catch (e: Exception) { Log.e("SlyOS", "outreach failed", e); "Couldn't queue that outreach." }
    }

    /** Reschedule an event the agent already resolved to an id (arg = {id, title, start_ms, end_ms}). */
    private fun moveEventRoute(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val id = o.optLong("id"); val s = o.optLong("start_ms"); val e = o.optLong("end_ms")
            if (id <= 0 || s <= 0) return "I couldn't identify that event."
            val r = CalendarTool.moveEvent(ctx, id, s, if (e > s) e else s + 1_800_000L)
            if (r == "OK") {
                MemoryLog.add(ctx, "response", "Calendar: move", "Moved “${o.optString("title")}”", "Calendar")
                "Moved “${o.optString("title")}” ✓"
            } else "Couldn't move that event."
        } catch (e: Exception) { Log.e("SlyOS", "moveEvent failed", e); "Couldn't move that event." }
    }

    /** Cancel an event the agent already resolved to an id (arg = {id, title}). */
    private fun cancelEventRoute(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg); val id = o.optLong("id")
            if (id <= 0) return "I couldn't identify that event."
            val r = CalendarTool.cancelEvent(ctx, id)
            if (r == "OK") {
                MemoryLog.add(ctx, "response", "Calendar: cancel", "Canceled “${o.optString("title")}”", "Calendar")
                "Canceled “${o.optString("title")}” ✓"
            } else "Couldn't cancel that event."
        } catch (e: Exception) { Log.e("SlyOS", "cancelEvent failed", e); "Couldn't cancel that event." }
    }

    /** Send/draft a message on a SPECIFIC app. SMS sends directly; WhatsApp opens pre-filled (one tap);
     *  Telegram copies + opens (paste). All are recorded to the brain. */
    /** Find photo(s) from the gallery by description — VERIFIED with vision so it's accurate — and send them. */
    private fun sendPhoto(ctx: Context, arg: String): String {
        return try {
            val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("query", arg) }
            val query = o.optString("query").ifBlank { o.optString("description") }.ifBlank { arg }
            val name = o.optString("name").trim()
            val app = o.optString("app").trim()
            val message = o.optString("message").trim()
            val count = o.optInt("count", 1).coerceIn(1, 6)

            val ss = Regex("(?i)screenshot")
            val ql = query.lowercase()
            // FREE on-device index first: it already knows which photos are full-body / selfie / portrait /
            // have a person — so we narrow to the right KIND across the whole gallery at zero API cost, and
            // only pay the vision model to confirm identity on a small shortlist.
            val kinds = when {
                Regex("full ?body|whole body|head to toe|standing").containsMatchIn(ql) -> listOf("fullbody", "portrait", "person")
                Regex("selfie").containsMatchIn(ql) -> listOf("selfie", "portrait")
                Regex("portrait|headshot|profile pic|face").containsMatchIn(ql) -> listOf("portrait", "selfie")
                Regex("\\b(me|myself|us|him|her|them|people|person)\\b").containsMatchIn(ql) -> listOf("fullbody", "portrait", "selfie", "person", "group")
                else -> emptyList()   // object/scene search runs on labels only
            }
            val stop = setOf("photo", "photos", "picture", "pictures", "image", "images", "pic", "pics", "send", "the", "and", "for", "via", "with", "full", "body", "find", "get")
            val terms = ql.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }
            val local = if (com.agentos.shell.tools.PhotoIndex.count(ctx) > 0) com.agentos.shell.tools.PhotoIndex.findLocal(ctx, kinds, terms, 30) else emptyList()

            val cands = if (local.isNotEmpty()) local else {
                // Index not built yet → live sweep of recent photos as a fallback.
                val pool = LinkedHashMap<String, FileResolver.Found>()
                FileResolver.find(ctx, query.ifBlank { "photo" })
                    .filter { !ss.containsMatchIn(it.where + it.name) && !it.name.contains(".pdf", true) }
                    .forEach { pool[it.uri.toString()] = it }
                FileResolver.recentPhotos(ctx, 40).filter { !ss.containsMatchIn(it.where + it.name) }
                    .forEach { pool.putIfAbsent(it.uri.toString(), it) }
                pool.values.toList().take(36)
            }
            if (cands.isEmpty()) return "I couldn't find any photos to match “$query”. Make sure photo access is on in Settings."

            // PRIVACY: intimate photos must never be uploaded to a cloud model (which would also just refuse
            // them). Everything upstream is on-device, so for these we match locally and skip the cloud confirm.
            val privateReq = Regex("(?i)nude|naked|nsfw|intimate|lingerie|underwear|\\bprivate\\b|spicy|sexy|explicit|onlyfans").containsMatchIn(ql)
            if (privateReq) {
                val picks = cands.take(count.coerceIn(1, 6))
                if (name.isBlank()) {
                    start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(picks.first().uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "Kept this fully on your device (never sent to any AI) — opening ${picks.first().name}."
                }
                if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find $name."
                val c2 = when (val r = ContactsTool.resolve(ctx, name)) {
                    is ContactsTool.Resolution.Found -> r.contact
                    is ContactsTool.Resolution.Ambiguous -> return "A few people match “$name”: ${r.options.joinToString(", ") { it.name }}. Which one?"
                    ContactsTool.Resolution.None -> return "I couldn't find a contact called “$name”."
                }
                return FileOps.sendToPerson(ctx, picks.map { it.uri }, app.ifBlank { "whatsapp" }, c2.name, toNumber = c2.number, message = message)
                    ?: "Found ${picks.size} — matched privately on-device — but couldn't open the share to ${c2.name}."
            }

            // Exact identity: reuse the Faces feature. If the ask is about "me" or a known person, grab their
            // reference face so the model matches the actual PERSON, not just "a full-body shot of someone".
            val refB64: String? = try {
                val people = com.agentos.shell.tools.PeopleStore.list(ctx)
                val named = people.firstOrNull { it.name.isNotBlank() && ql.contains(it.name.lowercase()) }
                when {
                    Regex("\\b(me|myself|my|i)\\b").containsMatchIn(ql) -> {
                        val hp = com.agentos.shell.tools.MemoryStore.headshotPath(ctx)
                        if (hp.isNotBlank() && java.io.File(hp).exists())
                            com.agentos.shell.tools.ImageUtil.encode(ctx, android.net.Uri.fromFile(java.io.File(hp)), 512) else null
                    }
                    named != null -> com.agentos.shell.tools.PeopleStore.photoB64(ctx, named.id)
                    else -> null
                }
            } catch (e: Exception) { null }

            // LOOK at them, in batches, so it genuinely scans dozens of photos and picks only real matches.
            val chosen = ArrayList<FileResolver.Found>()
            try {
                cands.chunked(12).forEach { batch ->
                    val enc = batch.mapNotNull { f -> com.agentos.shell.tools.ImageUtil.encode(ctx, f.uri, 512)?.let { f to it } }
                    if (enc.isNotEmpty()) {
                        val off = if (refB64 != null) 1 else 0
                        val imgs = (if (refB64 != null) listOf(refB64) else emptyList()) + enc.map { it.second }
                        val prompt = if (refB64 != null)
                            "Image 1 is a REFERENCE photo of the target person. Images 2 to ${imgs.size} are candidates. " +
                            "Reply with ONLY the numbers (each from 2 to ${imgs.size}) of candidates that BOTH match \"$query\" AND clearly show the SAME person as image 1. " +
                            "Best first, comma-separated. Be strict. If none, reply exactly NONE."
                        else
                            "The owner wants photos matching: \"$query\". Below are ${imgs.size} images, numbered 1 to ${imgs.size} in order. " +
                            "Reply with ONLY the numbers that genuinely match, best first, comma-separated. Be strict — exclude screenshots and wrong subjects. If none, reply exactly NONE."
                        val out = AgentClient.askVision(prompt, imgs, "")
                        if (!out.contains("NONE", true))
                            Regex("\\d+").findAll(out).map { it.value.toInt() }.filter { it in (1 + off)..(enc.size + off) }.distinct()
                                .forEach { chosen.add(enc[it - 1 - off].first) }
                    }
                }
            } catch (e: Exception) {}

            if (chosen.isEmpty())
                return "I looked through ${cands.size} of your photos but none clearly matched “$query”, so I didn't send anything random. Want me to widen it, or send the closest ones anyway?"

            val pick = chosen.take(count)
            if (name.isBlank()) {
                start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(pick.first().uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Found ${pick.size} match${if (pick.size == 1) "" else "es"} — opening ${pick.first().name}."
            }
            if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find $name."
            val c = when (val r = ContactsTool.resolve(ctx, name)) {
                is ContactsTool.Resolution.Found -> r.contact
                is ContactsTool.Resolution.Ambiguous ->
                    return "A few people match “$name”: ${r.options.joinToString(", ") { it.name }}. Which one?"
                ContactsTool.Resolution.None -> return "I couldn't find a contact called “$name”. What's their full name?"
            }
            FileOps.sendToPerson(ctx, pick.map { it.uri }, app.ifBlank { "whatsapp" }, c.name, toNumber = c.number, message = message)
                ?: "I found ${pick.size} photo(s) but couldn't open the share to ${c.name}."
        } catch (e: Exception) { "I couldn't send that photo." }
    }

    private fun sendMessage(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val name = o.optString("name")
            val body = o.optString("body")
            val app = o.optString("app").lowercase()
            if (body.isBlank()) return "What should the message say?"
            when {
                app.contains("whatsapp") -> {
                    if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find ${name.ifBlank { "them" }}."
                    val c = when (val r = ContactsTool.resolve(ctx, name)) {
                        is ContactsTool.Resolution.Found -> r.contact
                        is ContactsTool.Resolution.Ambiguous ->
                            return "A few people match “$name”: ${r.options.joinToString(", ") { it.name }}. Which one on WhatsApp? (tell me the full name)"
                        ContactsTool.Resolution.None ->
                            return "I couldn't find a contact called “$name”. What's their full name or number?"
                    }
                    val digits = c.number.filter { it.isDigit() }
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(body))))
                    // P1.3: this only OPENS WhatsApp with a prefilled draft — the user still taps send. Do
                    // NOT record it as a sent message, or the brain trains on things you never actually sent.
                    "Opened WhatsApp to ${c.name} with your message — just tap send."
                }
                app.contains("telegram") -> {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("msg", body))
                    val intent = ctx.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("tg://"))
                    start(ctx, intent)
                    // P1.3: only copies + opens Telegram to draft — not actually sent, so don't log it.
                    "Copied your message and opened Telegram — open ${name.ifBlank { "the chat" }} and paste."
                }
                else -> sendSms(ctx, JSONObject().put("name", name).put("body", body).toString())
            }
        } catch (e: Exception) { "I couldn't send that." }
    }

    /** Send a real email via Gmail, optionally minting + embedding a Google Meet link. */
    private fun sendEmail(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            var to = o.optString("to").trim()
            // "Email Joslyn about dinner" used to be refused with "What's their email address?" every
            // single time, while the brain held her address from thousands of imported messages. Same
            // missing call as the calendar path — one resolver, three callers.
            if (!to.contains("@")) {
                val p = try { PersonResolver.resolve(ctx, to) } catch (e: Exception) { null }
                if (p != null && p.candidates.size > 1 && p.email.isBlank()) {
                    return "Which $to? " + p.candidates.joinToString(", ") + " — tell me which and I'll send it."
                }
                if (p != null && p.email.isNotBlank()) to = p.email
            }
            if (!to.contains("@") || !to.contains(".")) return "What's their email address?"
            if (!GoogleAuth.isConnected(ctx)) return "Connect Google (Gmail) in settings first, then I can send it."
            val subject = o.optString("subject").ifBlank { "(no subject)" }
            var body = o.optString("body")
            if (body.isBlank()) return "What should the email say?"
            // Optional Google Meet: needs a time; create the event + attendee and append the join link.
            if (o.optBoolean("meet", false)) {
                val startMs = parseLocal(o.optString("start"))
                val endMs = parseLocal(o.optString("end"))
                if (startMs > 0 && endMs > 0) {
                    val r = GoogleCalendarClient.createEvent(ctx, subject, startMs, endMs, listOf(to), true)
                    if (r.ok && r.meetLink.isNotBlank()) body += "\n\nJoin Google Meet: ${r.meetLink}"
                }
            }
            // THE THING THE OWNER MEANT BY "IT".
            //
            // ActionChain puts the document made moments ago on this argument; without it, "make a
            // one-pager and email it to Carlos" sent Carlos an email about a document he could not
            // read, and both steps were reported as done.
            val attachUri = o.optString("attach")
            val files = ArrayList<java.io.File>()
            if (attachUri.isNotBlank())
                ActionChain.asFile(ctx, attachUri, o.optString("attach_name").ifBlank { "attachment" })
                    ?.let { files.add(it) }
            // SEVERAL FILES, BECAUSE PEOPLE SEND SEVERAL FILES.
            //
            // One was enough for the chained case ("make a one-pager and email it"), and wrong for
            // the human one — nobody attaches the slides without the agenda. Picked straight off the
            // phone, so `uri` is a content:// from the system picker rather than anything of ours.
            o.optJSONArray("attachments")?.let { arr ->
                (0 until arr.length()).forEach { i ->
                    arr.optJSONObject(i)?.let { a ->
                        ActionChain.asFile(ctx, a.optString("uri"),
                            a.optString("name").ifBlank { "attachment" })?.let { files.add(it) }
                    }
                }
            }

            val (ok, msg) = if (files.isNotEmpty())
                GmailClient.sendWithAttachments(ctx, to, subject, body, files)
            else GmailClient.send(ctx, to, subject, body)
            val attached = files.firstOrNull()
            if (ok) {
                MemoryLog.add(ctx, "response", "Email: $subject", "Sent to $to — $subject", "Email")
                // Name the attachment. "Sent ✓" for an email whose whole point was the file tells
                // the owner nothing about whether the file went with it.
                when {
                    files.size == 1 -> "Sent to $to with “${attached?.name}” attached ✓"
                    files.size > 1 -> "Sent to $to with ${files.size} files attached ✓"
                    else -> "Sent to $to ✓"
                }
            } else "Couldn't send the email — $msg"
        } catch (e: Exception) { Log.e("SlyOS", "sendEmail failed", e); "I couldn't send that email." }
    }

    /** Create a real Google Doc from drafted content. */
    private fun createDoc(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title").ifBlank { "Untitled" }
            val body = o.optString("content")
            if (!GoogleAuth.isConnected(ctx)) return "Connect Google in settings first, then I can create the doc."
            val r = GoogleWorkspace.createDoc(ctx, title, body)
            if (r.ok) { MemoryLog.add(ctx, "response", "Doc: $title", "Created Google Doc: $title", "Docs"); "Created Google Doc “$title” — ${r.url}" }
            else "Couldn't create the doc — ${r.error}"
        } catch (e: Exception) { "I couldn't create that doc." }
    }

    /** Create a real Google Sheet from rows: {"title":"…","rows":[["A","B"],["1","2"]]}. */
    private fun createSheet(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title").ifBlank { "Sheet" }
            val rows = ArrayList<List<String>>()
            o.optJSONArray("rows")?.let { rr ->
                for (i in 0 until rr.length()) {
                    val row = ArrayList<String>(); val a = rr.optJSONArray(i)
                    if (a != null) for (j in 0 until a.length()) row.add(a.optString(j))
                    rows.add(row)
                }
            }
            if (!GoogleAuth.isConnected(ctx)) return "Connect Google in settings first, then I can create the sheet."
            val r = GoogleWorkspace.createSheet(ctx, title, rows)
            if (r.ok) { MemoryLog.add(ctx, "response", "Sheet: $title", "Created Google Sheet: $title", "Sheets"); "Created Google Sheet “$title” — ${r.url}" }
            else "Couldn't create the sheet — ${r.error}"
        } catch (e: Exception) { "I couldn't create that sheet." }
    }

    /** Create a real Google Slides deck: {"title":"…","slides":[{"title":"…","body":"…"}]}. */
    private fun createSlides(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title").ifBlank { "Deck" }
            val slides = ArrayList<Pair<String, String>>()
            o.optJSONArray("slides")?.let { arr ->
                for (i in 0 until arr.length()) { val s = arr.optJSONObject(i) ?: continue; slides.add(s.optString("title") to s.optString("body")) }
            }
            if (slides.isEmpty()) return "What should the slides cover?"
            if (!GoogleAuth.isConnected(ctx)) return "Connect Google in settings first, then I can build the deck."
            val r = GoogleWorkspace.createSlides(ctx, title, slides)
            if (r.ok) { MemoryLog.add(ctx, "response", "Slides: $title", "Created Google Slides: $title", "Slides"); "Created Google Slides “$title” — ${r.url}" }
            else "Couldn't create the deck — ${r.error}"
        } catch (e: Exception) { "I couldn't create that deck." }
    }

    /** Create a real PDF from drafted content, save to Downloads/SlyOS, and open it. */
    private fun createPdf(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val title = o.optString("title").ifBlank { "Document" }
            val content = o.optString("content")
            if (content.isBlank()) return "What should the PDF contain?"
            val uri = PdfBuilder.makePdf(ctx, title, content) ?: return "Couldn't create the PDF."
            try {
                start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {}
            MemoryLog.add(ctx, "response", "PDF: $title", "Created PDF: $title", "PDF")
            "Created PDF “$title” — saved to Downloads/SlyOS and opening it."
        } catch (e: Exception) { "I couldn't create that PDF." }
    }

    /** Open a website in a real browser — never Maps. Prefers Chrome so a bare domain isn't hijacked. */
    private fun openUrl(ctx: Context, arg: String): String {
        var u = arg.trim()
        if (u.isBlank()) return "What site should I open?"
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = "https://$u"
        return try {
            val base = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chrome = Intent(base).setPackage("com.android.chrome")
            if (ctx.packageManager.resolveActivity(chrome, 0) != null) start(ctx, chrome) else start(ctx, base)
            "Opening $arg"
        } catch (e: Exception) { "Couldn't open $arg." }
    }

    /** Open Google Maps directions to a destination, optionally with a waypoint/stop + travel mode. */
    private fun navigate(ctx: Context, arg: String): String {
        return try {
            val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("destination", arg) }
            val dest = listOf(o.optString("destination"), o.optString("to"), arg).firstOrNull { it.isNotBlank() } ?: ""
            if (dest.isBlank()) return "Where do you want to go?"
            val stop = listOf(o.optString("stop"), o.optString("waypoint"), o.optString("via")).firstOrNull { it.isNotBlank() } ?: ""
            val mode = o.optString("mode").ifBlank { "driving" }
            val url = StringBuilder("https://www.google.com/maps/dir/?api=1&destination=")
                .append(Uri.encode(dest)).append("&travelmode=").append(Uri.encode(mode))
            if (stop.isNotBlank()) url.append("&waypoints=").append(Uri.encode(stop))
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
            "Opening Maps to $dest" + (if (stop.isNotBlank()) " via $stop" else "") + "."
        } catch (e: Exception) { "I couldn't open navigation." }
    }

    /**
     * "Share my location with <person> [on whatsapp/sms/telegram] [until I'm home]."
     * arg = {"name":"Mom","channel":"whatsapp|sms|telegram","home":"<addr, opt>","navigate":bool}.
     *
     * Default is a GENERAL live share (no home) — SMS/Telegram send a fresh Maps link on an interval; WhatsApp
     * opens a chat pre-filled with your current location (one tap to send, since WhatsApp can't auto-resend).
     * The home geofence + navigation only engage when the user explicitly asks to share "until I'm home" /
     * be navigated home. Nothing defaults to home anymore.
     */
    private fun shareLocation(ctx: Context, arg: String): String {
        return try {
            val o = try { JSONObject(arg) } catch (e: Exception) { JSONObject().put("name", arg) }
            val name = listOf(o.optString("name"), o.optString("to"), o.optString("contact")).firstOrNull { it.isNotBlank() } ?: ""
            var channel = o.optString("channel").lowercase().trim()
            val navHome = o.optBoolean("navigate", false) || o.optBoolean("navigate_home", false)

            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return "Turn on Location access so I can share where you are."

            // ── WhatsApp: open a chat pre-filled with a live Maps link (can't auto-send on a loop) ──
            if (channel == "whatsapp") {
                if (name.isBlank()) return "Who should I share your location with on WhatsApp?"
                if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find $name."
                val c = when (val r = ContactsTool.resolve(ctx, name)) {
                    is ContactsTool.Resolution.Found -> r.contact
                    is ContactsTool.Resolution.Ambiguous ->
                        return "A few people match “$name”: ${r.options.joinToString(", ") { it.name }}. Which one on WhatsApp?"
                    ContactsTool.Resolution.None ->
                        return "I couldn't find a contact called “$name”. What's their full name or number?"
                }
                val loc = lastKnownLocation(ctx)
                    ?: return "I couldn't get a GPS fix yet — open Maps once so the phone has a location, then try again."
                val link = "https://maps.google.com/?q=%.5f,%.5f".format(loc.latitude, loc.longitude)
                val digits = c.number.filter { it.isDigit() }
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode("📍 My location: $link"))))
                if (navHome) startNavHome(ctx, o)
                return "Opened WhatsApp to share your location with ${c.name} — just tap send. (WhatsApp can't auto-resend on a loop; for continuous updates use WhatsApp's own Live Location, or ask me to share via SMS.)"
            }

            // ── SMS / Telegram: a real interval-based live share ──
            var number = ""
            var toName = name.ifBlank { "them" }
            if (channel != "telegram") {
                channel = "sms"
                if (name.isBlank()) return "Who should I share your location with?"
                if (!ContactsTool.canRead(ctx)) return "Turn on Contacts access so I can find $toName."
                val contact = when (val r = ContactsTool.resolve(ctx, name)) {
                    is ContactsTool.Resolution.Found -> r.contact
                    is ContactsTool.Resolution.Ambiguous ->
                        return "A few people match “$name”: ${r.options.joinToString(", ") { it.name }}. Which one?"
                    ContactsTool.Resolution.None ->
                        return "I couldn't find a contact called “$name”. What's their full name or number?"
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                    return "Turn on SMS permission so I can text $toName your location."
                number = contact.number; toName = contact.name
            }

            // Home geofence + navigation ONLY when explicitly requested — never by default.
            var hlat = 0.0; var hlng = 0.0; var homeLabel = ""
            if (navHome) {
                homeLabel = homeLabelFor(ctx, o)
                if (homeLabel.isNotBlank()) {
                    try {
                        val geo = android.location.Geocoder(ctx).getFromLocationName(homeLabel, 1)
                        if (!geo.isNullOrEmpty()) { hlat = geo[0].latitude; hlng = geo[0].longitude }
                    } catch (e: Exception) { Log.w("SlyOS", "geocode failed", e) }
                }
            }
            com.agentos.shell.LiveLocationService.start(ctx, toName, number, channel, hlat, hlng, homeLabel, navHome)
            val chLabel = if (channel == "telegram") "Telegram" else "SMS"
            if (navHome) "Sharing your live location with $toName over $chLabel until you're home, and navigating you there."
            else "Sharing your live location with $toName over $chLabel. Say “stop sharing my location” when you're done."
        } catch (e: Exception) { Log.e("SlyOS", "shareLocation", e); "I couldn't start location sharing." }
    }

    private fun homeLabelFor(ctx: Context, o: JSONObject): String =
        listOf(o.optString("home"), o.optString("destination"), MemoryStore.profileAddress(ctx)).firstOrNull { it.isNotBlank() } ?: ""

    /** Best-effort current fix without waiting; null if we've never had one. */
    private fun lastKnownLocation(ctx: Context): android.location.Location? = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        lm?.let {
            it.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: it.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        }
    } catch (e: SecurityException) { null } catch (e: Exception) { null }

    /** Launch turn-by-turn navigation to the user's home (or an address in the arg). */
    private fun startNavHome(ctx: Context, o: JSONObject) {
        val dest = homeLabelFor(ctx, o)
        if (dest.isBlank()) return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=d"))
                .setPackage("com.google.android.apps.maps")
            if (i.resolveActivity(ctx.packageManager) != null) start(ctx, i)
            else start(ctx, Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest) + "&travelmode=driving")))
        } catch (e: Exception) { Log.e("SlyOS", "navHome", e) }
    }

    /** Open Spotify to play/find a song or artist (app if installed, else web). */
    private fun playMusic(ctx: Context, arg: String): String {
        return try {
            val query = (try { JSONObject(arg).optString("query") } catch (e: Exception) { "" }).ifBlank { arg }.trim()
            if (query.isBlank()) return "What should I play?"
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage("com.spotify.music")
            if (ctx.packageManager.resolveActivity(appIntent, 0) != null) start(ctx, appIntent)
            else start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))))
            "Opening Spotify for “$query” — tap play."
        } catch (e: Exception) { "I couldn't open Spotify." }
    }

    private fun parseLocal(s: String): Long = try {
        java.time.LocalDateTime.parse(s)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) { 0L }

    /** Route a free-typed command. Returns a short human-readable result. */
    fun handle(ctx: Context, raw: String): String {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return ""
        val low = cmd.lowercase()
        return try {
            when {
                low == "phone" || low.startsWith("call") ->
                    { start(ctx, Intent(Intent.ACTION_DIAL)); "Opening phone" }

                low.startsWith("text") || low.startsWith("message") || low.startsWith("sms") ->
                    { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))); "Opening messages" }

                low.contains("camera") || low.startsWith("photo") || low.startsWith("selfie") ->
                    { start(ctx, Intent(MediaStore.ACTION_IMAGE_CAPTURE)); "Opening camera" }

                low.startsWith("settings") ->
                    { start(ctx, Intent(Settings.ACTION_SETTINGS)); "Opening settings" }

                low.startsWith("http://") || low.startsWith("https://") ->
                    { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(cmd))); "Opening browser" }

                // A bare domain (slyos.world, nytimes.com, openai.com…) → browser, never Maps.
                Regex("^[\\w-]+(?:\\.[\\w-]+)+$").matches(low) -> openUrl(ctx, cmd)

                low.startsWith("search ") || low.startsWith("google ") -> {
                    val q = cmd.substringAfter(' '); webSearch(ctx, q); "Searching: $q"
                }

                low.startsWith("open ") -> {
                    val name = cmd.substringAfter(' ').trim()
                    val app = installedApps(ctx).firstOrNull { it.label.lowercase().contains(name.lowercase()) }
                    if (app != null) { launchApp(ctx, app.pkg); "Opening ${app.label}" }
                    else { webSearch(ctx, name); "No app matched \"$name\" — searched the web" }
                }

                else -> {
                    val app = installedApps(ctx).firstOrNull {
                        it.label.lowercase() == low || it.label.lowercase().contains(low)
                    }
                    if (app != null) { launchApp(ctx, app.pkg); "Opening ${app.label}" }
                    else { webSearch(ctx, cmd); "Searching: $cmd" }
                }
            }
        } catch (e: Exception) {
            "Couldn't do that (${e.message})"
        }
    }
}
