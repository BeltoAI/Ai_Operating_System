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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun StoreScreen(modifier: Modifier = Modifier, onOpenApp: (Long) -> Unit = {}, onArchitect: () -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf(0) }          // 0=For you, 1=Skill, 2=Connect, 3=Tool
    var selected by remember { mutableStateOf<Power?>(null) }
    var ghResults by remember { mutableStateOf<List<Power>>(emptyList()) }
    var loadingGh by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

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
        Text("Powers", fontSize = 34.sp, color = T.ink, fontWeight = FontWeight.Bold)
        if (installedCount > 0) Text("$installedCount active", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold)
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

        // ── Segmented view switch ────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.bgElevated).padding(3.dp)) {
            val labels = listOf("For you", "Skill", "Connect", "Tool")
            labels.forEachIndexed { i, lbl ->
                val sel = segment == i && query.isBlank()
                Text(lbl, fontSize = T.small, color = if (sel) Color.White else T.inkSoft, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { segment = i; query = "" }.padding(vertical = 9.dp))
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
                segment == 0 -> {
                    all.firstOrNull { it.featured }?.let { hero -> item(key = "hero") { FeaturedCard(hero, PowerRegistry.isInstalled(ctx, hero.id).also { tick }) { selected = hero } } }
                    val rec = all.filter { it.featured || it.trending }
                    if (rec.isNotEmpty()) {
                        item { RailHeader("recommended for you") }
                        item { LazyRow { items(rec, key = { "r_" + it.id }) { p -> RailCard(p) { selected = p } } } }
                    }
                    item { Spacer(Modifier.height(6.dp)); RailHeader("top powers") }
                    val top = all.sortedByDescending { it.starCount }.take(10)
                    itemsIndexedKeyed(top) { i, p -> RankRow(i + 1, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                }
                // ── Facet by type ──
                else -> {
                    val t = when (segment) { 1 -> PowerType.SKILL; 2 -> PowerType.CONNECT; else -> PowerType.TOOL }
                    val list = all.filter { it.type == t }.sortedByDescending { it.starCount }
                    item { RailHeader(t.label.lowercase() + " powers") }
                    itemsIndexedKeyed(list) { i, p -> RankRow(0, p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    selected?.let { p ->
        PowerSheet(p, PowerRegistry.isInstalled(ctx, p.id),
            onInstall = { ep -> PowerRegistry.install(ctx, p, ep); tick++; selected = null },
            onRemove = { PowerRegistry.remove(ctx, p.id); tick++; selected = null },
            onRepo = { openRepo(p) }) { selected = null }
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

@Composable
private fun RatingRow(p: Power) =
    Text("★ ${"%.1f".format(p.rating)}   ·   ${p.stars}", fontSize = T.caption, color = T.inkFaint)

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
                    Text("★ ${"%.1f".format(p.rating)}  ·  ${p.stars}", fontSize = T.caption, color = Color(0xDDFFFFFF))
                    Spacer(Modifier.weight(1f))
                    Text(if (installed) "ACTIVE" else "GET", fontSize = T.caption, color = if (installed) Color.White else a, fontWeight = FontWeight.Bold,
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
        Text(if (installed) "ACTIVE" else "GET", fontSize = T.caption, color = if (installed) T.inkFaint else T.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (installed) T.hairline else T.accentSoft.copy(alpha = 0.5f)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp))
    }
    Hairline()
}

@Composable
private fun PowerSheet(p: Power, installed: Boolean, onInstall: (String) -> Unit, onRemove: () -> Unit, onRepo: () -> Unit, onClose: () -> Unit) {
    var endpoint by remember(p.id) { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(22.dp)).background(T.bgElevated).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(p, 64)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("power to ${p.tagline}", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("★ ${"%.1f".format(p.rating)}  ·  ${p.stars}  ·  ${p.type.label}", fontSize = T.caption, color = T.inkFaint)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(p.description, fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
            Text("view on github ↗", fontSize = T.caption, color = T.accent, modifier = Modifier.padding(top = 10.dp).clickable { onRepo() })

            if (p.type != PowerType.SKILL) {
                Spacer(Modifier.height(14.dp))
                Column {
                    BasicTextField(endpoint, { endpoint = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        decorationBox = { inner -> if (endpoint.isEmpty()) Text("https://your-instance  (optional)", fontSize = 15.sp, color = T.inkFaint); inner() })
                    Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
                }
            }

            Spacer(Modifier.height(18.dp))
            if (installed) {
                Text("Remove", fontSize = T.small, color = T.danger, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.hairline).clickable { onRemove() }.padding(vertical = 13.dp))
            } else {
                Text(when (p.type) { PowerType.SKILL -> "Add skill"; PowerType.CONNECT -> "Connect"; PowerType.TOOL -> "Add tool" },
                    fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { onInstall(endpoint.trim()) }.padding(vertical = 13.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onClose() })
        }
    }
}
