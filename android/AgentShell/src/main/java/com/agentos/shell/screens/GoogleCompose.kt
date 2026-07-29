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
        }
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
            Spacer(Modifier.height(22.dp))
            listOf(
                Triple("Move it", "⇄", Verb.MOVE),
                Triple("Cancel it", "✕", Verb.CANCEL),
                Triple("Heads-up to everyone", "▤", Verb.NOTIFY),
                Triple("Email the guests", "✉", Verb.EMAIL)
            ).forEach { (label, glyph, to) ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        mode = to
                    }
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(glyph, fontSize = 14.sp, color = T.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(label, fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                    Text("›", fontSize = T.body, color = T.inkFaint)
                }
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
                listOf("Today" to 0, "Tomorrow" to 1, "In 2 days" to 2, "Next week" to 7)
                    .forEach { (l, d) ->
                        Chip(l, dayOffset == d) { dayOffset = d }
                    }
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
                            Text(g.name.ifBlank { g.email }, fontSize = T.caption, color = T.ink)
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
                val hits = remember(guestQuery) { Directory.search(ctx, guestQuery) }
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
            Field("AGENDA  ·  OPTIONAL", agenda, "What it's for") { agenda = it }
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

        if (result.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(result, fontSize = T.small,
                color = if (result.contains("✓")) T.good else T.danger, lineHeight = 20.sp)
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
                mode == Verb.NOTIFY -> "Send the heads-up"
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
                        Verb.NOTIFY -> run("event_followup", JSONObject()
                            .put("title", event!!.title)
                            .put("message", note.ifBlank { "A quick heads-up about “${event.title}”." })
                            .toString())
                        Verb.EMAIL -> {
                            // TAILORED FROM THE BRAIN, NOT A TEMPLATE.
                            //
                            // The composer writes in the owner's voice already; what it lacked was
                            // the specifics — which meeting this is about, what is attached, and
                            // who the recipient actually is to them. A note to someone you speak to
                            // weekly should not read like a note to a stranger, and the brain knows
                            // which is which.
                            val who = guests.firstOrNull()?.name.orEmpty()
                            val about = buildString {
                                append(note)
                                event?.let {
                                    append(". This is about “${it.title}” on ")
                                    append(java.text.SimpleDateFormat("EEEE d MMMM 'at' HH:mm",
                                        java.util.Locale.getDefault()).format(java.util.Date(it.begin)))
                                    if (it.location.isNotBlank()) append(" in ${it.location}")
                                }
                                if (who.isNotBlank()) {
                                    val hist = try {
                                        com.agentos.shell.tools.PersonResolver.historyFor(ctx, who, 6)
                                    } catch (e: Exception) { "" }
                                    if (hist.isNotBlank())
                                        append(". How you two normally write to each other:\n")
                                            .append(hist.take(900))
                                }
                            }
                            run("compose_email", JSONObject()
                                .put("to", guests.joinToString(",") { it.email })
                                .put("topic", about).toString())
                        }
                        else -> {
                            val o = JSONObject()
                                .put("title", title.ifBlank { "Meeting" })
                                .put("start", iso(startMs))
                                .put("end", iso(startMs + mins * 60_000L))
                            if (guests.isNotEmpty())
                                o.put("attendees", JSONArray().apply { guests.forEach { put(it.email) } })
                            if (place.isNotBlank()) o.put("location", place)
                            if (agenda.isNotBlank()) o.put("description", agenda)
                            if (meet) o.put("meet", true)
                            run("add_event", o.toString())
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

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (on) 1f else 0.97f,
        spring(dampingRatio = 0.55f, stiffness = 700f), label = "c")
    Text(label, fontSize = T.caption, color = if (on) Color.White else T.inkSoft,
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) T.accent else T.bgElevated)
            .clickable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp))
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

