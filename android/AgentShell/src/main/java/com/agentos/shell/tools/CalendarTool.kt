package com.agentos.shell.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reads + writes the phone calendar so the agent knows your schedule and can block time / invite people. */
object CalendarTool {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private data class Cal(val id: Long, val account: String, val type: String)

    /**
     * Create an event in a calendar that actually SHOWS UP — preferring a synced Google calendar so it
     * appears in Google Calendar, not a phone-local one. Optionally invites [attendees] (emails); on a
     * Google calendar, Google emails them the invite on sync. Returns a human-readable result.
     */
    fun addEvent(ctx: Context, title: String, startMs: Long, endMs: Long, attendees: List<String> = emptyList()): String {
        if (!canWrite(ctx)) return "ERR_PERM"
        val cal = bestCalendar(ctx) ?: return "ERR_NOCAL"
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, cal.id)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (attendees.isNotEmpty()) {
                    put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
                    put(CalendarContract.Events.ORGANIZER, cal.account)
                    put(CalendarContract.Events.GUESTS_CAN_MODIFY, 0)
                }
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return "ERR_INSERT"
            val eventId = ContentUris.parseId(uri)
            // Invite the guests (Google mails them on sync when this is a Google calendar).
            for (email in attendees.map { it.trim() }.filter { it.contains("@") }) {
                val av = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, eventId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                }
                try { ctx.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, av) } catch (e: Exception) {}
            }
            val where = if (cal.type.contains("google", true)) "Google Calendar (${cal.account})" else cal.account
            "OK::$where"
        } catch (e: Exception) { "ERR_INSERT" }
    }

    /** Pick the calendar most likely to be visible + synced: Google primary > Google > visible writable > any writable. */
    private fun bestCalendar(ctx: Context): Cal? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        return try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                var googlePrimary: Cal? = null; var google: Cal? = null; var visibleWritable: Cal? = null; var anyWritable: Cal? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0); val access = c.getInt(1); val primary = c.getInt(2)
                    val acct = c.getString(3) ?: ""; val type = c.getString(4) ?: ""
                    val visible = c.getInt(5); val sync = c.getInt(6)
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val cal = Cal(id, acct, type)
                    val isGoogle = type.contains("google", true)
                    if (isGoogle && primary == 1 && googlePrimary == null) googlePrimary = cal
                    if (isGoogle && sync == 1 && google == null) google = cal
                    if (visible == 1 && visibleWritable == null) visibleWritable = cal
                    if (anyWritable == null) anyWritable = cal
                }
                googlePrimary ?: google ?: visibleWritable ?: anyWritable
            }
        } catch (e: Exception) { null }
    }

    /** Is anything scheduled that overlaps [startMs, endMs]? For precise "am I free then?" answers. */
    fun busyTitlesBetween(ctx: Context, startMs: Long, endMs: Long): List<String> {
        if (!hasPermission(ctx)) return emptyList()
        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, startMs); ContentUris.appendId(it, endMs); it.build()
            }
            val out = ArrayList<String>()
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE), null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(c.getString(0) ?: "(busy)")
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    /** Your schedule over the next month, as plain text — so the agent knows when you're blocked. */
    fun upcoming(ctx: Context): String {
        if (!hasPermission(ctx)) return ""
        return try {
            val now = System.currentTimeMillis()
            val end = now + 1000L * 60 * 60 * 24 * 30
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, now); ContentUris.appendId(it, end); it.build()
            }
            val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
            val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
            val sb = StringBuilder()
            ctx.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 30) {
                    val title = c.getString(0) ?: "(busy)"; val begin = c.getLong(1)
                    sb.append("- ${fmt.format(Date(begin))}: $title\n"); n++
                }
            }
            sb.toString().trim()
        } catch (e: Exception) { "" }
    }

    /**
     * Ingest EVERY event — years of past appointments and everything scheduled ahead — into the brain's
     * searchable message store, so the agent can recall any appointment ever made. Incremental: each run
     * only writes events it hasn't stored before (tracked by event-instance key), so new future events
     * get picked up automatically on later runs. Safe to call on every app start (off the main thread).
     */
    fun syncAllToBrain(ctx: Context) {
        if (!hasPermission(ctx)) return
        try {
            val now = System.currentTimeMillis()
            val span = 1000L * 60 * 60 * 24 * 365 * 3   // ±3 years
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, now - span); ContentUris.appendId(it, now + span); it.build()
            }
            val proj = arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION
            )
            val fmt = SimpleDateFormat("EEE MMM d yyyy, h:mm a", Locale.getDefault())
            val prefs = ctx.getSharedPreferences("slyos_cal_sync", Context.MODE_PRIVATE)
            val seen = HashSet(prefs.getStringSet("keys", emptySet()) ?: emptySet())
            val added = ArrayList<String>()
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 3000) {
                    val eid = c.getLong(0); val title = c.getString(1) ?: "(busy)"
                    val begin = c.getLong(2); val loc = c.getString(3)
                    val key = "$eid|$begin"
                    if (seen.contains(key)) continue
                    val rel = if (begin < now) "past" else "upcoming"
                    val text = "[$rel] ${fmt.format(Date(begin))} — $title" + (if (!loc.isNullOrBlank()) " @ $loc" else "")
                    MessageStore.insertOne(ctx, "Calendar", "Calendar", "me", "me", text)
                    seen.add(key); added.add(key); n++
                }
            }
            if (added.isNotEmpty()) {
                // Keep the dedup set bounded.
                val capped = if (seen.size > 8000) seen.toList().takeLast(8000).toSet() else seen
                prefs.edit().putStringSet("keys", capped).apply()
                android.util.Log.i("SlyOS", "calendar synced ${added.size} new events to brain")
            }
        } catch (e: Exception) { android.util.Log.e("SlyOS", "calendar sync failed", e) }
    }
}
