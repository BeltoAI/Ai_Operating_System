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
                "timer" -> setTimer(ctx, arg)
                "alarm" -> setAlarm(ctx, arg)
                "checklist_add" -> { ChecklistStore.add(ctx, arg); "Added to checklist: \"$arg\"" }
                "pin_app" -> {
                    val app = installedApps(ctx).firstOrNull { it.label.lowercase().contains(arg.lowercase()) }
                    if (app != null) { ShortcutStore.add(ctx, "app", app.label, app.pkg); "Pinned ${app.label} to Home." }
                    else "No app named \"$arg\"."
                }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    /** Run an ordered list of actions, recording metrics; returns combined feedback. */
    fun executeActions(ctx: Context, actions: List<AgentAction>): String {
        Log.i("SlyOS", "actions(${actions.size}): " + actions.joinToString { "${it.type}=${it.arg}" })
        val msgs = mutableListOf<String>()
        for (a in actions) {
            if (a.type.isBlank() || a.type == "none") continue
            val m = executeAction(ctx, a.type, a.arg)
            MetricsStore.record(ctx, MetricsStore.secondsFor(a.type))
            if (m.isNotEmpty()) msgs.add(m)
        }
        return msgs.joinToString("  ")
    }

    private fun sendSms(ctx: Context, arg: String): String {
        return try {
            val o = JSONObject(arg)
            val name = o.optString("name")
            val body = o.optString("body")
            if (!ContactsTool.canRead(ctx)) return "Contacts access is off."
            val contact = ContactsTool.findContact(ctx, name) ?: return "No contact found for \"$name\"."
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
            if (CalendarTool.canWrite(ctx)) {
                val r = CalendarTool.addEvent(ctx, title, startMs, endMs, attendees)
                if (r.startsWith("OK::")) {
                    val where = r.removePrefix("OK::")
                    // Feed the brain so the agent knows about the block when it answers later.
                    MemoryLog.add(ctx, "response", "Calendar: $title", "Blocked “$title” in $where (${o.optString("start")}–${o.optString("end")})", "Calendar")
                    MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me",
                        "Blocked: $title · ${o.optString("start")} to ${o.optString("end")}" + (if (attendees.isNotEmpty()) " · with ${attendees.joinToString(", ")}" else ""))
                    val who = if (attendees.isNotEmpty()) " and invited ${attendees.joinToString(", ")}" else ""
                    return "Added “$title” to your $where$who."
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
                    val c = ContactsTool.findContact(ctx, name) ?: return "No contact found for \"$name\"."
                    val digits = c.number.filter { it.isDigit() }
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(body))))
                    MessageStore.insertOne(ctx, c.name, "WhatsApp", c.name, "me", body)
                    ConversationStore.add(ctx, "WhatsApp", c.name, "me", body)
                    "Opened WhatsApp to ${c.name} with your message — just tap send."
                }
                app.contains("telegram") -> {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("msg", body))
                    val intent = ctx.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("tg://"))
                    start(ctx, intent)
                    if (name.isNotBlank()) { MessageStore.insertOne(ctx, name, "Telegram", name, "me", body); ConversationStore.add(ctx, "Telegram", name, "me", body) }
                    "Copied your message and opened Telegram — open ${name.ifBlank { "the chat" }} and paste."
                }
                else -> sendSms(ctx, JSONObject().put("name", name).put("body", body).toString())
            }
        } catch (e: Exception) { "I couldn't send that." }
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

                low.endsWith(".com") || low.endsWith(".org") || low.endsWith(".net") ->
                    { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://$cmd"))); "Opening $cmd" }

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
