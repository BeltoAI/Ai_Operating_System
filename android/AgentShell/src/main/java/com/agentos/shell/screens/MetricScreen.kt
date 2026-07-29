package com.agentos.shell.screens

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.Brain
import com.agentos.shell.tools.VitalsInsight
import com.agentos.shell.tools.VitalsMath
import com.agentos.shell.tools.VitalsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One metric, in full.
 *
 * This was a cramped dialog with a list of numbers, which is why the page looked like it had nothing
 * in it — the averages, the projection, the day-of-week shape and the correlations were all being
 * computed and then hidden behind a tap into a box too small to show them. A metric people care
 * about deserves a page: the chart big enough to read, the windows side by side, where it is
 * heading with its band, which days differ, what moves with it, and somewhere to ask.
 *
 * Everything asked here goes into the brain, so a question asked once is answerable from Home
 * afterwards.
 */
@Composable
fun MetricScreen(metric: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var window by remember { mutableStateOf(30) }
    var ask by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val all = remember(metric) { VitalsStore.series(ctx, metric, 365) }
    val days = remember(metric, window) { all.takeLast(window) }
    val unit = VitalsStore.M.unit(metric)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader(VitalsStore.M.label(metric), onBack)

        if (all.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Nothing recorded for this yet.", fontSize = T.small, color = T.inkFaint)
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        // ── The number, and what it is against ──
        Spacer(Modifier.height(20.dp))
        val last = all.last().value
        val base = VitalsMath.baseline(all)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(VitalsStore.M.format(metric, last), fontSize = 44.sp, color = T.ink,
                fontWeight = FontWeight.Medium)
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(unit, fontSize = T.body, color = T.inkFaint, modifier = Modifier.padding(bottom = 7.dp))
            }
            Spacer(Modifier.weight(1f))
            base?.let {
                val d = last - it
                val better = VitalsStore.M.higherIsBetter(metric)
                val col = when (better) {
                    null -> T.inkSoft
                    true -> if (d > 0) T.good else T.danger
                    false -> if (d < 0) T.good else T.danger
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(VitalsStore.M.formatDelta(metric, d), fontSize = T.body, color = col)
                    Text("vs your baseline", fontSize = T.caption, color = T.inkFaint)
                }
            }
        }

        // ── The chart ──
        Spacer(Modifier.height(22.dp))
        VitalsChart(metric, days, showProjection = window >= 30)

        Spacer(Modifier.height(14.dp))
        Row {
            listOf(7, 30, 90, 365).forEach { w ->
                val on = window == w
                Text(if (w == 365) "1y" else "${w}d",
                    fontSize = T.caption, color = if (on) Color.White else T.inkSoft,
                    modifier = Modifier.padding(end = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) T.accent else T.bgElevated)
                        .clickable { window = w }
                        .padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }

        // ── The windows, side by side ──
        Spacer(Modifier.height(26.dp))
        SectionLabel("AVERAGES")
        Spacer(Modifier.height(10.dp))
        @Composable fun stat(k: String, v: String, hint: String = "") {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k, fontSize = T.small, color = T.inkSoft)
                    if (hint.isNotBlank()) Text(hint, fontSize = T.caption, color = T.inkFaint)
                }
                Text(v, fontSize = T.small, color = T.ink)
            }
        }
        fun f(v: Double?) = v?.let { VitalsStore.M.format(metric, it) + unit } ?: "—"
        stat("Last 7 days", f(VitalsMath.meanOfLast(all, 7)))
        stat("Last 30 days", f(VitalsMath.meanOfLast(all, 30)))
        stat("Last 90 days", f(VitalsMath.meanOfLast(all, 90)))
        stat("Your baseline", f(base), "weighted to the recent — this is what deltas are measured from")
        stat("Typical spread", f(VitalsMath.sd(all)), "one standard deviation of your own range")
        stat("Best / worst", f(all.maxOfOrNull { it.value }) + "  ·  " + f(all.minOfOrNull { it.value }))
        VitalsMath.z(all)?.let {
            stat("Today, in your terms",
                if (abs(it) < 1) "within your normal range"
                else String.format("%.1f sd %s average", abs(it), if (it > 0) "above" else "below"),
                "how unusual this is for you, not for anyone else")
        }

        // ── Where it is heading ──
        val trend = VitalsMath.trend(all, 30)
        Spacer(Modifier.height(26.dp))
        SectionLabel("PROJECTED")
        Spacer(Modifier.height(6.dp))
        if (trend == null) {
            Text("Needs about two weeks of readings before a trend means anything. " +
                 "${all.size} so far.",
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(VitalsStore.M.format(metric, trend.projected), fontSize = 28.sp, color = T.ink)
                Spacer(Modifier.width(6.dp))
                Text("± ${VitalsStore.M.format(metric, trend.band)}", fontSize = T.small,
                    color = T.inkFaint, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            val perWeek = trend.slopePerDay * 7
            Text("In 30 days, if nothing changes — moving " +
                 VitalsStore.M.formatDelta(metric, perWeek) + " a week. The band is the spread the " +
                 "line came from, and it is the honest part.",
                fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
        }

        // ── Sleep debt, where it applies ──
        if (metric == VitalsStore.M.SLEEP) {
            VitalsMath.sleepDebt(all)?.let { d ->
                Spacer(Modifier.height(26.dp))
                SectionLabel("SLEEP DEBT")
                Spacer(Modifier.height(8.dp))
                Text("On your better nights you sleep ${VitalsStore.M.format(metric, d.needMinutes.toDouble())}. " +
                     "Over the last fortnight you're ${d.minutes / 60}h ${d.minutes % 60}m short" +
                     (d.paybackDays?.let { ", which clears in about $it days at the last few nights' rate" }
                      ?: ", and the last few nights aren't paying it back") + ".",
                    fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
            }
        }

        // ── Which days differ ──
        if (all.size >= 14) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("BY DAY OF WEEK")
            Spacer(Modifier.height(4.dp))
            Text("Averaged across ${all.size} days. Scaled to your own range, so the differences show.",
                fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            WeekdayBars(metric, all)
        }

        // ── What moves with it ──
        val links = remember(metric) {
            VitalsMath.links(ctx, VitalsStore.present(ctx)).filter { it.a == metric || it.b == metric }
        }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionLabel("MOVES WITH")
            Spacer(Modifier.height(8.dp))
            links.forEach { l ->
                val other = if (l.a == metric) l.b else l.a
                Text("${VitalsStore.M.label(other)} — " +
                     (if (l.r > 0) "rises and falls with this" else "moves the opposite way") +
                     ". An association across 90 days, not a cause.",
                    fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        // ── Ask, about this metric ──
        Spacer(Modifier.height(26.dp))
        SectionLabel("ASK ABOUT YOUR ${VitalsStore.M.label(metric).uppercase()}")
        Spacer(Modifier.height(8.dp))
        Row {
            listOf("Why is it changing?", "Is this normal for me?", "What should I do?").forEach { q ->
                Text(q, fontSize = T.caption, color = T.ink,
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
                        .clickable(enabled = !thinking) { ask = q }
                        .padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
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
                        val q = "About my ${VitalsStore.M.label(metric)}: $ask"
                        ask = ""; thinking = true; answer = ""
                        scope.launch {
                            answer = withContext(Dispatchers.IO) {
                                try { AgentClient.answerWell(q, VitalsInsight.contextFor(ctx, q), emptyList()) }
                                catch (e: Exception) { "Couldn't answer just now." }
                            }
                            thinking = false
                            // INTO THE BRAIN. A question asked here should be answerable from Home
                            // tomorrow — otherwise every page is its own little island and the
                            // assistant knows less than its own screens do.
                            withContext(Dispatchers.IO) {
                                try {
                                    Brain.remember(ctx, "health_insight",
                                        "${VitalsStore.M.label(metric)} — asked",
                                        "Q: $q\nA: $answer", sensitivity = Brain.Sensitivity.SENSITIVE)
                                } catch (e: Exception) {}
                            }
                        }
                    }.padding(top = 9.dp))
        }
        if (answer.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(answer, fontSize = T.small, color = T.ink, lineHeight = 21.sp)
        }

        Spacer(Modifier.height(24.dp))
        Text("Compared against your own history, never a population range. " +
             "SlyOS is not a medical device.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
        Spacer(Modifier.height(50.dp))
    }
}
