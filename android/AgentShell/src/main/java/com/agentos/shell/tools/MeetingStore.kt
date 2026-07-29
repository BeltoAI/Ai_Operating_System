package com.agentos.shell.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Meetings as records rather than as one blob of text.
 *
 * Recording lived inside a composable on Home: a single undifferentiated transcript, no speakers, no
 * export, and it died the moment the screen did. A meeting is the one thing people record precisely
 * because they cannot hold it in their head, so losing it to a lock screen is the whole failure.
 *
 * Stored per meeting: the turns as they were spoken, who said them as far as that can be told, and
 * the summary once it exists. Segments are written as they arrive rather than at the end, so a
 * process kill costs the last sentence and not the hour.
 */
object MeetingStore {

    private const val PREFS = "slyos_meetings"
    private const val KEY = "index"
    /** Long enough to be a turn change rather than someone drawing breath. */
    private const val TURN_GAP_MS = 1_800L

    data class Segment(val speaker: Int, val text: String, val ts: Long)

    data class Meeting(
        val id: Long,
        val title: String,
        val startedAt: Long,
        val endedAt: Long,
        val segments: List<Segment>,
        val summary: String,
        /** Speaker index → the name the owner gave them. */
        val names: Map<Int, String>,
        /**
         * Which speaker is the owner, or -1 if they have not said.
         *
         * Without this, nothing can tell whose commitments are whose. The summary comes back
         * attributed to "Speaker 2", and "Speaker 2 will send the one-pager to Carlos by Thursday"
         * is either a task for the person holding the phone or a note about someone else — and
         * putting a colleague's promise on your own list is how a checklist stops being believed.
         */
        val me: Int = -1,
        /**
         * The calendar block this recording belongs to, when it was started from one.
         *
         * Without it a recording is an orphan: the notes exist, the meeting exists, and nothing
         * connects them — so "email everyone the notes from this morning's review" cannot be
         * answered even though both halves are on the device. Matching on title alone is not
         * enough, because a weekly standup has the same title every week.
         */
        val eventTitle: String = "",
        val eventStartMs: Long = 0L
    ) {
        val running: Boolean get() = endedAt == 0L
        val durationMs: Long get() = (if (running) System.currentTimeMillis() else endedAt) - startedAt

        fun speakerLabel(i: Int): String = if (i == me) "You" else names[i] ?: "Speaker ${i + 1}"

        /** The transcript as prose, grouped by turn — what goes to the model and into the PDF. */
        fun transcript(): String = segments.joinToString("\n\n") { "${speakerLabel(it.speaker)}: ${it.text}" }

        /** Just the words, for the cases where labels would be noise. */
        fun plain(): String = segments.joinToString(" ") { it.text }
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // MARK: - Read

    fun all(ctx: Context): List<Meeting> = try {
        val arr = JSONArray(p(ctx).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parse(it) } }
            .sortedByDescending { it.startedAt }
    } catch (e: Exception) { emptyList() }

    fun get(ctx: Context, id: Long): Meeting? = all(ctx).firstOrNull { it.id == id }

    /** The one still running, if any — how the screen knows to show the recorder. */
    fun live(ctx: Context): Meeting? = all(ctx).firstOrNull { it.running }

