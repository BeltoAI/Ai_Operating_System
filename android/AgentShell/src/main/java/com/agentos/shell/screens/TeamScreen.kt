package com.agentos.shell.screens

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.EmployeeRunner
import com.agentos.shell.tools.EmployeeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STAFF_GRADS = listOf(
    Color(0xFF5E9A78) to Color(0xFF34614A), Color(0xFF7B5EA7) to Color(0xFF4A3570),
    Color(0xFF4E86B0) to Color(0xFF2B5675), Color(0xFFC9863F) to Color(0xFF8A5A22),
    Color(0xFFB0506A) to Color(0xFF7A2E45), Color(0xFFE8642C) to Color(0xFFB23A1E)
)
private fun staffGrad(seed: String): List<Color> {
    val p = STAFF_GRADS[((seed.hashCode() % STAFF_GRADS.size) + STAFF_GRADS.size) % STAFF_GRADS.size]
    return listOf(p.first, p.second)
}
private fun initials(name: String) = name.trim().split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("").ifBlank { "•" }

// Cute blocky pixel critters — one per employee, in the same drawRect pixel spirit as the running dog.
private val PET_BODY = listOf(Color(0xFFC9863F), Color(0xFF9A9085), Color(0xFFE8642C), Color(0xFFF4EFE6), Color(0xFF7B9E6B))
private val PET_DARK = Color(0xFF2C2620)

@Composable
private fun PixelPet(seed: String, sizeDp: Int = 40) {
    val species = ((seed.hashCode() % 5) + 5) % 5   // 0 dog · 1 cat · 2 fox · 3 bunny · 4 frog
    val body = PET_BODY[species]
    val inf = rememberInfiniteTransition(label = "pet")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(640), RepeatMode.Reverse), label = "bob")
    Canvas(Modifier.size(sizeDp.dp)) {
        val p = size.minDimension / 9f
        val oy = -bob * p * 0.5f
        fun px(c: Int, r: Int, col: Color = body) = drawRect(col, Offset(c * p, r * p + oy), Size(p + 0.6f, p + 0.6f))
        // Body block (rounded by dropping corners).
        for (c in 2..6) for (r in 4..6) if (!((c == 2 || c == 6) && r == 6)) px(c, r)
        // Head row.
        for (c in 3..5) px(c, 3)
        // Ears by species.
        when (species) {
            0 -> { px(3, 2); px(5, 2) }                              // dog: rounded ears
            1, 2 -> { px(2, 2); px(3, 3); px(6, 2); px(5, 3) }       // cat/fox: pointed ears
            3 -> { px(3, 1); px(3, 2); px(5, 1); px(5, 2) }          // bunny: tall ears
            else -> { px(3, 3); px(5, 3) }                           // frog: eyes on top
        }
        // Eyes + nose.
        px(3, 4, PET_DARK); px(5, 4, PET_DARK)
        px(4, 5, PET_DARK)
        // Feet.
        px(2, 7); px(6, 7)
        // Fox tail.
        if (species == 2) { px(7, 5); px(7, 4, PET_DARK) }
    }
}

/**
 * TEAM — your AI staff. Hire one by describing it; each works a shift on request, reports what it did, and
 * flags when it needs you. Lives as the 4th option inside the Research panel. Everything is logged to the brain.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TeamPanel(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var staff by remember { mutableStateOf(EmployeeStore.all(ctx)) }
    var activity by remember { mutableStateOf(EmployeeStore.recentActivity(ctx, 12)) }
    var hireText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("") }
    fun refresh() { staff = EmployeeStore.all(ctx); activity = EmployeeStore.recentActivity(ctx, 12) }

    fun hire() {
        val req = hireText.trim(); if (req.isBlank() || busy) return
        busy = true; flash = "Hiring…"; hireText = ""
        scope.launch {
            val cfg = withContext(Dispatchers.IO) { EmployeeRunner.draftFromRequest(req) }
            val name = cfg.optString("name").ifBlank { "Sam" }
            withContext(Dispatchers.IO) {
                EmployeeStore.hire(ctx, name, cfg.optString("role").ifBlank { "assistant" },
                    cfg.optString("goal").ifBlank { req }, cfg.optString("tools"),
                    cfg.optInt("interval_min", 0), false)
            }
            refresh(); flash = "Hired $name ✓"; busy = false
        }
    }
    fun run(emp: EmployeeStore.Employee) {
        if (busy) return
        busy = true; flash = "${emp.name} is working…"
        scope.launch {
            val did = withContext(Dispatchers.IO) { EmployeeRunner.runShift(ctx, emp) }
            refresh(); flash = "${emp.name}: $did"; busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        // Hire bar
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(14.dp)) {
            Text("BUILD ME AN EMPLOYEE THAT —", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (hireText.isEmpty()) Text("keeps my inbox triaged and drafts replies", fontSize = 15.sp, color = T.inkFaint)
                    BasicTextField(hireText, { hireText = it }, singleLine = true,
                        textStyle = TextStyle(color = T.ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(10.dp))
                Text(if (busy) "…" else "Hire", fontSize = T.small, color = if (busy) T.inkFaint else T.bgElevated, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (busy || hireText.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = !busy && hireText.isNotBlank()) { hire() }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (flash.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(flash, fontSize = T.caption, color = T.accent, maxLines = 2) }
        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (staff.isEmpty()) item {
                Text("No staff yet. Describe an employee above — e.g. \"sorts my expenses and flags weird charges\" — and I'll hire them.",
                    fontSize = T.small, color = T.inkFaint, lineHeight = 21.sp)
            }
            items(staff, key = { it.id }) { e ->
                val last = remember(e.id, activity) { EmployeeStore.logFor(ctx, e.id, 1).firstOrNull() }
                val grad = remember(e.name) { staffGrad(e.name) }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
                    .combinedClickable(onClick = { run(e) }, onLongClick = { EmployeeStore.fire(ctx, e.id); refresh() })
                    .padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(grad)),
                            contentAlignment = Alignment.Center) {
                            PixelPet(e.id, 38)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${e.name} · ${e.role}", fontSize = 15.sp, color = T.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            val statusTxt = when (e.status) { "working" -> "working…"; "needs_you" -> "needs you"; else -> if (e.intervalMin > 0) "every ${e.intervalMin} min" else "on demand" }
                            val statusCol = when (e.status) { "needs_you" -> T.danger; "working" -> T.accent; else -> T.inkFaint }
                            Text(statusTxt, fontSize = T.caption, color = statusCol)
                        }
                        Text("Run", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accentSoft.copy(alpha = 0.5f))
                                .clickable { run(e) }.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                    if (last != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(last.line, fontSize = T.small, color = if (last.needsInput) T.danger else T.inkSoft, lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (last.needsInput) T.danger.copy(alpha = 0.10f) else T.bg).padding(10.dp))
                    }
                }
            }
            if (activity.isNotEmpty()) {
                item { Spacer(Modifier.height(16.dp)); Text("TEAM ACTIVITY", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp) }
                items(activity, key = { "a" + it.id }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(agoLabel(a.ts), fontSize = T.caption, color = T.inkFaint, modifier = Modifier.width(56.dp))
                        Text(a.line, fontSize = T.caption, color = if (a.needsInput) T.danger else T.inkSoft, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
