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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
    // A metric gets a PAGE, not a dialog. Everything worth showing — the windows, the projection,
    // the day-of-week shape, what moves with it — was already being computed and then hidden behind
    // a tap into a box too small to hold it, which is exactly why the page looked empty.
    var openMetric by remember { mutableStateOf<String?>(null) }
    openMetric?.let { m ->
        MetricScreen(m, onBack = { openMetric = null }, modifier = modifier)
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tick by remember { mutableStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    // A tap on Connect used to do nothing observable — the system sheet appears a beat later, and
    // in between there is no way to tell a working button from a dead one.
    val haptics = LocalHapticFeedback.current

    // THE WHOLE HISTORY, FROM THE EXPORT WHOOP ALREADY OFFERS.
    //
    // Health Connect is the wrong tool for a strap that has been worn for months: it carries only
    // what Whoop pushes from the moment it starts pushing, and Whoop pushes no HRV, recovery or
    // strain into it at all. The export has all three, one row per day, going back to the beginning.
    val pickExport = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            syncing = true
            val r = withContext(Dispatchers.IO) { com.agentos.shell.tools.WhoopImport.importFrom(ctx, uri) }
            syncing = false; tick++
            note = when {
                r.error.isNotEmpty() -> r.error
                r.samples > 0 -> {
                    val f = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                    "Imported ${r.samples} readings across ${r.rows} days — " +
                        f.format(java.util.Date(r.from)) + " to " + f.format(java.util.Date(r.to)) + " ✓"
                }
                else -> "Nothing readable in that file."
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val present = remember(tick) { VitalsStore.present(ctx) }
    val headline = remember(tick) { if (present.isEmpty()) "" else VitalsInsight.headline(ctx) }
    val flags = remember(tick) { if (present.isEmpty()) emptyList() else VitalsInsight.flags(ctx) }
    val availability = remember(tick) { VitalsSource.availability(ctx) }
    var grantedSome by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { grantedSome = VitalsSource.grantedAny(ctx) }

    // The system permission sheet. Health Connect grants are per-record-type and the owner can give
    // a subset — which is fine and normal, so the result is not checked for completeness, only for
    // whether anything at all came back to read.
    val askPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        scope.launch {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (granted.isEmpty()) {
                note = "Nothing was granted, so there's nothing to read yet. Tap Connect again and " +
                       "allow the ones you're happy with."
                return@launch
            }
            syncing = true
            val n = withContext(Dispatchers.IO) { VitalsSource.sync(ctx, 90) }
            // WHOSE EMPTY IS IT? "SlyOS can't read" and "there is nothing there to read" look
            // identical to the owner and have completely different fixes, so ask Health Connect
            // whether it holds anything from ANY app before implying this is our end.
            val anyone = if (n == 0) withContext(Dispatchers.IO) { VitalsSource.anyDataAtAll(ctx) } else true
            syncing = false; tick++
            note = when {
                n > 0 -> "Pulled $n readings from the last 90 days."
                !anyone -> "Connected ✓ — but Health Connect itself is empty. Nothing has written " +
                    "to it yet, so every app reading it would show the same. " + VitalsSource.writerHint(ctx)
                else -> "Connected ✓ — Health Connect has data, but none of the kinds SlyOS reads. " +
                    "Check which types are shared: " + VitalsSource.writerHint(ctx)
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (n > 0) withContext(Dispatchers.IO) { VitalsInsight.rememberDays(ctx) }
        }
    }

    // A quiet sync on arrival, so the page is never showing yesterday because nobody pressed refresh.
    LaunchedEffect(Unit) {
        if (availability == VitalsSource.State.READY && VitalsSource.grantedAny(ctx)) {
            syncing = true
            val n = withContext(Dispatchers.IO) { VitalsSource.sync(ctx, 90) }
            syncing = false; tick++
            if (n > 0) withContext(Dispatchers.IO) { VitalsInsight.rememberDays(ctx) }
        }
        // The written week, at most once a week, into the brain — so a year from now "what was my
        // worst sleep month?" has something to answer from. A review that appears daily is a log,
        // and nobody rereads a log.
        withContext(Dispatchers.IO) { try { com.agentos.shell.tools.VitalsReview.weekly(ctx) } catch (e: Exception) {} }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Health", onBack)
        Spacer(Modifier.height(18.dp))

        // CONNECTED BUT EMPTY IS STILL THE PAGE.
        //
        // Showing a connect prompt INSTEAD of the page meant that until the first reading landed
        // there was nothing to look at and no way to tell what you were even setting up. The layout
        // is the promise; it should be visible while it fills. Only a phone with no connection at
        // all gets the bare version.
        val connected = remember(tick) {
            availability == VitalsSource.State.READY &&
                com.agentos.shell.tools.VitalsStore.count(ctx) >= 0 && grantedSome
        }
        if (present.isEmpty() && !connected) {
            NotConnected(
                availability, syncing,
                onImport = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        pickExport.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "*/*"))
                    } catch (e: Exception) { note = "Couldn't open the file picker." }
                }
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                note = "Opening Health Connect…"
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

        if (present.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(T.bgElevated).padding(14.dp)) {
                Text("Connected ✓ — waiting for your first readings",
                    fontSize = T.small, color = T.ink)
                Spacer(Modifier.height(4.dp))
                Text("Health Connect has nothing in it yet. Open WHOOP and let it sync, or bring " +
                     "your whole history in from a WHOOP export — that one also carries HRV, " +
                     "recovery and strain, which the live connection can't send.",
                    fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text(if (syncing) "Reading…" else "Import a WHOOP export",
                    fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                        .background(T.accent).clickable(enabled = !syncing) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                pickExport.launch(arrayOf("application/zip", "text/csv",
                                    "text/comma-separated-values", "*/*"))
                            } catch (e: Exception) { note = "Couldn't open the file picker." }
                        }.padding(vertical = 12.dp))
            }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(22.dp))
        }

        Readiness(ctx, tick)

        Spacer(Modifier.height(16.dp))
        if (headline.isNotEmpty()) Text(headline, fontSize = T.small, color = T.ink, lineHeight = 22.sp)

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

        // ── What is actually notable ──
        //
        // Computed, not generated. A model asked to comment on a table of numbers will eventually
        // round one or call a figure low, and this is the part read first and trusted most. Each
        // observation carries the figures it came from: "you sleep 52 minutes less on Sundays,
        // across 11 of them" is a fact; "your sleep is worse on Sundays" is a claim.
        val notes = remember(tick) { com.agentos.shell.tools.VitalsReview.notes(ctx) }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("WHAT STANDS OUT")
            Spacer(Modifier.height(10.dp))
            notes.forEach { n ->
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
                    .clickable { openMetric = n.metric }
                    .padding(14.dp)) {
                    Text(n.title, fontSize = T.small, color = T.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(n.detail, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                }
            }
        }

        // ── Goals ──
        val goals = remember(tick) { com.agentos.shell.tools.VitalsGoals.all(ctx) }
        if (goals.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("GOALS")
            Spacer(Modifier.height(10.dp))
            goals.forEach { g ->
                com.agentos.shell.tools.VitalsGoals.progress(ctx, g)?.let { pr ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp)).background(T.bgElevated)
                        .clickable { openMetric = g.metric }
                        .padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(VitalsStore.M.label(g.metric), fontSize = T.small, color = T.inkSoft,
                                modifier = Modifier.weight(1f))
                            Text(VitalsStore.M.format(g.metric, g.target) + VitalsStore.M.unit(g.metric),
                                fontSize = T.small, color = T.ink)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(com.agentos.shell.tools.VitalsGoals.sentence(ctx, pr),
                            fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            repeat(pr.ofDays) { i ->
                                val met = i >= pr.ofDays - pr.hitDays
                                Box(Modifier.padding(end = 4.dp).size(width = 13.dp, height = 5.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (met) T.good else T.hairline))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("TODAY, AGAINST YOUR OWN BASELINE")
        Spacer(Modifier.height(10.dp))
        // The full set while empty, so the shape of the page is visible before it fills.
        val shown = if (present.isNotEmpty()) present
                    else listOf(VitalsStore.M.HRV, VitalsStore.M.RHR, VitalsStore.M.SLEEP,
                                VitalsStore.M.RECOVERY, VitalsStore.M.STRAIN, VitalsStore.M.STEPS,
                                VitalsStore.M.RESP, VitalsStore.M.SPO2)
        shown.forEach { m -> MetricCard(ctx, m, tick) { openMetric = m } }

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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                drawArc(T.hairline, -90f, 360f, false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke))
                drawArc(T.accent, -90f, 360f * ((score ?: 0) / 100f), false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Text(score?.toString() ?: "—", fontSize = 30.sp,
                color = if (score == null) T.inkFaint else T.ink, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text("READINESS", fontSize = 10.sp, color = T.inkFaint,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    score == null -> "Waiting for readings"
                    score >= 66 -> "More than your usual"
                    score >= 40 -> "About your usual"
                    else -> "Less than your usual"
                },
                fontSize = T.small, color = T.inkSoft)
            Spacer(Modifier.height(2.dp))
            Text(if (score == null) "Needs about a week of nights"
                 else "50 is exactly your own normal",
                fontSize = T.caption, color = T.inkFaint)
        }
    }
}

