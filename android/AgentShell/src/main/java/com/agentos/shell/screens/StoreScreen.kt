package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentStore
import com.agentos.shell.tools.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Agent Store — browse, search, install and run community agents (sandboxed HTML mini-apps), and
 * publish your own. Replaces the old on-phone "Apps" list in the bottom nav.
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

    Column(modifier) {
        ScreenHeader("Agent Store") { onBack() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Community agents — install & run, or publish your own", fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
            Text("＋ Publish", fontSize = T.caption, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable { showPublish = true }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search agents…", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.width(8.dp))
            Text("Go", fontSize = T.small, color = T.bgElevated, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable { refresh() }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf("" to "All") + AgentStore.CATEGORIES.map { it to it }).forEach { (id, lbl) ->
                val sel = category == id
                Text(lbl, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else T.hairline).clickable { category = id }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!AgentStore.configured()) {
            Text("Store backend isn't set up yet (SUPABASE keys + the agents table). See AGENT_STORE.md.", fontSize = T.small, color = T.inkFaint)
        } else if (loading) {
            Text("Loading agents…", fontSize = T.small, color = T.inkFaint)
        } else if (agents.isEmpty()) {
            Column {
                Text("No agents yet. Be the first — tap Publish, or build one in the Architect.", fontSize = T.small, color = T.inkFaint)
                Spacer(Modifier.height(10.dp))
                Text("Build an agent →", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { onArchitect() })
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(agents, key = { it.id }) { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selected = a }.padding(vertical = 11.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(T.accentSoft), contentAlignment = Alignment.Center) {
                        Text(a.icon.take(2), fontSize = T.body)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
                        Text("${a.author} · ${a.category} · ${a.installs} installs", fontSize = T.caption, color = T.inkFaint, maxLines = 1)
                    }
                    Text(if (installed.containsKey(a.id)) "Open" else "Get", fontSize = T.small, color = T.bgElevated,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable {
                                val local = installed[a.id]
                                if (local != null) onOpenApp(local)
                                else scope.launch {
                                    status = "Installing ${a.name}…"
                                    val id = withContext(Dispatchers.IO) { AgentStore.install(ctx, a) }
                                    if (id != null) { installed[a.id] = id; status = ""; onOpenApp(id) } else status = "Couldn't install."
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Hairline()
            }
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    }

    // Detail sheet.
    selected?.let { a ->
        Dialog(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(T.accentSoft), contentAlignment = Alignment.Center) { Text(a.icon.take(2), fontSize = T.body) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(a.name, fontSize = T.body, color = T.ink, fontWeight = FontWeight.SemiBold); Text("by ${a.author} · ${a.installs} installs", fontSize = T.caption, color = T.inkFaint) }
                }
                Spacer(Modifier.height(10.dp))
                Text(a.description.ifBlank { "No description." }, fontSize = T.small, color = T.inkSoft)
                Spacer(Modifier.height(16.dp))
                Text(if (installed.containsKey(a.id)) "Open" else "Install & run", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                        .clickable {
                            val local = installed[a.id]
                            if (local != null) { selected = null; onOpenApp(local) }
                            else scope.launch {
                                val id = withContext(Dispatchers.IO) { AgentStore.install(ctx, a) }
                                if (id != null) { installed[a.id] = id; selected = null; onOpenApp(id) } else status = "Couldn't install."
                            }
                        }.padding(horizontal = 18.dp, vertical = 10.dp))
                Spacer(Modifier.height(6.dp))
                Text("Agents run sandboxed on your phone.", fontSize = T.caption, color = T.inkFaint)
            }
        }
    }

    if (showPublish) PublishDialog(onClose = { showPublish = false }) { msg -> showPublish = false; status = msg; refresh() }
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
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
            Text("Publish an agent", fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
            Text("Share one of your mini-apps with the world. Build them in the Architect first.", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(12.dp))
            if (locals.isEmpty()) {
                Text("You have no mini-apps yet — build one in the Architect, then publish it.", fontSize = T.small, color = T.inkSoft)
            } else {
                Text("Which app?", fontSize = T.caption, color = T.inkSoft)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    locals.forEach { app ->
                        val sel = picked?.id == app.id
                        Text(app.name.take(18), fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                            modifier = Modifier.padding(end = 8.dp, top = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else T.hairline).clickable { picked = app }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                BasicTextField(desc, { desc = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp),
                    decorationBox = { inner -> if (desc.isEmpty()) Text("One-line description", fontSize = T.small, color = T.inkFaint); inner() })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(icon, { icon = it.take(2) }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.body),
                        modifier = Modifier.width(56.dp).clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp))
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        AgentStore.CATEGORIES.forEach { c ->
                            val sel = category == c
                            Text(c, fontSize = T.caption, color = if (sel) T.bgElevated else T.inkSoft,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (sel) T.accent else T.hairline).clickable { category = c }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                if (err.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(err, fontSize = T.caption, color = T.danger) }
                Spacer(Modifier.height(14.dp))
                Text(if (busy) "Publishing…" else "Publish", fontSize = T.small, color = T.bgElevated,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (picked != null && !busy) T.accent else T.hairline)
                        .clickable(enabled = picked != null && !busy) {
                            busy = true; err = ""
                            scope.launch {
                                val app = picked!!
                                val (ok, m) = withContext(Dispatchers.IO) { AgentStore.publish(ctx, app.name, desc, category, icon, app.html) }
                                busy = false
                                if (ok) onDone(m) else err = m
                            }
                        }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Close", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { onClose() })
        }
    }
}
