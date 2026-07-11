package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentStore
import com.agentos.shell.tools.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Agent Store — an App-Store-grade browse experience for community agents (sandboxed HTML mini-apps):
 * a featured carousel, ranked Top Charts, category filtering, install/run, and one-tap publishing.
 */
@Composable
fun StoreScreen(modifier: Modifier = Modifier, onOpenApp: (Long) -> Unit, onArchitect: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var agents by remember { mutableStateOf<List<AgentStore.Agent>>(emptyList()) }
    var selected by remember { mutableStateOf<AgentStore.Agent?>(null) }
    var showPublish by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val installed = remember { mutableStateMapOf<String, Long>() }   // agent.id → local app id

    fun refresh() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { AgentStore.list(query.trim(), category) }
            agents = r; loading = false
        }
    }
    LaunchedEffect(category) { refresh() }

    fun getOrOpen(a: AgentStore.Agent, thenClose: Boolean = false) {
        val local = installed[a.id]
        if (local != null) { if (thenClose) selected = null; onOpenApp(local); return }
        scope.launch {
            status = "Installing ${a.name}…"
            val id = withContext(Dispatchers.IO) { AgentStore.install(ctx, a) }
            if (id != null) { installed[a.id] = id; status = ""; if (thenClose) selected = null; onOpenApp(id) }
            else status = "Couldn't install ${a.name}."
        }
    }

    val browsing = query.isBlank() && category.isBlank()

    Column(modifier) {
        // ── Big title + publish action, App-Store style ───────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Store", fontSize = 30.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { showPublish = true }.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp)) {
                Icon(Icons.Filled.Add, "Publish", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Publish", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Search field with a proper (vector, not emoji) glyph ──────────────────────
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 11.dp)) {
            Icon(Icons.Filled.Search, "Search", tint = T.inkFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> if (query.isEmpty()) Text("Agents, tools, games…", fontSize = T.small, color = T.inkFaint); inner() })
            if (query.isNotEmpty())
                Text("Search", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { refresh() }.padding(start = 6.dp))
        }
        Spacer(Modifier.height(12.dp))

        // ── Category chips ────────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf("" to "All") + AgentStore.CATEGORIES.map { it to it }).forEach { (id, lbl) ->
                val sel = category == id
                Text(lbl, fontSize = T.small, color = if (sel) Color.White else T.inkSoft, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (sel) T.accent else T.bgElevated).clickable { category = id }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── States ────────────────────────────────────────────────────────────────────
        when {
            !AgentStore.configured() ->
                EmptyState("Store isn't set up yet", "Add your Supabase keys and the agents table (see AGENT_STORE.md), then reopen the Store.", "Build an agent", onArchitect)
            loading ->
                Column(Modifier.fillMaxWidth()) { repeat(6) { SkeletonRow(); Spacer(Modifier.height(4.dp)) } }
            agents.isEmpty() ->
                EmptyState("Nothing here yet", "Be the first to publish — tap Publish above, or craft a fresh agent in the Architect.", "Build an agent", onArchitect)
            else -> LazyColumn(Modifier.weight(1f)) {
                // Featured carousel — only on the untouched browse view.
                if (browsing && agents.size >= 1) {
                    item {
                        SectionHeader("Featured")
                        LazyRow {
                            items(agents.take(6), key = { "f_" + it.id }) { a ->
                                FeaturedCard(a, installed.containsKey(a.id), onClick = { selected = a }, onGet = { getOrOpen(a) })
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        SectionHeader("Top Charts")
                    }
                }
                itemsIndexed(agents, key = { _, a -> a.id }) { i, a ->
                    ChartRow(rank = if (browsing) i + 1 else 0, a = a, installedNow = installed.containsKey(a.id),
                        onClick = { selected = a }, onGet = { getOrOpen(a) })
                    if (i < agents.lastIndex) Hairline()
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    }

    selected?.let { a -> DetailSheet(a, installed.containsKey(a.id), onGet = { getOrOpen(a, thenClose = true) }) { selected = null } }
    if (showPublish) PublishDialog(onClose = { showPublish = false }) { msg -> showPublish = false; status = msg; refresh() }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
}

/** Five-star row. Read-only unless [onPick] is supplied. ★/☆ are geometric glyphs, not emoji. */
@Composable
private fun Stars(value: Int, size: Int = 13, onPick: ((Int) -> Unit)? = null) =
    Row {
        for (i in 1..5) {
            Text(if (i <= value) "★" else "☆", fontSize = size.sp, color = T.accent,
                modifier = if (onPick != null) Modifier.clickable { onPick(i) }.padding(end = 2.dp) else Modifier.padding(end = 1.dp))
        }
    }

/** Compact "★ 4.6 (12)" label; renders nothing until an agent has ratings. */
@Composable
private fun RatingLabel(rating: Double, count: Int, fontSize: Int = 11) {
    if (count <= 0) return
    Text("★ ${"%.1f".format(rating)} ($count)", fontSize = fontSize.sp, color = T.accent, fontWeight = FontWeight.SemiBold)
}

/** A rounded squircle icon tile with a soft accent gradient and the agent's glyph. */
@Composable
private fun IconTile(icon: String, size: Int) =
    Box(
        Modifier.size(size.dp).shadow(3.dp, RoundedCornerShape((size / 4).dp)).clip(RoundedCornerShape((size / 4).dp))
            .background(Brush.linearGradient(listOf(T.accentSoft, T.accent.copy(alpha = 0.32f)))),
        contentAlignment = Alignment.Center
    ) { Text(icon.take(2), fontSize = (size * 0.42f).sp) }

/** Small pill: GET → OPEN. Light filled, bold accent — the App-Store look. */
@Composable
private fun StorePill(installedNow: Boolean, onClick: () -> Unit) =
    Text(if (installedNow) "OPEN" else "GET", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accentSoft.copy(alpha = 0.5f))
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 7.dp))

@Composable
private fun FeaturedCard(a: AgentStore.Agent, installedNow: Boolean, onClick: () -> Unit, onGet: () -> Unit) =
    Column(
        Modifier.padding(end = 12.dp).width(200.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(T.bgElevated).clickable { onClick() }.padding(14.dp)
    ) {
        Text(a.category.uppercase(), fontSize = 10.sp, color = T.accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        IconTile(a.icon, 64)
        Spacer(Modifier.height(10.dp))
        Text(a.name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(a.description.ifBlank { "by ${a.author}" }, fontSize = T.caption, color = T.inkFaint, maxLines = 2, modifier = Modifier.height(30.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                if (a.ratingsCount > 0) RatingLabel(a.rating, a.ratingsCount, 10)
                Text("${a.installs} installs", fontSize = 10.sp, color = T.inkFaint)
            }
            StorePill(installedNow, onGet)
        }
    }

@Composable
private fun ChartRow(rank: Int, a: AgentStore.Agent, installedNow: Boolean, onClick: () -> Unit, onGet: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp)) {
        if (rank > 0) {
            Text("$rank", fontSize = T.body, color = T.inkFaint, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(26.dp))
        }
        IconTile(a.icon, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(a.description.ifBlank { a.author }, fontSize = T.caption, color = T.inkFaint, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.ratingsCount > 0) { RatingLabel(a.rating, a.ratingsCount); Text("  ·  ", fontSize = 11.sp, color = T.inkFaint) }
                Text("${a.category} · ${a.installs} installs", fontSize = 11.sp, color = T.inkFaint, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        StorePill(installedNow, onGet)
    }

@Composable
private fun SkeletonRow() =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(13.dp)).background(T.bgElevated))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.5f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(T.bgElevated))
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(T.bgElevated))
        }
    }

