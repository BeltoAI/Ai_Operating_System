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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MetricsStore
import com.agentos.shell.tools.QuoteClient
import com.agentos.shell.tools.TradeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACC = Color(0xFFE8642C)
private val UP = Color(0xFF2E9E5B)
private val DOWN = Color(0xFFB23A2E)

/**
 * Practice investing — the agent designs a portfolio from fake money, you confirm the buy, and we
 * track how it really performs. Buys are always user-confirmed; later this becomes real with a broker.
 */
@Composable
fun TradeScreen(modifier: Modifier = Modifier, initialPrompt: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var started by remember { mutableStateOf(TradeStore.started(ctx)) }
    var risk by remember { mutableStateOf(TradeStore.risk(ctx)) }
    var interests by remember { mutableStateOf(TradeStore.interests(ctx).ifBlank { initialPrompt.take(80) }) }
    var amount by remember { mutableStateOf("1000") }
    var picks by remember { mutableStateOf<List<AgentClient.Pick>>(emptyList()) }
    var quotes by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    // Live portfolio numbers.
    var value by remember { mutableStateOf(0.0) }
    var holdings by remember { mutableStateOf(TradeStore.holdings(ctx)) }

    fun refreshPortfolio() {
        busy = "load"
        scope.launch {
            val h = TradeStore.holdings(ctx); holdings = h
            val q = withContext(Dispatchers.IO) { QuoteClient.prices(h.map { it.symbol }) }
            quotes = q
            val invested = h.sumOf { (q[it.symbol] ?: it.avgCost) * it.shares }
            value = TradeStore.cash(ctx) + invested
            TradeStore.saveSnapshot(ctx, value)
            busy = ""
        }
    }

    LaunchedEffect(Unit) { if (started) refreshPortfolio() }

    fun build() {
        val amt = amount.toDoubleOrNull() ?: 0.0
        if (busy.isNotEmpty() || amt <= 0) return
        busy = "build"; error = ""; picks = emptyList()
        TradeStore.setRisk(ctx, risk); TradeStore.setInterests(ctx, interests)
        scope.launch {
            val ps = withContext(Dispatchers.IO) { AgentClient.suggestPortfolio(amt, risk, interests, MemoryStore.fullProfile(ctx)) }
            if (ps.isEmpty()) { error = "Couldn't build a portfolio — set replies to Claude/GPT in Settings and retry."; busy = ""; return@launch }
            val q = withContext(Dispatchers.IO) { QuoteClient.prices(ps.map { it.symbol }) }
            val priced = ps.filter { q.containsKey(it.symbol) }
            if (priced.isEmpty()) { error = "Couldn't fetch live prices right now — try again in a moment."; busy = ""; return@launch }
            picks = priced; quotes = q; busy = ""
        }
    }

    fun sharesFor(p: AgentClient.Pick, amt: Double): Double {
        val price = quotes[p.symbol] ?: return 0.0
        return if (price > 0) (amt * p.weight / price) else 0.0
    }

    fun confirmBuy() {
        val amt = amount.toDoubleOrNull() ?: return
        if (busy.isNotEmpty() || picks.isEmpty()) return
        busy = "buy"
        scope.launch {
            withContext(Dispatchers.IO) {
                TradeStore.deposit(ctx, amt)
                picks.forEach { p -> val sh = sharesFor(p, amt); val px = quotes[p.symbol] ?: 0.0; if (sh > 0 && px > 0) TradeStore.buy(ctx, p.symbol, p.name, sh, px) }
                MessageStore.insertOne(ctx, "Trading", "Trade", "system", "system",
                    "Built a $${amt.toInt()} practice portfolio ($risk): " + picks.joinToString(", ") { "${it.symbol} ${(it.weight * 100).toInt()}%" })
                MetricsStore.record(ctx, 900)
            }
            started = true; picks = emptyList()
            refreshPortfolio()
        }
    }

    fun sellAll() {
        if (busy.isNotEmpty()) return
        busy = "sell"
        scope.launch {
            withContext(Dispatchers.IO) {
                val q = QuoteClient.prices(TradeStore.holdings(ctx).map { it.symbol })
                TradeStore.holdings(ctx).forEach { h -> TradeStore.sell(ctx, h.symbol, h.shares, q[h.symbol] ?: h.avgCost) }
                MessageStore.insertOne(ctx, "Trading", "Trade", "system", "system", "Sold the whole practice portfolio to cash.")
            }
            refreshPortfolio()
        }
    }

    val deposited = TradeStore.deposited(ctx)
    val growth = if (deposited > 0) (value - deposited) / deposited * 100.0 else 0.0

    @Composable
    fun chip(label: String, key: String) {
        Text(label, fontSize = T.small, color = if (risk == key) T.bgElevated else T.ink, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (risk == key) ACC else T.bgElevated)
                .clickable { risk = key }.padding(horizontal = 16.dp, vertical = 10.dp))
    }
    @Composable
    fun bigBtn(label: String, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, fontSize = T.body, color = if (accent) T.bgElevated else T.ink, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (accent) (if (enabled) ACC else T.hairline) else T.bgElevated)
                .clickable(enabled = enabled) { onClick() }.padding(vertical = 15.dp))
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Invest") { onBack() }

        // ── Live portfolio ──
        if (started && holdings.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Portfolio value", fontSize = T.caption, color = T.inkFaint)
            Text("$" + "%,.2f".format(value), fontSize = T.time, color = T.ink)
            Text("%+.1f%%".format(growth) + "  ·  from $" + "%,.0f".format(deposited), fontSize = T.body, color = if (growth >= 0) UP else DOWN)
            Spacer(Modifier.height(4.dp))
            Text("Practice account · fake money · " + (if (busy == "load") "updating…" else "tap Refresh for live prices"), fontSize = T.caption, color = T.inkFaint)
            Spacer(Modifier.height(14.dp))
            holdings.forEach { h ->
                val px = quotes[h.symbol] ?: h.avgCost
                val pl = if (h.avgCost > 0) (px - h.avgCost) / h.avgCost * 100.0 else 0.0
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.symbol, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                        Text("$" + "%,.2f".format(px * h.shares), fontSize = T.body, color = T.ink)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(("%.3f".format(h.shares)) + " sh · $" + "%.2f".format(px), fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                        Text("%+.1f%%".format(pl), fontSize = T.caption, color = if (pl >= 0) UP else DOWN)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            bigBtn(if (busy == "load") "Refreshing…" else "Refresh prices", accent = true, enabled = busy.isEmpty()) { refreshPortfolio() }
            Spacer(Modifier.height(8.dp))
            bigBtn("Sell everything to cash", accent = false, enabled = busy.isEmpty()) { sellAll() }
            Spacer(Modifier.height(8.dp))
            Text("Start a new practice run", fontSize = T.small, color = T.inkFaint,
                modifier = Modifier.clickable { TradeStore.reset(ctx); started = false; holdings = emptyList(); value = 0.0 }.padding(vertical = 8.dp))
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        // ── Portfolio preview (built, awaiting your confirmation to buy) ──
        if (picks.isNotEmpty()) {
            val amt = amount.toDoubleOrNull() ?: 0.0
            Spacer(Modifier.height(16.dp))
            Text("Your AI portfolio", fontSize = T.caption, color = T.inkFaint)
            Text("$" + "%,.0f".format(amt) + " · $risk", fontSize = T.prompt, color = T.ink)
            Spacer(Modifier.height(12.dp))
            picks.forEach { p ->
                val px = quotes[p.symbol] ?: 0.0; val sh = sharesFor(p, amt)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.symbol + "  ·  " + (p.weight * 100).toInt() + "%", fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                        Text("$" + "%,.0f".format(amt * p.weight), fontSize = T.body, color = ACC)
                    }
                    if (p.name.isNotBlank()) Text(p.name, fontSize = T.caption, color = T.inkFaint)
                    Text(("%.3f".format(sh)) + " sh @ $" + "%.2f".format(px) + (if (p.why.isNotBlank()) "  ·  ${p.why}" else ""), fontSize = T.caption, color = T.inkSoft)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            bigBtn(if (busy == "buy") "Buying…" else "Confirm & buy — $" + "%,.0f".format(amt), accent = true, enabled = busy.isEmpty()) { confirmBuy() }
            Spacer(Modifier.height(8.dp))
            Text("Rebuild it", fontSize = T.small, color = T.inkFaint, modifier = Modifier.clickable { picks = emptyList(); build() }.padding(vertical = 8.dp))
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        // ── Onboarding ──
        Spacer(Modifier.height(16.dp))
        Text("Let your AI invest — for practice", fontSize = T.prompt, color = T.ink)
        Text("It builds and runs a real portfolio with fake money so you can see how it performs. You confirm every buy. Goes real once we're licensed.", fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(16.dp))
        Text("HOW BOLD SHOULD IT BE?", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chip("Careful", "conservative"); chip("Balanced", "balanced"); chip("Adventurous", "aggressive") }
        Spacer(Modifier.height(14.dp))
        Text("ANYTHING YOU'RE INTO? (optional)", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        BasicTextField(interests, { interests = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (interests.isEmpty()) Text("tech, clean energy, dividends, crypto-ish…", fontSize = T.small, color = T.inkFaint); inner() })
        Spacer(Modifier.height(14.dp))
        Text("PRACTICE MONEY", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        BasicTextField(amount, { amount = it.filter { c -> c.isDigit() } }, textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> Row { Text("$", fontSize = T.body, color = T.inkFaint); Spacer(Modifier.width(4.dp)); Box { if (amount.isEmpty()) Text("1000", fontSize = T.body, color = T.inkFaint); inner() } } })
        Spacer(Modifier.height(14.dp))
        bigBtn(if (busy == "build") "Building your portfolio…" else "Build my portfolio", accent = true, enabled = busy.isEmpty() && (amount.toIntOrNull() ?: 0) > 0) { build() }
        if (error.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(error, fontSize = T.caption, color = T.danger) }
        Spacer(Modifier.height(28.dp))
    }
}