    private fun parse(o: JSONObject): Meeting {
        val segs = o.optJSONArray("segments")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Segment(it.optInt("s"), it.optString("t"), it.optLong("ts")) }
            }
        } ?: emptyList()
        val names = HashMap<Int, String>()
        o.optJSONObject("names")?.let { n -> n.keys().forEach { k -> k.toIntOrNull()?.let { names[it] = n.optString(k) } } }
        return Meeting(
            id = o.optLong("id"),
            title = o.optString("title"),
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            segments = segs,
            summary = o.optString("summary"),
            names = names,
            me = if (o.has("me")) o.optInt("me", -1) else -1,
            eventTitle = o.optString("eventTitle"),
            eventStartMs = o.optLong("eventStartMs"))
    }

    private fun write(ctx: Context, meetings: List<Meeting>) {
        val arr = JSONArray()
        // Bounded, oldest dropped. A year of meetings in SharedPreferences would eventually be the
        // reason the app is slow to start.
        meetings.sortedByDescending { it.startedAt }.take(60).forEach { m ->
            val segs = JSONArray()
            m.segments.forEach { segs.put(JSONObject().put("s", it.speaker).put("t", it.text).put("ts", it.ts)) }
            val names = JSONObject()
            m.names.forEach { (k, v) -> names.put(k.toString(), v) }
            arr.put(JSONObject()
                .put("id", m.id).put("title", m.title)
                .put("startedAt", m.startedAt).put("endedAt", m.endedAt)
                .put("segments", segs).put("summary", m.summary).put("names", names)
                .put("me", m.me)
                .put("eventTitle", m.eventTitle).put("eventStartMs", m.eventStartMs))
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun replace(ctx: Context, id: Long, f: (Meeting) -> Meeting) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = f(list[i])
        write(ctx, list)
    }

    // MARK: - Write

    fun start(ctx: Context, title: String = "", eventTitle: String = "", eventStartMs: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val name = title.ifBlank {
            "Meeting " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(now))
        }
        // Any meeting left running by a crash is closed, so `live` can never return a ghost that
        // makes the screen show a recorder for a recording that stopped hours ago.
        val cleaned = all(ctx).map { if (it.running) it.copy(endedAt = it.startedAt + it.durationMs) else it }
        write(ctx, cleaned + Meeting(now, name, now, 0L, emptyList(), "", emptyMap(), -1,
            eventTitle, eventStartMs))
        return now
    }

    /**
     * The recording made for a calendar block, if there is one.
     *
     * Matched on start time rather than title, within a generous window either side — people start
     * recording a few minutes late and a weekly standup carries the same title every week, so the
     * title alone would hand back the wrong one. Falls back to the title only when nothing was
     * linked, which covers recordings started by hand before this existed.
     */
    fun forEvent(ctx: Context, eventTitle: String, eventStartMs: Long): Meeting? {
        val list = all(ctx).filterNot { it.running }
        return list.firstOrNull {
            it.eventStartMs > 0 && kotlin.math.abs(it.eventStartMs - eventStartMs) < 30 * 60_000L
        } ?: list.firstOrNull {
            eventTitle.isNotBlank() && it.title.equals(eventTitle, true) &&
                kotlin.math.abs(it.startedAt - eventStartMs) < 6 * 3_600_000L
        }
    }

    /**
     * Add what was just said.
     *
     * The speaker is inferred from the pause before it, which is all that is available without a
     * diarisation model: a long silence usually means someone else took the floor. It is right often
     * enough to be worth having in a two- or three-person room and wrong often enough that every
     * label is editable and none of them is presented as certain.
     */
    fun append(ctx: Context, id: Long, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        replace(ctx, id) { m ->
            val last = m.segments.lastOrNull()
            val gap = if (last == null) 0L else System.currentTimeMillis() - last.ts
            val speaker = when {
                last == null -> 0
                gap > TURN_GAP_MS -> (last.speaker + 1) % 2
                else -> last.speaker
            }
            // Continuing the same turn appends to it rather than fragmenting one person's sentence
            // across three lines because the recogniser restarted mid-thought.
            val segs = if (last != null && speaker == last.speaker)
                m.segments.dropLast(1) + last.copy(text = (last.text + " " + t).trim(), ts = System.currentTimeMillis())
            else m.segments + Segment(speaker, t, System.currentTimeMillis())
            m.copy(segments = segs)
        }
    }

    fun finish(ctx: Context, id: Long) = replace(ctx, id) { it.copy(endedAt = System.currentTimeMillis()) }

    fun setSummary(ctx: Context, id: Long, summary: String) = replace(ctx, id) { it.copy(summary = summary) }

    fun rename(ctx: Context, id: Long, title: String) = replace(ctx, id) { it.copy(title = title.trim().ifBlank { it.title }) }

    /** Name a speaker — and it back-fills, because a name is worth nothing on one line out of forty. */
    fun nameSpeaker(ctx: Context, id: Long, speaker: Int, name: String) =
        replace(ctx, id) { it.copy(names = it.names + (speaker to name.trim())) }

    /** Mark which voice is the owner's — the only way to know whose commitments are whose. */
    fun setMe(ctx: Context, id: Long, speaker: Int) = replace(ctx, id) { it.copy(me = speaker) }

    fun delete(ctx: Context, id: Long) = write(ctx, all(ctx).filterNot { it.id == id })

    /** mm:ss, or h:mm:ss once it has been going long enough to need it. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
               else String.format("%d:%02d", s / 60, s % 60)
    }
}
