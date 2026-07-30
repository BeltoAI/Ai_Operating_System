package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.Brain
import com.agentos.shell.tools.Crm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Who you know, what you call each other, and where you stand.
 *
 * This replaces a contact list, which is a strange thing for a phone to still have. A contact list
 * answers "what is their number" — a question that mattered when you had to dial it. It has never
 * answered the two questions people actually have, which are *who is this again* and *do I owe them
 * something*, and on this device every ingredient for both was already stored and never assembled.
 *
 * Three tabs, because there are only three real questions:
 *
 *  - **People** — everyone, most recent first, with the channel you actually use and how long it has
 *    been. Not alphabetical: nobody looks for a person by the letter their name starts with, they
 *    look for the person they were just speaking to.
 *  - **Companies** — the same people grouped by where they work, which is how deals are remembered
 *    and how a contact list has never let you look.
 *  - **Owed** — they wrote and you did not reply, or you wrote and they did not. The only tab that
 *    is a to-do list, and the reason to open the page at all.
 *
 * The filter takes a sentence rather than a prefix, because "investors I haven't spoken to since
 * June" is the actual query and no amount of typing "inv" gets to it.
 */
@Composable
fun CrmScreen(
    modifier: Modifier = Modifier,
    onEmail: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var all by remember { mutableStateOf(listOf<Crm.Person>()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var aiKeys by remember { mutableStateOf<Set<String>?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var openPerson by remember { mutableStateOf<Crm.Person?>(null) }
    /** Matches from the 20,005-strong connection list — people known OF, not talked to. */
    var network by remember { mutableStateOf(listOf<Crm.Person>()) }

    // Resolution walks the whole message table — never on the main thread.
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { try { Crm.people(ctx, 400) } catch (e: Exception) { emptyList() } }
        loading = false
    }

    /** The whole connection list, loaded once and only when asked for. */
    var netAll by remember { mutableStateOf(listOf<Crm.Person>()) }
    LaunchedEffect(tab) {
        if (tab == 3 && netAll.isEmpty()) {
            netAll = withContext(Dispatchers.IO) {
                try { Crm.networkAll(ctx, 3000) } catch (e: Exception) { emptyList() }
            }
        }
    }

    // Searching also reaches the rest of the network, debounced so it is not a query per keystroke.
    LaunchedEffect(query) {
        if (query.trim().length < 2) { network = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(220)
        network = withContext(Dispatchers.IO) {
            try { Crm.networkSearch(ctx, query, 40) } catch (e: Exception) { emptyList() }
        }
    }

    openPerson?.let { p ->
        PersonPage(p, modifier, onEmail = onEmail, onBack = { openPerson = null })
        return
    }

    // RECIPROCAL FIRST, ONE-WAY ONLY ON REQUEST.
    //
    // Default to people you have actually spoken WITH. Inbound-only senders are real rows and worth
    // keeping — a cold approach you never answered is a lead — but there are hundreds of them and
    // they are mostly newsletters, so they belong behind a deliberate tap rather than at the top of
    // the page pretending to be the people you know.
    var oneWay by remember { mutableStateOf(false) }
    // Plain text narrows by name, handle, company or role. A sentence goes to the model.
    val filtered = remember(all, query, aiKeys, tab, oneWay, netAll) {
        val book = if (oneWay) all.filterNot { it.reciprocal } else all.filter { it.reciprocal }
        val base = when (tab) {
            1 -> book
            2 -> all.filter { it.owedByMe || it.owedByThem }
            3 -> netAll
            else -> book
        }
        val byAi = aiKeys?.let { keys -> base.filter { it.key in keys } } ?: base
        if (query.isBlank() || aiKeys != null) byAi
        else byAi.filter { p ->
            p.name.contains(query, true) || p.company.contains(query, true) ||
                p.role.contains(query, true) ||
                p.identities.any { it.handle.contains(query, true) || it.platform.contains(query, true) }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("People", onBack)

        // ── Tabs ──
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("People", "Companies", "Owed", "Network").forEachIndexed { i, label ->
                val on = tab == i
                val owed = if (i == 2) all.count { it.owedByMe || it.owedByThem } else 0
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                        .background(if (on) T.accent else T.bgElevated)
                        .clickable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); tab = i }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (i == 2 && owed > 0) "$label · $owed" else label,
                        fontSize = 10.sp, color = if (on) Color.White else T.inkSoft,
                        maxLines = 1, softWrap = false)
                }
            }
        }

        // ── The filter that takes a sentence ──
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (query.isEmpty())
                Text("a name, or “investors I've gone quiet on”",
                    fontSize = T.caption, color = T.inkFaint)
            BasicTextField(query, { query = it; aiKeys = null },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }
        // Only offered when the query reads like a question rather than a name — otherwise it is a
        // button that does nothing useful sitting under every search.
        if (query.trim().split(' ').size >= 3) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (thinking) "thinking…" else "Ask it", enabled = !thinking) {
                    thinking = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val keys = withContext(Dispatchers.IO) { aiFilter(ctx, all, query) }
                        aiKeys = keys; thinking = false
                    }
                }
                if (aiKeys != null) Chip("Clear") { aiKeys = null; query = "" }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(when {
                loading -> "resolving…"
                aiKeys != null -> "${filtered.size} matched"
                tab == 1 -> "${Crm.companies(all).size} companies · ${all.size} people"
                network.isNotEmpty() -> "${filtered.size} in conversation · ${network.size} more in your network"
                tab == 3 -> "${filtered.size} connections · never messaged"
                oneWay -> "${filtered.size} you never replied to"
                else -> "${filtered.size} you've spoken with"
            }, fontSize = 10.sp, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (oneWay) "← Spoke with" else "Never replied to") { oneWay = !oneWay }
            }
            Spacer(Modifier.height(8.dp))
        }

        // LAZY, BECAUSE THE BOOK IS NOT SMALL.
        //
        // A Column with a scroll modifier composes every child it is given. With four thousand
        // conversations and twenty thousand connections behind them that is tens of thousands of
        // rows built before the first one appears — a guaranteed freeze on the screen whose whole
        // job is to open instantly. LazyColumn builds the dozen that are visible.
        LazyColumn(Modifier.fillMaxWidth()) {
            if (tab == 1) {
                Crm.companies(filtered).forEach { c ->
                    item(key = "co_" + c.name) {
                        Column {
                            Text(c.name, fontSize = T.small, color = T.ink,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                            Text("${c.people.size} ${if (c.people.size == 1) "person" else "people"}",
                                fontSize = 10.sp, color = T.inkFaint)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    items(c.people.take(8), key = { "cp_" + c.name + it.key }) { p ->
                        PersonRow(p) { openPerson = p }
                    }
                }
            } else {
                items(filtered, key = { it.key }) { p -> PersonRow(p) { openPerson = p } }

                // ── The rest of the network ──
                //
                // Shown as a separate group and never mixed in, because "someone you have never
                // spoken to" is a different fact from "someone you spoke to on Tuesday", and a list
                // that blends them makes the CRM look like it has twenty thousand relationships.
                val fresh = if (tab == 3) emptyList()
                    else network.filterNot { n -> filtered.any { it.name.equals(n.name, true) } }
                if (fresh.isNotEmpty()) {
                    item(key = "nethdr") {
                        Column {
                            Spacer(Modifier.height(18.dp))
                            SectionLabel("ALSO IN YOUR NETWORK")
                            Text("connected, never messaged", fontSize = 10.sp, color = T.inkFaint,
                                modifier = Modifier.padding(top = 3.dp, bottom = 6.dp))
                        }
                    }
                    items(fresh, key = { it.key }) { p -> PersonRow(p) { openPerson = p } }
                }
            }
            if (!loading && filtered.isEmpty() && network.isEmpty()) {
                item(key = "empty") {
                    Column {
                        Spacer(Modifier.height(30.dp))
                        Text("Nobody here.", fontSize = T.small, color = T.inkFaint)
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(60.dp)) }
        }
    }
}

/** One line per person: who, where you talk, and how long it has been. */
@Composable
private fun PersonRow(p: Crm.Person, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = T.small, color = T.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(buildString {
                    if (p.role.isNotBlank()) append(p.role.take(28))
                    if (p.company.isNotBlank()) {
                        if (isNotEmpty()) append(" · "); append(p.company)
                    }
                    if (isEmpty()) append(p.mainChannel)
                    // Several channels is itself worth knowing at a glance.
                    if (p.platforms.size > 1) append(" · ${p.platforms.size} channels")
                }, fontSize = 10.sp, color = T.inkFaint, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            StagePill(p.stage)
            Spacer(Modifier.height(3.dp))
            Text(when {
                    p.lastAny <= 0 -> ""
                    p.silentDays == 0 -> "today"
                    p.silentDays == 1 -> "yesterday"
                    p.silentDays < 60 -> "${p.silentDays}d"
                    else -> "${p.silentDays / 30}mo"
                }, fontSize = 9.sp, color = if (p.owedByMe) T.danger else T.inkFaint)
        }
    }
    Hairline()
}

@Composable
private fun StagePill(s: Crm.Stage) {
    val c = when (s) {
        Crm.Stage.TALKING -> T.good
        Crm.Stage.WARM -> T.accent
        Crm.Stage.COOLING -> T.danger
        else -> T.inkFaint
    }
    Text(Crm.stageLabel(s), fontSize = 8.sp, color = c, fontWeight = FontWeight.Medium,
        maxLines = 1, softWrap = false)
}

/**
 * One person, everything.
 *
 * The identities are listed rather than summarised because that IS the answer to "what's their
 * Instagram" — a question a contact list cannot answer about someone whose number it holds.
 */
@Composable
private fun PersonPage(
    p: Crm.Person,
    modifier: Modifier = Modifier,
    onEmail: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var about by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader(p.name.take(24), onBack)

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StagePill(p.stage)
            Text(" · " + when {
                    p.lastAny <= 0 -> "never spoken"
                    p.silentDays == 0 -> "spoke today"
                    else -> "${p.silentDays} days since you spoke"
                }, fontSize = T.caption, color = T.inkFaint)
        }
        if (p.role.isNotBlank() || p.company.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(listOf(p.role, p.company).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
        }
        if (p.owedByMe) {
            Spacer(Modifier.height(10.dp))
            Text("They wrote last — you haven't replied.", fontSize = T.caption, color = T.danger)
        } else if (p.owedByThem) {
            Spacer(Modifier.height(10.dp))
            Text("You wrote last — no answer yet.", fontSize = T.caption, color = T.inkFaint)
        }

        // ── WHO THEY ARE, EVERYWHERE ──
        Spacer(Modifier.height(22.dp))
        SectionLabel("KNOWN AS")
        Spacer(Modifier.height(6.dp))
        p.identities.groupBy { it.platform }.forEach { (platform, ids) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(platform, fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.width(84.dp), maxLines = 1)
                Column(Modifier.weight(1f)) {
                    ids.sortedByDescending { it.messages }.forEach { i ->
                        Text(i.handle, fontSize = T.caption, color = T.ink, maxLines = 1)
                    }
                }
                Text("${ids.sumOf { it.messages }}", fontSize = 9.sp, color = T.inkFaint)
            }
            Hairline()
        }
        if (p.emails.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(p.emails.joinToString("\n"), fontSize = T.caption, color = T.inkSoft,
                lineHeight = 18.sp)
        }

        // ── ASK, AND WRITE ──
        Spacer(Modifier.height(24.dp))
        SectionLabel("ASK OR WRITE")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (busy) "…" else "What's the story?", enabled = !busy) {
                busy = true; about = ""
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        try {
                            // EVERY channel at once. A summary written from the email thread alone
                            // is blind to the two thousand messages beside it.
                            // SPEAK TO THEM, NOT ABOUT THEM.
                            //
                            // The first version asked for "who they are to the owner" and got back
                            // "the owner's spouse" — about a man's own wife, to his face. Every
                            // prompt in this app is written from the outside and the OUTPUT must not
                            // be: it is read by the one person who needs no introduction to their
                            // own marriage.
                            AgentClient.complete(
                                "You brief the reader on someone they know. Address them directly " +
                                "as \"you\". Never say \"the owner\", \"the user\" or refer to " +
                                "the reader in the third person. Plain, specific, no filler.",
                                "Here is everything you know about this person and every message " +
                                    "between them and me, across all channels.\n\n" +
                                    Crm.historyBrief(ctx, p) +
                                    "\n\nIn under 120 words, written to me: who this person is to " +
                                    "me, what the last thing between us was, and what is " +
                                    "outstanding. If they are family or a partner, say so plainly " +
                                    "— \"your wife\", not \"the spouse\". Only what the material " +
                                    "supports; invent nothing.", 500)
                        } catch (e: Exception) { "" }
                    }
                    about = out.ifBlank { "Couldn't summarise that." }
                    busy = false
                    // Into the brain, so the same question from Home gets the same answer.
                    withContext(Dispatchers.IO) {
                        try {
                            Brain.remember(ctx, "note", "About ${p.name}", about, role = "system")
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        if (about.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(about, fontSize = T.caption, color = T.ink, lineHeight = 19.sp)
        }

        // ── MESSAGE THEM, ON WHICHEVER CHANNEL YOU ACTUALLY USE ──
        //
        // Email was the only way out of this page, which is wrong for a book where most people are
        // reached on WhatsApp or Instagram. Pick the channel and the draft changes with it: the
        // persona set for that platform in Settings, and the history of THAT thread — because where
        // you left off on Instagram is no guide to where you left off in email.
        Spacer(Modifier.height(24.dp))
        SectionLabel("MESSAGE THEM")
        Spacer(Modifier.height(8.dp))
        var channel by remember { mutableStateOf(p.mainChannel) }
        var topic by remember { mutableStateOf("") }
        var sent by remember { mutableStateOf("") }
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.platforms.forEach { plat ->
                val on = plat == channel
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (on) T.accent else T.bgElevated)
                        .clickable { channel = plat; draft = ""; sent = "" }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(plat, fontSize = T.caption,
                        color = if (on) Color.White else T.inkSoft, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (topic.isEmpty())
                Text("what about?", fontSize = T.caption, color = T.inkFaint)
            BasicTextField(topic, { topic = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Chip(if (busy) "writing…" else "Draft for $channel",
            enabled = !busy && topic.isNotBlank() && channel.isNotBlank()) {
            busy = true; draft = ""; sent = ""
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                val out = withContext(Dispatchers.IO) {
                    try {
                        AgentClient.complete(
                            "You write messages in the owner's voice. Message only, no preamble.",
                            Crm.draftPromptFor(ctx, p, channel, topic), 600)
                    } catch (e: Exception) { "" }
                }
                draft = out.ifBlank { "Couldn't write that one." }
                busy = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // Every draft into the brain, like everything else here.
                withContext(Dispatchers.IO) {
                    try {
                        Brain.remember(ctx, "note", "Draft to ${p.name} on $channel",
                            "About: $topic\n\n$draft", actors = listOf(p.name), role = "me")
                    } catch (e: Exception) {}
                }
            }
        }
        if (draft.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated)
                .padding(14.dp)) {
                BasicTextField(draft, { draft = it },
                    textStyle = TextStyle(color = T.ink, fontSize = T.caption, lineHeight = 20.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            val route = remember(channel, draft) { Crm.sendAction(p, channel, draft) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (channel.equals("Email", true) && p.emails.isNotEmpty()) {
                    // Email keeps going through the review screen, which is the one that can attach.
                    Chip("Open the email") { onEmail(p.emails.first()) }
                } else if (route != null) {
                    Chip(if (busy) "…" else "Send on $channel", enabled = !busy) {
                        busy = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                try {
                                    com.agentos.shell.tools.ToolRouter.executeAction(
                                        ctx, route.first, route.second.toString())
                                } catch (e: Exception) { "Couldn't: ${e.message?.take(50)}" }
                            }
                            sent = r.ifBlank { "Sent ✓" }; busy = false
                        }
                    }
                } else {
                    // Said plainly rather than offering a button that cannot work. Instagram and X
                    // have no send API here; the draft is still the useful part.
                    Text("$channel has no send from here — copy it across.",
                        fontSize = 10.sp, color = T.inkFaint)
                }
            }
            if (sent.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(sent, fontSize = T.caption,
                    color = if (sent.contains("✓")) T.good else T.danger)
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}

/**
 * The filter that takes a sentence.
 *
 * The model is given ONLY a numbered list of one-line summaries and asked for numbers back — never
 * asked to write the answer. A model that returns row numbers cannot invent a person, and inventing
 * a contact who does not exist is the one failure a directory must not have.
 */
private suspend fun aiFilter(
    ctx: android.content.Context, all: List<Crm.Person>, question: String
): Set<String> = try {
    val pool = all.take(140)
    val listing = pool.mapIndexed { i, p ->
        "$i. ${p.name}${if (p.role.isNotBlank()) " — ${p.role}" else ""}" +
            "${if (p.company.isNotBlank()) " at ${p.company}" else ""}" +
            " · ${Crm.stageLabel(p.stage)} · ${p.silentDays}d ago · ${p.mainChannel}"
    }.joinToString("\n")
    val raw = AgentClient.complete(
        "You select rows from a list. Reply with numbers only, comma-separated. Nothing else.",
        "People:\n$listing\n\nWhich of these match: \"$question\"\n" +
            "Reply with only the numbers, comma-separated. If none match, reply NONE.", 300)
    Regex("\\d+").findAll(raw).mapNotNull { m ->
        pool.getOrNull(m.value.toIntOrNull() ?: -1)?.key
    }.toSet()
} catch (e: Exception) { emptySet() }

@Composable
private fun Chip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (enabled) 1f else 0.98f,
        spring(dampingRatio = 0.7f, stiffness = 400f), label = "c")
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = T.caption, color = if (enabled) T.inkSoft else T.inkFaint,
            maxLines = 1, softWrap = false)
    }
}
