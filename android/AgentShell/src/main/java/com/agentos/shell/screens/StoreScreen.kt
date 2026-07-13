package com.agentos.shell.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restrained type-colour per power kind — a single quiet dot, never an emoji or icon widget. */
private fun kindColor(t: PowerType): Color = when (t) {
    PowerType.SKILL -> Color(0xFFE8642C)   // accent
    PowerType.CONNECT -> Color(0xFF4E86B0) // slate blue
    PowerType.TOOL -> Color(0xFF5E9A78)    // sage
}

/**
 * THE POWER STORE — reimagined as an editorial "power menu", not an app store. No icons, no tiles, no
 * emoji: just typeset "power to ___" lines that expand inline as you tap, over quiet motion. Search the
 * curated menu or all of GitHub; adding a power wires it into the brain and every AI.
 */
@Composable
fun StoreScreen(modifier: Modifier = Modifier, onOpenApp: (Long) -> Unit = {}, onArchitect: () -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<PowerType?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var ghResults by remember { mutableStateOf<List<Power>>(emptyList()) }
    var loadingGh by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    // Gentle entrance for the whole surface.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(if (shown) 1f else 0f, tween(500), label = "enter")

    val installedCount = remember(tick) { PowerRegistry.count(ctx) }

    fun matches(p: Power): Boolean {
        if (typeFilter != null && p.type != typeFilter) return false
        val q = query.trim().lowercase()
        if (q.isBlank()) return true
        return listOf(p.name, p.tagline, p.description, p.category).any { it.lowercase().contains(q) }
    }
    val catalogMatches = PowerCatalog.SEED.filter(::matches)

    fun searchGitHub() {
        val q = query.trim(); if (q.isBlank()) return
        loadingGh = true
        scope.launch { val r = withContext(Dispatchers.IO) { GitHubSearch.search(q) }; ghResults = r; loadingGh = false }
    }

    Column(modifier.alpha(enter)) {
        // ── Masthead ────────────────────────────────────────────────────────────────
        Text("Powers", fontSize = 34.sp, color = T.ink, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Give your phone the power to —", fontSize = T.small, color = T.inkFaint, modifier = Modifier.weight(1f))
            if (installedCount > 0) Text("$installedCount active", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))

        // ── Underline search (no boxes) ─────────────────────────────────────────────
        Column {
            BasicTextField(query, { query = it; ghResults = emptyList() }, singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("search powers, or any GitHub repo", fontSize = 16.sp, color = T.inkFaint)
                    inner()
                })
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (query.isBlank()) T.hairline else T.accent))
        }
        Spacer(Modifier.height(16.dp))

        // ── Filter as quiet text toggles ────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FilterText("All", null, typeFilter) { typeFilter = null }
            for (t in PowerType.values()) FilterText(t.label, t, typeFilter) { typeFilter = t }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (query.isBlank()) {
                for (cat in PowerCatalog.CATEGORIES) {
                    val inCat = catalogMatches.filter { it.category == cat }
                    if (inCat.isEmpty()) continue
                    item(key = "h_$cat") { CategoryLabel(cat) }
                    items(inCat, key = { it.id }) { p ->
                        PowerLine(p, expanded == p.id, PowerRegistry.isInstalled(ctx, p.id).also { tick },
                            onToggle = { expanded = if (expanded == p.id) null else p.id },
                            onInstall = { ep -> PowerRegistry.install(ctx, p, ep); tick++; status = "Your phone can now ${p.tagline}."; expanded = null },
                            onRemove = { PowerRegistry.remove(ctx, p.id); tick++; status = "Removed ${p.name}."; expanded = null },
                            onRepo = { openRepo(ctx, p) })
                    }
                }
            } else {
                if (catalogMatches.isNotEmpty()) item(key = "cm") { CategoryLabel("From the menu") }
                items(catalogMatches, key = { it.id }) { p ->
                    PowerLine(p, expanded == p.id, PowerRegistry.isInstalled(ctx, p.id).also { tick },
                        onToggle = { expanded = if (expanded == p.id) null else p.id },
                        onInstall = { ep -> PowerRegistry.install(ctx, p, ep); tick++; status = "Your phone can now ${p.tagline}."; expanded = null },
                        onRemove = { PowerRegistry.remove(ctx, p.id); tick++; status = "Removed ${p.name}."; expanded = null },
                        onRepo = { openRepo(ctx, p) })
                }
                item(key = "ghhead") {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryLabel(if (loadingGh) "searching github…" else "the rest of github")
                        Spacer(Modifier.weight(1f))
                        if (!loadingGh && ghResults.isEmpty())
                            Text("search →", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { searchGitHub() })
                    }
                }
                items(ghResults, key = { it.id }) { p ->
                    PowerLine(p, expanded == p.id, PowerRegistry.isInstalled(ctx, p.id).also { tick },
                        onToggle = { expanded = if (expanded == p.id) null else p.id },
                        onInstall = { ep -> PowerRegistry.install(ctx, p, ep); tick++; status = "Added ${p.name}."; expanded = null },
                        onRemove = { PowerRegistry.remove(ctx, p.id); tick++; status = "Removed ${p.name}."; expanded = null },
                        onRepo = { openRepo(ctx, p) })
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    }
}

