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

/** Reads upcoming calendar events (local, READ_CALENDAR) so the agent knows your schedule. */
object CalendarTool {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Create an event. Returns true on success. */
    fun addEvent(ctx: Context, title: String, startMs: Long, endMs: Long): Boolean {
        if (!canWrite(ctx)) { android.util.Log.w("SlyOS", "addEvent: no WRITE_CALENDAR"); return false }
        val calId = writableCalendarId(ctx)
        android.util.Log.i("SlyOS", "addEvent: calId=$calId")
        if (calId == null) return false
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        } catch (e: Exception) { false }
    }

    private fun writableCalendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        return try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)
                ?.use { c ->
                    var fallback: Long? = null
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val access = c.getInt(1)
                        val primary = c.getInt(2)
                        if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                            if (primary == 1) return id
                            if (fallback == null) fallback = id
                        }
                    }
                    fallback
                }
        } catch (e: Exception) { null }
    }

    /** Next few events over the coming 2 days, as plain text. Empty if no permission/events. */
    fun upcoming(ctx: Context): String {
        if (!hasPermission(ctx)) return ""
        return try {
            val now = System.currentTimeMillis()
            val end = now + 1000L * 60 * 60 * 24 * 2
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, end)
                it.build()
            }
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN
            )
            val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
            val sb = StringBuilder()
            ctx.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 6) {
                    val title = c.getString(0) ?: "(busy)"
                    val begin = c.getLong(1)
                    sb.append("- ${fmt.format(Date(begin))}: $title\n")
                    n++
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }
}
