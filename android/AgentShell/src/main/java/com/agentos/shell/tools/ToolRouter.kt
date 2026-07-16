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
                "torch", "flashlight" -> {
                    val a = arg.trim().lowercase()
                    val want: Boolean? = when {
                        Regex("off|out|kill|stop|disable|0|false").containsMatchIn(a) -> false
                        Regex("on|light|enable|1|true").containsMatchIn(a) -> true
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
                "add_event" -> addEvent(ctx, arg)
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
        "settings", "send_sms", "message", "send_photo", "send_email", "add_event", "share_location",
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
        try { MessageStore.insertOne(ctx, "Timers", "Timer", "me", "me", "Timer set for ${secs / 60} min") } catch (e: Exception) {}
        return "Timer set for ${secs / 60} min."
    }

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
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        val pretty = prettyTime(h, m)
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
