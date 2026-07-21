package com.agentos.shell.tools

import android.app.ActivityManager
import android.content.Context

/**
 * LONG-RUNNING SERVICE LIVENESS.
 *
 * Second major blind spot: SlyOS depends on six services that are supposed to stay alive — the Telegram
 * bot, the accessibility engine, live location, the call agent, the overlay, call screening. Android kills
 * services routinely (memory pressure, battery optimisation, a crash inside the service). When that
 * happens the feature simply stops: the bot goes quiet, tap-send stops working, location sharing ends —
 * and nothing tells you. Everything else keeps reporting green because nothing was watching the service.
 *
 * This records every start and death, so "the Telegram bot died 4 hours ago" becomes visible instead of
 * being experienced as "the bot feels broken lately".
 */
object ServiceHealth {
    private const val PREFS = "slyos_services"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Services that are MEANT to run continuously once enabled. */
    val LONG_RUNNING = listOf("TelegramService", "InteractionLogService", "LiveLocationService", "OverlayNavService")

    fun started(ctx: Context, service: String) {
        p(ctx).edit()
            .putLong("start_$service", System.currentTimeMillis())
            .putBoolean("alive_$service", true)
            .putInt("starts_$service", p(ctx).getInt("starts_$service", 0) + 1)
            .apply()
    }

    fun died(ctx: Context, service: String, reason: String = "onDestroy") {
        val started = p(ctx).getLong("start_$service", 0L)
        val lived = if (started > 0) System.currentTimeMillis() - started else 0L
        p(ctx).edit()
            .putLong("died_$service", System.currentTimeMillis())
            .putBoolean("alive_$service", false)
            .putLong("lived_$service", lived)
            .putInt("deaths_$service", p(ctx).getInt("deaths_$service", 0) + 1)
            .apply()
        // A service dying is only normal if the user turned it off — otherwise the feature just stopped.
        Fail.log(ctx, "Service", "$service stopped",
            "$reason — ran for ${lived / 60000}m; anything it powers is now dead", "warn")
    }

    data class Status(val name: String, val alive: Boolean, val lastStart: Long, val lastDeath: Long,
                      val starts: Int, val deaths: Int, val livedMs: Long)

    fun statuses(ctx: Context): List<Status> = LONG_RUNNING.map { s ->
        Status(s, p(ctx).getBoolean("alive_$s", false), p(ctx).getLong("start_$s", 0L),
            p(ctx).getLong("died_$s", 0L), p(ctx).getInt("starts_$s", 0),
            p(ctx).getInt("deaths_$s", 0), p(ctx).getLong("lived_$s", 0L))
    }

    /** Is a service ACTUALLY running right now, rather than merely believed to be? */
    fun reallyRunning(ctx: Context, serviceSimpleName: String): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className.endsWith(serviceSimpleName) }
    } catch (e: Exception) { false }

    /** Services we believe are alive but that Android says are not — the silent-death case. */
    fun ghosts(ctx: Context): List<String> = statuses(ctx)
        .filter { it.alive && !reallyRunning(ctx, it.name) }
        .map { it.name }
}
