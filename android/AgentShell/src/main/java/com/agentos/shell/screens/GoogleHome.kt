package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.CalendarTool
import com.agentos.shell.tools.Directory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * The Google page as a *place*: your week, then a way to change it.
 *
 * The first version opened onto seven example sentences with real people's names baked into them,
 * which is both useless to anyone else and slightly rude — it teaches the app's phrasing rather than
 * showing the owner their own week, and it only ever appeared mid-request anyway.
 *
 * Three ways in, and the ordering is the whole design:
 *
 *  1. **The calendar.** Tap an event and act on THAT event. The ambiguity that breaks every parser —
 *     which 2pm, whose meeting — simply does not exist when you have pointed at the thing.
 *  2. **The verbs.** Book, Move, Cancel, Invite, Email, Meet. For when you know what you want and
 *     would rather not compose a sentence about it. Each opens a form where everything is optional
 *     except what makes it valid.
 *  3. **The prompt bar.** For when saying it is faster, which for fluent requests it usually is.
 *
 * All three build the same steps and run through the same executor, so there is one path to be right
 * about rather than three to keep in sync.
 */
@Composable
fun GoogleHome(
    modifier: Modifier = Modifier,
    onAsk: (String) -> Unit,
    onCompose: (Verb, CalendarTool.Event?, Int) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var dayOffset by remember { mutableStateOf(0) }
    /**
     * The first day of the strip, as an offset from today.
     *
     * The strip was hardcoded to (0..6) — today plus six days — with no way to move it. So a meeting
     * created five weeks out, which the form now allows, could never be LOOKED at: the furthest
     * viewable day was next Tuesday. Creating and viewing have to reach the same distance or one of
     * them is decoration.
     */
    var weekStart by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(40); appear = true }
    // Built on a background thread. Reading it from a composable is what killed the app: four
    // hundred resolver lookups on the main thread is a watchdog kill, not an exception, which is
    // why nothing appeared in the log.
    LaunchedEffect(Unit) { Directory.warm(ctx) }
    var known by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(30) { if (Directory.ready) { known = Directory.count(ctx); return@LaunchedEffect }; delay(400) }
    }

    val dayStart = remember(dayOffset) {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val events = remember(dayStart) {
        CalendarTool.eventsBetween(ctx, dayStart, dayStart + 86_400_000L, 40)
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(14.dp))
            ScreenHeader("Calendar & mail", onBack)

            // ── The week, as a strip ──
            //
            // Seven days is the unit people plan in, and a strip costs one line where a month grid
            // costs a screen. The dot says "something is here" without needing to read anything.
            Spacer(Modifier.height(14.dp))
            // ── Which week, and a way to leave it ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 20.sp, color = T.inkSoft,
                    modifier = Modifier.clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        weekStart -= 7
                    }.padding(horizontal = 10.dp, vertical = 2.dp))
                // The month, and a tap to jump anywhere — because paging seven days at a time to
                // reach December is not navigation, it is a punishment.
                Text(
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, weekStart) }
                        .let { java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
                            .format(it.time) },
                    fontSize = T.caption, color = T.inkSoft, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable {
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, weekStart) }
                        android.app.DatePickerDialog(ctx, { _, y, mo, dy ->
                            val picked = Calendar.getInstance().apply {
                                set(Calendar.YEAR, y); set(Calendar.MONTH, mo)
                                set(Calendar.DAY_OF_MONTH, dy)
                                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }
                            val today = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }
                            val off = Math.round(
                                (picked.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
                            // Land the chosen day IN the strip and selected, not merely nearby.
                            weekStart = off; dayOffset = off
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                           c.get(Calendar.DAY_OF_MONTH)).show()
                    })
                if (weekStart != 0) Text("Today", fontSize = 10.sp, color = T.accent,
                    modifier = Modifier.clickable { weekStart = 0; dayOffset = 0 }
                        .padding(horizontal = 8.dp))
                Text("›", fontSize = 20.sp, color = T.inkSoft,
                    modifier = Modifier.clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        weekStart += 7
                    }.padding(horizontal = 10.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (weekStart..weekStart + 6).forEach { i ->
                    val d = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
                    val busy = remember(i, weekStart) {
                        val s = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, i)
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                        }.timeInMillis
                        CalendarTool.eventsBetween(ctx, s, s + 86_400_000L, 5).size
                    }
                    val on = dayOffset == i
                    val scale by animateFloatAsState(if (on) 1f else 0.94f,
                        spring(dampingRatio = 0.6f, stiffness = 500f), label = "d$i")
                    Column(
                        Modifier.weight(1f).padding(horizontal = 2.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (on) T.accent else T.bgElevated)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dayOffset = i
                            }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                            .format(d.time).take(2),
                            fontSize = 9.sp, color = if (on) Color.White else T.inkFaint,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(d.get(Calendar.DAY_OF_MONTH).toString(),
                            fontSize = T.small, color = if (on) Color.White else T.ink)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape)
                            .background(when {
                                busy == 0 -> Color.Transparent
                                on -> Color.White
                                else -> T.accent
                            }))
                    }
                }
            }

            // ── INVITATIONS WAITING ON YOU ──
            //
            // The RSVP loop only ran one way: SlyOS could see who had answered its own invitations
            // and had no way at all to answer one. An invitation arriving sat unanswered forever
            // unless the owner opened Google Calendar — the app SlyOS exists to replace.
            var pending by remember { mutableStateOf<List<com.agentos.shell.tools.GoogleCalendarClient.EventInfo>>(emptyList()) }
            var rsvpNote by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                pending = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { com.agentos.shell.tools.GoogleCalendarClient.awaitingMyReply(ctx) }
                    catch (e: Exception) { emptyList() }
                }
            }
            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                SectionLabel("WAITING ON YOU")
                Spacer(Modifier.height(10.dp))
                pending.take(4).forEach { inv ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text(inv.title.ifBlank { "Untitled" }, fontSize = T.small, color = T.ink)
                        Spacer(Modifier.height(2.dp))
                        Text(inv.startIso.replace('T', ' ').take(16) +
                            (inv.attendees.firstOrNull { it.organizer }
                                ?.let { "  ·  from ${it.email.substringBefore('@')}" } ?: ""),
                            fontSize = T.caption, color = T.inkFaint)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            listOf("accepted" to "Yes", "tentative" to "Maybe", "declined" to "No")
                                .forEach { (status, label) ->
                                    Box(Modifier.padding(end = 8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (status == "accepted") T.accent else T.bgElevated)
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch {
                                                val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.agentos.shell.tools.GoogleCalendarClient
                                                        .respond(ctx, inv.id, status)
                                                }
                                                rsvpNote = if (r.ok)
                                                    "Answered “${inv.title}” — $label" else "Couldn't answer that one."
                                                if (r.ok) pending = pending.filterNot { it.id == inv.id }
                                            }
                                        }
                                        .padding(horizontal = 18.dp, vertical = 9.dp)) {
                                        Text(label, fontSize = T.caption,
                                            color = if (status == "accepted") Color.White else T.inkSoft,
                                            maxLines = 1, softWrap = false)
                                    }
                                }
                        }
                    }
                    Hairline()
                }
                if (rsvpNote.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(rsvpNote, fontSize = T.caption, color = T.good)
                }
            }

            // ── That day ──
            Spacer(Modifier.height(20.dp))
            Text(
                when (dayOffset) {
                    0 -> "Today"; 1 -> "Tomorrow"
                    else -> java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
                        .format(java.util.Date(dayStart))
                },
                fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Medium)

            Spacer(Modifier.height(12.dp))
            if (events.isEmpty()) {
                Text("Nothing scheduled.", fontSize = T.small, color = T.inkFaint)
            } else {
                events.forEachIndexed { i, ev ->
                    val fade by animateFloatAsState(if (appear) 1f else 0f, tween(200 + i * 60), label = "e$i")
                    val shift by animateFloatAsState(if (appear) 0f else 12f,
                        spring(dampingRatio = 0.85f, stiffness = 320f), label = "s$i")
                    // No card, no coloured bar, no chevron. A time and a title is what a day is;
                    // everything else was chrome competing with the only two things that matter.
                    Row(
                        Modifier.fillMaxWidth()
                            .offset(y = shift.dp).graphicsLayer { alpha = fade }
                            // Tapping an event is the whole point: no parsing, no ambiguity about
                            // which one, because you pointed at it.
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCompose(Verb.ACT_ON, ev, dayOffset)
                            }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ev.begin)),
                            fontSize = T.small, color = T.inkFaint, modifier = Modifier.width(58.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ev.title, fontSize = T.small, color = T.ink, lineHeight = 20.sp)
                            if (ev.location.isNotBlank())
                                Text(ev.location, fontSize = T.caption, color = T.inkFaint)
                        }
                    }
                }
            }

            // ── The verbs ──
            //
            // One per line, no icons, no two-column grid. A grid of tiles reads as a dashboard;
            // six words down the left reads as a list of things you can do, which is what it is.
            Spacer(Modifier.height(26.dp))
            Verb.values().toList().filter { it != Verb.ACT_ON }.forEachIndexed { i, v ->
                val fade by animateFloatAsState(if (appear) 1f else 0f, tween(180 + i * 55), label = "v$i")
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { alpha = fade }
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCompose(v, null, dayOffset)
                        }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(v.label, fontSize = T.body, color = T.ink, modifier = Modifier.width(104.dp))
                    Text(v.hint, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
                if (i < 5) Hairline()
            }

            Spacer(Modifier.height(18.dp))
            if (known > 0) {
                Text("$known addresses to invite from — your contacts and everyone you've " +
                     "corresponded with.",
                    fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(30.dp))
        }

        // ── The prompt bar ──
        //
        // Pinned, because it is the fastest route for anyone who already knows what to say, and it
        // should not be something you scroll to find.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(T.bgElevated)
                .padding(horizontal = 16.dp, vertical = 13.dp)) {
                if (typed.isEmpty())
                    Text("Say what you want…", fontSize = T.small, color = T.inkFaint,
                        maxLines = 1, softWrap = false)
                BasicTextField(typed, { typed = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Text("↑", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(if (typed.isBlank()) T.hairline else T.accent)
                    .clickable(enabled = typed.isNotBlank()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val q = typed; typed = ""; onAsk(q)
                    }.padding(top = 11.dp))
        }
    }
}

/** The things anyone actually wants to do here. Named as verbs because that is how they are thought of. */
enum class Verb(val label: String, val hint: String, val glyph: String) {
    BOOK("Book", "a meeting or a block", "▣"),
    INVITE("Invite", "someone, with a Meet link", "✚"),
    MOVE("Move", "something to another time", "⇄"),
    CANCEL("Cancel", "and tell the guests", "✕"),
    EMAIL("Email", "someone, with an attachment", "✉"),
    NOTIFY("Heads-up", "everyone in a meeting", "▤"),
    /** Not a chip — what tapping an existing event opens. */
    ACT_ON("", "", "")
}