private fun openRepo(ctx: android.content.Context, p: Power) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.repoUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
}

@Composable
private fun CategoryLabel(text: String) =
    Text(text.uppercase(), fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))

@Composable
private fun FilterText(label: String, t: PowerType?, current: PowerType?, onClick: () -> Unit) {
    val sel = t == current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 20.dp).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }) {
        Text(label, fontSize = 15.sp, color = if (sel) T.ink else T.inkFaint, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(if (sel) 18.dp else 0.dp).height(2.dp).clip(CircleShape).background(T.accent))
    }
}

/** One editorial line — the phrase is the hero; expands inline to reveal detail + actions. */
@Composable
private fun PowerLine(
    p: Power, open: Boolean, installed: Boolean,
    onToggle: () -> Unit, onInstall: (String) -> Unit, onRemove: () -> Unit, onRepo: () -> Unit
) {
    var endpoint by remember(p.id) { mutableStateOf("") }
    val press = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().animateContentSize(tween(260, easing = LinearOutSlowInEasing))
            .clickable(interactionSource = press, indication = null) { onToggle() }
            .padding(vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // quiet colour dot instead of any icon
            Box(Modifier.padding(top = 8.dp).size(7.dp).clip(CircleShape).background(kindColor(p.type)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.tagline, fontSize = 21.sp, color = T.ink, fontWeight = FontWeight.Medium, lineHeight = 26.sp)
                Spacer(Modifier.height(4.dp))
                Text("${p.type.label.lowercase()}  ·  ${p.name}  ·  ★ ${p.stars}", fontSize = T.caption, color = T.inkFaint)
            }
            if (installed) {
                Spacer(Modifier.width(8.dp))
                Text("active", fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (open) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.padding(start = 21.dp)) {
                Text(p.description, fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
                val how = when (p.type) {
                    PowerType.SKILL -> "A skill — it upgrades the AI itself. Add it and every AI on your phone can use it."
                    PowerType.CONNECT -> "Connect your instance (or a hosted one); the AI reads & writes it. Link now or later."
                    PowerType.TOOL -> "A tool the AI runs on your files. Point it at your instance when ready."
                }
                Text(how, fontSize = T.caption, color = T.inkFaint, lineHeight = 17.sp)
                Text("view on github ↗", fontSize = T.caption, color = T.accent, modifier = Modifier.padding(top = 8.dp).clickable { onRepo() })

                if (p.type != PowerType.SKILL) {
                    Spacer(Modifier.height(12.dp))
                    Column {
                        BasicTextField(endpoint, { endpoint = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = 15.sp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            decorationBox = { inner -> if (endpoint.isEmpty()) Text("https://your-instance  (optional)", fontSize = 15.sp, color = T.inkFaint); inner() })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (installed) {
                    Text("Remove", fontSize = T.small, color = T.danger, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onRemove() })
                } else {
                    val cta = when (p.type) { PowerType.SKILL -> "Add this skill"; PowerType.CONNECT -> "Connect this power"; PowerType.TOOL -> "Add this tool" }
                    Text(cta, fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.accent).clickable { onInstall(endpoint.trim()) }.padding(vertical = 13.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Hairline()
    }
}
