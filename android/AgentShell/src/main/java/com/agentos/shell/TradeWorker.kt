package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.QuoteClient
import com.agentos.shell.tools.TradeStore

/**
 * Daily portfolio update + big-move alert. Prices the practice portfolio, computes the day's move, and
 * (when web search is available) pulls the news driving the holdings with one actionable suggestion —
 * so the user can act fast. Posts a notification and feeds the brain. No-op if no portfolio.
 */
class TradeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Record that this worker actually ran. Ten of eleven workers previously recorded
        // nothing, so a silently-unscheduled worker was indistinguishable from a working one.
        com.agentos.shell.tools.WorkerHealth.started(applicationContext, "TradeWorker")
        val ctx = applicationContext
        if (!TradeStore.started(ctx)) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "TradeWorker", true).let { Result.success() }
        val holdings = TradeStore.holdings(ctx)
        if (holdings.isEmpty()) return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "TradeWorker", true).let { Result.success() }

        val q = QuoteClient.quotes(holdings.map { it.symbol })
        val value = TradeStore.cash(ctx) + holdings.sumOf { (q[it.symbol]?.price ?: it.avgCost) * it.shares }
        val prev = TradeStore.cash(ctx) + holdings.sumOf { (q[it.symbol]?.prevClose ?: it.avgCost) * it.shares }
        val dayPct = if (prev > 0) (value - prev) / prev * 100.0 else 0.0
        TradeStore.saveSnapshot(ctx, value)

        val dayStr = "%+.1f%%".format(dayPct)
        val brief = try { AgentClient.portfolioBriefing(TradeStore.summary(ctx), "$dayStr today", MemoryStore.about(ctx)) } catch (e: Exception) { "" }
        val body = if (brief.isNotBlank() && !AgentClient.looksLikeError(brief)) brief
                   else "Portfolio ~$" + "%,.0f".format(value) + " · $dayStr today."

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("trading", "Portfolio updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 31, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (kotlin.math.abs(dayPct) >= 3.0) "Portfolio moved $dayStr today" else "Your portfolio update"
        val note = Notification.Builder(ctx, "trading")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(9931, note)
        try { MessageStore.insertOne(ctx, "Trading", "Trade", "system", "system", "Daily update: " + body.take(600)) } catch (e: Exception) {}

        // Semi-automated trading: the AI suggests concrete moves. Hands-off ON → execute + log; else drop a
        // one-tap "Confirm / counter" proposal into Now. Everything is logged to the brain + outbox.
        try {
            val moves = AgentClient.portfolioMoves(TradeStore.summary(ctx), "$dayStr today", MemoryStore.about(ctx))
            val handsOff = MemoryStore.autoTrade(ctx)
            for (m in moves) {
                val argJson = org.json.JSONObject().put("symbol", m.symbol).put("action", m.action).put("shares", m.shares).toString()
                if (handsOff) {
                    val res = com.agentos.shell.tools.ToolRouter.executeAction(ctx, "trade", argJson)
                    com.agentos.shell.tools.OutboxStore.record(ctx, "Trading", m.symbol, "trade",
                        "${m.action} ${m.shares} ${m.symbol} — ${m.why}", "auto-traded (hands-off): $res")
                } else {
                    com.agentos.shell.tools.ProposalStore.add(ctx,
                        "${m.action.replaceFirstChar { it.uppercase() }} ${"%.2f".format(m.shares)} ${m.symbol}",
                        "${m.why} · one-tap (practice)",
                        listOf(com.agentos.shell.tools.AgentAction("trade", argJson)))
                }
            }
        } catch (e: Exception) {}
        return com.agentos.shell.tools.WorkerHealth.finished(applicationContext, "TradeWorker", true).let { Result.success() }
    }
}