/**
 * One metric as a card you can actually read: the number, how far it is from your own baseline, the
 * shape of the last month, and the averages that would otherwise need a tap to find.
 *
 * The previous version was a single 22dp line in a row — enough to say a number exists, not enough
 * to say anything about it.
 */
@Composable
private fun MetricCard(ctx: android.content.Context, m: String, tick: Int, onOpen: () -> Unit) {
    val s = remember(tick, m) { VitalsStore.series(ctx, m, 30) }
    val unit = VitalsStore.M.unit(m)

    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
            .clickable { onOpen() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(VitalsStore.M.label(m), fontSize = T.small, color = T.inkSoft,
                modifier = Modifier.weight(1f))
            Text("›", fontSize = T.body, color = T.inkFaint)
        }
        Spacer(Modifier.height(6.dp))

        if (s.isEmpty()) {
            Text("—", fontSize = 30.sp, color = T.inkFaint)
            Spacer(Modifier.height(4.dp))
            Text("waiting for readings", fontSize = T.caption, color = T.inkFaint)
            return@Column
        }

        val last = s.last().value
        val base = remember(tick, m) { VitalsMath.baseline(s.dropLast(1)) }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(VitalsStore.M.format(m, last), fontSize = 30.sp, color = T.ink,
                fontWeight = FontWeight.Medium)
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
            Spacer(Modifier.weight(1f))
            if (s.size < 7) {
                Text("${s.size} day${if (s.size == 1) "" else "s"} in",
                    fontSize = T.caption, color = T.inkFaint)
            } else base?.let {
                val d = last - it
                // GOOD IS NOT THE SAME AS UP. Resting heart rate rising is not HRV rising, and a
                // page that paints every increase green is wrong half the time.
                val better = VitalsStore.M.higherIsBetter(m)
                val col = when (better) {
                    null -> T.inkSoft
                    true -> if (d > 0) T.good else T.danger
                    false -> if (d < 0) T.good else T.danger
                }
                Text(VitalsStore.M.formatDelta(m, d), fontSize = T.small, color = col)
            }
        }

        if (s.size >= 2) {
            Spacer(Modifier.height(12.dp))
            VitalsChart(m, s, Modifier.fillMaxWidth().height(64.dp), showProjection = false)
        }

        // The averages, on the card. They were behind a tap, which is why the page read as though it
        // had computed nothing.
        if (s.size >= 3) {
            Spacer(Modifier.height(10.dp))
            Row {
                @Composable fun mini(k: String, v: Double?) {
                    Column(Modifier.weight(1f)) {
                        Text(k, fontSize = 9.sp, color = T.inkFaint,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text(v?.let { VitalsStore.M.format(m, it) } ?: "—",
                            fontSize = T.caption, color = T.inkSoft)
                    }
                }
                mini("7-DAY", VitalsMath.meanOfLast(s, 7))
                mini("30-DAY", VitalsMath.meanOfLast(s, 30))
                mini("BASELINE", base)
            }
        }
    }
}

