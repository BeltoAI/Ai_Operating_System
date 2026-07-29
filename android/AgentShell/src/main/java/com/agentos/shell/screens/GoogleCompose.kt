package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.CalendarTool
import com.agentos.shell.tools.Directory
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * The form behind a verb. Everything optional except the one thing that makes it valid.
 *
 * Written as a form rather than a sentence for the reason the whole Google surface was rebuilt: a
 * sentence has to be interpreted and an interpretation can be wrong, while a filled field is simply
 * the value. Nothing here is inferred — the time is the time you picked, the guests are the guests
 * you chose from your own address book.
 *
 * The two rules it follows:
 *
 *  - **Optional means optional.** A title, a room, an agenda and a Meet link are all skippable, and
 *    the button stays live without them. Only a time is required for a booking, because an event
 *    without one cannot exist and guessing it is the failure this replaced.
 *  - **Never a blank text box for a person.** Typing a name was how invitations ended up with an
 *    empty guest list. Guests come from [Directory] — real addresses, searched, with where each one
 *    came from shown beside it.
 */
@Composable
fun GoogleCompose(
    verb: Verb,
    event: CalendarTool.Event?,
    modifier: Modifier = Modifier,
    onDone: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Acting on an event you tapped starts as a menu, not a form — you already said which one.
    var mode by remember { mutableStateOf(if (verb == Verb.ACT_ON) Verb.ACT_ON else verb) }

    var title by remember { mutableStateOf(event?.title.orEmpty()) }
    var dayOffset by remember { mutableStateOf(if (event != null) 0 else 1) }
    var hour by remember {
        mutableStateOf(event?.let {
            Calendar.getInstance().apply { timeInMillis = it.begin }.get(Calendar.HOUR_OF_DAY)
        } ?: 10)
    }
    var minute by remember { mutableStateOf(0) }
    var mins by remember { mutableStateOf(60) }
    var place by remember { mutableStateOf(event?.location.orEmpty()) }
    var agenda by remember { mutableStateOf("") }
    var meet by remember { mutableStateOf(false) }
    var guests by remember { mutableStateOf(listOf<Directory.Entry>()) }
    var guestQuery by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    // Same rule here: never touch the directory synchronously from composition.
    androidx.compose.runtime.LaunchedEffect(Unit) { Directory.warm(ctx) }

    // WHO IS ALREADY ON THE MEETING.
    //
    // Reported: an invitation was created with Joslyn on it, and choosing "email the guests"
    // offered an empty box. The guests are a property of the event — asking someone to type them
    // again, having just been asked once, is the app forgetting something it was told a minute ago.
    androidx.compose.runtime.LaunchedEffect(event) {
        if (event != null && guests.isEmpty()) {
            val found = withContext(Dispatchers.IO) {
                try {
                    com.agentos.shell.tools.GoogleCalendarClient
                        .findEvents(ctx, event.title).firstOrNull()?.attendees.orEmpty()
                } catch (e: Exception) { emptyList() }
            }
            guests = found.filterNot { it.organizer }
                .map { Directory.Entry(it.email.substringBefore('@'), it.email, "on this meeting", 999) }
        }
    }

    // Anything already made, for attaching. Read once, off the main thread.
    var docs by remember { mutableStateOf(listOf<com.agentos.shell.tools.SlyFolder.Doc>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        docs = withContext(Dispatchers.IO) {
            try { com.agentos.shell.tools.DocForge.library(ctx).take(12) } catch (e: Exception) { emptyList() }
        }
    }
    var attach by remember { mutableStateOf<com.agentos.shell.tools.SlyFolder.Doc?>(null) }
    var remindMins by remember { mutableStateOf(0) }
    /** Minutes before the start that "leave now" would mean, when the place is a real address. */
    var leaveMins by remember { mutableStateOf(0) }
    var takeNotes by remember { mutableStateOf(false) }
    var mailPurpose by remember { mutableStateOf<com.agentos.shell.tools.MailDraft.Purpose?>(null) }

    val startMs = remember(dayOffset, hour, minute) {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun iso(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(ms))

    fun run(action: String, arg: String) {
        running = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try { ToolRouter.executeAction(ctx, action, arg) }
                catch (e: Exception) { "Couldn't: ${e.message?.take(60)}" }
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            result = out.ifBlank { "Done ✓" }
            running = false
            // INTO THE BRAIN, like everything else. A calendar change made here should be
            // answerable from Home tomorrow — otherwise this page knows things the assistant does
            // not, which is the failure the whole brain exists to prevent.
            withContext(Dispatchers.IO) {
                try {
                    com.agentos.shell.tools.Brain.remember(ctx, "action",
                        when (action) {
                            "add_event" -> "Booked"
                            "move_event" -> "Moved an event"
                            "cancel_event" -> "Cancelled an event"
                            "event_followup" -> "Told the attendees"
                            else -> "Email"
                        },
                        "$action — $out\nDetails: ${arg.take(400)}", role = "system")
                } catch (e: Exception) {}
            }
        }
    }

    // A chosen purpose takes over the screen — the draft is the thing now, not the menu.
    mailPurpose?.let { p ->
        // WHAT THE DRAFT ALREADY KNOWS.
        //
        // "Notes afterwards" is the one that used to be impossible to write: the meeting happened,
        // SlyOS recorded it, and the email asking what was decided started from a blank page. If a
        // recording is linked to this block, its summary IS the content of that email — otherwise
        // the owner is retyping something the phone already has.
        // Off the main thread: reading the store parses every meeting it holds, and doing that in
        // composition is how this app has been killed by the watchdog before.
        var seed by remember(p) { mutableStateOf(note) }
        var seedReady by remember(p) { mutableStateOf(p != com.agentos.shell.tools.MailDraft.Purpose.NOTES) }
        androidx.compose.runtime.LaunchedEffect(p, event?.begin) {
            if (seedReady) return@LaunchedEffect
            val found = withContext(Dispatchers.IO) {
                try {
                    com.agentos.shell.tools.MeetingStore
                        .forEvent(ctx, event?.title.orEmpty(), event?.begin ?: 0L)
                        ?.let { m -> m.summary.ifBlank { m.transcript().take(2500) } }.orEmpty()
                } catch (e: Exception) { "" }
            }
            if (found.isNotBlank()) seed = found
            seedReady = true
        }
        // The draft is written ONCE, from whatever the seed turns out to be — so it must not start
        // writing before the recording has been looked for, or the notes email is written from the
        // title while the notes sit on the device unused.
        if (!seedReady) { Spacer(Modifier.height(1.dp)); return }
        MailReview(
            purpose = p,
            eventTitle = event?.title.orEmpty(),
            whenText = event?.let {
                java.text.SimpleDateFormat("EEEE d MMMM 'at' HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.begin))
            }.orEmpty(),
            where = event?.location.orEmpty(),
            recipients = guests,
            modifier = modifier,
            seed = seed,
            onSent = { },
            onBack = { mailPurpose = null })
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader(
            when (mode) {
                Verb.ACT_ON -> event?.title?.take(22).orEmpty()
                else -> mode.label
            }, onBack)

        // ── An event you tapped: what can be done to it ──
        if (mode == Verb.ACT_ON && event != null) {
            Spacer(Modifier.height(10.dp))
            Text(java.text.SimpleDateFormat("EEEE d MMMM, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(event.begin)),
                fontSize = T.small, color = T.inkSoft)

            // ── WHO IS ACTUALLY COMING ──
            //
            // A declined invitation was invisible: you found out by opening Google Calendar, or by
            // turning up. Shown here because this is where someone looks when deciding what to do
            // about the meeting, which is exactly when it matters.
            var rsvp by remember(event) {
                mutableStateOf<List<com.agentos.shell.tools.GoogleCalendarClient.Attendee>>(emptyList())
            }
            androidx.compose.runtime.LaunchedEffect(event) {
                rsvp = withContext(Dispatchers.IO) {
                    try {
                        com.agentos.shell.tools.GoogleCalendarClient
                            .findEvents(ctx, event.title).firstOrNull()?.attendees.orEmpty()
                            .filterNot { it.organizer }
                    } catch (e: Exception) { emptyList() }
                }
            }
            if (rsvp.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("WHO'S COMING")
                Spacer(Modifier.height(8.dp))
                rsvp.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(a.email.substringBefore('@'), fontSize = T.small, color = T.ink,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            when (a.responseStatus) {
                                "accepted" -> "coming"
                                "declined" -> "can't make it"
                                "tentative" -> "maybe"
                                else -> "no answer"
                            },
                            fontSize = T.caption,
                            color = when (a.responseStatus) {
                                "accepted" -> T.good
                                "declined" -> T.danger
                                else -> T.inkFaint
                            })
                    }
                }
                val declined = rsvp.count { it.responseStatus == "declined" }
                if (declined > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(if (declined == rsvp.size)
                            "Nobody can make it — worth moving or dropping."
                         else "$declined can't make it. Move it, go ahead without them, or ask why.",
                        fontSize = T.caption, color = T.danger, lineHeight = 17.sp)
                }
            }

            Spacer(Modifier.height(22.dp))
            // WHAT A CALENDAR BLOCK ACTUALLY GENERATES.
            //
            // Two things you do TO the event, and six things you write ABOUT it. Every one of the
            // six goes through a draft you read before it leaves — the old path fired a one-line
            // message straight at the attendees, which is fine for a friend and unacceptable for
            // anyone else, and most of a calendar is anyone else.
            listOf("Move it" to Verb.MOVE, "Cancel it" to Verb.CANCEL).forEach { (label, to) ->
                Row(Modifier.fillMaxWidth().clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress); mode = to
                    }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
                Hairline()
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel("WRITE TO THE GUESTS")
            Spacer(Modifier.height(4.dp))
            Text("You'll see the email before it goes.", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            com.agentos.shell.tools.MailDraft.Purpose.values().forEach { p ->
                Row(Modifier.fillMaxWidth().clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        mailPurpose = p
                    }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, fontSize = T.small, color = T.ink, modifier = Modifier.width(150.dp))
                    Text(p.hint, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
                Hairline()
            }
            Spacer(Modifier.height(50.dp))
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        // ── WHEN — the only thing ever required ──
        if (mode in setOf(Verb.BOOK, Verb.INVITE, Verb.MOVE)) {
            SectionLabel(if (mode == Verb.MOVE) "MOVE IT TO" else "WHEN")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                // Short labels so four fit a narrow phone without scrolling or wrapping.
                listOf("Today" to 0, "Tomorrow" to 1, "+2d" to 2, "+1w" to 7)
                    .forEach { (l, d) -> Chip(l, dayOffset == d) { dayOffset = d } }
            }
            // Hours as chips: nine taps covers the working day, and a wheel picker for a thing
            // people say out loud is a worse trade than a row of numbers.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                (7..20).forEach { h ->
                    Chip("$h:00", hour == h && minute == 0) { hour = h; minute = 0 }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row { listOf(15, 30, 45).forEach { m -> Chip(":$m", minute == m) { minute = m } } }

            Spacer(Modifier.height(14.dp))
            SectionLabel("HOW LONG")
            Spacer(Modifier.height(8.dp))
            Row { listOf(15, 30, 60, 90, 120).forEach { m ->
                Chip(if (m >= 60) "${m / 60}h${if (m % 60 > 0) m % 60 else ""}" else "${m}m", mins == m) { mins = m }
            } }
            Spacer(Modifier.height(8.dp))
            Text(java.text.SimpleDateFormat("EEEE d MMMM, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(startMs)) + " – " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(startMs + mins * 60_000L)),
                fontSize = T.caption, color = T.accent)

            // ── WHAT THIS WOULD COLLIDE WITH ──
            //
            // Nothing checked. The event was created, the calendar showed two things at once, and
            // the first anyone knew was two people waiting in different places. It does not block —
            // double-booking on purpose is a real thing — it just refuses to be silent about it.
            val clashes = remember(startMs, mins, event) {
                com.agentos.shell.tools.CalendarSense.clashes(ctx, startMs, startMs + mins * 60_000L)
                    .filterNot { event != null && it.title == event.title }
            }
            if (clashes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(com.agentos.shell.tools.CalendarSense.clashLine(clashes),
                    fontSize = T.caption, color = T.danger, lineHeight = 17.sp)
            }
            com.agentos.shell.tools.CalendarSense.oddHour(startMs)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = T.caption, color = T.danger)
            }

            // ── WHEN AM I ACTUALLY FREE ──
            //
            // The commonest calendar job had no path at all: you had to already know a time before
            // you could book one. Working hours, weekdays, never the past.
            var showFree by remember { mutableStateOf(false) }
            Spacer(Modifier.height(10.dp))
            Text(if (showFree) "Hide free times" else "Find me a free time",
                fontSize = T.caption, color = T.accent,
                modifier = Modifier.clickable { showFree = !showFree }.padding(vertical = 4.dp))
            if (showFree) {
                val slots = remember(mins, showFree) {
                    com.agentos.shell.tools.CalendarSense.freeSlots(ctx, mins)
                }
                Spacer(Modifier.height(6.dp))
                if (slots.isEmpty())
                    Text("Nothing free in working hours this week.",
                        fontSize = T.caption, color = T.inkFaint)
                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    slots.forEach { sl ->
                        val c = Calendar.getInstance().apply { timeInMillis = sl.begin }
                        Chip(java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(sl.begin)), false) {
                            // Pick it and the whole form follows — the day chips too.
                            val today = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            dayOffset = ((sl.begin - today) / 86_400_000L).toInt().coerceAtLeast(0)
                            hour = c.get(Calendar.HOUR_OF_DAY); minute = c.get(Calendar.MINUTE)
                            showFree = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── WHO — never a blank box ──
        if (mode in setOf(Verb.INVITE, Verb.EMAIL, Verb.BOOK)) {
            SectionLabel(if (mode == Verb.EMAIL) "TO" else "GUESTS  ·  OPTIONAL")
            Spacer(Modifier.height(8.dp))
            if (guests.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState())) {
                    guests.forEach { g ->
                        Row(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp))
                            .background(T.accent.copy(alpha = 0.16f))
                            .clickable { guests = guests - g }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name.ifBlank { g.email }.take(22), fontSize = T.caption,
                                color = T.ink, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(6.dp))
                            Text("✕", fontSize = 10.sp, color = T.inkFaint)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bgElevated)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (guestQuery.isEmpty())
                    Text("Search your people…", fontSize = T.small, color = T.inkFaint)
                BasicTextField(guestQuery, { guestQuery = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth())
            }
            if (guestQuery.length >= 2) {
                // Searched off the composition thread, so a long list can never stall a keystroke.
                var hits by remember { mutableStateOf(listOf<Directory.Entry>()) }
                androidx.compose.runtime.LaunchedEffect(guestQuery) {
                    hits = withContext(Dispatchers.Default) { Directory.search(ctx, guestQuery) }
                }
                Spacer(Modifier.height(6.dp))
                hits.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(11.dp)).background(T.bg)
                        .clickable {
                            if (guests.none { it.email == h.email }) guests = guests + h
                            guestQuery = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name.ifBlank { h.email }, fontSize = T.caption, color = T.ink)
                            // Where the address came from — "in your contacts" and "seen in your
                            // messages" are different levels of confidence and should look it.
                            Text("${h.email} · ${h.source}", fontSize = 10.sp, color = T.inkFaint)
                        }
                    }
                }
                // A guest not in the book yet is a normal thing, not an error.
                if (guestQuery.contains('@') && hits.none { it.email == guestQuery.lowercase() }) {
                    Text("Use “$guestQuery”", fontSize = T.caption, color = T.accent,
                        modifier = Modifier.clickable {
                            guests = guests + Directory.Entry(guestQuery, guestQuery.lowercase(), "typed", 0)
                            guestQuery = ""
                        }.padding(vertical = 10.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── The optional detail ──
        if (mode in setOf(Verb.BOOK, Verb.INVITE)) {
            Field("WHAT  ·  OPTIONAL", title, "Meeting") { title = it }
            Field("WHERE  ·  OPTIONAL", place, "A room, an address, a link") { place = it }
            // Rooms and addresses already used, then the geocoder. A calendar repeats itself far
            // more than it invents, so what you booked last Tuesday outranks anything new.
            val placeHits = remember(place) {
                if (place.length < 2) com.agentos.shell.tools.CalendarSense.recentPlaces(ctx).take(4)
                else com.agentos.shell.tools.CalendarSense.places(ctx, place)
                    .filterNot { it.equals(place, true) }
            }
            if (placeHits.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)) {
                    placeHits.forEach { p -> Chip(p.take(26), place == p) { place = p } }
                }
            }
            // WHEN TO SET OFF. A calendar that knows the address and says nothing about the journey
            // is withholding the only part that changes what you do this afternoon.
            var journey by remember { mutableStateOf<com.agentos.shell.tools.CalendarSense.Journey?>(null) }
            androidx.compose.runtime.LaunchedEffect(place, startMs) {
                journey = if (place.length < 6) null else withContext(Dispatchers.IO) {
                    com.agentos.shell.tools.CalendarSense.journey(ctx, place, startMs)
                }
            }
            androidx.compose.runtime.LaunchedEffect(journey) { leaveMins = journey?.minutes ?: 0 }
            journey?.let {
                Text(com.agentos.shell.tools.CalendarSense.journeyLine(it),
                    fontSize = T.caption, color = T.accent, modifier = Modifier.padding(bottom = 14.dp))
            }
            Field("AGENDA  ·  OPTIONAL", agenda, "What it's for") { agenda = it }
            // ── NOTES FOR THIS MEETING ──
            //
            // Google's own "take notes for me" is a Workspace feature with no Calendar API surface —
            // it cannot be switched on from here, and a toggle that pretends to would be the worst
            // kind of feature. What CAN be promised is the recorder SlyOS already has: it starts
            // when the meeting does, separates the speakers, and puts the decisions and your own
            // commitments on your list. So the toggle says what actually happens.
            if (meet) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Record it and take notes", fontSize = T.small, color = T.ink)
                        Text("SlyOS records, separates who said what, and adds your commitments",
                            fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
                    }
                    val nx by animateFloatAsState(if (takeNotes) 18f else 0f,
                        spring(dampingRatio = 0.6f, stiffness = 600f), label = "nsw")
                    Box(Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (takeNotes) T.accent else T.hairline)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            takeNotes = !takeNotes
                        }, contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.padding(start = (3 + nx).dp).size(20.dp).clip(CircleShape)
                            .background(Color.White))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Add a Google Meet link", fontSize = T.small, color = T.ink,
                    modifier = Modifier.weight(1f))
                val x by animateFloatAsState(if (meet) 18f else 0f,
                    spring(dampingRatio = 0.6f, stiffness = 600f), label = "sw")
                Box(Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (meet) T.accent else T.hairline)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress); meet = !meet
                    }, contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.padding(start = (3 + x).dp).size(20.dp).clip(CircleShape)
                        .background(Color.White))
                }
            }
        }

        if (mode in setOf(Verb.EMAIL, Verb.NOTIFY, Verb.CANCEL)) {
            Field(if (mode == Verb.EMAIL) "ABOUT" else "MESSAGE  ·  OPTIONAL", note,
                if (mode == Verb.EMAIL) "What the email is about"
                else "Leave blank and I'll write it") { note = it }
        }

        // ── Attach something ──
        //
        // Before or after the meeting, and on the invitation itself. A deck that exists and cannot
        // be attached without leaving the page is a deck nobody attaches.
        if (docs.isNotEmpty() && mode in setOf(Verb.EMAIL, Verb.BOOK, Verb.INVITE)) {
            SectionLabel("ATTACH  ·  OPTIONAL")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
                docs.forEach { d ->
                    Chip(d.name.substringBeforeLast('.').take(18), attach?.name == d.name) {
                        attach = if (attach?.name == d.name) null else d
                    }
                }
            }
        }

        // ── A nudge before it starts ──
        if (mode in setOf(Verb.BOOK, Verb.INVITE) || (event != null && mode == Verb.NOTIFY)) {
            SectionLabel("REMIND ME  ·  OPTIONAL")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                listOf(0 to "No", 10 to "10m", 30 to "30m", 60 to "1h", 1440 to "1 day")
                    .forEach { (m, l) -> Chip(l, remindMins == m) { remindMins = m } }
                // "Leave now" is a different reminder from "it starts soon", and the useful one
                // when the meeting is across town.
                if (leaveMins > 0) Chip("Leave by", remindMins == leaveMins) { remindMins = leaveMins }
            }
        }

        if (result.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(result, fontSize = T.small,
                color = if (result.contains("✓")) T.good else T.danger, lineHeight = 20.sp)

            // MOVING A MEETING IS HALF A JOB.
            //
            // The block moved, Google emailed a bare invitation update, and the people who had put
            // it in their day got a notification with no reason in it. The half that matters — "we
            // moved it to Thursday because Ana's flight changed, sorry" — had no path at all. It is
            // offered here, at the only moment anyone would want it, pre-filled with the change.
            val told = mode == Verb.MOVE || mode == Verb.CANCEL
            if (told && result.contains("✓") && guests.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                    .background(T.hairline)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        note = if (mode == Verb.MOVE)
                            "It has moved to " + java.text.SimpleDateFormat(
                                "EEEE d MMMM 'at' HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(startMs)) + "."
                        else "It is cancelled."
                        mailPurpose = if (mode == Verb.MOVE)
                            com.agentos.shell.tools.MailDraft.Purpose.MOVED
                        else com.agentos.shell.tools.MailDraft.Purpose.CANCELLED
                    }
                    .padding(vertical = 13.dp, horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Tell the ${guests.size} guest${if (guests.size == 1) "" else "s"} why",
                        fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── One button ──
        val ready = when (mode) {
            Verb.EMAIL -> guests.isNotEmpty() && note.isNotBlank()
            Verb.CANCEL, Verb.NOTIFY -> event != null
            Verb.MOVE -> event != null
            else -> true
        }
        Text(
            when {
                running -> "Working…"
                mode == Verb.MOVE -> "Move it"
                mode == Verb.CANCEL -> "Cancel it"
                mode == Verb.NOTIFY -> "Write the heads-up"
                mode == Verb.EMAIL -> "Write and open it"
                guests.isEmpty() -> "Put it in the calendar"
                else -> "Invite ${guests.size}"
            },
            fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(if (ready && !running) T.accent else T.hairline)
                .clickable(enabled = ready && !running) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (mode) {
                        Verb.MOVE -> {
                            val f = CalendarTool.findEvent(ctx, event!!.title, event.begin)
                            run("move_event", JSONObject()
                                .put("id", f?.id ?: 0L).put("title", event.title)
                                .put("start_ms", startMs)
                                .put("end_ms", startMs + (event.end - event.begin).coerceAtLeast(1_800_000L))
                                .toString())
                        }
                        Verb.CANCEL -> {
                            val f = CalendarTool.findEvent(ctx, event!!.title, event.begin)
                            run("cancel_event",
                                JSONObject().put("id", f?.id ?: 0L).put("title", event.title).toString())
                        }
                        // A HEADS-UP IS STILL AN EMAIL TO COLLEAGUES.
                        //
                        // This fired `event_followup` — one line, straight at every attendee, never
                        // shown to the person it was sent on behalf of, no attachment possible. The
                        // one flow on this screen that still sent blind, and the one people reach
                        // for most. It goes through the same draft as everything else now: read it,
                        // edit it, attach to it, then send.
                        // A heads-up is what the owner TYPED, written properly — not an agenda.
                        // Routing it to Agenda made every heads-up come back as a list of items
                        // nobody asked for, and lost the sentence they had actually written.
                        Verb.NOTIFY -> {
                            mailPurpose = com.agentos.shell.tools.MailDraft.Purpose.CUSTOM
                        }
                        // Straight to the draft, never straight to a send. Even a plain "email
                        // someone" gets read before it leaves.
                        Verb.EMAIL -> {
                            mailPurpose = com.agentos.shell.tools.MailDraft.Purpose.CUSTOM
                        }
                        Verb.ACT_ON -> {}
                        else -> {
                            val o = JSONObject()
                                .put("title", title.ifBlank { "Meeting" })
                                .put("start", iso(startMs))
                                .put("end", iso(startMs + mins * 60_000L))
                            if (guests.isNotEmpty())
                                o.put("attendees", JSONArray().apply { guests.forEach { put(it.email) } })
                            if (place.isNotBlank()) o.put("location", place)
                            // The attachment rides in the description as a note, because a calendar
                            // invitation cannot carry a file — naming it there is the difference
                            // between a guest knowing to look for it and not.
                            val desc = listOfNotNull(
                                agenda.takeIf { it.isNotBlank() },
                                attach?.let { "Attached separately: ${it.name}" }
                            ).joinToString("\n\n")
                            if (desc.isNotBlank()) o.put("description", desc)
                            if (meet) o.put("meet", true)
                            run("add_event", o.toString())

                            // A reminder is its own thing, not a calendar field — it fires on this
                            // phone whether or not the calendar app is set up to nag.
                            if (remindMins > 0) {
                                val at = startMs - remindMins * 60_000L
                                if (at > System.currentTimeMillis()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                ToolRouter.executeAction(ctx, "remind", JSONObject()
                                                    .put("text",
                                                        if (leaveMins > 0 && remindMins == leaveMins)
                                                            "Leave now for ${title.ifBlank { "your meeting" }}" +
                                                                (if (place.isNotBlank()) " — $place" else "")
                                                        else "${title.ifBlank { "Meeting" }} starts in " +
                                                            (if (remindMins >= 60) "${remindMins / 60}h" else "${remindMins}m"))
                                                    .put("at", iso(at)).toString())
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                            // "Take notes" puts the RECORDER on the lock screen at the start time,
                            // one tap, already named and linked to this block. The earlier version
                            // set a reminder saying "open Meetings and tap record" — honest, and
                            // useless at the only moment it matters, because nobody unlocks a phone
                            // and hunts for a button while a meeting is starting.
                            if (takeNotes && startMs > System.currentTimeMillis()) {
                                com.agentos.shell.MeetingCue.schedule(
                                    ctx, title.ifBlank { "Meeting" }, startMs)
                            }
                            // And the file goes out separately, since the invitation cannot carry it.
                            attach?.let { d ->
                                if (guests.isNotEmpty()) scope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            ToolRouter.executeAction(ctx, "send_document", JSONObject()
                                                .put("name", d.name)
                                                .put("to", guests.joinToString(",") { it.email })
                                                .toString())
                                        } catch (e: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(vertical = 15.dp))

        Spacer(Modifier.height(10.dp))
        Text("Nothing is guessed — the time is the one you picked and the guests are the ones you " +
             "chose. Works the same whichever model you're on.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        Spacer(Modifier.height(50.dp))
    }
}

/**
 * A pill whose text always fits inside it.
 *
 * maxLines and softWrap are not defaults worth trusting here: a chip that wraps grows to two lines
 * and shunts the row below it, and one that clips looks broken. Both were happening — "Next week"
 * and "1h30" are wider than they look once the font scales up on someone's phone.
 *
 * The press is a scale-down that springs back rather than a colour flash: it reads as the thing
 * being pushed, which is what a finger just did to it.
 */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (on) 1.04f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 800f), label = "c")
    Box(
        Modifier.padding(end = 7.dp, bottom = 7.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = T.caption, color = if (on) Color.White else T.inkSoft,
            maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    SectionLabel(label)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().padding(bottom = 18.dp)
        .clip(RoundedCornerShape(13.dp)).background(T.bgElevated)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint)
        BasicTextField(value, onChange,
            textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth())
    }
}

