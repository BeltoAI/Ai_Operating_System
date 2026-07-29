package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.PermissionController
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.VitalsInsight
import com.agentos.shell.tools.VitalsMath
import com.agentos.shell.tools.VitalsSource
import com.agentos.shell.tools.VitalsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Health: a page you talk to that happens to show everything.
 *
 * Not a dashboard with a chat bolted on. The ring and the sentence answer "how am I today" in three
 * seconds; the tiles answer "compared to what" without a second tap; and the field at the bottom
 * answers everything else — including the questions no wearable app can, because none of them know
 * what else was happening in your life. SlyOS does.
 *
 * Every delta is against the owner's own 30-day baseline. Projections are drawn as bands, because a
 * single forecast line is a lie dressed as a fact.
 */
@Composable
fun HealthScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tick by remember { mutableStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val present = remember(tick) { VitalsStore.present(ctx) }
    val headline = remember(tick) { if (present.isEmpty()) "" else VitalsInsight.headline(ctx) }
    val flags = remember(tick) { if (present.isEmpty()) emptyList() else VitalsInsight.flags(ctx) }
    val availability = remember(tick) { VitalsSource.availability(ctx) }

    // The system permission sheet. Health Connect grants are per-record-type and the owner can give
    // a subset — which is fine and normal, so the result is not checked for completeness, only for
    // whether anything at all came back to read.
    val askPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        scope.launch {
            if (granted.isEmpty()) {
                note = "No permissions granted, so there is nothing to read yet."
                return@launch
            }
            syncing = true
            val n = withContext(Dispatchers.IO) { VitalsSource.sync(ctx, 90) }
            syncing = false; tick++
            note = if (n > 0) "Pulled $n readings from the last 90 days."
                   else "Connected, but nothing is writing to Health Connect yet. " + VitalsSource.writerHint()
            if (n > 0) withContext(Dispatchers.IO) { VitalsInsight.rememberToday(ctx) }
        }
    }

    // A quiet sync on arrival, so the page is never showing yesterday because nobody pressed refresh.
    LaunchedEffect(Unit) {
        if (availability == VitalsSource.State.READY && VitalsSource.grantedAny(ctx)) {
            syncing = true
            val n = withContext(Dispatchers.IO) { VitalsSource.sync(ctx, 90) }
            syncing = false; tick++
            if (n > 0) withContext(Dispatchers.IO) { VitalsInsight.rememberToday(ctx) }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Health", onBack)
        Spacer(Modifier.height(18.dp))

        if (present.isEmpty()) {
            NotConnected(availability, syncing) {
                try { askPermissions.launch(VitalsSource.PERMISSIONS) }
                catch (e: Exception) { note = "Couldn't open the permission screen." }
            }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(note, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        Readiness(ctx, tick)

        Spacer(Modifier.height(16.dp))
        Text(headline, fontSize = T.small, color = T.ink, lineHeight = 22.sp)

        flags.forEach { f ->
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (f.serious) T.danger.copy(alpha = 0.12f) else T.bgElevated)
                .padding(14.dp)) {
                Text(f.title, fontSize = T.small, color = if (f.serious) T.danger else T.ink)
                Spacer(Modifier.height(4.dp))
                Text(f.detail, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("TODAY, AGAINST YOUR OWN BASELINE")
        Spacer(Modifier.height(10.dp))
        present.forEach { m -> MetricRow(ctx, m, tick) { detail = m } }

        // Projections, as bands. Only for series long enough to earn one.
        val projections = remember(tick) {
            present.mapNotNull { m ->
                val s = VitalsStore.series(ctx, m, 90)
                VitalsMath.trend(s)?.let { t -> Triple(m, t, s.lastOrNull()?.value ?: 0.0) }
            }.filter { (m, t, cur) ->
                // Only worth showing when it is actually going somewhere.
                abs(t.projected - cur) > t.band * 0.6
            }.take(4)
        }
        if (projections.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("PROJECTED, 30 DAYS")
            Spacer(Modifier.height(4.dp))
            Text("A band, not a number — this is where the trend points, with the spread it came from.",
                fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
            Spacer(Modifier.height(10.dp))
            projections.forEach { (m, t, cur) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(VitalsStore.M.label(m), fontSize = T.small, color = T.inkSoft, modifier = Modifier.weight(1f))
                    Text("${VitalsStore.M.format(m, cur)} → ${VitalsStore.M.format(m, t.projected)}",
                        fontSize = T.small, color = T.ink)
                    Spacer(Modifier.width(8.dp))
                    Text("±${VitalsStore.M.format(m, t.band)}", fontSize = T.caption, color = T.inkFaint)
                }
            }
        }

        // What moves together — stated as association, never as cause.
        val links = remember(tick) { VitalsMath.links(ctx, present) }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("WHAT MOVES TOGETHER")
            Spacer(Modifier.height(8.dp))
            links.forEach { l ->
                Text("${VitalsStore.M.label(l.a)} and ${VitalsStore.M.label(l.b)} " +
                     (if (l.r > 0) "rise and fall together" else "move in opposite directions") +
                     " — an association across 90 days, not a cause.",
                    fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("ASK ABOUT YOUR HEALTH")
        Spacer(Modifier.height(4.dp))
        Text("Try: how did I sleep this week · is my HRV going up · should I train today · " +
             "what happens to my sleep when I travel",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(T.bgElevated)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (ask.isEmpty()) Text("Ask…", fontSize = T.small, color = T.inkFaint)
                BasicTextField(ask, { ask = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Text(if (thinking) "…" else "↑", fontSize = 17.sp, color = Color.White,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (ask.isBlank()) T.hairline else T.accent)
                    .clickable(enabled = !thinking && ask.isNotBlank()) {
                        val q = ask; ask = ""; thinking = true; answer = ""
                        scope.launch {
                            answer = withContext(Dispatchers.IO) {
                                try {
                                    AgentClient.answerWell(q, VitalsInsight.contextFor(ctx, q), emptyList())
                                } catch (e: Exception) { "Couldn't answer just now." }
                            }
                            thinking = false
                        }
                    }.padding(top = 9.dp))
        }
        if (answer.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(answer, fontSize = T.small, color = T.ink, lineHeight = 21.sp)
        }

        Spacer(Modifier.height(26.dp))
        Text("SlyOS is not a medical device. It describes your own numbers and never diagnoses.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
        Spacer(Modifier.height(40.dp))
    }

    detail?.let { m -> MetricSheet(ctx, m) { detail = null } }
}

/**
 * The ring: one number for "how much have you got today".
 *
 * Composed from recovery when a Whoop is writing one, and otherwise from sleep and HRV against the
 * owner's own baselines — never from a population range, and never shown at all when there is too
 * little history to mean anything.
 */
@Composable
private fun Readiness(ctx: android.content.Context, tick: Int) {
    val score = remember(tick) {
        VitalsStore.latest(ctx, VitalsStore.M.RECOVERY)?.value?.roundToInt() ?: run {
            val parts = ArrayList<Double>()
            listOf(VitalsStore.M.SLEEP, VitalsStore.M.HRV).forEach { m ->
                val s = VitalsStore.series(ctx, m, 90)
                if (s.size >= 7) {
                    val base = VitalsMath.baseline(s.dropLast(1)) ?: return@forEach
                    val sd = VitalsMath.sd(s.dropLast(1)) ?: return@forEach
                    if (sd < 1e-6) return@forEach
                    // Their own z, mapped onto 0..100 with 50 as "exactly your normal".
                    val z = ((s.last().value - base) / sd).coerceIn(-2.5, 2.5)
                    parts.add(50 + z * 20)
                }
            }
            if (parts.isEmpty()) null else (parts.sum() / parts.size).roundToInt().coerceIn(1, 99)
        }
    }
    if (score == null) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                drawArc(T.hairline, -90f, 360f, false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke))
                drawArc(T.accent, -90f, 360f * (score / 100f), false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Text("$score", fontSize = 30.sp, color = T.ink, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text("READINESS", fontSize = 10.sp, color = T.inkFaint,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    score >= 66 -> "More than your usual"
                    score >= 40 -> "About your usual"
                    else -> "Less than your usual"
                },
                fontSize = T.small, color = T.inkSoft)
            Spacer(Modifier.height(2.dp))
            Text("50 is exactly your own normal", fontSize = T.caption, color = T.inkFaint)
        }
    }
}

/** One metric: value, its own sparkline, and how far it is from this person's baseline. */
@Composable
private fun MetricRow(ctx: android.content.Context, m: String, tick: Int, onOpen: () -> Unit) {
    val s = remember(tick, m) { VitalsStore.series(ctx, m, 30) }
    if (s.isEmpty()) return
    val last = s.last().value
    val base = remember(tick, m) { VitalsMath.baseline(s.dropLast(1)) }
    val d = base?.let { last - it }

    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(VitalsStore.M.label(m), fontSize = T.small, color = T.inkSoft, modifier = Modifier.width(96.dp))
        Text(VitalsStore.M.format(m, last) + VitalsStore.M.unit(m),
            fontSize = T.small, color = T.ink, modifier = Modifier.width(78.dp))
        Spark(s.map { it.value }, Modifier.weight(1f).height(22.dp))
        Spacer(Modifier.width(10.dp))
        if (d != null && abs(d) > 0.01) {
            // GOOD IS NOT THE SAME AS UP. Resting heart rate rising is not HRV rising, and a page
            // that paints every increase green is wrong half the time.
            val better = VitalsStore.M.higherIsBetter(m)
            val col = when (better) {
                null -> T.inkSoft
                true -> if (d > 0) T.good else T.danger
                false -> if (d < 0) T.good else T.danger
            }
            Text(VitalsStore.M.formatDelta(m, d), fontSize = T.caption, color = col,
                modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
        } else Spacer(Modifier.width(58.dp))
    }
}

@Composable
private fun Spark(values: List<Double>, modifier: Modifier) {
    if (values.size < 2) { Spacer(modifier); return }
    Canvas(modifier) {
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val dx = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, T.accent.copy(alpha = 0.75f), style = Stroke(1.6.dp.toPx()))
    }
}

/** One metric in full: the windows, the spread, the day-of-week shape, and where it is heading. */
@Composable
private fun MetricSheet(ctx: android.content.Context, m: String, onClose: () -> Unit) {
    val s = remember(m) { VitalsStore.series(ctx, m, 90) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
            Text(VitalsStore.M.label(m), fontSize = 17.sp, color = T.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${s.size} days of your own readings", fontSize = T.caption, color = T.inkFaint)

            Spacer(Modifier.height(16.dp))
            Spark(s.map { it.value }, Modifier.fillMaxWidth().height(60.dp))

            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(16.dp))
                @Composable fun line(k: String, v: String) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                        Text(v, fontSize = T.caption, color = T.ink)
                    }
                }
                fun fmt(v: Double?) = v?.let { VitalsStore.M.format(m, it) + VitalsStore.M.unit(m) } ?: "—"
                line("7-day mean", fmt(VitalsMath.meanOfLast(s, 7)))
                line("30-day mean", fmt(VitalsMath.meanOfLast(s, 30)))
                line("90-day mean", fmt(VitalsMath.mean(s)))
                line("Your baseline", fmt(VitalsMath.baseline(s)))
                line("Spread (1 sd)", fmt(VitalsMath.sd(s)))
                VitalsMath.z(s)?.let {
                    line("Today, in your own terms",
                        if (abs(it) < 1) "within your normal range"
                        else String.format("%.1f sd %s your average", abs(it), if (it > 0) "above" else "below"))
                }
                VitalsMath.trend(s)?.let {
                    line("Where it's heading (30d)",
                        "${VitalsStore.M.format(m, it.projected)} ±${VitalsStore.M.format(m, it.band)}")
                }
                if (m == VitalsStore.M.SLEEP) {
                    VitalsMath.sleepDebt(s)?.let { d ->
                        Spacer(Modifier.height(10.dp))
                        Text("You sleep ${VitalsStore.M.format(m, d.needMinutes.toDouble())} on your better " +
                             "nights. Over the last fortnight you're ${d.minutes / 60}h short" +
                             (d.paybackDays?.let { ", which clears in about $it days at the last few nights' rate" } ?: "") + ".",
                            fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                    }
                }

                // Day of week — your Mondays are not your Saturdays.
                val byDay = remember(m) { VitalsMath.byWeekday(s) }
                if (byDay.size >= 5) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("BY DAY")
                    Spacer(Modifier.height(6.dp))
                    val names = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    (1..7).forEach { d ->
                        byDay[d]?.let { v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(names[d], fontSize = T.caption, color = T.inkFaint, modifier = Modifier.width(44.dp))
                                Text(VitalsStore.M.format(m, v) + VitalsStore.M.unit(m),
                                    fontSize = T.caption, color = T.inkSoft)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** The states before there is anything to show — each with the one thing that fixes it. */
@Composable
private fun NotConnected(state: VitalsSource.State, syncing: Boolean, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    Column {
        Text(
            when (state) {
                VitalsSource.State.NOT_INSTALLED -> "Health Connect isn't installed"
                VitalsSource.State.UNAVAILABLE -> "This phone doesn't have Health Connect"
                else -> "Connect your health data"
            },
            fontSize = T.body, color = T.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state) {
                VitalsSource.State.NOT_INSTALLED ->
                    "It's Google's own app, and it's where Whoop, Garmin, Fitbit and Samsung Health " +
                    "all put their numbers. Install it, then come back."
                VitalsSource.State.UNAVAILABLE ->
                    "Health Connect needs Android 9 or newer with Google Play services."
                else ->
                    "One connection reads Whoop, Garmin, Fitbit and Samsung Health, because they all " +
                    "write into Health Connect. Nothing leaves this phone."
            },
            fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)

        if (state != VitalsSource.State.UNAVAILABLE) {
            Spacer(Modifier.height(18.dp))
            Text(
                when {
                    syncing -> "Reading…"
                    state == VitalsSource.State.NOT_INSTALLED -> "Get Health Connect"
                    else -> "Connect Health Connect"
                },
                fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !syncing) {
                        if (state == VitalsSource.State.NOT_INSTALLED) {
                            try {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {}
                        } else onConnect()
                    }
                    .padding(vertical = 14.dp))
            Spacer(Modifier.height(10.dp))
            Text("Already connected but empty? " + VitalsSource.writerHint(),
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        }
    }
}