@Composable
private fun Spark(values: List<Double>, modifier: Modifier) {
    if (values.isEmpty()) { Spacer(modifier); return }
    if (values.size < 4) {
        Canvas(modifier) {
            val dx = if (values.size == 1) size.width / 2 else size.width / (values.size - 1)
            values.indices.forEach { i ->
                drawCircle(T.accent.copy(alpha = 0.6f), radius = 2.2.dp.toPx(),
                    center = Offset(if (values.size == 1) dx else i * dx, size.height / 2))
            }
        }
        return
    }
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

/** The states before there is anything to show — each with the one thing that fixes it. */
@Composable
private fun NotConnected(
    state: VitalsSource.State,
    syncing: Boolean,
    onImport: () -> Unit,
    onConnect: () -> Unit
) {
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
            Text("Already connected but empty? " + VitalsSource.writerHint(ctx),
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        }

        // ── The history route, which the live connection cannot cover ──
        //
        // Offered with equal weight rather than as a fallback: for anyone who has worn the strap for
        // months this is the better source, and for a Whoop owner it is the ONLY source of HRV,
        // recovery and strain.
        Spacer(Modifier.height(26.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        Text("Bring your whole history", fontSize = T.body, color = T.ink)
        Spacer(Modifier.height(8.dp))
        Text(com.agentos.shell.tools.WhoopImport.howToGetIt(),
            fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        Text(if (syncing) "Reading…" else "Import a WHOOP export",
            fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                .background(T.bgElevated).clickable(enabled = !syncing) { onImport() }
                .padding(vertical = 14.dp))
    }
}
