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
    /**
     * @param location where it is. WAS NOT SENT AT ALL — the room, the address and the agenda were
     *   collected, shown back to the owner, and then dropped on the floor at the point of creation.
     *   A guest opening that invitation saw a title and a time and had no idea where to go.
     * @param description the agenda, which is the reason people accept an invitation.
     * @param recurrence an RFC-5545 RRULE for a repeating event ("every Monday"), or null.
     * @param timeZone the zone the times are IN. Defaults to this phone's, which is right until
     *   someone says "3pm their time" and means a different hour entirely.
     */
    fun createEvent(ctx: Context, title: String, startMs: Long, endMs: Long,
                    attendees: List<String>, withMeet: Boolean,
                    location: String = "", description: String = "",
                    recurrence: String? = null, timeZone: String? = null): Result {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return Result(false, error = "not-connected")
        val tz = timeZone ?: TimeZone.getDefault().id
        val body = JSONObject().apply {
            put("summary", title)
            put("start", JSONObject().put("dateTime", rfc3339(startMs)).put("timeZone", tz))
            put("end", JSONObject().put("dateTime", rfc3339(endMs)).put("timeZone", tz))
            if (location.isNotBlank()) put("location", location)
            if (description.isNotBlank()) put("description", description)
            recurrence?.takeIf { it.isNotBlank() }?.let { put("recurrence", JSONArray().put(it)) }
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

    /** One invitee as Google actually sees them: the address that was invited and whether they replied. */
    data class Attendee(val email: String, val responseStatus: String, val organizer: Boolean = false)

    /** An event read back FROM Google — the only trustworthy answer to "did that actually happen?". */
    data class EventInfo(
        val ok: Boolean, val id: String = "", val title: String = "",
        val startIso: String = "", val endIso: String = "",
        val meetLink: String = "", val htmlLink: String = "",
        val attendees: List<Attendee> = emptyList(), val error: String = "")

    /**
     * READ EVENTS BACK. This is the piece whose absence let the app lie.
     *
     * The client could only create; nothing could look at a calendar again. So "was the invite sent to
     * Joslyn?" had no way to check and was answered from chat history instead — a confident yes for an
     * invite she never received. An event's attendee list and each attendee's responseStatus are facts
     * that live at Google; they should never be inferred from anything the app remembers saying.
     *
     * Searches by title within a time window (upcoming by default) because that is how the owner refers
     * to events — "the date night invite", not an opaque event id.
     */
    fun findEvents(ctx: Context, titleContains: String = "", fromMs: Long = System.currentTimeMillis(),
                   toMs: Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000,
                   max: Int = 20): List<EventInfo> {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return emptyList()
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
            "?timeMin=" + java.net.URLEncoder.encode(rfc3339(fromMs), "UTF-8") +
            "&timeMax=" + java.net.URLEncoder.encode(rfc3339(toMs), "UTF-8") +
            "&singleEvents=true&orderBy=startTime&maxResults=$max" +
            (if (titleContains.isNotBlank()) "&q=" + java.net.URLEncoder.encode(titleContains, "UTF-8") else "")
        val (code, resp) = request("GET", url, token, null)
        if (code !in 200..299) { Log.w(TAG, "calendar list failed $code: ${resp.take(200)}"); return emptyList() }
        return try {
            val items = JSONObject(resp).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).map { parseEvent(items.getJSONObject(it)) }
        } catch (e: Exception) { Log.w(TAG, "calendar list parse: ${e.message}"); emptyList() }
    }

    /** One event by id, straight from Google. */
    fun getEvent(ctx: Context, eventId: String): EventInfo {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return EventInfo(false, error = "not-connected")
        val (code, resp) = request("GET",
            "https://www.googleapis.com/calendar/v3/calendars/primary/events/$eventId", token, null)
        if (code !in 200..299) return EventInfo(false, error = "$code: ${resp.take(160)}")
        return try { parseEvent(JSONObject(resp)) } catch (e: Exception) { EventInfo(false, error = e.message ?: "parse") }
    }

    /**
     * Change an existing event and TELL THE PEOPLE ON IT.
     *
     * `sendUpdates=all` is not optional politeness — a time change nobody is told about is worse than no
     * change at all, because everyone still believes the old time. Adding a Meet link to an event that
     * lacks one goes through here too (conferenceDataVersion=1), as does adding or removing an invitee.
     * Only the fields passed are touched; everything else on the event is left alone.
     */
    fun patchEvent(ctx: Context, eventId: String, title: String? = null,
                   startMs: Long? = null, endMs: Long? = null,
                   addAttendees: List<String> = emptyList(), addMeet: Boolean = false,
                   description: String? = null, notify: Boolean = true): EventInfo {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return EventInfo(false, error = "not-connected")
        // Adding attendees must MERGE with whoever is already invited — Google replaces the whole array,
        // so patching with only the new person silently uninvites everyone already on the event.
        val existing = if (addAttendees.isNotEmpty()) getEvent(ctx, eventId) else EventInfo(true)
        val body = JSONObject()
        val tz = TimeZone.getDefault().id
        title?.let { body.put("summary", it) }
        description?.let { body.put("description", it) }
        startMs?.let { body.put("start", JSONObject().put("dateTime", rfc3339(it)).put("timeZone", tz)) }
        endMs?.let { body.put("end", JSONObject().put("dateTime", rfc3339(it)).put("timeZone", tz)) }
        if (addAttendees.isNotEmpty()) {
            val emails = LinkedHashSet<String>()
            existing.attendees.forEach { emails.add(it.email) }
            addAttendees.map { it.trim() }.filter { it.contains("@") && it.contains(".") }.forEach { emails.add(it) }
            val arr = JSONArray(); emails.forEach { arr.put(JSONObject().put("email", it)) }
            body.put("attendees", arr)
        }
        if (addMeet) body.put("conferenceData", JSONObject().put("createRequest", JSONObject()
            .put("requestId", "slyos-" + System.currentTimeMillis())
            .put("conferenceSolutionKey", JSONObject().put("type", "hangoutsMeet"))))
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events/$eventId" +
            "?conferenceDataVersion=1&sendUpdates=" + (if (notify) "all" else "none")
        val (code, resp) = request("PATCH", url, token, body.toString())
        if (code !in 200..299) return EventInfo(false, error = "$code: ${resp.take(160)}")
        return try { parseEvent(JSONObject(resp)) } catch (e: Exception) { EventInfo(false, error = e.message ?: "parse") }
    }

    /** Cancel an event. Attendees are told by default — a silent cancellation strands everyone. */
    fun deleteEvent(ctx: Context, eventId: String, notify: Boolean = true): Boolean {
        val token = GoogleAuth.accessToken(ctx)
        if (token.isBlank()) return false
        val (code, _) = request("DELETE",
            "https://www.googleapis.com/calendar/v3/calendars/primary/events/$eventId" +
                "?sendUpdates=" + (if (notify) "all" else "none"), token, null)
        return code in 200..299
    }

    private fun parseEvent(o: JSONObject): EventInfo {
        val people = ArrayList<Attendee>()
        o.optJSONArray("attendees")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                people.add(Attendee(a.optString("email"),
                    a.optString("responseStatus", "needsAction"), a.optBoolean("organizer", false)))
            }
        }
        var meet = o.optString("hangoutLink", "")
        if (meet.isBlank()) o.optJSONObject("conferenceData")?.optJSONArray("entryPoints")?.let { eps ->
            for (i in 0 until eps.length()) {
                val ep = eps.getJSONObject(i)
                if (ep.optString("entryPointType") == "video") { meet = ep.optString("uri"); break }
            }
        }
        return EventInfo(true, o.optString("id"), o.optString("summary"),
            o.optJSONObject("start")?.optString("dateTime").orEmpty(),
            o.optJSONObject("end")?.optString("dateTime").orEmpty(),
            meet, o.optString("htmlLink"), people)
    }

    /** GET/PATCH/DELETE share one path; POST keeps its own for backwards compatibility. */
    private fun request(method: String, endpoint: String, token: String, json: String?): Pair<Int, String> {
        return try {
            val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = if (method == "PATCH") "POST" else method
                // HttpURLConnection has no native PATCH; Google honours the override header.
                if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                doOutput = json != null
                connectTimeout = 20000; readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            if (json != null) c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) { Log.e(TAG, "calendar $method failed", e); 0 to (e.message ?: "network error") }
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