@Composable
private fun EmptyState(title: String, body: String, cta: String, onCta: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile("✨", 64)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = T.small, color = T.inkFaint, modifier = Modifier.fillMaxWidth(0.85f))
        Spacer(Modifier.height(16.dp))
        Text(cta, fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable { onCta() }.padding(horizontal = 20.dp, vertical = 10.dp))
    }

/** Full detail sheet — rating summary, install action, About, What's New (versions), and reviews. */
@Composable
private fun DetailSheet(a: AgentStore.Agent, installedNow: Boolean, onGet: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reviews by remember { mutableStateOf<List<AgentStore.Review>>(emptyList()) }
    var releases by remember { mutableStateOf<List<AgentStore.Release>>(emptyList()) }
    var rating by remember { mutableStateOf(a.rating) }
    var ratingsCount by remember { mutableStateOf(a.ratingsCount) }
    var myStars by remember { mutableStateOf(0) }
    var myText by remember { mutableStateOf("") }
    var reviewMsg by remember { mutableStateOf("") }

    fun reload() = scope.launch {
        val rv = withContext(Dispatchers.IO) { AgentStore.reviews(a.id) }
        val rl = withContext(Dispatchers.IO) { AgentStore.releases(a.id) }
        val fresh = withContext(Dispatchers.IO) { AgentStore.list("", a.category).firstOrNull { it.id == a.id } }
        reviews = rv; releases = rl
        if (fresh != null) { rating = fresh.rating; ratingsCount = fresh.ratingsCount }
    }
    LaunchedEffect(a.id) { reload() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(22.dp)).background(T.bgElevated)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(a.icon, 64)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("by ${a.author}", fontSize = T.small, color = T.inkFaint)
                    }
                    StorePill(installedNow, onGet)
                }
                Spacer(Modifier.height(16.dp))
                // Stats strip
                Row(Modifier.fillMaxWidth()) {
                    MetaCell(if (ratingsCount > 0) "%.1f".format(rating) else "—", if (ratingsCount > 0) "★ $ratingsCount ratings" else "no ratings yet", Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(34.dp).background(T.hairline))
                    MetaCell(a.installs.toString(), "installs", Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(34.dp).background(T.hairline))
                    MetaCell("v${a.version}", a.category, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                // About
                Text("About", fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(a.description.ifBlank { "No description provided." }, fontSize = T.small, color = T.inkSoft)

                // What's New (version history)
                if (releases.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("What's New", fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    releases.take(6).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("v${r.version}", fontSize = T.caption, color = T.accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.notes.ifBlank { "Update" }, fontSize = T.caption, color = T.inkSoft)
                                Text(r.date, fontSize = 10.sp, color = T.inkFaint)
                            }
                        }
                    }
                }

                // Reviews
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ratings & Reviews", fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RatingLabel(rating, ratingsCount, 12)
                }
                Spacer(Modifier.height(10.dp))
                // Write a review
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bg).padding(12.dp)) {
                    Text("Rate this agent", fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(6.dp))
                    Stars(myStars, 22) { myStars = it }
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(myText, { myText = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp),
                        decorationBox = { inner -> if (myText.isEmpty()) Text("Say something (optional)", fontSize = T.small, color = T.inkFaint); inner() })
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Submit", fontSize = T.caption, color = Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (myStars > 0) T.accent else T.hairline)
                                .clickable(enabled = myStars > 0) {
                                    scope.launch {
                                        val (ok, m) = withContext(Dispatchers.IO) { AgentStore.postReview(ctx, a.id, myStars, myText) }
                                        reviewMsg = m
                                        if (ok) { myText = ""; myStars = 0; reload() }
                                    }
                                }.padding(horizontal = 16.dp, vertical = 7.dp))
                        if (reviewMsg.isNotBlank()) { Spacer(Modifier.width(10.dp)); Text(reviewMsg, fontSize = 11.sp, color = T.inkSoft) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (reviews.isEmpty()) {
                    Text("No reviews yet — be the first.", fontSize = T.caption, color = T.inkFaint)
                } else {
                    reviews.forEach { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Stars(r.stars, 12)
                                Spacer(Modifier.width(8.dp))
                                Text(r.author, fontSize = T.caption, color = T.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(r.date, fontSize = 10.sp, color = T.inkFaint)
                            }
                            if (r.body.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(r.body, fontSize = T.small, color = T.inkSoft) }
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onClose() })
            }
        }
    }
}

