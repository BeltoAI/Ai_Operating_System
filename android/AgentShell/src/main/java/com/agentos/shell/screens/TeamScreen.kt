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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

/** Ready-to-hire roles shown under the "+", so hiring is one tap and every preset is genuinely useful. */
data class Preset(val label: String, val desc: String, val name: String, val role: String, val goal: String, val tools: String, val interval: Int)
val TEAM_PRESETS = listOf(
    Preset("Inbox triager", "Sorts mail, drafts replies", "Maya", "Inbox Manager",
        "Keep my inbox triaged: surface what's urgent and draft replies I can approve.", "email", 30),
    Preset("Research analyst", "Daily brief on my field", "Rana", "Research Analyst",
        "Track news about my field and competitors and give me a short, specific daily brief.", "web", 720),
    Preset("Chief of staff", "Your top 3 each morning", "Leo", "Chief of Staff",
        "Read my notes and every morning tell me my top 3 priorities for the day.", "brain", 720),
    Preset("Bookkeeper", "Files + flags expenses", "Nia", "Bookkeeper",
        "Review my receipts and expenses, categorize them, and flag anything unusual.", "expenses", 1440),
    Preset("Calendar keeper", "Guards + preps your day", "Kai", "Calendar Manager",
        "Keep my calendar clean, catch conflicts, and prep a short agenda before meetings.", "calendar", 30),
    Preset("Deep expert", "Feed him PDFs; he masters them", "Bastardi", "Deep Expert",
        "You are the owner's deep expert. The documents the owner feeds you are your PRIMARY source of truth — master them and answer from them first, then the owner's brain, then live web search (including their published papers). Be precise, technical, and concrete; cite what you found and never fabricate. If the documents don't cover something, say so before reasoning from the web.", "knowledge,web,brain", 0),
    Preset("Designer", "VC-grade decks & one-pagers", "Vera", "Design Lead",
        "You are a genius founder-CEO with world-class design taste. Everything you make is minimal, sharp, and on-point: active voice, zero filler, no fluff words — every line earns its place. You judge and build exactly the way a top VC or a demanding customer would want, so they're genuinely impressed. When asked for a deck/one-pager/document, research the person or topic first (web + my brain + my CRM + any example templates I've fed you), then design a stunning PDF, save it to my SlyOS folder, send it into our chat for review, and iterate on my edits until it's flawless. Match my company's voice and any template style I've given you; never invent facts.", "web,files,brain", 0),
    Preset("Full-stack dev", "Ships web apps end-to-end", "Dex", "Full-Stack Engineer",
        "You are a senior full-stack engineer who ships production web apps end-to-end, entirely through this chat. When the owner (or anyone in the chat, on the owner's behalf) asks for a site or app: (1) gather concrete requirements first — audience, pages, features, data, look & feel; (2) design a stunning, responsive frontend and a working backend; (3) target Vercel for hosting + Supabase for the database/auth/storage; (4) send back a live URL (or the complete, ready-to-deploy code + exact deploy steps) right here; (5) go back and forth on feedback until the owner or their customer is genuinely happy. Write clean, secure, production-grade code — never fake a deployment or a URL. If you're missing something you truly need to deploy (a Vercel token, a Supabase project URL + keys, a domain), say exactly what's missing in one line and, meanwhile, build everything that doesn't require it so nothing waits. Prefer the simplest stack that fully satisfies the ask.", "web,files,brain", 0))

private val NAME_POOL = listOf(
    "Maya", "Leo", "Nova", "Kai", "Ivy", "Rex", "Zoe", "Milo", "Luna", "Finn",
    "Ada", "Sol", "Remy", "Juno", "Wren", "Cleo", "Ash", "Nia", "Theo", "Iris",
    "Otto", "Sage", "Bruno", "Elle", "Dax", "Vera", "Hugo", "Mira", "Enzo", "Pia")
