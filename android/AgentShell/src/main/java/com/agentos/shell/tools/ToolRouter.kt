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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
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
                "add_event" -> addEvent(ctx, arg)
                "send_sms" -> sendSms(ctx, arg)
                "message" -> sendMessage(ctx, arg)
                "navigate" -> navigate(ctx, arg)
                "send_email" -> sendEmail(ctx, arg)
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
        "settings", "send_sms", "message", "send_email", "add_event",
        "create_doc", "create_sheet", "create_slides", "create_pdf", "trade"
    )

    /**
     * Run an ordered list of actions, recording metrics; returns combined feedback. Every entry point
     * routes through here, so the [userInitiated] gate is enforced in ONE place. Autonomous/worker/bot
     * callers MUST pass userInitiated=false so gated actions are skipped instead of auto-executed.
     */
    fun executeActions(ctx: Context, actions: List<AgentAction>, userInitiated: Boolean = true): String {
        Log.i("SlyOS", "actions(${actions.size}, user=$userInitiated): " + actions.joinToString { "${it.type}=${it.arg}" })
        val msgs = mutableListOf<String>()
        for (a in actions) {
            if (a.type.isBlank() || a.type == "none") continue
            if (!userInitiated && a.type in GATED) {   // code-level gate: never auto-fire these unattended
                Log.w("SlyOS", "action gated (non-user-initiated): ${a.type}")
                continue
            }
            val m = executeAction(ctx, a.type, a.arg)
            MetricsStore.record(ctx, MetricsStore.secondsFor(a.type))
            if (m.isNotEmpty()) msgs.add(m)
        }
        return msgs.joinToString("  ")
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
        start(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return "Timer set for ${secs / 60} min."
    }

    private fun setAlarm(ctx: Context, arg: String): String {
        val parts = arg.trim().split(":")
        val h = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: return "What time?"
        val m = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return "Alarm set for %02d:%02d".format(h, m)
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

    private fun parseDuration(s: String): Int {
        val t = s.trim().lowercase()
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
            val attendees = ArrayList<String>()
            o.optJSONArray("attendees")?.let { for (i in 0 until it.length()) attendees.add(it.optString(i)) }
            val wantsMeet = o.optBoolean("meet", false) || o.optString("location").contains("meet", true)
            val hasEmails = attendees.any { it.contains("@") && it.contains(".") }

            // Real Google path: if connected, create the event via the Calendar API so we get an actual
            // Google Meet link and email invites — something CalendarContract simply can't do.
            if (GoogleAuth.isConnected(ctx) && (wantsMeet || hasEmails)) {
                val r = GoogleCalendarClient.createEvent(ctx, title, startMs, endMs, attendees, wantsMeet)
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
                    MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                        "Blocked: $title · ${o.optString("start")} to ${o.optString("end")}" + (if (attendees.isNotEmpty()) " · with ${attendees.joinToString(", ")}" else ""))
                    val who = if (attendees.isNotEmpty()) " and invited ${attendees.joinToString(", ")}" else ""
                    val meetHint = if (wantsMeet && !GoogleAuth.isConnected(ctx))
                        " (Connect Google in settings and I'll add a real Meet link + email the invite.)" else ""
                    return "Added “$title” to your $where$who.$meetHint"
                }
            }
            // Fallback: open the calendar's new-event screen pre-filled (always works).
            start(ctx, Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                .putExtra(CalendarContract.Events.TITLE, title))
            "Opened your calendar to confirm “$title” — I couldn't write it directly (no synced calendar found)."
        } catch (e: Exception) {
            Log.e("SlyOS", "addEvent failed", e); "I couldn't read those times."
        }
    }

    /** Send/draft a message on a SPECIFIC app. SMS sends directly; WhatsApp opens pre-filled (one tap);
     *  Telegram copies + opens (paste). All are recorded to the brain. */
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
            val to = o.optString("to").trim()
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
            val (ok, msg) = GmailClient.send(ctx, to, subject, body)
            if (ok) {
                MemoryLog.add(ctx, "response", "Email: $subject", "Sent to $to — $subject", "Email")
                "Sent to $to ✓"
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
