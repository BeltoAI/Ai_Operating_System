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
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shop — say what you want, SlyOS web-finds the real, best-value places to buy it, and you tap to
 * open the exact listing (your saved address is copied so checkout autofill is one paste). It never
 * spends on its own; the buy tap is always yours. Feeds the brain.
 */
@Composable
fun ShopScreen(modifier: Modifier = Modifier, initialQuery: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var query by remember { mutableStateOf(initialQuery) }
    var products by remember { mutableStateOf<List<AgentClient.Product>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val ship = remember { MemoryStore.shippingProfile(ctx) }

    fun openUrl(u: String) {
        if (u.isBlank()) return
        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    fun run(q: String) {
        if (busy || q.isBlank()) return
        busy = true; msg = ""; products = emptyList()
        scope.launch {
            val res = withContext(Dispatchers.IO) { AgentClient.findProducts(q, MemoryStore.fullProfile(ctx)) }
            products = res; busy = false
            if (res.isEmpty()) msg = "No buy options came back — web search needs Claude (set replies/Heavy to Claude in Settings), or try more detail."
            else withContext(Dispatchers.IO) {
                MessageStore.insertOne(ctx, "Shop", "Shop", "system", "system", "Shopped “$q”: " + res.take(3).joinToString(" · ") { "${it.name} ${it.price} (${it.merchant})" })
                MetricsStore.record(ctx, 600)
            }
        }
    }

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) run(initialQuery) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Shop") { onBack() }
        Spacer(Modifier.height(12.dp))

        BasicTextField(query, { query = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (query.isEmpty()) Text("What do you want to buy? (brand, size, budget…)", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(8.dp))
        Text(if (busy) "Finding the best price…" else "Find best price", fontSize = T.body, color = T.bgElevated, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (busy || query.isBlank()) T.hairline else T.accent)
                .clickable(enabled = !busy && query.isNotBlank()) { run(query) }.padding(vertical = 15.dp))

        if (ship.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("Ship to: " + ship.replace("\n", " · "), fontSize = T.caption, color = T.inkFaint) }
        if (msg.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(msg, fontSize = T.caption, color = T.inkFaint) }

        if (products.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            products.forEachIndexed { i, p ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (i == 0) T.accent.copy(alpha = 0.10f) else T.bgElevated).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = T.small, color = T.ink, modifier = Modifier.weight(1f))
                        if (p.price.isNotBlank()) { Spacer(Modifier.width(8.dp)); Text(p.price, fontSize = T.body, color = T.accent) }
                    }
                    Text(p.merchant + (if (p.note.isNotBlank()) "  ·  ${p.note}" else "") + (if (i == 0) "  ·  best pick" else ""), fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Text("Open to buy →", fontSize = T.small, color = T.bgElevated, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(T.accent)
                            .clickable {
                                if (ship.isNotBlank()) clip.setText(AnnotatedString(ship))   // paste-to-fill at checkout
                                openUrl(p.url)
                                scope.launch { withContext(Dispatchers.IO) { MessageStore.insertOne(ctx, "Shop", "Shop", "me", "me", "Opened to buy: ${p.name} ${p.price} @ ${p.merchant}") } }
                            }.padding(vertical = 11.dp))
                }
            }
            if (ship.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Your address is copied on tap — paste it into checkout.", fontSize = T.caption, color = T.inkFaint) }
        }
        Spacer(Modifier.height(28.dp))
    }
}
