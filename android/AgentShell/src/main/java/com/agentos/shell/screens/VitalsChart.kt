package com.agentos.shell.screens

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
    val lo = lo0 - pad
    val hi = hi0 + pad
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

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

            // The series: filled, so a month reads as a shape rather than as a wire.
            if (days.size >= 2) {
                val line = Path().apply {
                    days.forEachIndexed { i, d -> if (i == 0) moveTo(x(i), y(d.value)) else lineTo(x(i), y(d.value)) }
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
            // Every point, so a thin series is visibly thin.
            days.forEachIndexed { i, d ->
                drawCircle(T.accent, radius = if (days.size > 30) 1.4.dp.toPx() else 2.4.dp.toPx(),
                    center = Offset(x(i), y(d.value)))
            }

            // The forecast, as a cone opening from today to the band at 30 days.
            trend?.let { t ->
                val x0 = x(days.size - 1); val y0 = y(days.last().value)
                val x1 = size.width; val yc = y(t.projected)
                val cone = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x1, y(t.projected + t.band))
                    lineTo(x1, y(t.projected - t.band))
                    close()
                }
                drawPath(cone, T.accent.copy(alpha = 0.13f))
                drawLine(T.accent.copy(alpha = 0.6f), Offset(x0, y0), Offset(x1, yc),
                    strokeWidth = 1.6.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)))
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(VitalsStore.M.format(metric, lo0), fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.weight(1f))
            baseline?.let {
                Text("- - -  your baseline ${VitalsStore.M.format(metric, it)}",
                    fontSize = T.caption, color = T.inkFaint)
            }
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
