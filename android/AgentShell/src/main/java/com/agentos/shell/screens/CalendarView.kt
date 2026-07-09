package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.CalendarTool
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The time window a calendar question is asking about. */
data class CalWindow(val start: Long, val end: Long, val label: String)

object CalendarQuery {
    private const val DAY = 24L * 60 * 60 * 1000
    private val trigger = Regex(
        "(?i)\\b(my (calendar|schedule|agenda)|on my (calendar|schedule|agenda)|" +
            "what'?s? on (my )?(calendar|schedule|agenda)|appointments?|" +
            "what do i have (on|today|tomorrow|this|next)|what'?s? (on |up )?(today|tomorrow|this week))\\b"
    )

    /** Returns the window a calendar-list question refers to, or null if it isn't one. */
    fun parse(q: String): CalWindow? {
        if (!trigger.containsMatchIn(q)) return null
        val ql = q.lowercase()
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val startOfToday = c.timeInMillis
        val now = System.currentTimeMillis()
        return when {
            ql.contains("today") -> CalWindow(startOfToday, startOfToday + DAY, "Today")
            ql.contains("tomorrow") -> CalWindow(startOfToday + DAY, startOfToday + 2 * DAY, "Tomorrow")
            ql.contains("month") -> CalWindow(now, now + 31 * DAY, "This month")
            ql.contains("week") -> CalWindow(startOfToday, startOfToday + 7 * DAY, "This week")
            else -> CalWindow(now, now + 7 * DAY, "Coming up")
        }
    }
}

/** An elegant rendered agenda — events grouped by day, times in accent — for "what's on my calendar".
 *  Swipe the card left or right to dismiss it. */
@Composable
fun CalendarCard(label: String, events: List<CalendarTool.Event>, onDismiss: () -> Unit = {}) {
    val dayFmt = remember { SimpleDateFormat("EEEE · MMM d", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val groups = remember(events) { events.groupBy { dayFmt.format(Date(it.begin)) } }
    val surface = Brush.verticalGradient(listOf(T.bgElevated, T.accentSoft.copy(alpha = 0.18f)))
    val dragX = remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth()
            .offset { IntOffset(dragX.value.toInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (kotlin.math.abs(dragX.value) > 150f) onDismiss() else dragX.value = 0f },
                    onDragCancel = { dragX.value = 0f }
                ) { _, dx -> dragX.value += dx }
            }
            .clip(RoundedCornerShape(22.dp)).background(surface)
            .border(1.dp, T.hairline, RoundedCornerShape(22.dp)).padding(20.dp)
    ) {
        Text("CALENDAR · ${label.uppercase()}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = T.accent, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        if (events.isEmpty()) {
            Text("Nothing scheduled ${label.lowercase()} — you're free.", fontSize = T.body, color = T.inkSoft)
            return@Column
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            groups.forEach { (day, evs) ->
                Text(day, fontSize = T.small, fontWeight = FontWeight.SemiBold, color = T.ink, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                evs.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.width(78.dp)) {
                            Text(timeFmt.format(Date(e.begin)), fontSize = T.small, color = T.accent, fontWeight = FontWeight.Medium)
                        }
                        Box(Modifier.width(2.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(999.dp)).background(T.hairline))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontSize = T.body, color = T.ink)
                            if (e.location.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text("📍 " + e.location, fontSize = T.caption, color = T.inkFaint)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
