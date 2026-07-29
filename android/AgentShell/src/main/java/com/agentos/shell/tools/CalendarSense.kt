package com.agentos.shell.tools

import android.content.Context
import android.location.Geocoder
import java.util.Calendar

/**
 * The things a calendar should notice on your behalf, which SlyOS was not noticing.
 *
 * Found by auditing the booking flow rather than by waiting for someone to hit them. Every one is a
 * silent-wrong-outcome — the booking succeeds, nothing warns, and the cost lands later:
 *
 *  - **A double booking.** Nothing checked whether the slot was free. The event is created, the
 *    calendar shows two things at once, and the first anyone knows is when two people are waiting.
 *  - **No way to find a time.** "When are we both free" is the single most common calendar job and
 *    there was no path to it at all — you had to know a time before you could book one.
 *  - **A place with no time to get there.** A meeting across town at 3pm needs leaving at 2:20, and
 *    a calendar that knows the address and says nothing about the journey is withholding the only
 *    part that changes what you do.
 *  - **3am bookings.** A parsed "at 3" that lands overnight is almost always wrong, and worth
 *    querying rather than writing into a calendar.
 *
 * All local. Conflicts and free slots come from the device calendar; distance from the Geocoder,
 * which needs no key and no network account.
 */
object CalendarSense {

    // MARK: - Conflicts

    data class Clash(val title: String, val begin: Long, val end: Long)

    /**
     * What this booking would collide with.
     *
     * Overlap, not containment: a 14:00–15:00 clashes with a 14:30–15:30 and neither contains the
     * other. Getting that wrong is how a "no conflicts" check passes over an obvious one.
     */
    fun clashes(ctx: Context, startMs: Long, endMs: Long): List<Clash> = try {
        CalendarTool.eventsBetween(ctx, startMs - 12 * 3_600_000L, endMs + 12 * 3_600_000L, 60)
            .filter { it.begin < endMs && it.end > startMs }
            .map { Clash(it.title, it.begin, it.end) }
    } catch (e: Exception) { emptyList() }

    /** Said plainly, with the thing named — "you're busy" is not actionable. */
    fun clashLine(c: List<Clash>): String {
        if (c.isEmpty()) return ""
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return if (c.size == 1)
            "Overlaps “${c[0].title}” (${f.format(java.util.Date(c[0].begin))}–${f.format(java.util.Date(c[0].end))})"
        else "Overlaps ${c.size} things, starting with “${c[0].title}”"
    }

    // MARK: - Finding a time

    data class Slot(val begin: Long, val end: Long)

    /**
     * Free slots of [minutes], inside working hours, over the next [days].
     *
     * Working hours rather than "any gap", because 06:40 on a Sunday is technically free and never
     * the answer. Skips today's past, so the first suggestion is always somewhere you could
     * actually still go.
     */
    fun freeSlots(
        ctx: Context, minutes: Int, days: Int = 7,
        dayStartHour: Int = 9, dayEndHour: Int = 18, max: Int = 6
    ): List<Slot> {
        val out = ArrayList<Slot>()
        val now = System.currentTimeMillis()
        val len = minutes * 60_000L
        for (d in 0 until days) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, dayStartHour); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            // Nobody wants a Saturday suggestion for a work meeting.
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue
            var cursor = maxOf(cal.timeInMillis, now + 15 * 60_000L)
            val dayEnd = cal.apply { set(Calendar.HOUR_OF_DAY, dayEndHour) }.timeInMillis
            if (cursor >= dayEnd) continue

