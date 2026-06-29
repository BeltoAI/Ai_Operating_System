package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates real Google Calendar events — with a Google Meet link and invited attendees — using the
 * Calendar API and the user's own OAuth token. This is the piece a notification listener or deep
 * link can't do: a genuine Meet video link plus emailed invites.
 */
object GoogleCalendarClient {
    private const val TAG = "SlyOS"

    data class Result(val ok: Boolean, val meetLink: String = "", val htmlLink: String = "", val error: String = "")

    private fun rfc3339(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return f.format(Date(ms))
    }

    /**
     * Insert an event on the user's primary calendar. If [withMeet], attaches a Google Meet
     * conference. [attendees] may be names or emails; only valid emails get invited.
     */
    fun createEvent(ctx: Context, title: String, startMs: Long, endMs: Long,
                    attendees: List<String>, withMeet: Boolean): Result {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return Result(false, error = "not-connected")
        val tz = TimeZone.getDefault().id
        val body = JSONObject().apply {
            put("summary", title)
            put("start", JSONObject().put("dateTime", rfc3339(startMs)).put("timeZone", tz))
            put("end", JSONObject().put("dateTime", rfc3339(endMs)).put("timeZone", tz))
            val emails = attendees.map { it.trim() }.filter { it.contains("@") && it.contains(".") }
            if (emails.isNotEmpty()) {
                val arr = JSONArray()
                emails.forEach { arr.put(JSONObject().put("email", it)) }
                put("attendees", arr)
            }
            if (withMeet) {
                put("conferenceData", JSONObject().put("createRequest", JSONObject()
                    .put("requestId", "slyos-" + System.currentTimeMillis())
                    .put("conferenceSolutionKey", JSONObject().put("type", "hangoutsMeet"))))
            }
        }
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
            "?conferenceDataVersion=1&sendUpdates=all"
        val (code, resp) = post(url, token, body.toString())
        if (code !in 200..299) {
            Log.e(TAG, "calendar insert $code: $resp")
            return Result(false, error = "api-$code")
        }
        return try {
            val o = JSONObject(resp)
            var meet = o.optString("hangoutLink")
            if (meet.isBlank()) {
                o.optJSONObject("conferenceData")?.optJSONArray("entryPoints")?.let { eps ->
                    for (i in 0 until eps.length()) {
                        val ep = eps.getJSONObject(i)
                        if (ep.optString("entryPointType") == "video") { meet = ep.optString("uri"); break }
                    }
                }
            }
            Result(true, meetLink = meet, htmlLink = o.optString("htmlLink"))
        } catch (e: Exception) { Result(true) }
    }

    private fun post(endpoint: String, token: String, json: String): Pair<Int, String> {
        return try {
            val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 20000; readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) { Log.e(TAG, "calendar post failed", e); 0 to (e.message ?: "network error") }
    }
}
