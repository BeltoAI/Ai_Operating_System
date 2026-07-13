package com.agentos.shell.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.GitHubSearch
import com.agentos.shell.tools.Power
import com.agentos.shell.tools.PowerCatalog
import com.agentos.shell.tools.PowerRegistry
import com.agentos.shell.tools.PowerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * THE POWER STORE — "give your phone the power to X." A curated catalogue of Skills, Connected apps and
 * Tools, plus live GitHub search to add any repo. Installing a Power wires it into the brain and every AI.
 */
@Composable
fun StoreScreen(modifier: Modifier = Modifier, onOpenApp: (Long) -> Unit = {}, onArchitect: () -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<PowerType?>(null) }
    var selected by remember { mutableStateOf<Power?>(null) }
    var ghResults by remember { mutableStateOf<List<Power>>(emptyList()) }
    var loadingGh by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }                 // bump to refresh installed state

    val installedCount = remember(tick) { PowerRegistry.count(ctx) }

    fun matches(p: Power): Boolean {
        if (typeFilter != null && p.type != typeFilter) return false
        val q = query.trim().lowercase()
        if (q.isBlank()) return true
        return listOf(p.name, p.tagline, p.description, p.category).any { it.lowercase().contains(q) }
    }
    val catalogMatches = PowerCatalog.SEED.filter(::matches)

    fun searchGitHub() {
        val q = query.trim()
        if (q.isBlank()) return
        loadingGh = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { GitHubSearch.search(q) }
            ghResults = r; loadingGh = false
        }
    }

    Column(modifier) {
        // Title
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Power Store", fontSize = 30.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (installedCount > 0)
                Text("$installedCount active", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accentSoft.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Text("Give your phone the power to…", fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(14.dp))

        // Search (catalogue + GitHub)
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 11.dp)) {
            Icon(Icons.Filled.Search, "Search", tint = T.inkFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(query, { query = it; ghResults = emptyList() }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search powers, or any GitHub repo…", fontSize = T.small, color = T.inkFaint); inner() })
            if (query.isNotEmpty())
                Text("GitHub →", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { searchGitHub() }.padding(start = 6.dp))
        }
        Spacer(Modifier.height(12.dp))

        // Type filter chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TypeChip("All", null, typeFilter) { typeFilter = null }
            for (t in PowerType.values()) TypeChip("${t.glyph} ${t.label}", t, typeFilter) { typeFilter = t }
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (query.isBlank()) {
                // Grouped by outcome category.
                for (cat in PowerCatalog.CATEGORIES) {
                    val inCat = catalogMatches.filter { it.category == cat }
                    if (inCat.isEmpty()) continue
                    item(key = "h_$cat") { SectionHeader(powerVerb(cat)) }
                    items(inCat, key = { it.id }) { p ->
                        PowerCard(p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p }
                    }
                    item(key = "s_$cat") { Spacer(Modifier.height(14.dp)) }
                }
            } else {
                if (catalogMatches.isNotEmpty()) {
                    item { SectionHeader("Curated") }
                    items(catalogMatches, key = { it.id }) { p -> PowerCard(p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (loadingGh) "Searching GitHub…" else "More from GitHub", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!loadingGh && ghResults.isEmpty())
                            Text("Search →", fontSize = T.small, color = T.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { searchGitHub() })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(ghResults, key = { it.id }) { p -> PowerCard(p, PowerRegistry.isInstalled(ctx, p.id).also { tick }) { selected = p } }
                if (catalogMatches.isEmpty() && ghResults.isEmpty() && !loadingGh)
                    item { Text("Tap “Search →” to find “${query.trim()}” across GitHub.", fontSize = T.small, color = T.inkFaint) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    }

    selected?.let { p ->
        PowerSheet(p, installed = PowerRegistry.isInstalled(ctx, p.id),
            onInstall = { endpoint -> PowerRegistry.install(ctx, p, endpoint); tick++; status = "${p.icon} Your phone can now ${p.tagline}."; selected = null },
            onRemove = { PowerRegistry.remove(ctx, p.id); tick++; status = "Removed ${p.name}."; selected = null },
            onOpenRepo = { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.repoUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
        ) { selected = null }
    }
}

/** Turn a category into a "Power to …" section heading. */
private fun powerVerb(cat: String): String = when (cat) {
    "See" -> "Power to see"
    "Speak" -> "Power to speak"
    "Create" -> "Power to create"
    "Remember" -> "Power to remember"
    "Automate" -> "Power to automate"
    "Own your data" -> "Power to own your data"
    "Taste" -> "Power to have taste"
    else -> cat
}

@Composable
private fun SectionHeader(title: String) =
    Text(title, fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun TypeChip(label: String, t: PowerType?, current: PowerType?, onClick: () -> Unit) {
    val sel = t == current
    Text(label, fontSize = T.small, color = if (sel) Color.White else T.inkSoft, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
            .background(if (sel) T.accent else T.bgElevated).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun IconTile(icon: String, size: Int) =
    Box(
        Modifier.size(size.dp).shadow(3.dp, RoundedCornerShape((size / 4).dp)).clip(RoundedCornerShape((size / 4).dp))
            .background(Brush.linearGradient(listOf(T.accentSoft, T.accent.copy(alpha = 0.32f)))),
        contentAlignment = Alignment.Center
    ) { Text(icon.take(2), fontSize = (size * 0.44f).sp) }

@Composable
private fun TypeBadge(type: PowerType) =
    Text("${type.glyph} ${type.label}", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accentSoft.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 3.dp))

@Composable
private fun PowerCard(p: Power, installed: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp)) {
        IconTile(p.icon, 50)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                TypeBadge(p.type)
            }
            Text("…the power to ${p.tagline}", fontSize = T.caption, color = T.inkSoft, maxLines = 1)
            Text("${p.repo}  ·  ★ ${p.stars}", fontSize = 11.sp, color = T.inkFaint, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (installed) "ACTIVE" else "GET", fontSize = T.caption, color = if (installed) T.inkFaint else T.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (installed) T.hairline else T.accentSoft.copy(alpha = 0.5f)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp))
    }
    Hairline()
}

@Composable
private fun PowerSheet(p: Power, installed: Boolean, onInstall: (String) -> Unit, onRemove: () -> Unit, onOpenRepo: () -> Unit, onClose: () -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(22.dp)).background(T.bgElevated).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(p.icon, 60)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(3.dp))
                    TypeBadge(p.type)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Gives your phone the power to ${p.tagline}.", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(p.description, fontSize = T.small, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text("${p.repo}  ·  ★ ${p.stars}  ·  open-source", fontSize = T.caption, color = T.inkFaint,
                modifier = Modifier.clickable { onOpenRepo() })

            // How it wires in.
            Spacer(Modifier.height(14.dp))
            val how = when (p.type) {
                PowerType.SKILL -> "This is a Skill — it upgrades your AI directly. Once added, HomeAI and every other AI can use it automatically."
                PowerType.CONNECT -> "This is a Connect power — link your running instance (or a hosted one) and the AI can read & write it. Paste its address below, or add now and connect later."
                PowerType.TOOL -> "This is a Tool — the AI can run it on your files. Add it, and point it at your instance when ready."
            }
            Text(how, fontSize = T.caption, color = T.inkFaint)

            if (p.type != PowerType.SKILL) {
                Spacer(Modifier.height(10.dp))
                BasicTextField(endpoint, { endpoint = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp),
                    decorationBox = { inner -> if (endpoint.isEmpty()) Text("https://your-instance…  (optional)", fontSize = T.small, color = T.inkFaint); inner() })
            }

            Spacer(Modifier.height(16.dp))
            if (installed) {
                Text("Remove power", fontSize = T.small, color = T.danger, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.hairline).clickable { onRemove() }.padding(vertical = 13.dp),
                )
            } else {
                val cta = when (p.type) { PowerType.SKILL -> "Add this skill"; PowerType.CONNECT -> "Connect this power"; PowerType.TOOL -> "Add this tool" }
                Text(cta, fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { onInstall(endpoint.trim()) }.padding(vertical = 13.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onClose() })
        }
    }
}
