package com.agentos.shell.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Practice investing — the agent designs a portfolio from practice money, you confirm the buy, and we
 * track real market performance (live prices, a value graph, a natural-language buy/sell bar, and
 * daily/big-move alerts). Buys always need your tap. Goes real once we're licensed.
 */
@Composable
fun TradeScreen(modifier: Modifier = Modifier, initialPrompt: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // The invest arg from Home AI may be structured ("amount:1000, risk:balanced") — parse it out so it
    // sets the amount/risk fields instead of polluting the interests box.
    val parsed = remember {
        val low = initialPrompt.lowercase()
        val amt = Regex("(?:amount[:=]\\s*\\$?|\\$)(\\d{2,7})").find(low)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(\\d{3,7})\\b").find(low)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rsk = when {
            low.contains("conservativ") || low.contains("careful") || low.contains("safe") -> "conservative"
            low.contains("aggress") || low.contains("adventur") || low.contains("bold") || low.contains("risky") -> "aggressive"
            low.contains("balanced") || low.contains("moderate") -> "balanced"; else -> ""
        }
        val rest = initialPrompt
            .replace(Regex("(?i)amount[:=]\\s*\\$?\\d+"), "").replace(Regex("(?i)risk[:=]\\s*\\w+"), "")
            .replace(Regex("\\$?\\d{3,7}"), "")
            .replace(Regex("(?i)\\b(conservative|careful|safe|aggressive|adventurous|bold|risky|balanced|moderate|invest|trade|trading|portfolio|for me|make money|money)\\b"), "")
            .replace(Regex("[,;:]+"), " ").trim()
        Triple(rest, amt, rsk)
    }
    var started by remember { mutableStateOf(TradeStore.started(ctx)) }
    var risk by remember { mutableStateOf(parsed.third.ifBlank { TradeStore.risk(ctx) }) }
    var interests by remember { mutableStateOf(TradeStore.interests(ctx).takeUnless { it.contains("amount:") || it.contains("risk:") }.orEmpty().ifBlank { parsed.first }) }
    var amount by remember { mutableStateOf(if (parsed.second > 0) parsed.second.toString() else "1000") }
    var picks by remember { mutableStateOf<List<AgentClient.Pick>>(emptyList()) }
    var quotes by remember { mutableStateOf<Map<String, QuoteClient.Quote>>(emptyMap()) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    var value by remember { mutableStateOf(0.0) }
    var dayPct by remember { mutableStateOf(0.0) }
    var refreshing by remember { mutableStateOf(false) }   // live price refresh — separate from command busy
    var updatedAt by remember { mutableStateOf(0L) }
    var marketState by remember { mutableStateOf("") }
    var priceMsg by remember { mutableStateOf("") }
    var holdings by remember { mutableStateOf(TradeStore.holdings(ctx)) }
    var series by remember { mutableStateOf(TradeStore.valueSeries(ctx)) }

    // Buy/sell command bar.
    data class Plan(val action: String, val symbol: String, val name: String, val shares: Double, val price: Double, val cost: Double)
    var cmd by remember { mutableStateOf("") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var cmdError by remember { mutableStateOf("") }

    fun refreshPortfolio() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val h = TradeStore.holdings(ctx); holdings = h
                val q = withContext(Dispatchers.IO) { QuoteClient.quotes(h.map { it.symbol }) }
                if (q.isNotEmpty()) quotes = q
                val keySet = com.agentos.shell.tools.QuoteClient.finnhubKey.isNotBlank()
                priceMsg = when {
                    h.isEmpty() -> ""
                    q.isEmpty() -> "⚠ feed unreachable (0/${h.size})" + (if (!keySet) " · add a Finnhub key in Settings" else " · retrying")
                    q.size < h.size -> "⚠ ${q.size}/${h.size} live · missing: ${h.filter { !q.containsKey(it.symbol) }.joinToString(", ") { it.symbol }}" + (if (!keySet) " · add Finnhub key" else "")
                    else -> "${q.size}/${q.size} live ✓" + (if (keySet) " · finnhub" else "")
                }
                val use = if (q.isNotEmpty()) q else quotes
                val invested = h.sumOf { (use[it.symbol]?.price ?: it.avgCost) * it.shares }
                val prevInv = h.sumOf { (use[it.symbol]?.prevClose ?: it.avgCost) * it.shares }
                value = TradeStore.cash(ctx) + invested
                val prevVal = TradeStore.cash(ctx) + prevInv
                dayPct = if (prevVal > 0) (value - prevVal) / prevVal * 100.0 else 0.0
                marketState = use.values.firstOrNull()?.state ?: ""
                updatedAt = System.currentTimeMillis()
                TradeStore.saveSnapshot(ctx, value)
                series = TradeStore.valueSeries(ctx)
            } catch (e: Exception) { priceMsg = "⚠ refresh error: ${e.message}" } finally { refreshing = false }
        }
    }
    // Auto-refresh live prices every ~15s while viewing the portfolio — no manual button needed.
    LaunchedEffect(started) { while (started) { refreshPortfolio(); kotlinx.coroutines.delay(15_000) } }

    fun build() {
        val amt = amount.toDoubleOrNull() ?: 0.0
        if (busy.isNotEmpty() || amt <= 0) return
        busy = "build"; error = ""; picks = emptyList()
        TradeStore.setRisk(ctx, risk); TradeStore.setInterests(ctx, interests)
        scope.launch {
            val ps = withContext(Dispatchers.IO) { AgentClient.suggestPortfolio(amt, risk, interests, MemoryStore.fullProfile(ctx)) }
            if (ps.isEmpty()) { error = "Couldn't build a portfolio — set replies to Claude/GPT in Settings and retry."; busy = ""; return@launch }
            val q = withContext(Dispatchers.IO) { QuoteClient.quotes(ps.map { it.symbol }) }
            val priced = ps.filter { q.containsKey(it.symbol) }
            if (priced.isEmpty()) { error = "Couldn't fetch live prices right now — try again in a moment."; busy = ""; return@launch }
            picks = priced; quotes = q; busy = ""
        }
    }
    fun sharesFor(p: AgentClient.Pick, amt: Double): Double { val px = quotes[p.symbol]?.price ?: return 0.0; return if (px > 0) amt * p.weight / px else 0.0 }

    fun confirmBuild() {
        val amt = amount.toDoubleOrNull() ?: return
        if (busy.isNotEmpty() || picks.isEmpty()) return
        busy = "buy"
        scope.launch {
            withContext(Dispatchers.IO) {
                TradeStore.deposit(ctx, amt)
                picks.forEach { p -> val sh = sharesFor(p, amt); val px = quotes[p.symbol]?.price ?: 0.0; if (sh > 0 && px > 0) TradeStore.buy(ctx, p.symbol, p.name, sh, px) }
                MessageStore.insertOne(ctx, "Investing portfolio", "Trade", "me", "me", "Invested $${amt.toInt()} into a practice portfolio ($risk). Holdings: " + picks.joinToString(", ") { "${it.symbol} ${it.name} ${(it.weight * 100).toInt()}%" })
                MetricsStore.record(ctx, 900)
            }
            started = true; picks = emptyList(); busy = ""; refreshPortfolio()   // clear busy or all buttons stay disabled
        }
    }

    // Instant, offline parser for the common cases so "Preview" never waits on the network/LLM.
    fun localParse(text: String): List<AgentClient.TradeIntent> {
        val t = text.lowercase()
        val map = mapOf("gold" to "GLD", "silver" to "SLV", "bitcoin" to "BTC-USD", "btc" to "BTC-USD",
            "ethereum" to "ETH-USD", "eth" to "ETH-USD", "oil" to "USO", "s&p" to "VOO", "sp500" to "VOO",
            "s&p500" to "VOO", "nasdaq" to "QQQ", "dogecoin" to "DOGE-USD", "doge" to "DOGE-USD",
            "solana" to "SOL-USD", "sol" to "SOL-USD", "total market" to "VTI", "bonds" to "BND")
        val action = when { Regex("\\bsell\\b").containsMatchIn(t) -> "sell"; Regex("\\b(buy|add|get|invest|put)\\b").containsMatchIn(t) -> "buy"; else -> return emptyList() }
        var symbol = ""
        for ((k, v) in map) if (Regex("\\b" + Regex.escape(k) + "\\b").containsMatchIn(t)) { symbol = v; break }
        if (symbol.isBlank()) symbol = Regex("\\b[A-Z]{1,5}(?:-USD)?\\b").findAll(text).map { it.value }.firstOrNull { it !in setOf("USD", "I", "A", "AI") } ?: ""
        if (symbol.isBlank()) symbol = holdings.firstOrNull { t.contains(it.symbol.lowercase()) }?.symbol ?: ""
        if (symbol.isBlank()) return emptyList()
        val usd = Regex("\\$\\s?(\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(?:dollars|bucks|usd)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val shares = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:shares|share|sh|units?|coins?)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val fraction = when { t.contains("half") -> 0.5; t.contains("all") || t.contains("everything") -> 1.0; t.contains("quarter") -> 0.25; else -> 0.0 }
        val usd2 = if (action == "buy" && usd == 0.0 && shares == 0.0) 100.0 else usd   // default $100 buy
        return listOf(AgentClient.TradeIntent(action, symbol, "", usd2, shares, fraction))
    }
    fun runCmd() {
        val c = cmd.trim(); if (c.isBlank() || busy.isNotEmpty()) return
        busy = "cmd"; cmdError = ""; plans = emptyList()
        scope.launch {
            val hstr = holdings.joinToString(", ") { "${it.symbol} ${"%.2f".format(it.shares)}sh" }
            val intents = localParse(c).ifEmpty { withContext(Dispatchers.IO) { AgentClient.parseTradeCommand(c, hstr, TradeStore.cash(ctx)) } }
            val out = ArrayList<Plan>()
            for (it in intents) {
                val px = quotes[it.symbol]?.price ?: (withContext(Dispatchers.IO) { QuoteClient.quote(it.symbol)?.price } ?: 0.0)
                if (px <= 0) continue
                if (it.action == "buy") {
                    val sh = if (it.usd > 0) it.usd / px else it.shares
                    if (sh > 0) out.add(Plan("buy", it.symbol, it.name.ifBlank { it.symbol }, sh, px, sh * px))
                } else {
                    val cur = holdings.firstOrNull { h -> h.symbol.equals(it.symbol, true) }
                    val sh = when { it.shares > 0 -> it.shares; it.fraction > 0 -> (cur?.shares ?: 0.0) * it.fraction; else -> cur?.shares ?: 0.0 }
                    if (sh > 0) out.add(Plan("sell", it.symbol, cur?.name ?: it.symbol, sh, px, sh * px))
                }
            }
            plans = out; busy = ""
            if (out.isEmpty()) cmdError = "Couldn't read that — try “buy $200 of NVDA”, “add some gold”, or “sell half my AAPL”."
        }
    }
    fun confirmPlans() {
        if (busy.isNotEmpty() || plans.isEmpty()) return
        busy = "exec"
        scope.launch {
            withContext(Dispatchers.IO) {
                plans.forEach { p ->
                    if (p.action == "buy") {
                        val need = p.cost - TradeStore.cash(ctx)
                        if (need > 0) TradeStore.deposit(ctx, kotlin.math.ceil(need).toDouble())   // add practice cash to cover
                        TradeStore.buy(ctx, p.symbol, p.name, p.shares, p.price)
                    } else TradeStore.sell(ctx, p.symbol, p.shares, p.price)
                    MessageStore.insertOne(ctx, "Investing portfolio", "Trade", "me", "me", (if (p.action == "buy") "Bought " else "Sold ") + "%.3f".format(p.shares) + " ${p.symbol}" + (if (p.name.isNotBlank() && !p.name.equals(p.symbol, true)) " (${p.name})" else "") + " @ $${"%.2f".format(p.price)}")
                }
                MetricsStore.record(ctx, 120)
            }
            cmd = ""; plans = emptyList(); busy = ""; refreshPortfolio()   // clear busy or buttons stay disabled
        }
    }
    fun sellAll() {
        if (busy.isNotEmpty()) return
        busy = "sell"
        scope.launch {
            withContext(Dispatchers.IO) {
                // Sell at the last-known price we already have — no network call, so it's instant.
                TradeStore.holdings(ctx).forEach { TradeStore.sell(ctx, it.symbol, it.shares, quotes[it.symbol]?.price ?: it.avgCost) }
                MessageStore.insertOne(ctx, "Trading", "Trade", "system", "system", "Sold the whole practice portfolio to cash.")
            }
            holdings = TradeStore.holdings(ctx); busy = ""
        }
    }

    val deposited = TradeStore.deposited(ctx)
    val growth = if (deposited > 0) (value - deposited) / deposited * 100.0 else 0.0
    fun marketLabel() = when (marketState) { "REGULAR" -> "market open · live"; "PRE" -> "pre-market"; "POST" -> "after hours"; "" -> ""; else -> "market closed" }

    @Composable
    fun chip(label: String, key: String) = Text(label, fontSize = T.small, color = if (risk == key) T.bgElevated else T.ink, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (risk == key) ACC else T.bgElevated).clickable { risk = key }.padding(horizontal = 16.dp, vertical = 10.dp))
    @Composable
    fun bigBtn(label: String, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Text(label, fontSize = T.body, color = if (accent) T.bgElevated else T.ink, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (accent) (if (enabled) ACC else T.hairline) else T.bgElevated).clickable(enabled = enabled) { onClick() }.padding(vertical = 15.dp))

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader("Invest") { onBack() }

        if (started && holdings.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Portfolio value", fontSize = T.caption, color = T.inkFaint)
            Text("$" + "%,.2f".format(value), fontSize = T.time, color = T.ink)
            Text("%+.2f%%".format(growth) + " all-time  ·  " + "%+.2f%%".format(dayPct) + " today", fontSize = T.body, color = if (growth >= 0) UP else DOWN)
            Spacer(Modifier.height(4.dp))
            val stamp = if (updatedAt > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(updatedAt)) else "—"
            Text((if (refreshing) "updating…" else "auto · updated $stamp · tap to refresh") + (marketLabel().let { if (it.isNotBlank()) " · $it" else "" }),
                fontSize = T.caption, color = T.inkFaint, modifier = Modifier.clickable { refreshPortfolio() })
            if (priceMsg.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(priceMsg, fontSize = T.caption, color = if (priceMsg.contains("⚠")) T.danger else UP) }

            // Value graph — always starts from your deposit baseline so a line shows from the first refresh.
            run {
                Spacer(Modifier.height(12.dp))
                Text("PORTFOLIO OVER TIME", fontSize = T.caption, color = T.inkFaint)
                Spacer(Modifier.height(6.dp))
                val vals = listOf(deposited) + series.map { it.second }
                val lo = (vals.min()).coerceAtMost(deposited); val hi = (vals.max()).coerceAtLeast(deposited); val span = (hi - lo).coerceAtLeast(0.01)
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    val w = size.width; val h = size.height
                    // deposited baseline
                    val by = h - ((deposited - lo) / span * h).toFloat()
                    drawLine(T.hairline, Offset(0f, by), Offset(w, by), 1.5f)
                    val path = Path()
                    vals.forEachIndexed { i, v ->
                        val x = if (vals.size == 1) w else w * i / (vals.size - 1)
                        val y = h - ((v - lo) / span * h).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, if (growth >= 0) UP else DOWN, style = Stroke(width = 4f))
                }
            }

            Spacer(Modifier.height(14.dp))
            holdings.forEach { h ->
                val px = quotes[h.symbol]?.price ?: h.avgCost
                val pl = if (h.avgCost > 0) (px - h.avgCost) / h.avgCost * 100.0 else 0.0
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.symbol, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                        Text("$" + "%,.2f".format(px * h.shares), fontSize = T.body, color = T.ink)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(("%.3f".format(h.shares)) + " sh · $" + "%.2f".format(px), fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
                        Text("%+.2f%%".format(pl), fontSize = T.caption, color = if (pl >= 0) UP else DOWN)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Command bar ──
            Spacer(Modifier.height(6.dp))
            BasicTextField(cmd, { cmd = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
                decorationBox = { inner -> if (cmd.isEmpty()) Text("buy $200 of NVDA · add some gold · sell half my AAPL", fontSize = T.small, color = T.inkFaint); inner() })
            Spacer(Modifier.height(8.dp))
            bigBtn(if (busy == "cmd") "Reading…" else "Preview trade", accent = false, enabled = busy.isEmpty() && cmd.isNotBlank()) { runCmd() }
            if (cmdError.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(cmdError, fontSize = T.caption, color = T.inkFaint) }
            plans.forEach { p ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (p.action == "buy") ACC.copy(alpha = 0.10f) else T.bgElevated).padding(14.dp)) {
                    Text((if (p.action == "buy") "Buy " else "Sell ") + "%.3f".format(p.shares) + " " + p.symbol + "  ·  ~$" + "%,.2f".format(p.cost), fontSize = T.small, color = T.ink)
                    Text("@ $" + "%.2f".format(p.price) + (if (p.name.isNotBlank() && !p.name.equals(p.symbol, true)) " · ${p.name}" else ""), fontSize = T.caption, color = T.inkFaint)
                }
            }
            if (plans.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                bigBtn(if (busy == "exec") "Working…" else "Confirm ${plans.size} trade" + (if (plans.size > 1) "s" else ""), accent = true, enabled = busy.isEmpty()) { confirmPlans() }
            }

            Spacer(Modifier.height(12.dp))
            bigBtn(if (busy == "sell") "Selling…" else "Sell everything to cash", accent = false, enabled = busy.isEmpty()) { sellAll() }
            Spacer(Modifier.height(8.dp))
            Text("Start a new practice run", fontSize = T.small, color = T.inkFaint, modifier = Modifier.clickable { TradeStore.reset(ctx); started = false; holdings = emptyList(); value = 0.0; series = emptyList() }.padding(vertical = 8.dp))
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        if (picks.isNotEmpty()) {
            val amt = amount.toDoubleOrNull() ?: 0.0
            Spacer(Modifier.height(16.dp))
            Text("Your AI portfolio", fontSize = T.caption, color = T.inkFaint)
            Text("$" + "%,.0f".format(amt) + " · $risk", fontSize = T.prompt, color = T.ink)
            Spacer(Modifier.height(12.dp))
            picks.forEach { p ->
                val px = quotes[p.symbol]?.price ?: 0.0; val sh = sharesFor(p, amt)
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
            bigBtn(if (busy == "buy") "Buying…" else "Confirm & buy — $" + "%,.0f".format(amt), accent = true, enabled = busy.isEmpty()) { confirmBuild() }
            Spacer(Modifier.height(8.dp))
            Text("Rebuild it", fontSize = T.small, color = T.inkFaint, modifier = Modifier.clickable { picks = emptyList(); build() }.padding(vertical = 8.dp))
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Text("Let your AI invest — for practice", fontSize = T.prompt, color = T.ink)
        Text("It builds and runs a real portfolio with practice money so you can see how it performs. You confirm every buy. Goes real once we're licensed.", fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(16.dp))
        Text("HOW BOLD SHOULD IT BE?", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chip("Careful", "conservative"); chip("Balanced", "balanced"); chip("Adventurous", "aggressive") }
        Spacer(Modifier.height(14.dp))
        Text("ANYTHING YOU'RE INTO? (optional)", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        BasicTextField(interests, { interests = it }, textStyle = TextStyle(color = T.ink, fontSize = T.small),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner -> if (interests.isEmpty()) Text("tech, clean energy, dividends, gold, crypto…", fontSize = T.small, color = T.inkFaint); inner() })
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