            val busy = CalendarTool.eventsBetween(ctx, cursor, dayEnd, 40)
                .map { it.begin to it.end }.sortedBy { it.first }
            busy.forEach { (b, e) ->
                if (b - cursor >= len) out.add(Slot(cursor, cursor + len))
                if (e > cursor) cursor = e
                if (out.size >= max) return out
            }
            if (dayEnd - cursor >= len) out.add(Slot(cursor, cursor + len))
            if (out.size >= max) return out
        }
        return out
    }

    // MARK: - Getting there

    data class Journey(val km: Double, val minutes: Int, val leaveBy: Long)

    /**
     * Roughly how long it takes to reach [place], and when to set off.
     *
     * Straight-line distance with a speed that degrades for short trips, because a route API needs a
     * key and a subscription and this needs to be right to the nearest ten minutes, not the nearest
     * minute. Deliberately conservative — being told to leave slightly early costs nothing, and the
     * opposite costs the meeting.
     *
     * Null when the place is not an address (a room, a Meet link, "the usual") or when there is no
     * location fix — a made-up travel time would be worse than none.
     */
    fun journey(ctx: Context, place: String, startMs: Long): Journey? {
        if (place.isBlank() || place.length < 6) return null
        if (Regex("(?i)(meet\\.google|zoom|teams|https?://|online|remote|call)").containsMatchIn(place)) return null
        // The same best-effort fix the rest of the app uses: whatever the system last had, never a
        // request that makes someone wait for GPS to decide whether to leave.
        val here = try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null } catch (e: Exception) { null } ?: return null
        val there = try {
            @Suppress("DEPRECATION")
            Geocoder(ctx).getFromLocationName(place, 1)?.firstOrNull()
        } catch (e: Exception) { null } ?: return null

        val km = haversine(here.latitude, here.longitude, there.latitude, there.longitude)
        if (km < 0.3) return null                      // already there
        if (km > 400) return null                      // a flight, not a journey
        // Slower for short hops (parking, walking either end), faster on the open road.
        val kmh = when {
            km < 3 -> 14.0
            km < 15 -> 26.0
            km < 60 -> 55.0
            else -> 80.0
        }
        val mins = ((km / kmh) * 60).toInt() + 8       // the eight minutes nobody plans for
        return Journey(km, mins, startMs - mins * 60_000L)
    }

    fun journeyLine(j: Journey): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "About ${j.minutes} min away — leave by ${f.format(java.util.Date(j.leaveBy))}"
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // MARK: - Places

    /**
     * Real place suggestions for what has been typed.
     *
     * Two sources, and the first matters more: rooms and addresses the owner has ALREADY used, since
     * a calendar repeats itself far more than it invents. Then the Geocoder for anything new. No
     * Places API, so no key and no billing account for something that mostly needs to complete
     * "Boa" into the boardroom they booked last Tuesday.
     */
    fun places(ctx: Context, typed: String, max: Int = 5): List<String> {
        val q = typed.trim()
        if (q.length < 2) return recentPlaces(ctx).take(max)
        val out = LinkedHashSet<String>()
        recentPlaces(ctx).filter { it.contains(q, true) }.forEach { out.add(it) }
        if (out.size < max) try {
            @Suppress("DEPRECATION")
            Geocoder(ctx).getFromLocationName(q, max)?.forEach { a ->
                val line = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }
                    .firstOrNull().orEmpty()
                if (line.isNotBlank()) out.add(line)
            }
        } catch (e: Exception) {}
        return out.take(max).toList()
    }

    /** Where meetings have actually been held, most recent first. */
    fun recentPlaces(ctx: Context): List<String> = try {
        val now = System.currentTimeMillis()
        CalendarTool.eventsBetween(ctx, now - 120L * 86_400_000L, now + 30L * 86_400_000L, 200)
            .mapNotNull { it.location.takeIf { l -> l.isNotBlank() && l.length in 3..60 } }
            .distinct().take(12)
    } catch (e: Exception) { emptyList() }

    // MARK: - Sanity

    /** An hour that is almost certainly not what was meant. Worth asking rather than booking. */
    fun oddHour(startMs: Long): String? {
        val h = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY)
        return when {
            h in 0..5 -> "That's ${h}am — did you mean ${if (h == 0) 12 else h}pm?"
            h == 23 -> "That's 11pm."
            else -> null
        }
    }
}