/** Never ship a second "Alex": keep the drafted name if it's free, else pull a distinct one from the pool. */
private fun uniqueName(drafted: String, taken: List<String>): String {
    val t = taken.map { it.trim().lowercase() }.toSet()
    val d = drafted.trim()
    if (d.isNotBlank() && d.lowercase() !in t) return d
    NAME_POOL.firstOrNull { it.lowercase() !in t }?.let { return it }
    var n = 2; val base = d.ifBlank { "Sam" }
    while ("${base.lowercase()} $n" in t) n++
    return "$base $n"
}

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
            val name = uniqueName(cfg.optString("name").ifBlank { "Sam" }, staff.map { it.name })
            withContext(Dispatchers.IO) {
                EmployeeStore.hire(ctx, name, cfg.optString("role").ifBlank { "assistant" },
                    cfg.optString("goal").ifBlank { req }, cfg.optString("tools"),
                    cfg.optInt("interval_min", 0), false)
            }
            refresh(); flash = "Hired $name ✓"; busy = false
        }
    }
    fun hirePreset(p: Preset) {
        if (busy) return
        busy = true; flash = "Hiring ${p.name}…"
        scope.launch {
            val nm = uniqueName(p.name, staff.map { it.name })
            withContext(Dispatchers.IO) { EmployeeStore.hire(ctx, nm, p.role, p.goal, p.tools, p.interval, true) }
            refresh(); flash = "Hired $nm — ${p.role} ✓"; busy = false
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
    // Opened from a team notification → jump straight to that agent's card.
    LaunchedEffect(Unit) {
        com.agentos.shell.tools.TeamInbox.openEmpId?.let { id ->
            EmployeeStore.get(ctx, id)?.let { detailEmp = it }
            com.agentos.shell.tools.TeamInbox.openEmpId = null
        }
    }

    var teamText by remember { mutableStateOf("") }
    var teamReply by remember { mutableStateOf<String?>(null) }
    fun teamAsk() {
        val ask = teamText.trim(); if (ask.isBlank() || busy) return
        busy = true; teamText = ""; flash = "Asking the team…"
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val owner = com.agentos.shell.tools.MemoryStore.ownerName(ctx).ifBlank { "the owner" }
                val roster = staff.joinToString("; ") { "${it.name} (${it.role}): ${it.goal}" }.ifBlank { "no employees yet" }
                val caps = try { com.agentos.shell.tools.Capabilities.summary(ctx) } catch (e: Exception) { "" }
                val sys = "You coordinate $owner's AI team. Roster: $roster. $caps " +
                    "Reply to $owner in under 40 words: name which teammate takes this and their first concrete step. Plain text, no fluff."
                val r = try { com.agentos.shell.tools.AgentClient.complete(sys, ask, 200) } catch (e: Exception) { "Couldn't reach the team right now." }
                try { com.agentos.shell.tools.MemoryLog.add(ctx, "note", "Team ask", "You: $ask\nTeam: $r", "Team") } catch (e: Exception) {}
                r
            }
            teamReply = reply.ifBlank { "Couldn't reach the team right now." }; flash = ""; busy = false
        }
    }
    fun askAgent(e: EmployeeStore.Employee, q: String) {
        if (q.isBlank() || busy) return
        busy = true; flash = "${e.name} is thinking…"
        scope.launch {
            // Same full-capability engine as the Telegram chat: fed knowledge (PDFs) + brain + web + real actions.
            val reply = withContext(Dispatchers.IO) {
                val r = try { com.agentos.shell.tools.EmployeeRunner.answer(ctx, e, q) } catch (ex: Exception) { "" }
                if (e.status == "needs_you") try { EmployeeStore.setStatus(ctx, e.id, "idle") } catch (ex: Exception) {}
                r
            }
            teamReply = "${e.name}: " + reply.ifBlank { "Couldn't answer just now." }; flash = ""; refresh(); busy = false
        }
    }

    // ── THE OFFICE BUILDING — full-screen cutaway: rooms off a central hallway, doors, a shared lounge. ──
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
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
                        Text(e.name.take(9), fontSize = 9.sp, color = Color(0xFF2C2620), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(e.role.take(16), fontSize = 7.sp, color = Color(0xFF6E5F4C), maxLines = 1, lineHeight = 8.sp)
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
            // overlays scoped to the office area
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(30.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable { onExit() }, contentAlignment = Alignment.Center) { Text("←", fontSize = 15.sp, color = Color.White) }
            Box(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(50.dp).clip(CircleShape).background(T.accent)
                .clickable { hireOpen = true }, contentAlignment = Alignment.Center) {
                Text("+", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            if (flash.isNotBlank()) Text(flash, fontSize = T.caption, color = T.accent, maxLines = 3,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        // ── Telegram team chat toggle: you + agents + humans in one group ──
        var tgOn by remember { mutableStateOf(com.agentos.shell.tools.TeamChat.enabled(ctx)) }
        Row(Modifier.fillMaxWidth().background(T.bg).clickable {
            if (!tgOn) {
                if (!com.agentos.shell.tools.TelegramClient.configured()) flash = "Add a Telegram bot token in Settings first."
                else { com.agentos.shell.tools.TeamChat.setEnabled(ctx, true); tgOn = true; try { com.agentos.shell.TelegramService.start(ctx.applicationContext) } catch (e: Exception) {}; flash = "Team chat on — add your SlyOS bot to a Telegram group and send a message there to link it." }
            } else { com.agentos.shell.tools.TeamChat.setEnabled(ctx, false); tgOn = false; flash = "Telegram team chat off." }
        }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (!tgOn) "Connect a Telegram team chat"
                else if (com.agentos.shell.tools.TeamChat.isConnected(ctx)) "Telegram team chat · linked"
                else "Telegram team chat · add the bot to a group",
                fontSize = 11.sp, color = if (tgOn) T.good else T.inkFaint, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (tgOn) "on" else "off", fontSize = 11.sp, color = if (tgOn) T.good else T.inkFaint, fontWeight = FontWeight.Bold)
        }
        // ── ready-for-you strip: agents surface finished/blocked items here — tap to open & confirm ──
        val pending = staff.filter { it.status == "needs_you" }
        if (pending.isNotEmpty()) Row(Modifier.fillMaxWidth().background(T.accent.copy(alpha = 0.16f))
            .clickable { detailEmp = pending.first() }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(T.accent))
            Spacer(Modifier.width(8.dp))
            Text(if (pending.size == 1) "${pending.first().name} has something for you" else "${pending.size} agents need your eyes",
                fontSize = 12.sp, color = T.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text("Review →", fontSize = 12.sp, color = T.accent, fontWeight = FontWeight.SemiBold)
        }
        // ── talk to your team — the office's line to you ──
        Row(Modifier.fillMaxWidth().background(T.bg).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(T.bgElevated).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (teamText.isEmpty()) Text("Ask your team to do something…", fontSize = 14.sp, color = T.inkFaint)
                BasicTextField(teamText, { teamText = it }, textStyle = TextStyle(color = T.ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (teamText.isBlank()) T.hairline else T.accent)
                .clickable(enabled = !busy && teamText.isNotBlank()) { teamAsk() }, contentAlignment = Alignment.Center) {
                Text("↑", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        }
    }

    // ── Hire — ready-made roles up top, or describe your own ──
    if (hireOpen) Dialog(onDismissRequest = { hireOpen = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("HIRE A TEAMMATE", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(4.dp))
            Text("Tap a role to hire instantly — or describe your own below.", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(12.dp))
            TEAM_PRESETS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(T.bg)
                            .clickable(enabled = !busy) { hireOpen = false; hirePreset(p) }.padding(11.dp)) {
                            Text(p.label, fontSize = 13.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(p.desc, fontSize = 11.sp, color = T.inkFaint, lineHeight = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(if (p.interval >= 1440) "daily" else "every ${p.interval}m", fontSize = 9.sp, color = T.good, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("OR DESCRIBE YOUR OWN", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg).padding(12.dp)) {
                if (hireText.isEmpty()) Text("grows my Reddit presence and drafts human posts", fontSize = 15.sp, color = T.inkFaint)
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
            var askText by remember(e.id) { mutableStateOf("") }
            fun approveDone() {
                EmployeeStore.log(ctx, e.id, "You approved — marked done.", false)
                EmployeeStore.setStatus(ctx, e.id, "idle")
                com.agentos.shell.tools.EmployeeStats.approve(ctx, e.id)
                flash = "Approved ✓"; detailEmp = null; refresh()
            }
            val draftObj = com.agentos.shell.tools.AgentDraft.get(ctx, e.id)
            fun copyAndOpenReddit() {
                val text = draftObj?.text ?: (needs?.line ?: "")
                val title = draftObj?.title ?: ""
                try {
                    // Body onto the clipboard as a safety net; title+body pre-filled via the submit URL params.
                    val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("SlyOS post", text))
                    val sub = (draftObj?.target ?: "").let { Regex("r/([A-Za-z0-9_]+)").find(it)?.groupValues?.get(1) }
                        ?: Regex("r/([A-Za-z0-9_]+)").find(needs?.line ?: "")?.groupValues?.get(1)
                    val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
                    val url = if (sub != null) {
                        val base = "https://www.reddit.com/r/$sub/submit"
                        if (title.isNotBlank()) "$base?title=${enc(title)}&text=${enc(text)}" else base
                    } else "https://www.reddit.com"
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (ex: Exception) {}
                com.agentos.shell.tools.AgentDraft.clear(ctx, e.id)
                EmployeeStore.log(ctx, e.id, "Copied the post and opened Reddit.", false)
                EmployeeStore.setStatus(ctx, e.id, "idle"); com.agentos.shell.tools.EmployeeStats.approve(ctx, e.id)
                flash = if (title.isNotBlank()) "Opening Reddit with your title + post" else "Copied — paste it into Reddit"; detailEmp = null; refresh()
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(staffGrad(e.name))), contentAlignment = Alignment.Center) { PixelPet(e.id, 38) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.name} · ${e.role}", fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                        Text(if (e.intervalMin > 0) "runs every ${e.intervalMin} min" else "runs on demand", fontSize = T.caption, color = T.inkFaint)
                    }
                }
                // ── scrollable middle so the action bar below is ALWAYS reachable ──
                Column(Modifier.weight(1f, true).verticalScroll(rememberScrollState())) {
                    var editing by remember(e.id) { mutableStateOf(false) }
                    var goalText by remember(e.id) { mutableStateOf(e.goal) }
                    Spacer(Modifier.height(10.dp))
                    if (!editing) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(e.goal.ifBlank { "No persona set." }, fontSize = T.caption, color = T.inkSoft, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            Text("Edit", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { goalText = e.goal; editing = true }.padding(start = 10.dp, top = 1.dp))
                        }
                    } else {
                        var nameText by remember(e.id) { mutableStateOf(e.name) }
                        var roleText by remember(e.id) { mutableStateOf(e.role) }
                        Text("NAME", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(10.dp)) {
                            if (nameText.isEmpty()) Text("Agent name", fontSize = 13.sp, color = T.inkFaint)
                            BasicTextField(nameText, { nameText = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("ROLE", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(10.dp)) {
                            if (roleText.isEmpty()) Text("e.g. Design Lead", fontSize = 13.sp, color = T.inkFaint)
                            BasicTextField(roleText, { roleText = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("PERSONA / INSTRUCTIONS", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(10.dp)) {
                            if (goalText.isEmpty()) Text("e.g. You are a genius CEO with world-class design taste — minimal, active voice, no filler…", fontSize = 12.sp, color = T.inkFaint, lineHeight = 16.sp)
                            BasicTextField(goalText, { goalText = it }, textStyle = TextStyle(color = T.ink, fontSize = 13.sp, lineHeight = 18.sp), modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text("Save", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (goalText.isBlank()) T.hairline else T.accent)
                                    .clickable(enabled = goalText.isNotBlank()) { com.agentos.shell.tools.EmployeeStore.edit(ctx, e.id, goalText, nameText, roleText); editing = false; flash = "${nameText.ifBlank { e.name }} updated ✓"; refresh(); detailEmp = com.agentos.shell.tools.EmployeeStore.get(ctx, e.id) }.padding(vertical = 9.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel", fontSize = T.small, color = T.inkSoft, textAlign = TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(T.hairline).clickable { editing = false }.padding(horizontal = 16.dp, vertical = 9.dp))
                        }
                    }
                    // ── Feed this agent PDFs — they become its PRIMARY knowledge (on top of brain + web) ──
                    var kbCount by remember(e.id) { mutableStateOf(com.agentos.shell.tools.AgentKnowledge.count(ctx, e.id)) }
                    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                            flash = "Reading the PDF for ${e.name}…"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        val name = com.agentos.shell.tools.FileOps.displayName(ctx, uri)
                                        var txt = com.agentos.shell.tools.FileOps.pdfText(ctx, uri)
                                        // Image-based (slides/scans) → no text layer → OCR the pages on-device.
                                        if (txt.length < 200) { val ocr = com.agentos.shell.tools.PdfOcr.fromUri(ctx, uri); if (ocr.length > txt.length) txt = ocr }
                                        if (txt.length > 40) { com.agentos.shell.tools.AgentKnowledge.add(ctx, e.id, name, txt); true } else false
                                    } catch (ex: Exception) { false }
                                }
                                kbCount = com.agentos.shell.tools.AgentKnowledge.count(ctx, e.id)
                                flash = if (ok) "${e.name} learned it ✓ ($kbCount docs)" else "Couldn't read that PDF (a scan with no text?)."
                            }
                        }
                    }
                    var kbItems by remember(e.id, kbCount) { mutableStateOf(com.agentos.shell.tools.AgentKnowledge.items(ctx, e.id)) }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (kbCount > 0) "Knowledge · $kbCount doc${if (kbCount == 1) "" else "s"} fed" else "No docs fed yet",
                            fontSize = 11.sp, color = if (kbCount > 0) T.good else T.inkFaint, modifier = Modifier.weight(1f))
                        Text("Feed a PDF  →", fontSize = 11.sp, color = T.accent, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(T.accent.copy(alpha = 0.12f))
                                .clickable { try { pdfPicker.launch(arrayOf("application/pdf")) } catch (ex: Exception) { flash = "No file picker available." } }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    kbItems.forEach { doc ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(doc.title, fontSize = 11.sp, color = T.inkSoft, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("✕", fontSize = 13.sp, color = T.danger, modifier = Modifier
                                .clickable { com.agentos.shell.tools.AgentKnowledge.remove(ctx, doc.id); kbItems = com.agentos.shell.tools.AgentKnowledge.items(ctx, e.id); kbCount = com.agentos.shell.tools.AgentKnowledge.count(ctx, e.id); flash = "Removed “${doc.title}”." }
                                .padding(start = 10.dp, end = 4.dp))
                        }
                    }
                    val stat = remember(e.id, log) { com.agentos.shell.tools.EmployeeStats.stat(ctx, e.id) }
                    val tokLabel = if (stat.tokens >= 1000) String.format("%.1fk", stat.tokens / 1000.0) else stat.tokens.toString()
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg).padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SPENT", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(tokLabel, fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                            Text("~$" + String.format("%.2f", stat.costUsd), fontSize = 9.sp, color = T.inkFaint)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DID", fontSize = 9.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("${stat.actions}", fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                            Text("${stat.approved} approved", fontSize = 9.sp, color = T.inkFaint)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SAVED", fontSize = 9.sp, color = T.good, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("~${stat.valueMin}m", fontSize = 16.sp, color = T.good, fontWeight = FontWeight.SemiBold)
                            Text("${stat.shifts} shifts", fontSize = 9.sp, color = T.inkFaint)
                        }
                    }
                    if (needs != null) {
                        val isPost = Regex("(?i)post|comment|r/|reddit|paste this|publish|tweet").containsMatchIn(needs.line)
                        val isConn = Regex("(?i)connect|hubspot|api key|set ?up|integrat|sign ?in|log ?in|credential").containsMatchIn(needs.line)
                        Spacer(Modifier.height(12.dp))
                        Text(needs.line, fontSize = T.small, color = T.danger, lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.danger.copy(alpha = 0.10f)).padding(12.dp))
                        if (draftObj != null && draftObj.text.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text((if (draftObj.target.isNotBlank()) "READY TO POST · ${draftObj.target}" else "READY TO POST"), fontSize = 9.sp, color = T.good, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(4.dp))
                            if (draftObj.title.isNotBlank())
                                Text(draftObj.title, fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                            Text(draftObj.text, fontSize = T.small, color = T.ink, lineHeight = 19.sp,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bg).padding(12.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        if (isPost) Text("Copy the post & open Reddit  →", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { copyAndOpenReddit() }.padding(vertical = 12.dp))
                        else if (isConn) Text("Connect what it needs  →", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { connectEmp = e; detailEmp = null }.padding(vertical = 12.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Approve & mark done", fontSize = T.small, color = T.good, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.good.copy(alpha = 0.14f)).clickable { approveDone() }.padding(vertical = 12.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("RECENT", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    log.forEach { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(agoLabel(l.ts), fontSize = T.caption, color = T.inkFaint, modifier = Modifier.width(52.dp))
                            Text(l.line, fontSize = T.caption, color = if (l.needsInput) T.danger else T.inkSoft, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // ── pinned action bar (always visible) ──
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(T.bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (askText.isEmpty()) Text(if (needs != null) "Answer ${e.name}…" else "Ask ${e.name} a question…", fontSize = 13.sp, color = T.inkFaint)
                        BasicTextField(askText, { askText = it }, textStyle = TextStyle(color = T.ink, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(38.dp).clip(CircleShape).background(if (askText.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = !busy && askText.isNotBlank()) { val q = askText; askText = ""; detailEmp = null; askAgent(e, q) }, contentAlignment = Alignment.Center) {
                        Text("↑", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold)
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
                Text("Connect what ${e.name} needs", fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Paste an API key or webhook URL — the reliable way. Most services (HubSpot, Zapier, Notion…) give you one in their settings under “API” or “Developer”.", fontSize = T.caption, color = T.inkFaint, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                @Composable fun field(hint: String, v: String, on: (String) -> Unit) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(T.bg).padding(11.dp)) {
                        if (v.isEmpty()) Text(hint, fontSize = 14.sp, color = T.inkFaint)
                        BasicTextField(v, on, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                    }
                }
                field("Service name (e.g. Zapier)", svc) { svc = it }
                field("API key / token / webhook URL", key) { key = it }
                field("Web address (optional)", url) { url = it }
                Spacer(Modifier.height(12.dp))
                Text("Save connection", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (svc.isBlank()) T.hairline else T.accent)
                        .clickable(enabled = svc.isNotBlank()) {
                            com.agentos.shell.tools.IntegrationStore.add(ctx, svc, url, key, "Added for ${e.name}")
                            EmployeeStore.log(ctx, e.id, "$svc is now connected — resuming.", false)
                            EmployeeStore.setStatus(ctx, e.id, "idle")
                            flash = "$svc connected ✓"; connectEmp = null; refresh()
                        }.padding(vertical = 13.dp))
                Spacer(Modifier.height(14.dp))
                Text("Or let SlyOS try setting it up in your browser (experimental — may not finish)", fontSize = T.caption, color = T.inkFaint, textAlign = TextAlign.Center, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        com.agentos.shell.tools.ScreenAgent.start(ctx.applicationContext,
                            "Set up the tool/connection ${e.name} (${e.role}) needs to do this goal: \"${e.goal}\". Open the right app or website, help me sign in or register step by step, and stop to ask me for any credentials or codes you need.")
                        flash = "SlyOS is trying to set it up — follow along on your screen."; connectEmp = null
                    }.padding(vertical = 8.dp))
            }
        }
    }

    // ── A readable answer from the team / an agent (not a tiny truncated flash) ──
    teamReply?.let { msg ->
        Dialog(onDismissRequest = { teamReply = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).clip(RoundedCornerShape(20.dp)).background(T.bgElevated).padding(18.dp)) {
                Text("YOUR TEAM", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(10.dp))
                Text(com.agentos.shell.tools.TeamChat.stripMd(msg), fontSize = 15.sp, color = T.ink, lineHeight = 22.sp,
                    modifier = Modifier.weight(1f, false).verticalScroll(rememberScrollState()))
                Spacer(Modifier.height(14.dp))
                Text("Close", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { teamReply = null }.padding(vertical = 12.dp))
            }
        }
    }
}
