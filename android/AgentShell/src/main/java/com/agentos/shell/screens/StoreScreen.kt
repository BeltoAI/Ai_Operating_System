package com.agentos.shell.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.GitHubSearch
import com.agentos.shell.tools.Power
import com.agentos.shell.tools.PowerCatalog
import com.agentos.shell.tools.PowerRegistry
import com.agentos.shell.tools.PowerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A curated palette — every crest is a designer-picked gradient, chosen deterministically per power.
private val CRESTS = listOf(
    Color(0xFFE8642C) to Color(0xFFB23A1E),
    Color(0xFF4E86B0) to Color(0xFF2B5675),
    Color(0xFF5E9A78) to Color(0xFF34614A),
    Color(0xFF7B5EA7) to Color(0xFF4A3570),
    Color(0xFFC9863F) to Color(0xFF8A5A22),
    Color(0xFFB0506A) to Color(0xFF7A2E45),
    Color(0xFF3E8E8E) to Color(0xFF205C5C),
    Color(0xFF8A7BC8) to Color(0xFF5847A0)
)
private fun crestOf(id: String) = CRESTS[((id.hashCode() % CRESTS.size) + CRESTS.size) % CRESTS.size]
private fun monogram(name: String) = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"

/**
 * THE POWER STORE — a discovery surface, not a list. Say what you want ("give my phone the power to…") and
 * the best matches surface; or browse featured, recommended and top-ranked powers, each with a generated
 * crest, a ★ rating and star-count. Switch the view to facet by Skill / Connect / Tool. No emoji, no clutter.
 */
