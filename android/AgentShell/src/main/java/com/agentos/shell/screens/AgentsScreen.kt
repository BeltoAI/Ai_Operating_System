package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentCatalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agents — the shelf that replaces Powers.
 *
 * The Power store asked somebody else's GitHub repository to become a feature of your phone, and
 * every layer of that was a negotiation: clone it, install its dependencies, hope a Python wheel
 * exists for aarch64, watch a five-minute build you could not see. What it could reliably deliver
 * was a paragraph of distilled prose.
 *
 * An agent is one self-contained HTML file that runs in the sandbox SlyOS already has. So installing
 * is a local write that cannot half-fail, reviewing is a person reading a page, and the result looks
 * and behaves like a built-in screen because it runs in the same place the Architect's own output
 * does.
 *
 * The screen is deliberately plainer than the store it replaces. That one had a featured hero, four
 * rails, monograms, ratings and a search box — and shipped without the agents' NAMES on the cards.
 * Decoration is what you add when the shelf is thin.
 */
@Composable
fun AgentsScreen(
    modifier: Modifier = Modifier,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var listings by remember { mutableStateOf<List<AgentCatalogue.Listing>>(emptyList()) }
    var category by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<AgentCatalogue.Listing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(category) {
        loading = true
        listings = withContext(Dispatchers.IO) {
            try { AgentCatalogue.browse(ctx, category) } catch (e: Exception) { emptyList() }
        }
        loading = false
    }
    LaunchedEffect(query) {
        if (query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(320)
        listings = withContext(Dispatchers.IO) {
            try { AgentCatalogue.search(ctx, query) } catch (e: Exception) { emptyList() }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Agents", onBack)
        Spacer(Modifier.height(4.dp))
        Text("small programs that live on your phone and know your brain",
            fontSize = 10.sp, color = T.inkFaint)

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (query.isEmpty())
                Text("what do you want it to do?", fontSize = T.caption, color = T.inkFaint)
            BasicTextField(query, { query = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AgentCatalogue.CATEGORIES.forEach { c ->
                Box(Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (category == c) T.accent else T.bgElevated)
                    .clickable { category = c; query = "" }
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(c, fontSize = T.caption,
                        color = if (category == c) Color.White else T.inkSoft,
                        maxLines = 1, softWrap = false)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listings, key = { it.id }) { l ->
                val on = picked === l
                val here = remember(l.id, busy) { AgentCatalogue.installed(ctx, l) }
                val lift by animateFloatAsState(if (on) 1f else 0.995f,
                    spring(dampingRatio = 0.8f, stiffness = 400f), label = "a")
                Column(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (on) T.bgElevated else T.bg)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            picked = if (on) null else l; note = ""
                        }
                        .padding(horizontal = 16.dp, vertical = 15.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.icon, fontSize = 20.sp)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            // THE NAME. The store this replaces drew a monogram and a tagline and
                            // left the name for the detail page, so its shelf read "P · power to
                            // see the live web". A shop where nothing is labelled.
                            Text(l.name, fontSize = T.small, color = T.ink,
                                fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text(buildString {
                                    if (l.author.isNotBlank()) append("by ").append(l.author)
                                    if (l.installs > 0) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append("${l.installs} installs")
                                    }
                                    // Real ratings or none — never a decorative number.
                                    if (l.ratingsCount > 0) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append("★ ${"%.1f".format(l.rating)} (${l.ratingsCount})")
                                    }
                                }, fontSize = 10.sp, color = T.inkFaint, maxLines = 1)
                        }
                        if (here) Text("added", fontSize = 9.sp, color = T.good)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(l.description, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp,
                        maxLines = if (on) 8 else 2)

                    if (on) {
                        Spacer(Modifier.height(13.dp))
                        Text(if (busy) "…" else if (here) "Open it" else "Add to my phone",
                            fontSize = T.caption, color = Color.White,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                                .background(T.accent).clickable(enabled = !busy) {
                                    busy = true
                                    scope.launch {
                                        val id = withContext(Dispatchers.IO) {
                                            try {
                                                if (here) com.agentos.shell.tools.AppStore.load(ctx)
                                                    .firstOrNull { it.name.equals(l.name, true) }?.id ?: -1L
                                                else AgentCatalogue.install(ctx, l)
                                            } catch (e: Exception) { -1L }
                                        }
                                        busy = false
                                        // Straight into it. An agent you cannot immediately use is
                                        // an install nobody can tell happened.
                                        if (id > 0) onOpen(id) else note = "Couldn't add that one."
                                    }
                                }.padding(vertical = 14.dp))
                        if (note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(note, fontSize = 10.sp, color = T.danger)
                        }
                    }
                }
            }
            if (listings.isEmpty() && !loading) item {
                Spacer(Modifier.height(30.dp))
                Text(if (query.isNotBlank()) "Nothing matches."
                     else "No agents published yet. Ask the Architect to build you one — " +
                          "anything it makes can be published here.",
                    fontSize = T.caption, color = T.inkFaint, lineHeight = 19.sp)
            }
            if (loading) item {
                Spacer(Modifier.height(30.dp))
                Text("loading…", fontSize = T.caption, color = T.inkFaint)
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}
