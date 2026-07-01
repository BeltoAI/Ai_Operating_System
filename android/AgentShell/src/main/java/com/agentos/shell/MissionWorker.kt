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
import com.agentos.shell.tools.ChecklistStore
import com.agentos.shell.tools.ConnectionStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MissionStore
import com.agentos.shell.tools.PaperStore
import com.agentos.shell.tools.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Once a day, if a mission is set, SlyOS quietly re-assesses progress and records it — so the tracker
 * trends on its own. It only pings you when there's something worth knowing: a real jump forward, or
 * a stall (no progress across several checks). Otherwise it stays silent.
 */
class MissionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val mission = MissionStore.mission(ctx)
        if (mission.isBlank() || !AgentClient.hasKey()) return Result.success()

        val prev = MissionStore.latest(ctx)?.percent ?: 0
        val a = withContext(Dispatchers.IO) {
            val about = MemoryStore.about(ctx)
            val tasks = ChecklistStore.load(ctx).joinToString("\n") { "- ${it.text} (${if (it.done) "done" else "todo"})" }
            val papers = PaperStore.list(ctx).joinToString("\n") { "Paper: ${it.title}" }
            val hits = MessageStore.search(ctx, mission, 40)
                .joinToString("\n") { (if (it.role == "me") "you→${it.contact}" else it.contact) + ": " + it.body }
            val done = MissionStore.milestones(ctx).filter { it.done }.joinToString("; ") { it.text }
            val sem = VectorStore.search(ctx, mission, 10).joinToString("\n") { it.contact + ": " + it.body }
            val context = buildString {
                if (about.isNotBlank()) append("About me: ").append(about).append("\n")
                append("LinkedIn connections: ").append(ConnectionStore.count(ctx)).append("\n")
                if (done.isNotBlank()) append("Milestones completed: ").append(done).append("\n")
                if (tasks.isNotBlank()) append("Checklist:\n").append(tasks).append("\n")
                if (papers.isNotBlank()) append(papers).append("\n")
                if (sem.isNotBlank()) append("Relevant memories:\n").append(sem).append("\n")
                if (hits.isNotBlank()) append("Messages related to the goal:\n").append(hits)
            }.take(9000)
            val days = ((System.currentTimeMillis() - MissionStore.since(ctx)) / 86_400_000L)
            AgentClient.assessMission(mission, context, "$days days ago")
        }
        if (a.percent < 0) return Result.retry()   // transient (rate limit / no reachable model)
        MissionStore.addCheck(ctx, a.percent, a.argument, a.next)

        val checks = MissionStore.checks(ctx)
        val jumped = a.percent - prev >= 10
        val stalled = checks.size >= 3 && checks.takeLast(3).map { it.percent }.distinct().size == 1 && a.percent < 100
        val reached = a.percent >= 100
        when {
            reached -> notify(ctx, "Mission complete", "SlyOS assesses your goal as done (100%). Set a new one?")
            jumped -> notify(ctx, "Progress: ${a.percent}%", "Up from $prev%. ${a.argument.take(120)}")
            stalled -> notify(ctx, "Mission stalled at ${a.percent}%", "No movement lately. Next: ${a.next.take(120)}")
        }
        return Result.success()
    }

    private fun notify(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("mission", "Mission progress", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 21, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, "mission")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(9913, n)
    }
}
