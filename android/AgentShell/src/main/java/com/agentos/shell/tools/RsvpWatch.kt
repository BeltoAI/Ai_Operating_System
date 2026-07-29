package com.agentos.shell.tools

import android.content.Context
import org.json.JSONObject

/**
 * Who accepted, who declined, and who never answered — noticed on your behalf.
 *
 * Until now: nothing. An invitation went out and the owner learned that someone had declined by
 * opening Google Calendar and looking, or by turning up to a meeting that was not happening. A
 * decline is not information to be filed — it is a decision to be made, and the moment to make it
 * is when it arrives rather than five minutes before the meeting.
 *
 * Three things are worth being told, and nothing else:
 *
 *  - **someone declined** — the meeting may need moving, or may not be worth having;
 *  - **the last person accepted** — it is definitely going ahead;
 *  - **nobody has answered and it is close** — worth a nudge while a nudge still helps.
 *
 * Deliberately quiet about everything else. An assistant that reports each individual acceptance is
 * one people turn off, and then the decline goes unnoticed too.
 */
object RsvpWatch {

    private const val PREFS = "slyos_rsvp"

    data class Change(
        val eventTitle: String,
        val startMs: Long,
        val who: String,
        val status: String,
        /** What the owner would plausibly want to do about it. */
        val suggestion: String
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Look at the next fortnight and report what changed since last time.
     *
     * State is kept per attendee per event, so a decline is reported once rather than every half
     * hour until the meeting — the difference between being told something and being nagged.
     */
    fun check(ctx: Context): List<Change> {
        if (!GoogleAuth.isConnected(ctx)) return emptyList()
        val out = ArrayList<Change>()
        val events = try {
            GoogleCalendarClient.findEvents(ctx, "", System.currentTimeMillis())
        } catch (e: Exception) { return emptyList() }

        val edit = p(ctx).edit()
        events.take(25).forEach { ev ->
            val startMs = try { isoToMs(ev.startIso) } catch (e: Exception) { 0L }
            if (startMs <= 0 || startMs > System.currentTimeMillis() + 14L * 86_400_000L) return@forEach
            val guests = ev.attendees.filterNot { it.organizer }
            if (guests.isEmpty()) return@forEach

            guests.forEach { a ->
                val key = "s_${ev.id}_${a.email}"
                val was = p(ctx).getString(key, "").orEmpty()
                val now = a.responseStatus
                if (was != now) {
                    edit.putString(key, now)
                    // Only a decline is worth interrupting for on its own.
                    if (now == "declined" && was.isNotEmpty() || now == "declined" && was.isEmpty()) {
                        out.add(Change(ev.title, startMs, a.email, "declined",
                            "Move it, drop them, or ask why — tap to write to them."))
                    }
                }
            }

            // Everyone in: worth one line, because it changes whether you prepare.
            val allIn = guests.isNotEmpty() && guests.all { it.responseStatus == "accepted" }
            val allInKey = "allin_${ev.id}"
            if (allIn && !p(ctx).getBoolean(allInKey, false)) {
                edit.putBoolean(allInKey, true)
                out.add(Change(ev.title, startMs, "", "confirmed",
                    "Everyone's in."))
            }

            // Silence, close to the day. A nudge is only useful while it can still change anything.
            val soon = startMs - System.currentTimeMillis() < 2 * 86_400_000L
            val quiet = guests.filter { it.responseStatus != "accepted" && it.responseStatus != "declined" }
            val nudgeKey = "nudge_${ev.id}"
            if (soon && quiet.isNotEmpty() && !p(ctx).getBoolean(nudgeKey, false)) {
                edit.putBoolean(nudgeKey, true)
                out.add(Change(ev.title, startMs,
                    quiet.joinToString(", ") { it.email }, "no answer",
                    "Nudge them while it still helps."))
            }
        }
        edit.apply()

        // Into the brain, so "did Joslyn ever reply about Thursday?" is answerable.
        out.forEach { c ->
            try {
                Brain.remember(ctx, "note", "RSVP: ${c.eventTitle}",
                    when (c.status) {
                        "declined" -> "${c.who} declined “${c.eventTitle}”."
                        "confirmed" -> "Everyone accepted “${c.eventTitle}”."
                        else -> "No answer yet from ${c.who} for “${c.eventTitle}”."
                    }, role = "system")
            } catch (e: Exception) {}
        }
        return out
    }

    /** A line for the Now feed — what happened and what it means, in one breath. */
    fun line(c: Change): String {
        val whenText = java.text.SimpleDateFormat("EEE d MMM 'at' HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(c.startMs))
        return when (c.status) {
            "declined" -> "${c.who.substringBefore('@')} declined “${c.eventTitle}” — $whenText"
            "confirmed" -> "Everyone's accepted “${c.eventTitle}” — $whenText"
            else -> "Still no answer for “${c.eventTitle}” — $whenText"
        }
    }

    private fun isoToMs(iso: String): Long =
        try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .parse(iso)?.time ?: 0L
        } catch (e: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)?.time ?: 0L
            } catch (e2: Exception) { 0L }
        }
}
