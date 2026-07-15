package com.agentos.shell.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
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

// Office palette.
private val FLOOR_A = Color(0xFF6B4E2E); private val FLOOR_B = Color(0xFF614426)
private val WALL = Color(0xFF3A2C1E); private val BASEBOARD = Color(0xFF2A2016)
private val WINDOW_SKY = Color(0xFF7FB4D6); private val WINDOW_FR = Color(0xFF241A11)
private val DESK = Color(0xFF8A5A2E); private val MONITOR = Color(0xFF15140F); private val SCREEN = Color(0xFF4FC3D6)
private val MUG = Color(0xFFD0603A)
private val COUNTER = Color(0xFFB7BCC1); private val SINK = Color(0xFF5E6266); private val FRIDGE = Color(0xFFCBD0D4)
private val FRIDGE_H = Color(0xFF8A8E92); private val COFFEE = Color(0xFF241F19); private val COFFEE_RED = Color(0xFFE24B4A)
private val COUCH = Color(0xFF4E86B0); private val COUCH_BK = Color(0xFF37627F); private val TABLE = Color(0xFF7A4E2A)
private val RUG = Color(0xFF9C5566); private val POT = Color(0xFFB0623A); private val LEAF = Color(0xFF57955A)

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

    var hireOpen by remember { mutableStateOf(false) }
    var detailEmp by remember { mutableStateOf<EmployeeStore.Employee?>(null) }
    var connectEmp by remember { mutableStateOf<EmployeeStore.Employee?>(null) }
    var talker by remember { mutableStateOf(0) }
    var lastLines by remember { mutableStateOf<Map<String, EmployeeStore.LogLine?>>(emptyMap()) }
    LaunchedEffect(staff) { lastLines = withContext(Dispatchers.IO) { staff.associate { it.id to EmployeeStore.logFor(ctx, it.id, 1).firstOrNull() } } }
    LaunchedEffect(Unit) { while (true) { delay(3600); talker += 1 } }

    // ── THE OFFICE — the whole screen. Staff mill about; tap one for details; + to hire. ──────────────
    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(FLOOR_A)) {
            val s = maxWidth.value / 10f   // tile side (dp); the whole office is a 10-wide grid
            // ── Draw the room: floor, walls, window, desks, kitchen, meeting table, lounge ──
            Canvas(Modifier.fillMaxSize()) {
                val t = size.width / 10f
                val rows = (size.height / t).toInt() + 2
                fun rect(c: Float, r: Float, w: Float, h: Float, col: Color) = drawRect(col, Offset(c * t, r * t), Size(w * t, h * t))
                fun rr(c: Float, r: Float, w: Float, h: Float, col: Color, rad: Float = 0.3f) =
                    drawRoundRect(col, Offset(c * t, r * t), Size(w * t, h * t), CornerRadius(rad * t, rad * t))
                for (rr2 in 0 until rows) for (cc in 0 until 10)
                    rect(cc.toFloat(), rr2.toFloat(), 1f, 1f, if ((cc + rr2) % 2 == 0) FLOOR_A else FLOOR_B)
                for (cc in 0 until 10) { rect(cc.toFloat(), 0f, 1f, 1f, WALL); rect(cc.toFloat(), (rows - 1).toFloat(), 1f, 1f, WALL) }
                for (rr2 in 0 until rows) { rect(0f, rr2.toFloat(), 1f, 1f, WALL); rect(9f, rr2.toFloat(), 1f, 1f, WALL) }
                rect(0f, 1f, 10f, 0.18f, BASEBOARD)                                   // baseboard shadow
                rect(2f, 0.15f, 3f, 0.7f, WINDOW_SKY); rect(3.45f, 0.15f, 0.1f, 0.7f, WINDOW_FR)   // window + mullion
                // Desks (work zone).
                fun desk(c: Float) { rect(c, 2f, 1.7f, 0.75f, DESK); rect(c + 0.35f, 1.45f, 1f, 0.6f, MONITOR)
                    rect(c + 0.45f, 1.55f, 0.8f, 0.38f, SCREEN); rect(c + 1.4f, 2.15f, 0.28f, 0.32f, MUG) }
                desk(1f); desk(3.2f); desk(5.4f)
                // Kitchen (top-right).
                rect(6.5f, 2f, 1.6f, 0.75f, COUNTER); rect(6.8f, 2.15f, 0.55f, 0.45f, SINK)
                rect(8.05f, 1.4f, 0.85f, 1.35f, FRIDGE); rect(8.12f, 2.2f, 0.12f, 0.5f, FRIDGE_H); rect(8.05f, 1.95f, 0.85f, 0.06f, FRIDGE_H)
                rect(6.55f, 1.65f, 0.42f, 0.4f, COFFEE); rect(6.63f, 1.7f, 0.1f, 0.1f, COFFEE_RED)
                // Meeting table (centre).
                rr(3.3f, 5.6f, 3.4f, 1.5f, TABLE, 0.5f)
                // Lounge (bottom).
                rr(0.8f, 9.2f, 5.2f, 3f, RUG, 0.6f)
                rect(1.2f, 9.55f, 3.6f, 0.28f, COUCH_BK); rr(1.2f, 9.8f, 3.6f, 1.2f, COUCH, 0.35f)
                rr(2.1f, 11.2f, 1.5f, 0.6f, TABLE, 0.3f)
                rect(6.5f, 10f, 0.8f, 0.7f, POT); rect(6.35f, 9.1f, 1.1f, 1f, LEAF)
            }
            // ── Workers at valid stations (desks, meeting seats, lounge) — never on furniture ──
            val stations = listOf(1.55f to 2.95f, 3.75f to 2.95f, 5.95f to 2.95f, 6.6f to 5.7f, 3.1f to 5.7f, 2.2f to 10.3f, 4.1f to 10.3f)
            staff.forEachIndexed { i, e ->
                val st = stations[i % stations.size]
                val inf = rememberInfiniteTransition(label = "b$i")
                val bx by inf.animateFloat(-3f, 3f, infiniteRepeatable(tween(1400 + (i % 4) * 350), RepeatMode.Reverse), label = "bx$i")
                val talk = if (staff.isNotEmpty() && staff[talker % staff.size].id == e.id) lastLines[e.id] else null
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset((st.first * s + bx).dp, (st.second * s).dp).width((s * 1.6f).dp).clickable { detailEmp = e }) {
                    Box(Modifier.height((s * 1.1f).dp), contentAlignment = Alignment.BottomCenter) {
                        if (talk != null) Text(talk.line.take(48), fontSize = 9.sp, maxLines = 3, lineHeight = 11.sp,
                            color = if (talk.needsInput) T.danger else T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (talk.needsInput) T.danger.copy(alpha = 0.18f) else T.bgElevated).padding(6.dp))
                        else if (e.status == "needs_you") Text("!", fontSize = 13.sp, color = T.danger, fontWeight = FontWeight.Bold)
                    }
                    PixelPet(e.id, (s * 1.15f).toInt().coerceIn(34, 52))
                    Text(e.name, fontSize = 10.sp, color = Color(0xFFF2E9DC), maxLines = 1)
                }
            }
            if (staff.isEmpty())
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The office is ready", fontSize = 18.sp, color = Color(0xFFF2E9DC), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to hire your first employee. They'll take a desk and get to work.",
                        fontSize = T.small, color = Color(0xFFD8C6B0), textAlign = TextAlign.Center, lineHeight = 20.sp)
                }
        }
        // + to hire
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(58.dp).clip(CircleShape).background(T.accent)
            .clickable { hireOpen = true }, contentAlignment = Alignment.Center) {
            Text("+", fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (flash.isNotBlank()) Text(flash, fontSize = T.caption, color = T.accent, maxLines = 2,
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 8.dp))
    }

    // ── Hire ──
    if (hireOpen) Dialog(onDismissRequest = { hireOpen = false }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
            Text("BUILD ME AN EMPLOYEE THAT —", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg).padding(12.dp)) {
                if (hireText.isEmpty()) Text("keeps my inbox triaged and drafts replies", fontSize = 15.sp, color = T.inkFaint)
                BasicTextField(hireText, { hireText = it }, textStyle = TextStyle(color = T.ink, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            Text(if (busy) "Hiring…" else "Hire", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hireText.isBlank()) T.hairline else T.accent)
                    .clickable(enabled = !busy && hireText.isNotBlank()) { hire(); hireOpen = false }.padding(vertical = 13.dp))
        }
    }

    // ── Employee detail ──
    detailEmp?.let { e ->
        val log = remember(e.id, staff) { EmployeeStore.logFor(ctx, e.id, 10) }
        val needs = log.firstOrNull { it.needsInput }
        Dialog(onDismissRequest = { detailEmp = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(staffGrad(e.name))), contentAlignment = Alignment.Center) { PixelPet(e.id, 38) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.name} · ${e.role}", fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                        Text(if (e.intervalMin > 0) "runs every ${e.intervalMin} min" else "runs on demand", fontSize = T.caption, color = T.inkFaint)
                    }
                }
                if (needs != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(needs.line, fontSize = T.small, color = T.danger, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.danger.copy(alpha = 0.10f)).padding(12.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Let SlyOS set it up for me", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { connectEmp = e; detailEmp = null }.padding(vertical = 12.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("RECENT", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                LazyColumn(Modifier.weight(1f, false).heightIn(max = 220.dp)) {
                    items(log, key = { it.id }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(agoLabel(l.ts), fontSize = T.caption, color = T.inkFaint, modifier = Modifier.width(52.dp))
                            Text(l.line, fontSize = T.caption, color = if (l.needsInput) T.danger else T.inkSoft, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Run a shift", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { run(e); detailEmp = null }.padding(vertical = 12.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Fire", fontSize = T.small, color = T.danger, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(T.hairline).clickable { EmployeeStore.fire(ctx, e.id); detailEmp = null; refresh() }.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }

    // ── Set up a connection (the employee drives your phone, or you paste credentials) ──
    connectEmp?.let { e ->
        var svc by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { connectEmp = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("Set up what ${e.name} needs", fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Let SlyOS open the app or site and sign you in — or paste the details yourself.", fontSize = T.caption, color = T.inkFaint, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                Text("Let SlyOS log me in / set it up  →", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable {
                        com.agentos.shell.tools.ScreenAgent.start(ctx.applicationContext,
                            "Set up the tool/connection ${e.name} (${e.role}) needs to do this goal: \"${e.goal}\". Open the right app or website, help me sign in or register step by step, and stop to ask me for any credentials or codes you need.")
                        flash = "SlyOS is setting it up — follow along on your screen."; connectEmp = null
                    }.padding(vertical = 13.dp))
                Spacer(Modifier.height(16.dp))
                Text("OR PASTE IT", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(8.dp))
                @Composable fun field(hint: String, v: String, on: (String) -> Unit) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(T.bg).padding(11.dp)) {
                        if (v.isEmpty()) Text(hint, fontSize = 14.sp, color = T.inkFaint)
                        BasicTextField(v, on, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                    }
                }
                field("Service name (e.g. HubSpot)", svc) { svc = it }
                field("Web address (optional)", url) { url = it }
                field("API key / token (optional)", key) { key = it }
                Spacer(Modifier.height(12.dp))
                Text("Save connection", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (svc.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = svc.isNotBlank()) {
                            com.agentos.shell.tools.IntegrationStore.add(ctx, svc, url, key, "Added for ${e.name}")
                            EmployeeStore.log(ctx, e.id, "$svc is now connected — resuming.", false)
                            EmployeeStore.setStatus(ctx, e.id, "idle")
                            flash = "$svc connected ✓"; connectEmp = null; refresh()
                        }.padding(vertical = 13.dp))
            }
        }
    }
}
