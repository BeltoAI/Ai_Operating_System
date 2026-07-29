package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.VitalsMath
import com.agentos.shell.tools.VitalsStore

/**
 * The chart the health page is actually about.
 *
 * What was there before was a 22dp scratch with no axis, no baseline and no scale — it could not
 * answer "is this high for me?", which is the only question a health chart exists to answer. So this
 * draws four things, and each of them earns its place:
 *
 *  - **the series**, as a filled area, because the shape of a month is the point;
 *  - **your baseline**, as a dashed line across the whole width, so every point is read against it
 *    rather than against the top and bottom of whatever window happens to be showing;
 *  - **the projection**, as a widening cone rather than a line, because the spread is the honest
 *    part of a forecast and a single line hides it;
 *  - **the numbers at the edges**, so the vertical scale is not a mystery.
 *
 * Deliberately no gridlines and no legend. The brand is quiet, and a chart that needs a legend has
 * too much in it.
 */
@Composable
fun VitalsChart(
    metric: String,
    days: List<VitalsStore.Day>,
    modifier: Modifier = Modifier.fillMaxWidth().height(170.dp),
    showProjection: Boolean = true
) {
    if (days.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No readings yet", fontSize = T.caption, color = T.inkFaint)
        }
        return
    }

    val baseline = VitalsMath.baseline(days)
    val trend = if (showProjection) VitalsMath.trend(days, 30) else null

    // The vertical range covers the data, the baseline and the whole projection cone — a forecast
    // drawn off the top of its own chart is worse than no forecast.
    val lo0 = minOf(days.minOf { it.value },
        baseline ?: Double.MAX_VALUE,
        trend?.let { it.projected - it.band } ?: Double.MAX_VALUE)
    val hi0 = maxOf(days.maxOf { it.value },
        baseline ?: Double.MIN_VALUE,
        trend?.let { it.projected + it.band } ?: Double.MIN_VALUE)
    val pad = ((hi0 - lo0) * 0.12).takeIf { it > 1e-9 } ?: (kotlin.math.abs(hi0) * 0.1 + 1)
    // THE SCALE GLIDES; IT DOES NOT JUMP.
    //
    // Switching 30d → 90d changes both the points and the vertical range they are drawn in, and
    // swapping both at once is a cut — the eye has nothing to follow and has to re-read the whole
    // chart. Animating the axis means the old shape visibly re-scales into the new one, so the
    // change reads as "same series, wider view" rather than "different picture".
    val loT by animateFloatAsState((lo0 - pad).toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 190f), label = "lo")
    val hiT by animateFloatAsState((hi0 + pad).toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 190f), label = "hi")
    val lo = loT.toDouble()
    val hi = hiT.toDouble()
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

    // MOTION THAT IS CONTINUOUS, NOT STEPPED.
    //
    // The first attempt revealed the series one point at a time, so it arrived as a stutter of
    // discrete segments — a progress bar wearing a chart's clothes. This instead grows every point
    // from the flat baseline into its real value at once, on a spring: the shape RISES out of the
    // line it is measured against, which is also what the chart means. Nothing pops, nothing steps,
    // and a range change morphs rather than cuts.
    // Re-keyed on the window as well as the metric, so a range change REPLAYS the rise instead of
    // snapping to a finished chart — the new span forms in front of you.
    var shown by remember(metric, days.size) { mutableStateOf(false) }
    LaunchedEffect(metric, days.size) { shown = false; shown = true }
    val grow by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.82f,           // a touch of settle, no visible bounce
            stiffness = 220f),
        label = "chart")

    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(VitalsStore.M.format(metric, hi0), fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.weight(1f))
            if (trend != null) Text("projected →", fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(4.dp))

        Canvas(modifier) {
            // The projection occupies the last third when there is one, so the past keeps two
            // thirds of the width — the history is what you look at.
            val futureFrac = if (trend != null) 0.3f else 0f
            val histW = size.width * (1f - futureFrac)
            fun x(i: Int) = if (days.size == 1) histW / 2 else i * (histW / (days.size - 1))
            fun y(v: Double) = (size.height - ((v - lo) / span * size.height)).toFloat()

            // Your own baseline, across everything.
            baseline?.let { b ->
                drawLine(T.inkFaint.copy(alpha = 0.45f),
                    Offset(0f, y(b)), Offset(size.width, y(b)),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }

            // Every point eased from the baseline toward its real value — the whole shape rises
            // together instead of being drawn on left to right.
            val restY = baseline?.let { y(it) } ?: size.height
            fun ay(v: Double): Float {
                val target = y(v)
                return restY + (target - restY) * grow
            }

            // The series: filled, so a month reads as a shape rather than as a wire.
            if (days.size >= 2) {
                val line = Path().apply {
                    // A quadratic through the midpoints: the join at every reading is smooth, and a
                    // month of nights stops looking like a saw.
                    days.forEachIndexed { i, d ->
                        val px = x(i); val py = ay(d.value)
                        if (i == 0) moveTo(px, py)
                        else {
                            val prevX = x(i - 1); val prevY = ay(days[i - 1].value)
                            val midX = (prevX + px) / 2
                            quadraticBezierTo(prevX, prevY, midX, (prevY + py) / 2)
                            quadraticBezierTo(px, py, px, py)
                        }
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(days.size - 1), size.height)
                    lineTo(x(0), size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(
                    listOf(T.accent.copy(alpha = 0.28f), T.accent.copy(alpha = 0.02f))))
                drawPath(line, T.accent, style = Stroke(2.2.dp.toPx()))
            }
            // ── THE TREND THROUGH THE NOISE ──
            //
            // A rolling seven-day mean, drawn thin over the daily line. The daily series answers
            // "what happened"; this answers "which way is it going", and on ninety days of sleep
            // those are genuinely different questions — the raw line is a saw and the trend under
            // it can be moving steadily the other way.
            if (days.size >= 10) {
                val w = if (days.size > 45) 7 else 5
                val roll = days.indices.map { i ->
                    val from = (i - w + 1).coerceAtLeast(0)
                    days.subList(from, i + 1).sumOf { it.value } / (i - from + 1)
                }
                val trendPath = Path().apply {
                    roll.forEachIndexed { i, v ->
                        val px = x(i); val py = ay(v)
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                }
                drawPath(trendPath, T.ink.copy(alpha = 0.42f * grow),
                    style = Stroke(1.4.dp.toPx()))
            }

            // Points only on a short series — on ninety days they are noise, and the last one is
            // the only one anybody looks for.
            if (days.size <= 31) {
                days.forEachIndexed { i, d ->
                    drawCircle(T.accent.copy(alpha = 0.55f + 0.45f * grow),
                        radius = 2.4.dp.toPx(), center = Offset(x(i), ay(d.value)))
                }
            }
            days.lastOrNull()?.let { d ->
                // The current reading, haloed. It is the number in the headline, and the eye should
                // be able to find it on the line without counting.
                drawCircle(T.accent.copy(alpha = 0.18f * grow), radius = 8.dp.toPx(),
                    center = Offset(x(days.size - 1), ay(d.value)))
                drawCircle(T.accent, radius = 3.4.dp.toPx(),
                    center = Offset(x(days.size - 1), ay(d.value)))
            }

            // The forecast, as a cone opening from today to the band at 30 days.
            trend?.let { t ->
                val x0 = x(days.size - 1); val y0 = ay(days.last().value)
                val x1 = size.width; val yc = ay(t.projected)
                val cone = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x1, ay(t.projected + t.band))
                    lineTo(x1, ay(t.projected - t.band))
                    close()
                }
                // The forecast arrives after the history has drawn, which is also the order it
                // should be read in.
                val late = ((grow - 0.6f) / 0.4f).coerceIn(0f, 1f)
                drawPath(cone, T.accent.copy(alpha = 0.13f * late))
                drawLine(T.accent.copy(alpha = 0.6f * late), Offset(x0, y0), Offset(x1, yc),
                    strokeWidth = 1.6.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)))
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(VitalsStore.M.format(metric, lo0), fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.weight(1f))
            // Why 30d and 90d can look identical: there are only so many days. Said, rather than
            // left as a puzzle about whether the buttons work.
            Text((if (days.size >= 10) "— trend  ·  " else "") +
                "${days.size} day${if (days.size == 1) "" else "s"}" +
                (baseline?.let { "  ·  baseline ${VitalsStore.M.format(metric, it)}" } ?: ""),
                fontSize = T.caption, color = T.inkFaint)
        }
    }
}

/** Day-of-week averages as bars — your Mondays are not your Saturdays. */
@Composable
fun WeekdayBars(metric: String, days: List<VitalsStore.Day>, modifier: Modifier = Modifier.fillMaxWidth().height(84.dp)) {
    val byDay = VitalsMath.byWeekday(days)
    if (byDay.size < 3) return
    val names = listOf("", "S", "M", "T", "W", "T", "F", "S")
    val vals = (1..7).map { byDay[it] }
    val present = vals.filterNotNull()
    val lo = present.min(); val hi = present.max()
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

    Column {
        Canvas(modifier) {
            val gap = 10f
            val bw = (size.width - gap * 6) / 7
            vals.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                // Scaled within the person's own weekly range, not from zero: the interesting thing
                // is which days differ, and a zero-based bar chart of resting heart rates is seven
                // identical bars.
                val h = (((v - lo) / span) * (size.height - 10f) + 8f).toFloat()
                drawRoundRect(
                    T.accent.copy(alpha = 0.35f + 0.5f * ((v - lo) / span).toFloat()),
                    topLeft = Offset(i * (bw + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(bw, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            (1..7).forEach { d ->
                Text(names[d], fontSize = 9.sp, color = T.inkFaint,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
