package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.FlowBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The flow results as a matrix, because a list of failures answers the wrong question.
 *
 * A column of red lines tells you *that* something broke. What you need before shipping is *where*:
 * whether the calendar is solid and email is not, whether a model is fine on ordinary input and
 * improvises the moment anything is ambiguous. That shape is only visible when everything is on
 * screen at once.
 *
 * So: three bars per model for the three kinds of input, a per-flow grid of cells you can read in
 * one glance, and the detail only when a cell is tapped. Green and red are load-bearing here rather
 * than decorative — this is the one screen in SlyOS where the whole point is spotting the odd one
 * out.
 */
@Composable
fun FlowBenchCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val report = remember(tick) { FlowBench.last(ctx) }

    Collapsible("Google flows, stress-tested", "Ideal, awkward and hostile input — Claude vs Groq",
        keywords = "flow test google calendar email meet stress scenarios break edge cases bench") {

        Text("${FlowBench.SCENARIOS.size} real scenarios through the actual planning path — the ones " +
             "that already broke for someone: two people with the same name, a name with no address, " +
             "a time that has passed, a request whose first half failed, the same email twice. " +
             "Nothing is executed.",
            fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)

        report?.let { r ->
            // ── The three numbers that matter, per model ──
            Spacer(Modifier.height(16.dp))
            FlowBench.PROVIDERS.filter { p -> r.results.any { it.provider == p } }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(FlowBench.label(p), fontSize = T.small, color = T.ink,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${r.medianMs(p)}ms", fontSize = T.caption, color = T.inkFaint)
                }
                Spacer(Modifier.height(8.dp))
                listOf(
                    FlowBench.Kind.IDEAL to "ordinary",
                    FlowBench.Kind.AWKWARD to "vague or partial",
                    FlowBench.Kind.HOSTILE to "designed to break it"
                ).forEach { (kind, name) ->
                    val pct = r.rate(p, kind)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = T.caption, color = T.inkSoft,
                            modifier = Modifier.width(112.dp))
                        val grow by animateFloatAsState(pct / 100f,
                            spring(dampingRatio = 0.85f, stiffness = 180f), label = "bar")
                        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(999.dp))
                            .background(T.hairline)) {
                            Box(Modifier.fillMaxWidth(grow).height(7.dp)
                                .clip(RoundedCornerShape(999.dp))
                                // Colour by how bad it is, not by which bar it is. 100% is the only
                                // acceptable number on the hostile row and it should look like it.
                                .background(when {
                                    pct == 100 -> T.good
                                    pct >= 70 -> T.accent
                                    else -> T.danger
                                }))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("$pct%", fontSize = T.caption, color = T.inkSoft,
                            modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── The grid: every scenario against every model ──
            Spacer(Modifier.height(4.dp))
            SectionLabel("BY FLOW")
            Spacer(Modifier.height(8.dp))
            FlowBench.GROUPS.forEach { group ->
                val inGroup = FlowBench.SCENARIOS.filter { it.group == group }
                if (inGroup.isEmpty()) return@forEach
                Text(group, fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                inGroup.forEach { sc ->
                    Row(Modifier.fillMaxWidth().clickable { open = if (open == sc.id) null else sc.id }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The kind, as a single letter — the mix is the point and it should be
                        // visible without a legend at the bottom.
                        Text(when (sc.kind) {
                            FlowBench.Kind.IDEAL -> "·"
                            FlowBench.Kind.AWKWARD -> "~"
                            FlowBench.Kind.HOSTILE -> "!"
                        }, fontSize = T.caption,
                            color = if (sc.kind == FlowBench.Kind.HOSTILE) T.danger else T.inkFaint,
                            modifier = Modifier.width(14.dp))
                        Text(sc.prompt.take(38) + if (sc.prompt.length > 38) "…" else "",
                            fontSize = T.caption, color = T.inkSoft, modifier = Modifier.weight(1f))
                        FlowBench.PROVIDERS.forEach { p ->
                            val res = r.results.firstOrNull { it.provider == p && it.scenarioId == sc.id }
                            Box(Modifier.padding(start = 6.dp).size(width = 26.dp, height = 16.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(when {
                                    res == null -> T.hairline
                                    res.passed -> T.good.copy(alpha = 0.85f)
                                    else -> T.danger.copy(alpha = 0.85f)
                                }))
                        }
                    }
                    // Tapping a row opens what was expected and what each model actually did —
                    // a percentage nobody can act on becomes a specific thing to fix.
                    if (open == sc.id) {
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 8.dp)) {
                            Text("wants: ${sc.expect}", fontSize = T.caption, color = T.inkFaint,
                                lineHeight = 16.sp)
                            FlowBench.PROVIDERS.forEach { p ->
                                r.results.firstOrNull { it.provider == p && it.scenarioId == sc.id }?.let { res ->
                                    Text("${FlowBench.label(p)}: ${res.detail}",
                                        fontSize = T.caption,
                                        color = if (res.passed) T.inkSoft else T.danger,
                                        lineHeight = 16.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(FlowBench.verdict(r), fontSize = T.caption, color = T.inkSoft, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text("·  ordinary    ~  vague or partial    !  designed to break it   ·   run " +
                java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(r.at)),
                fontSize = T.caption, color = T.inkFaint, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(14.dp))
        Text(if (running) step.ifBlank { "Running…" } else "Run the flow test",
            fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent)
                .clickable(enabled = !running) {
                    running = true; step = ""
                    scope.launch {
                        withContext(Dispatchers.IO) { FlowBench.run(ctx) { s -> step = s } }
                        running = false; tick++
                    }
                }.padding(vertical = 12.dp))
        Spacer(Modifier.height(6.dp))
        Text("${FlowBench.SCENARIOS.size * FlowBench.PROVIDERS.size} planning calls, no execution — " +
             "nothing is sent, booked or created.",
            fontSize = T.caption, color = T.inkFaint)
    }
}
