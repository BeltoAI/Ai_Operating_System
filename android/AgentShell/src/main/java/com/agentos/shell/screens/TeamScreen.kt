package com.agentos.shell.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import kotlin.math.hypot
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
fun TeamPanel(modifier: Modifier = Modifier, onExit: () -> Unit = {}) {
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

    // ── THE OFFICE BUILDING — full-screen cutaway: rooms off a central hallway, doors, a shared lounge. ──
    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val uxDp = maxWidth.value / 128f; val uyDp = uxDp   // uniform pixels (no stretch)
            run {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / 128f; val bh = size.height / k   // building units tall so it fills the screen
                    fun c(v: Long) = Color(v)
                    fun rc(x: Float, y: Float, w: Float, h: Float, col: Color) = drawRect(col, Offset(x * k, y * k), Size(w * k, h * k))
                    fun ov(cx: Float, cy: Float, rx: Float, ry: Float, col: Color) = drawOval(col, Offset((cx - rx) * k, (cy - ry) * k), Size(rx * 2 * k, ry * 2 * k))
                    fun bxf(x: Float, y: Float, w: Float, h: Float, fill: Color, ol: Color, hi: Color? = null) { rc(x, y, w, h, ol); rc(x + 1, y + 1, w - 2, h - 2, fill); if (hi != null) rc(x + 1, y + 1, w - 2, 1f, hi) }
                    fun dt(x: Float, y: Float, col: Color) = rc(x, y, 1f, 1f, col)
                    val OUTc = c(0xFF211812); val DOORc = c(0xFF7A4E2A); val DOORF = c(0xFF3A2C1E); val HALL = c(0xFFB79CA6); val HALLC = c(0xFFC6AEB6)
                    rc(0f, 0f, 128f, bh, c(0xFF2A2016)); rc(3f, 3f, 122f, bh - 6f, OUTc)
                    // hallway
                    rc(52f, 4f, 24f, bh - 7f, HALL); rc(58f, 4f, 12f, bh - 7f, HALLC)
                    var yy = 8f; while (yy < bh - 3f) { rc(53f, yy, 22f, 0.7f, c(0xFFA98C97)); yy += 10f }
                    for (i in 0 until 5) { rc(55f, 60f + i * 2.2f, 18f, 2.2f, if (i % 2 == 0) c(0xFFC6AEB6) else c(0xFFA98C97)); rc(55f, 60f + i * 2.2f, 18f, 0.6f, c(0xFF8A6E7A)) }
                    bxf(54f, 120f, 6f, 12f, c(0xFFDDEAF2), c(0xFF9FB4C0)); rc(55f, 122f, 4f, 5f, c(0xFF7FB4D6)); dt(56f, 131f, c(0xFF4E86B0))   // cooler
                    bxf(66f, 150f, 8f, 5f, c(0xFF8A5A34), c(0xFF5A3A20), c(0xFFA9713C))                                                        // bench
                    rc(63f, 18f, 4f, 6f, c(0xFFC1743E)); ov(65f, 15f, 6f, 5f, c(0xFF5A9A5C)); rc(70f, 178f, 4f, 6f, c(0xFFC1743E)); ov(72f, 175f, 5f, 5f, c(0xFF57955A))
                    fun doorL(dy: Float) { rc(50f, dy, 3f, 16f, HALL); rc(50f, dy - 1, 3f, 1.5f, DOORF); rc(50f, dy + 15, 3f, 1.5f, DOORF); rc(52f, dy + 2, 1.5f, 12f, DOORc); rc(47f, dy + 4, 4f, 8f, c(0xFF9C7A86)) }
                    fun doorR(dy: Float) { rc(75f, dy, 3f, 16f, HALL); rc(75f, dy - 1, 3f, 1.5f, DOORF); rc(75f, dy + 15, 3f, 1.5f, DOORF); rc(75.5f, dy + 2, 1.5f, 12f, DOORc); rc(77f, dy + 4, 4f, 8f, c(0xFF9C7A86)) }
                    // DEV (F1 left)
                    run { val x = 4f; val y = 4f; val w = 48f; val h = 62f; rc(x, y, w, h, c(0xFF3A2C1E)); rc(x + 1, y + 1, w - 2, h - 2, c(0xFF26333E)); rc(x + 1, y + 1, w - 2, 16f, c(0xFF2E3E4A)); rc(x + 1, y + h - 8, w - 2, 7f, c(0xFF3B4A54))
                        bxf(x + 5, y + 28, 20f, 9f, c(0xFF7A4E2A), c(0xFF4A2E18), c(0xFF95633A)); bxf(x + 8, y + 20, 14f, 8f, c(0xFF15140F), OUTc); rc(x + 9, y + 21, 12f, 5f, c(0xFF7CE0A0)); for (i in 0 until 3) rc(x + 10, y + 22 + i * 1.4f, 7f, 0.8f, c(0xFF2B4A38))
                        bxf(x + 30, y + 14, 14f, 38f, c(0xFF20262B), c(0xFF10151A)); for (i in 0 until 6) { rc(x + 32, y + 17 + i * 5.5f, 10f, 3f, c(0xFF161B20)); dt(x + 40, y + 18 + i * 5.5f, if (i % 2 == 0) c(0xFF7CE0A0) else c(0xFFE0A24E)) }
                        rc(x + 3, y + 5, 12f, 8f, c(0xFFEDE7DB)); dt(x + 6, y + 8, c(0xFF4E86B0)); dt(x + 9, y + 10, c(0xFFC05B4A)); doorL(y + 40) }
                    // MEETING (F1 right)
                    run { val x = 78f; val y = 4f; val w = 46f; val h = 62f; rc(x, y, w, h, c(0xFF3A2C1E)); rc(x + 1, y + 1, w - 2, h - 2, c(0xFFEADFC9)); rc(x + 1, y + 20, w - 2, h - 21, c(0xFFC69A5E)); var s2 = y + 20; while (s2 < y + h) { rc(x + 1, s2, w - 2, 0.7f, c(0xFFA67C42)); s2 += 8f }
                        bxf(x + 16, y + 4, 24f, 13f, c(0xFF2E2A22), OUTc); rc(x + 17, y + 5, 22f, 11f, c(0xFFF3ECDD)); rc(x + 19, y + 8, 8f, 3f, c(0xFF4E86B0)); rc(x + 29, y + 7, 7f, 5f, c(0xFF5A9A5C))
                        ov(x + 23, y + 42, 17f, 10f, c(0xFF7A4E2A)); ov(x + 23, y + 41, 17f, 9f, c(0xFF8A5A32)); bxf(x + 12, y + 30, 6f, 5f, c(0xFF6E7B8A), c(0xFF49525C)); bxf(x + 30, y + 30, 6f, 5f, c(0xFF6E7B8A), c(0xFF49525C)); doorR(y + 40) }
                    // KITCHEN (F2 left)
                    run { val x = 4f; val y = 68f; val w = 48f; val h = 60f; rc(x, y, w, h, c(0xFF3A2C1E)); rc(x + 1, y + 1, w - 2, h - 2, c(0xFFD3E4DE)); for (a in 0 until 7) for (b in 0 until 5) if ((a + b) % 2 == 0) rc(x + 2 + a * 6.5f, y + 22 + b * 6.5f, 6.5f, 6.5f, c(0xFFE7EFEB))
                        bxf(x + 3, y + 4, 24f, 6f, c(0xFFC9BFAE), c(0xFFA98F63), c(0xFFDAD2C4)); bxf(x + 3, y + 28, 28f, 9f, c(0xFFDAD2C4), c(0xFFB4AA98), c(0xFFEAE3D6)); bxf(x + 8, y + 31, 7f, 4f, c(0xFF8F9598), c(0xFF5E6266))
                        bxf(x + 34, y + 20, 13f, 30f, c(0xFFEDE7DB), c(0xFFC9BFAE)); rc(x + 34, y + 34, 13f, 1f, c(0xFFC9BFAE)); rc(x + 45, y + 25, 1.4f, 5f, c(0xFFB4AA98)); dt(x + 37, y + 38, c(0xFFE0A24E))
                        bxf(x + 6, y + 22, 5f, 6f, c(0xFF4E6E7E), c(0xFF2E4652)); doorL(y + 38) }
                    // HR (F2 right)
                    run { val x = 78f; val y = 68f; val w = 46f; val h = 60f; rc(x, y, w, h, c(0xFF3A2C1E)); rc(x + 1, y + 1, w - 2, h - 2, c(0xFFE8DCC8)); rc(x + 1, y + 28, w - 2, h - 29, c(0xFFB98F53)); var s3 = y + 28; while (s3 < y + h) { rc(x + 1, s3, w - 2, 0.7f, c(0xFF9C7440)); s3 += 8f }
                        bxf(x + 5, y + 24, 20f, 9f, c(0xFFA9713C), c(0xFF6B4426), c(0xFFC08A50)); bxf(x + 8, y + 16, 12f, 7f, c(0xFF15140F), OUTc); rc(x + 9, y + 17, 10f, 4f, c(0xFF6FD0C4)); rc(x + 22, y + 27, 2.5f, 2.5f, c(0xFFD0603A))
                        bxf(x + 30, y + 20, 10f, 18f, c(0xFF7E8894), c(0xFF565E68)); for (i in 0 until 3) rc(x + 31, y + 23 + i * 5f, 8f, 3f, c(0xFF6B7581))
                        bxf(x + 5, y + 42, 22f, 9f, c(0xFFC05B4A), c(0xFF8A3A2E), c(0xFFD67A6C)); doorR(y + 38) }
                    // COMMON LOUNGE (F3 full)
                    run { val x = 4f; val y = 130f; val w = 120f; val h = (bh - 132f).coerceAtLeast(74f); rc(x, y, w, h, c(0xFF3A2C1E)); rc(x + 1, y + 1, w - 2, h - 2, c(0xFFF0E4CE)); rc(x + 1, y + 16, w - 2, h - 17, c(0xFFC69A5E)); var s4 = y + 16; while (s4 < y + h) { rc(x + 1, s4, w - 2, 0.7f, c(0xFFA67C42)); s4 += 8f }
                        ov(x + 34, y + 46, 32f, 20f, c(0xFF7FB6AE)); ov(x + 34, y + 46, 26f, 15f, c(0xFF8FC1B9))
                        rc(x + 8, y + 26, 48f, 4f, c(0xFF5E8F7C)); bxf(x + 8, y + 28, 48f, 15f, c(0xFF6FA58F), c(0xFF4E7A68), c(0xFF83B6A2)); for (i in 0 until 3) bxf(x + 12 + i * 15f, y + 30, 13f, 10f, c(0xFF83B6A2), c(0xFF5E8F7C))
                        bxf(x + 26, y + 50, 18f, 7f, c(0xFF7A4E2A), c(0xFF4E2E18), c(0xFF8A5A32)); dt(x + 33, y + 52, c(0xFFD0603A))
                        bxf(x + 66, y + 6, 12f, 10f, c(0xFF20262B), c(0xFF10151A)); rc(x + 67, y + 7, 10f, 8f, c(0xFF4FC3D6))
                        bxf(x + 84, y + 28, 26f, 40f, c(0xFF8A5A34), c(0xFF5A3A20)); for (i in 0 until 4) { val ry2 = y + 32 + i * 10f; rc(x + 85, ry2, 24f, 1f, c(0xFF6B4426)); val cols = listOf(c(0xFFC05B4A), c(0xFF5A9A5C), c(0xFF4E86B0), c(0xFFE0A24E), c(0xFF7FB6AE)); for (j in 0 until 5) rc(x + 86 + j * 4.5f, ry2 - 8, 3.4f, 8f, cols[(i + j) % 5]) }
                        bxf(x + 64, y + 34, 14f, 24f, c(0xFFC05B4A), c(0xFF7A2E24), c(0xFFE24B4A)); rc(x + 66, y + 37, 10f, 8f, c(0xFFF3D9A0)); dt(x + 68, y + 39, c(0xFFE24B4A))
                        rc(x + 56, y + 20, 4f, 7f, c(0xFFC1743E)); ov(x + 58, y + 16, 7f, 6f, c(0xFF5A9A5C)) }
                }
                // ── Living workers: they actually walk — out their door, down the hallway, into the lounge/kitchen, and back. ──
                val stations = listOf(18f to 50f, 100f to 44f, 24f to 96f, 96f to 96f, 40f to 180f, 68f to 152f, 64f to 168f)
                // room of a point: 0 dev, 1 meeting, 2 kitchen, 3 hr, 4 lounge, 5 hallway
                fun roomOf(x: Float, y: Float): Int = when {
                    y >= 130f -> 4
                    x < 52f && y < 66f -> 0
                    x > 76f && y < 66f -> 1
                    x < 52f -> 2
                    x > 76f -> 3
                    else -> 5
                }
                // where a room opens onto the hallway (door threshold)
                fun hallDoor(room: Int): Offset = when (room) {
                    0 -> Offset(54f, 48f); 1 -> Offset(74f, 48f); 2 -> Offset(54f, 114f); 3 -> Offset(74f, 114f); 4 -> Offset(64f, 132f); else -> Offset(64f, 100f)
                }
                // social spots workers wander to: cooler, lounge sofa, lounge rug, kitchen counter, hallway bench
                data class Spot(val x: Float, val y: Float, val room: Int, val line: String)
                val hangouts = listOf(
                    Spot(57f, 122f, 5, "grabbing water"),
                    Spot(34f, 172f, 4, "taking five"),
                    Spot(64f, 168f, 4, "on the couch"),
                    Spot(100f, 168f, 4, "checking the shelf"),
                    Spot(24f, 100f, 2, "coffee run"),
                    Spot(68f, 150f, 5, "stretching legs")
                )
                fun route(fx: Float, fy: Float, fromRoom: Int, tx: Float, ty: Float, toRoom: Int): List<Offset> {
                    val w = ArrayList<Offset>()
                    if (fromRoom != 5 && fromRoom != toRoom) w.add(hallDoor(fromRoom))          // step out to the hall
                    if (toRoom != 5 && toRoom != fromRoom) w.add(hallDoor(toRoom))               // arrive at the target's door
                    w.add(Offset(tx, ty))
                    return w
                }
                // live positions (plain map — only the crossing-detector reads it, no recomposition needed)
                val posMap = remember { HashMap<String, Offset>() }
                // when two workers pass close, both pop a short line for a moment
                val chat = remember { mutableStateMapOf<String, String>() }
                LaunchedEffect(staff.map { it.id }) {
                    if (staff.size < 2) return@LaunchedEffect
                    val lines = listOf("nice work", "how's it going?", "on it", "good call", "let's sync later", "almost there", "need a hand?", "morning!", "got it", "one sec")
                    while (true) {
                        delay(380)
                        val ids = staff.map { it.id }
                        for (a in ids.indices) for (b in a + 1 until ids.size) {
                            val pa = posMap[ids[a]] ?: continue; val pb = posMap[ids[b]] ?: continue
                            if (hypot(pa.x - pb.x, pa.y - pb.y) < 12f && chat[ids[a]] == null && chat[ids[b]] == null) {
                                chat[ids[a]] = lines.random(); chat[ids[b]] = lines.random()
                                val ia = ids[a]; val ib = ids[b]
                                launch { delay(2500); chat.remove(ia); chat.remove(ib) }
                            }
                        }
                    }
                }
                staff.forEachIndexed { i, e ->
                    val st = stations[i % stations.size]
                    val homeRoom = roomOf(st.first, st.second)
                    val pos = remember(e.id) { Animatable(Offset(st.first, st.second), Offset.VectorConverter) }
                    var saying by remember(e.id) { mutableStateOf<String?>(null) }
                    LaunchedEffect(e.id) {
                        val rnd = kotlin.random.Random(e.id.hashCode() * 31 + 7)
                        val speed = 0.015f + rnd.nextInt(0, 8) * 0.0015f   // each worker moves at their own pace
                        fun jit(o: Offset): Offset {
                            val jx = (rnd.nextFloat() - 0.5f) * 7f; val jy = (rnd.nextFloat() - 0.5f) * 7f
                            val nx = if (roomOf(o.x, o.y) == 5) (o.x + jx).coerceIn(54f, 74f) else o.x + jx
                            return Offset(nx, o.y + jy)
                        }
                        suspend fun walk(wps: List<Offset>) {
                            for (wp in wps) {
                                val d = hypot(wp.x - pos.value.x, wp.y - pos.value.y)
                                pos.animateTo(wp, tween((d / speed).toInt().coerceIn(300, 6000), easing = LinearEasing)) { posMap[e.id] = value }
                            }
                        }
                        delay(400L + i * 450L)
                        posMap[e.id] = pos.value
                        while (true) {
                            delay((2400L..8000L).random())
                            val hops = if (rnd.nextFloat() < 0.45f) 2 else 1   // sometimes a longer, meandering trip
                            repeat(hops) {
                                val dest = hangouts.random()
                                val r = pos.value
                                walk(route(r.x, r.y, roomOf(r.x, r.y), dest.x, dest.y, dest.room).map { jit(it) })
                                saying = dest.line
                                delay((1500L..3600L).random())
                                saying = null
                            }
                            val r2 = pos.value
                            walk(route(r2.x, r2.y, roomOf(r2.x, r2.y), st.first, st.second, homeRoom).map { jit(it) })
                        }
                    }
                    val inf = rememberInfiniteTransition(label = "b$i")
                    val bob by inf.animateFloat(-1.4f, 1.4f, infiniteRepeatable(tween(620 + (i % 4) * 120), RepeatMode.Reverse), label = "bx$i")
                    val ax = pos.value.x; val ay = pos.value.y
                    val talk: EmployeeStore.LogLine? = when {
                        chat[e.id] != null -> EmployeeStore.LogLine(0L, e.id, 0L, chat[e.id]!!, false)
                        lastLines[e.id]?.needsInput == true -> lastLines[e.id]
                        saying != null -> EmployeeStore.LogLine(0L, e.id, 0L, saying!!, false)
                        staff.isNotEmpty() && staff[talker % staff.size].id == e.id -> lastLines[e.id]
                        else -> null
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(((ax) * uxDp - 22).dp, ((ay + bob) * uyDp - 30).dp).width(44.dp).clickable { detailEmp = e }) {
                        if (talk != null) Text(talk.line.take(40), fontSize = 8.sp, maxLines = 2, lineHeight = 10.sp,
                            color = if (talk.needsInput) T.danger else T.ink,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (talk.needsInput) T.danger.copy(alpha = 0.2f) else T.bgElevated).padding(5.dp))
                        else if (e.status == "needs_you") Text("!", fontSize = 12.sp, color = T.danger, fontWeight = FontWeight.Bold)
                        PixelPet(e.id, 30)
                        Text(e.name.take(8), fontSize = 9.sp, color = Color(0xFF2C2620), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                if (staff.isEmpty())
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The office is open", fontSize = 18.sp, color = Color(0xFFF2E9DC), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap + to hire your first employee. They'll move into a room and get to work.",
                            fontSize = T.small, color = Color(0xFFE7D8C0), textAlign = TextAlign.Center, lineHeight = 20.sp)
                    }
            }
        }
        // back to Research — small and subtle, tucked in the corner
        Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(30.dp).clip(CircleShape).background(Color(0x99000000))
            .clickable { onExit() }, contentAlignment = Alignment.Center) { Text("←", fontSize = 15.sp, color = Color.White) }
        // + to hire — tidy FAB
        Box(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(50.dp).clip(CircleShape).background(T.accent)
            .clickable { hireOpen = true }, contentAlignment = Alignment.Center) {
            Text("+", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
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