@Composable
fun StoreScreen(modifier: Modifier = Modifier, onOpenApp: (Long) -> Unit = {}, onArchitect: () -> Unit = {}, onTry: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("For you") }   // browse by what you want to DO, not tech type
    var selected by remember { mutableStateOf<Power?>(null) }
    var ghResults by remember { mutableStateOf<List<Power>>(emptyList()) }
    var loadingGh by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var showReset by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("") }
    // A live, ranked feed of popular phone-native skills pulled from GitHub — so the store feels vast, not thin.
    var discover by remember { mutableStateOf<List<Power>>(emptyList()) }
    LaunchedEffect(Unit) { discover = withContext(Dispatchers.IO) { GitHubSearch.discover(30) } }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "enter")

    // Cycling intent prompt.
    val prompts = listOf("see the live web", "speak in my voice", "read any scan", "make any image", "run my own model", "research while I sleep")
    var ph by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2600); ph = (ph + 1) % prompts.size } }

    val installedCount = remember(tick) { PowerRegistry.count(ctx) }
    val all = PowerCatalog.SEED

    fun score(p: Power): Int {
        val terms = Regex("[a-z]{3,}").findAll(query.lowercase()).map { it.value }.toList()
        if (terms.isEmpty()) return 0
        val hay = "${p.tagline} ${p.description} ${p.name} ${p.category}".lowercase()
        return terms.count { hay.contains(it) }
    }
    val matched = remember(query) { all.filter { score(it) > 0 }.sortedWith(compareByDescending<Power> { score(it) }.thenByDescending { it.starCount }) }

    fun openRepo(p: Power) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.repoUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
    fun searchGitHub() { val q = query.trim(); if (q.isBlank()) return; loadingGh = true; scope.launch { val r = withContext(Dispatchers.IO) { GitHubSearch.search(q) }; ghResults = r; loadingGh = false } }

    Column(modifier.alpha(enter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Powers", fontSize = 34.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (installedCount > 0) Text("reset", fontSize = T.caption, color = T.danger, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showReset = true }.padding(6.dp))
        }
        if (installedCount > 0) Text("$installedCount added", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold)
        if (flash.isNotBlank()) Text(flash, fontSize = T.caption, color = T.accent, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))

        // ── Intent bar — the hero ────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
            Text("give your phone the power to —", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Crossfade(ph, label = "ph") { i -> Text(prompts[i] + "…", fontSize = 19.sp, color = T.inkFaint) }
                    BasicTextField(query, { query = it; ghResults = emptyList() }, singleLine = true,
                        textStyle = TextStyle(color = T.ink, fontSize = 19.sp, fontWeight = FontWeight.Medium), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty())
                    Text("clear", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.clickable { query = ""; ghResults = emptyList() }.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Browse by what you want to DO — plain outcome categories, not tech types ─────────────────
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val cats = listOf("For you") + PowerCatalog.CATEGORIES
            cats.forEach { c ->
                val sel = category == c && query.isBlank()
                Text(c, fontSize = T.small, color = if (sel) Color.White else T.inkSoft,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (sel) T.accent else T.bgElevated)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { category = c; query = "" }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.weight(1f)) {
            when {
                // ── Intent search results ──
                query.isNotBlank() -> {
                    if (matched.isNotEmpty()) {
                        item { RailHeader("best matches") }
                        items(matched, key = { it.id }) { p -> RankRow(0, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RailHeader(if (loadingGh) "searching github…" else "from github")
                            Spacer(Modifier.weight(1f))
                            if (!loadingGh && ghResults.isEmpty()) Text("search →", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { searchGitHub() })
                        }
                    }
                    items(ghResults, key = { it.id }) { p -> RankRow(0, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                }
                // ── For you ──
                category == "For you" -> {
                    val hero = all.firstOrNull { it.featured }
                    hero?.let { item(key = "hero") { FeaturedCard(it, PowerRegistry.isInstalled(ctx, it.id).also { tick }) { selected = it } } }
                    // "Works on your phone right now" — the wins Joe can use with one tap, no computer.
                    val onPhone = all.filter { (it.onPhone || it.type == PowerType.SKILL) && it.id != hero?.id }
                    if (onPhone.isNotEmpty()) {
                        item { RailHeader("works on your phone now") }
                        item { LazyRow { items(onPhone, key = { "r_" + it.id }) { p -> RailCard(p) { selected = p } } } }
                    }
                    item { Spacer(Modifier.height(6.dp)); RailHeader("most popular") }
                    val top = all.filter { it.id != hero?.id }.sortedByDescending { it.starCount }.take(12)
                    itemsIndexedKeyed(top) { i, p -> RankRow(i + 1, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                    // The infinite tail — popular skills from all of GitHub, ranked, each one addable in a tap.
                    if (discover.isNotEmpty()) {
                        item { Spacer(Modifier.height(6.dp)); RailHeader("trending on github") }
                        items(discover, key = { it.id }) { p -> RankRow(0, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                    }
                }
                // ── Browse a category ──
                else -> {
                    val list = all.filter { it.category.equals(category, true) }.sortedByDescending { it.starCount }
                    item { RailHeader(category) }
                    if (list.isEmpty()) item { Text("Nothing here yet — try the search above.", fontSize = T.small, color = T.inkFaint, modifier = Modifier.padding(vertical = 12.dp)) }
                    itemsIndexedKeyed(list) { i, p -> RankRow(0, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    selected?.let { p ->
        PowerSheet(p, PowerRegistry.isInstalled(ctx, p.id),
            onInstall = { ep ->
                val power = p
                // Keep the sheet OPEN so it flips to the "It's live — try it" state; the user sees value at once.
                if (power.type == PowerType.SKILL && power.instructions.isBlank() && power.repo.contains("/")) {
                    flash = "Teaching your AI \"${power.name}\"…"
                    scope.launch {
                        val docs = withContext(Dispatchers.IO) { GitHubSearch.fetchDocs(power.repo) }
                        val instr = withContext(Dispatchers.IO) { com.agentos.shell.tools.AgentClient.distillSkill(power.name, docs) }
                        val toInstall = if (instr.isNotBlank()) power.copy(instructions = instr) else power
                        withContext(Dispatchers.IO) { PowerRegistry.install(ctx, toInstall, ep) }
                        selected = toInstall   // refresh the open sheet with the enriched, installed power
                        tick++
                        flash = "Added \"${power.name}\" ✓"
                    }
                } else {
                    PowerRegistry.install(ctx, power, ep); tick++
                    flash = "Added \"${power.name}\" ✓"
                }
            },
            onTry = onTry,
            onRemove = { PowerRegistry.remove(ctx, p.id); tick++; selected = null },
            onRepo = { openRepo(p) }) { selected = null }
    }

    if (showReset) androidx.compose.ui.window.Dialog(onDismissRequest = { showReset = false }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(T.bgElevated).padding(20.dp)) {
            Text("Reset all Powers?", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Removes every installed power, stops their local servers in Termux, and clears the skills they added to your brain. Your chats, memory and settings stay untouched.",
                fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Text("Reset everything", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.danger)
                    .clickable {
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { com.agentos.shell.tools.PowerReset.resetAll(ctx) }
                            flash = msg; tick++; showReset = false
                        }
                    }.padding(vertical = 13.dp))
            Spacer(Modifier.height(10.dp))
            Text("Cancel", fontSize = T.small, color = T.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { showReset = false })
        }
    }
}

/** LazyColumn helper: indexed items with a stable key. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedKeyed(
    list: List<Power>, crossinline row: @androidx.compose.runtime.Composable (Int, Power) -> Unit
) = items(list.size, key = { list[it].id }) { row(it, list[it]) }

@Composable
private fun RailHeader(t: String) =
    Text(t.uppercase(), fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

/** The generated crest — a gradient tile with a script monogram. This is the visual anchor, no emoji. */
@Composable
private fun Crest(p: Power, size: Int) {
    val (a, b) = crestOf(p.id)
    Box(Modifier.size(size.dp).shadow(5.dp, RoundedCornerShape((size / 3.2f).dp)).clip(RoundedCornerShape((size / 3.2f).dp))
        .background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Text(monogram(p.name), fontSize = (size * 0.5f).sp, color = Color(0xFFFBF3E9), fontFamily = T.scriptFamily, fontWeight = FontWeight.Medium)
    }
}

/** Works with one tap on the phone? (native capability, or a Skill that upgrades the AI directly.) */
private fun onPhoneNow(p: Power) = p.onPhone || p.type == PowerType.SKILL

@Composable
private fun RatingRow(p: Power) {
    val phone = onPhoneNow(p)
    Text("★ ${"%.1f".format(p.rating)}   ·   ${if (phone) "works on your phone" else "needs a computer"}",
        fontSize = T.caption, color = if (phone) T.accent else T.inkFaint, fontWeight = if (phone) FontWeight.Medium else FontWeight.Normal)
}

@Composable
private fun FeaturedCard(p: Power, installed: Boolean, onClick: () -> Unit) {
    val (a, b) = crestOf(p.id)
    Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(168.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(a, b))).clickable { onClick() }) {
        // faint oversized monogram as texture
        Text(monogram(p.name), fontSize = 190.sp, color = Color(0x22FFFFFF), fontFamily = T.scriptFamily,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 20.dp, y = 18.dp))
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("FEATURED", fontSize = 10.sp, color = Color(0xCCFFFFFF), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Column {
                Text("power to ${p.tagline}", fontSize = 23.sp, color = Color.White, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★ ${"%.1f".format(p.rating)}  ·  ${if (onPhoneNow(p)) "works on your phone" else "needs a computer"}", fontSize = T.caption, color = Color(0xDDFFFFFF))
                    Spacer(Modifier.weight(1f))
                    Text(if (installed) "ADDED" else "GET", fontSize = T.caption, color = if (installed) Color.White else a, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (installed) Color(0x33FFFFFF) else Color.White).padding(horizontal = 18.dp, vertical = 7.dp))
                }
            }
        }
    }
}

@Composable
private fun RailCard(p: Power, onClick: () -> Unit) {
    Column(Modifier.padding(end = 12.dp, top = 4.dp).width(150.dp).clip(RoundedCornerShape(16.dp)).background(T.bgElevated).clickable { onClick() }.padding(12.dp)) {
        Crest(p, 52)
        Spacer(Modifier.height(10.dp))
        Text(p.tagline, fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, maxLines = 3, modifier = Modifier.height(54.dp))
        Spacer(Modifier.height(6.dp))
        RatingRow(p)
    }
}

@Composable
private fun RankRow(rank: Int, p: Power, installed: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp)) {
        if (rank > 0) Text("%02d".format(rank), fontSize = 15.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Crest(p, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.tagline, fontSize = 16.sp, color = T.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 20.sp)
            Spacer(Modifier.height(3.dp))
            RatingRow(p)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (installed) "ADDED" else "GET", fontSize = T.caption, color = if (installed) T.inkFaint else T.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (installed) T.hairline else T.accentSoft.copy(alpha = 0.5f)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp))
    }
    Hairline()
}

@Composable
private fun PowerSheet(p: Power, installed: Boolean, onInstall: (String) -> Unit, onTry: (String) -> Unit, onRemove: () -> Unit, onRepo: () -> Unit, onClose: () -> Unit) {
    var endpoint by remember(p.id) { mutableStateOf("") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(p.id) { mutableStateOf("") }
    var askQ by remember(p.id) { mutableStateOf("") }
    var askA by remember(p.id) { mutableStateOf("") }
    var asking by remember(p.id) { mutableStateOf(false) }
    var showConnect by remember(p.id) { mutableStateOf(false) }

    fun ask() {
        asking = true; askA = ""
        scope.launch {
            val fact = when {
                p.type == PowerType.SKILL ->
                    "FACT you must respect: this is a SKILL — it upgrades SlyOS's own AI (how it thinks and behaves), " +
                    "instantly, right on the phone. There is NOTHING to run, install or connect and NO computer is needed. " +
                    "To use it, the person just talks to their AI on the Home screen as usual and it now behaves this way. "
                p.onPhone ->
                    "FACT you must respect: SlyOS can do this RIGHT ON THE PHONE, instantly, with no setup. "
                else ->
                    "FACT you must respect: this is bigger software meant for a computer or home server — it does NOT run on a " +
                    "phone by itself; the phone would connect to one they run on a computer. "
            }
            val ans = withContext(Dispatchers.IO) {
                com.agentos.shell.tools.AgentClient.appAsk(
                    "You are SlyOS, a friendly AI phone assistant. A NON-TECHNICAL person is looking at '${p.name}', which can ${p.description}. " +
                        fact +
                        "Be HONEST and never over-promise, but NEVER contradict the FACT above. " +
                        (if (askQ.isBlank()) "In 2–3 short, warm, plain sentences, tell them what this does for them and how they'd use it. "
                         else "They said: \"${askQ.trim()}\". In 2–3 short, warm, plain sentences, tell them truthfully how SlyOS handles that and how they'd use it. ") +
                        "Absolutely NO markdown, NO headings, NO bullet points, NO code, NO technical jargon (avoid words like API, Termux, HTTP, server, endpoint, Docker), and NO emoji. Talk like a helpful human.",
                    "")
            }
            askA = cleanMd(ans); asking = false
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(T.bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("←  Powers", fontSize = T.small, color = T.inkSoft, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onClose() }.padding(vertical = 6.dp))
            Spacer(Modifier.height(16.dp))
            RepoImage(p.repo)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(p, 72)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 24.sp, color = T.ink, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text("★ ${"%.1f".format(p.rating)}    ·    ${if (onPhoneNow(p)) "works on your phone" else "needs a computer"}",
                        fontSize = T.caption, color = if (onPhoneNow(p)) T.accent else T.inkFaint)
                    if (installed) { Spacer(Modifier.height(4.dp)); Text("added", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("power to ${p.tagline}", fontSize = 18.sp, color = T.ink, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
            Spacer(Modifier.height(10.dp))
            Text(p.description, fontSize = T.small, color = T.inkSoft, lineHeight = 21.sp)
            Text("view on github ↗", fontSize = T.caption, color = T.accent, modifier = Modifier.padding(top = 10.dp).clickable { onRepo() })

            // ── "What do you want it to do?" — the user's own words, answered plainly ──
            Spacer(Modifier.height(24.dp))
            Text("WHAT DO YOU WANT IT TO DO?", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicTextField(askQ, { askQ = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        decorationBox = { inner -> if (askQ.isEmpty()) Text("e.g. cut out the background of my photos", fontSize = 15.sp, color = T.inkFaint); inner() })
                    Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (asking) T.hairline else T.accent)
                    .clickable(enabled = !asking) { ask() }, contentAlignment = Alignment.Center) {
                    Text(if (asking) "…" else "→", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (askA.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(askA, fontSize = T.small, color = T.inkSoft, lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp))
            }

            // ── Get it (clear, distinct paths) ──
            Spacer(Modifier.height(26.dp))
            Text("GET IT", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            when {
                installed -> {
                    val howTo = when {
                        p.type == PowerType.SKILL -> "Your AI just leveled up — it now knows how to ${p.tagline}. Tap one to see it work, or just talk to your AI on Home."
                        p.onPhone -> "You can use this right from the Home screen — just ask, or attach a photo, and your AI will ${p.tagline}."
                        else -> "Connected. Your AI will use this when it's relevant."
                    }
                    // Concrete, tappable ways to USE it — one tap runs it live in Home, so value is immediate.
                    var starters by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
                    LaunchedEffect(p.id) {
                        if (p.type == PowerType.SKILL)
                            starters = withContext(Dispatchers.IO) {
                                com.agentos.shell.tools.AgentClient.skillStarters(p.name, p.instructions.ifBlank { p.description })
                            }
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                        Text("✓ It's live", fontSize = T.body, color = T.accent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(howTo, fontSize = T.small, color = T.inkSoft, lineHeight = 21.sp)
                        if (starters.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("TRY IT NOW", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Spacer(Modifier.height(8.dp))
                            starters.forEach { s ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                                        .background(T.accentSoft.copy(alpha = 0.35f)).clickable { onTry(s) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Text(s, fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text("pin", fontSize = T.caption, color = T.inkFaint, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable {
                                            com.agentos.shell.tools.ShortcutStore.add(ctx, "prompt", p.name.take(12), s)
                                            status = "Pinned \"${p.name.take(12)}\" to your Home screen ✓"
                                        }.padding(horizontal = 8.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("→", fontSize = T.body, color = T.accent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Remove this power", fontSize = T.small, color = T.danger, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.hairline).clickable { onRemove() }.padding(vertical = 13.dp))
                }
                p.type == PowerType.SKILL -> ActionCard("Add to your AI", "It's a skill — upgrades the AI directly, instantly. Nothing to run or connect.", "Add skill") { onInstall("") }
                p.onPhone -> ActionCard("Add to your phone", "Runs right on your phone — no setup, no computer, nothing to install.", "Add") { onInstall("") }
                else -> {
                    Text("This one runs on a computer or home server — it's too big for a phone.", fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (showConnect) "Hide" else "I run it on a computer  →", fontSize = T.caption, color = T.accent,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { showConnect = !showConnect }.padding(vertical = 6.dp))
                    if (showConnect) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
                            Text("Connect it", fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
                            Text("If you already run it on your computer, paste its address here.", fontSize = T.caption, color = T.inkFaint)
                            Spacer(Modifier.height(10.dp))
                            BasicTextField(endpoint, { endpoint = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 15.sp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                decorationBox = { inner -> if (endpoint.isEmpty()) Text("http://192.168.1.x:port", fontSize = 15.sp, color = T.inkFaint); inner() })
                            Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Connect", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (endpoint.isBlank()) T.hairline else T.accent)
                                        .clickable(enabled = endpoint.isNotBlank()) { onInstall(endpoint.trim()) }.padding(vertical = 11.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Test", fontSize = T.small, color = T.accent, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable {
                                        status = "checking…"
                                        scope.launch { val ok = withContext(Dispatchers.IO) { com.agentos.shell.tools.PowerDispatch.ping(endpoint) }; status = if (ok) "Connected ✓" else "Couldn't reach it — is it running?" }
                                    }.padding(horizontal = 16.dp, vertical = 11.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Advanced: build it on this phone (needs Termux)", fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.hairline).clickable {
                                status = "building in Termux… this can take a minute"
                                scope.launch {
                                    val (ok, log) = withContext(Dispatchers.IO) { com.agentos.shell.tools.PowerBuilder.build(ctx, p) }
                                    status = log
                                    if (ok) onInstall(com.agentos.shell.tools.PowerRegistry.endpointOf(ctx, p.id))
                                }
                            }.padding(vertical = 11.dp))
                    }
                }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(status, fontSize = T.caption, color = T.inkSoft, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Turn model markdown (#, **, ```, bullets, emoji) into clean, plain, human text. */
private fun cleanMd(s: String): String {
    var t = s
    t = t.replace(Regex("```[a-zA-Z]*\\n?"), "").replace("```", "")   // drop code fences
    t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")               // headings
    t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")                 // bold
    t = t.replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")     // italic
    t = t.replace(Regex("`([^`]+)`"), "$1")                          // inline code
    t = t.replace(Regex("(?m)^\\s*[-*]\\s+"), "•  ")                  // list bullets → dot
    t = t.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2705\\u274C\\u26A0\\uFE0F\\u2714\\u2728\\u2192\\u2193]"), "") // emoji/arrows
    t = t.replace(Regex("\\n{3,}"), "\n\n")
    return t.trim()
}

/** GitHub's social-preview image for a repo — a real picture of the project. */
@Composable
private fun RepoImage(repo: String) {
    var img by remember(repo) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(repo) {
        img = withContext(Dispatchers.IO) {
            try {
                val c = (java.net.URL("https://opengraph.githubassets.com/1/$repo").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "SlyOS")
                }
                val bytes = if (c.responseCode in 200..299) c.inputStream.readBytes() else null
                c.disconnect()
                bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            } catch (e: Exception) { null }
        }
    }
    img?.let {
        Image(bitmap = it, contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
    }
}

@Composable
private fun ActionCard(title: String, sub: String, cta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
        Text(title, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
        Text(sub, fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(12.dp))
        Text(cta, fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.accent).clickable { onClick() }.padding(vertical = 12.dp))
    }
}