@Composable
private fun MetaCell(value: String, label: String, modifier: Modifier) =
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = T.small, color = T.ink, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, fontSize = 10.sp, color = T.inkFaint)
    }

/** Pick one of your local mini-apps and publish it to the store. */
@Composable
private fun PublishDialog(onClose: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val locals = remember { AppStore.load(ctx) }
    var picked by remember { mutableStateOf<AppStore.MiniApp?>(null) }
    var desc by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var icon by remember { mutableStateOf("🤖") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(T.bgElevated).padding(20.dp)) {
            Text("Publish an agent", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Bold)
            Text("Share one of your mini-apps with the world. Build them in the Architect first.", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(14.dp))
            if (locals.isEmpty()) {
                Text("You have no mini-apps yet — build one in the Architect, then publish it.", fontSize = T.small, color = T.inkSoft)
            } else {
                Text("WHICH APP", fontSize = 10.sp, color = T.inkFaint, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    locals.forEach { app ->
                        val sel = picked?.id == app.id
                        Text(app.name.take(18), fontSize = T.small, color = if (sel) Color.White else T.inkSoft, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else T.bg).clickable { picked = app }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                BasicTextField(desc, { desc = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(T.bg).padding(12.dp),
                    decorationBox = { inner -> if (desc.isEmpty()) Text("One-line description", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(icon, { icon = it.take(2) }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.body),
                        modifier = Modifier.width(56.dp).clip(RoundedCornerShape(12.dp)).background(T.bg).padding(vertical = 12.dp), )
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        AgentStore.CATEGORIES.forEach { c ->
                            val sel = category == c
                            Text(c, fontSize = T.caption, color = if (sel) Color.White else T.inkSoft,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else T.bg).clickable { category = c }.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
                if (err.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(err, fontSize = T.caption, color = T.danger) }
                Spacer(Modifier.height(16.dp))
                Text(if (busy) "Publishing…" else "Publish agent", fontSize = T.small, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (picked != null && !busy) T.accent else T.hairline)
                        .clickable(enabled = picked != null && !busy) {
                            busy = true; err = ""
                            scope.launch {
                                val app = picked!!
                                val (ok, m) = withContext(Dispatchers.IO) { AgentStore.publish(ctx, app.name, desc, category, icon, app.html) }
                                busy = false
                                if (ok) onDone(m) else err = m
                            }
                        }.padding(vertical = 13.dp), )
            }
            Spacer(Modifier.height(12.dp))
            Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onClose() })
        }
    }
}
