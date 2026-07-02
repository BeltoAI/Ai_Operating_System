package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Do I have X in my network?" → the matching people + a ready message + one-tap LinkedIn per person. */
@Composable
fun NetworkScreen(modifier: Modifier = Modifier, initialQuery: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var query by remember { mutableStateOf(initialQuery) }
    var people by remember { mutableStateOf<List<ConnectionStore.Conn>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    fun firstName(full: String) = full.trim().split(" ").firstOrNull().orEmpty()

    fun run(q: String) {
        if (busy || q.isBlank()) return
        busy = true; searched = true
        scope.launch {
            val ppl = withContext(Dispatchers.IO) { ConnectionStore.search(ctx, q, 25) }
            people = ppl
            if (ppl.isNotEmpty()) {
                val m = withContext(Dispatchers.IO) { AgentClient.networkOutreach(q, MemoryStore.fullProfile(ctx)) }
                if (!AgentClient.looksLikeError(m)) message = m
                // System note — sender is "system", not "me", so it never pollutes voice/style learning.
                MessageStore.insertOne(ctx, "Network", "Network", "system", "system", "Found ${ppl.size} people in my network for “$q”")
                MetricsStore.record(ctx, 300)
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) run(initialQuery) }

    fun openLinkedIn(c: ConnectionStore.Conn) {
        val personalized = message.replace("{name}", firstName(c.name).ifBlank { "there" })
        clip.setText(AnnotatedString(personalized))
        val url = c.url.ifBlank { "https://www.linkedin.com/search/results/people/?keywords=" + java.net.URLEncoder.encode(c.name, "UTF-8") }
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("My network") { onBack() }
        Spacer(Modifier.height(12.dp))
        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (query.isEmpty()) Text("Who are you looking for? e.g. CTOs, investors, people at Google", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(10.dp))
        Text(if (busy) "Searching…" else "Search my network", fontSize = T.body, color = T.bgElevated, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.accent)
                .clickable(enabled = !busy) { run(query) }.padding(vertical = 14.dp))

        if (message.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("Your message (edit if you like — {name} fills in per person):", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            BasicTextField(message, { message = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp))
        }

        if (people.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("${people.size} people in your network", fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(6.dp))
            people.forEach { c ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(c.name, fontSize = T.body, color = T.ink)
                    if (c.role.isNotBlank() || c.company.isNotBlank())
                        Text(listOf(c.role, c.company).filter { it.isNotBlank() }.joinToString(" @ "), fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Text("Message on LinkedIn", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable { openLinkedIn(c) }.padding(vertical = 11.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tapping copies the message and opens their LinkedIn — just paste and send.", fontSize = T.caption, color = T.inkFaint)
        } else if (searched && !busy) {
            Spacer(Modifier.height(16.dp))
            Text("No matches found. Make sure your LinkedIn connections are imported (Settings ▸ Import ▸ LinkedIn).",
                fontSize = T.small, color = T.inkFaint)
        }
        Spacer(Modifier.height(28.dp))
    }
}
