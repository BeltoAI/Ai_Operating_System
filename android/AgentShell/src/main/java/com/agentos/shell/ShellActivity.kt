package com.agentos.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.agentos.shell.screens.*
import com.agentos.shell.theme.T
import kotlinx.coroutines.delay

/** The boot face of AgentOS. A single activity hosting the screen state machine. */
enum class Screen { Boot, Lock, Home, Now, People, Memory, MemorySettings, Mission, Apps, Compose, EmailCompose, SpicyPost, Checklist, Outreach, Research, Cowork, Job, Network, Look, Shop, Trade, Converse, Architect, AppView, Manual, Reconnect, Setup, Outbox, Expenses, Faces, Docs }

class ShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        // Lets every model call read provider keys + record cost, and route across Claude/OpenAI/Gemini.
        com.agentos.shell.tools.AgentClient.appContext = applicationContext
        // Your Anthropic key, pasted in-app and stored on-device (so a prebuilt APK needs no compiled key).
        com.agentos.shell.tools.AgentClient.apiKeyOverride = com.agentos.shell.tools.MemoryStore.anthropicKey(this)
        com.agentos.shell.tools.AgentClient.bookingLink = com.agentos.shell.tools.MemoryStore.effectiveBookingLink(this)
        com.agentos.shell.tools.AgentClient.styleProfile = com.agentos.shell.tools.MemoryStore.styleProfile(this)
        com.agentos.shell.tools.QuoteClient.finnhubKey = com.agentos.shell.tools.MemoryStore.finnhubKey(this)
        com.agentos.shell.theme.T.dark = com.agentos.shell.tools.MemoryStore.darkMode(this)
        // Auto-relearn your writing voice as new samples accumulate (sent posts/replies grow the pool),
        // so the profile keeps sharpening instead of going stale after the one-time learn.
        run {
            val app = applicationContext
            val samples = com.agentos.shell.tools.MemoryStore.voiceSamples(app)
            val learnedAt = com.agentos.shell.tools.MemoryStore.voiceLearnedCount(app)
            if (samples.size >= 40 && samples.size - learnedAt >= 80) {
                Thread {
                    val profile = com.agentos.shell.tools.AgentClient.learnStyle(samples)
                    if (profile.isNotBlank() && !profile.startsWith("[")) {
                        com.agentos.shell.tools.MemoryStore.setStyleProfile(app, profile)
                        com.agentos.shell.tools.AgentClient.styleProfile = profile
                        com.agentos.shell.tools.MemoryStore.setVoiceLearnedCount(app, samples.size)
                    }
                }.start()
            }
        }
        // Pull the whole calendar (past + future) into the brain so the agent knows every appointment.
        Thread { com.agentos.shell.tools.CalendarTool.syncAllToBrain(applicationContext) }.start()
        // Embed the semantic-memory backlog so the brain retrieves by meaning, not just keywords.
        Thread { try { com.agentos.shell.tools.VectorStore.backfill(applicationContext, 250) } catch (e: Exception) {} }.start()
        // Keep filling the index in the background (free-tier-friendly) so the user needn't babysit it.
        try {
            val embReq = androidx.work.PeriodicWorkRequestBuilder<EmbedWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_embed", androidx.work.ExistingPeriodicWorkPolicy.KEEP, embReq)
        } catch (e: Exception) {}
        // Periodic checklist nudge (self-throttles to once/5h, and only fires if items are open).
        try {
            val chkReq = androidx.work.PeriodicWorkRequestBuilder<ChecklistReminderWorker>(3, java.util.concurrent.TimeUnit.HOURS).build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_checklist", androidx.work.ExistingPeriodicWorkPolicy.KEEP, chkReq)
        } catch (e: Exception) {}
        // Keep new inbox mail flowing into the brain in the background (deduped; no-op if not connected).
        try {
            val gmReq = androidx.work.PeriodicWorkRequestBuilder<GmailSyncWorker>(1, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_gmail", androidx.work.ExistingPeriodicWorkPolicy.KEEP, gmReq)
        } catch (e: Exception) {}
        // Daily self-assessment of the mission (no-op if no goal set); pings only on jumps/stalls.
        try {
            val misReq = androidx.work.PeriodicWorkRequestBuilder<MissionWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_mission", androidx.work.ExistingPeriodicWorkPolicy.KEEP, misReq)
        } catch (e: Exception) {}
        // Daily practice-portfolio update + big-move/news alert (no-op if no portfolio).
        try {
            val tradeReq = androidx.work.PeriodicWorkRequestBuilder<TradeWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_trade", androidx.work.ExistingPeriodicWorkPolicy.KEEP, tradeReq)
        } catch (e: Exception) {}
        // P5.4: nightly memory consolidation — distill recent messages into durable facts.
        try {
            val consReq = androidx.work.PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_consolidate", androidx.work.ExistingPeriodicWorkPolicy.KEEP, consReq)
        } catch (e: Exception) {}
        // Continuous brain backup to Google Drive (+ local Downloads) every 6h, so the memory is never
        // one uninstall away from gone again. Plus a one-shot on launch so there's always a fresh copy.
        try {
            val bkReq = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("slyos_backup", androidx.work.ExistingPeriodicWorkPolicy.KEEP, bkReq)
            val bkNow = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInitialDelay(2, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("slyos_backup_boot", androidx.work.ExistingWorkPolicy.KEEP, bkNow)
        } catch (e: Exception) {}
        // If Google is connected, pull recent Gmail (subjects, bodies, PDF attachments) into the brain.
        if (com.agentos.shell.tools.GoogleAuth.isConnected(applicationContext))
            Thread {
                try { com.agentos.shell.tools.GmailClient.syncToBrain(applicationContext) } catch (e: Exception) {}
                try { com.agentos.shell.tools.GmailClient.syncReceipts(applicationContext) } catch (e: Exception) {}   // expense receipts
            }.start()
        if (com.agentos.shell.tools.MemoryStore.telegramBot(this) && com.agentos.shell.tools.TelegramClient.configured())
            TelegramService.start(this)
        if (com.agentos.shell.tools.MemoryStore.lockVoice(this))
            com.agentos.shell.tools.VoiceShortcut.post(this)
        if (com.agentos.shell.tools.MemoryStore.reconnectWeekly(this))
            ReconnectScheduler.set(this, true)
        val startVoice = intent?.getBooleanExtra("start_voice", false) == true
        val openReconnect = intent?.getBooleanExtra("open_reconnect", false) == true
        setContent {
            var screen by remember {
                mutableStateOf(when {
                    !com.agentos.shell.tools.AgentClient.hasKey() -> Screen.Setup   // first run: paste your key
                    startVoice -> Screen.Home; openReconnect -> Screen.Reconnect; else -> Screen.Boot })
            }
            var pendingVoice by remember { mutableStateOf(startVoice) }   // one-shot: cleared after the mic opens
            var agentPaused by remember { mutableStateOf(false) }
            var composePlatform by remember { mutableStateOf("") }
            var composeTopic by remember { mutableStateOf("") }
            var emailTo by remember { mutableStateOf("") }
            var emailTopic by remember { mutableStateOf("") }
            var currentAppId by remember { mutableStateOf(0L) }
            var spicyTopic by remember { mutableStateOf("") }
            var researchTopic by remember { mutableStateOf("") }
            var jobTopic by remember { mutableStateOf("") }
            var networkQuery by remember { mutableStateOf("") }
            var missionGoal by remember { mutableStateOf("") }
            var shopQuery by remember { mutableStateOf("") }
            var tradePrompt by remember { mutableStateOf("") }

            // Boot -> Lock after a calm beat.
            LaunchedEffect(Unit) { delay(1600); if (screen == Screen.Boot) screen = Screen.Lock }

            // Pressing Home from anywhere returns to Home (or Manual if paused).
            BackHandler(enabled = screen != Screen.Home && screen != Screen.Boot) {
                screen = if (agentPaused) Screen.Manual else Screen.Home
            }

            // Panels that show the persistent bottom nav (the calm, recurring surfaces).
            val mainScreens = setOf(
                Screen.Home, Screen.Now, Screen.Memory, Screen.MemorySettings,
                Screen.Research, Screen.Apps, Screen.People, Screen.Checklist, Screen.Manual
            )
            // Recolor the system status/navigation bars to match the theme (the XML theme hardcodes them
            // ivory, which is why the lock screen's bars stayed light in dark mode). Re-runs on toggle.
            LaunchedEffect(T.dark) {
                val c = T.bg.toArgb()
                window.statusBarColor = c
                window.navigationBarColor = c
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !T.dark
                    isAppearanceLightNavigationBars = !T.dark
                }
            }
            Surface(Modifier.fillMaxSize(), color = T.bg) {
              androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
              Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (fadeIn(tween(600)) togetherWith fadeOut(tween(400)))
                    },
                    label = "screen",
                    modifier = Modifier.weight(1f)
                ) { s ->
                    val m = Modifier.fillMaxSize().background(T.bg).padding(24.dp)
                    when (s) {
                        Screen.Setup  -> SetupScreen(m) { screen = Screen.Home }
                        Screen.Boot   -> BootScreen(m)
                        Screen.Lock   -> LockScreen(m, onEnter = { screen = Screen.Home })
                        Screen.Home   -> HomeScreen(
                            m,
                            paused = agentPaused,
                            autoVoice = pendingVoice,
                            onVoiceConsumed = { pendingVoice = false },
                            onOpen = { screen = it },
                            onManual = { agentPaused = true; screen = Screen.Manual },
                            onCompose = { p, t -> composePlatform = p; composeTopic = t; screen = Screen.Compose },
                            onComposeEmail = { to, t -> emailTo = to; emailTopic = t; screen = Screen.EmailCompose },
                            onArchitect = { screen = Screen.Architect },
                            onSpicy = { t -> spicyTopic = t; screen = Screen.SpicyPost },
                            onResearch = { t -> researchTopic = t; screen = Screen.Research },
                            onJob = { t -> jobTopic = t; screen = Screen.Job },
                            onNetwork = { t -> networkQuery = t; screen = Screen.Network },
                            onSetMission = { g -> missionGoal = g; screen = Screen.Mission },
                            onLook = { screen = Screen.Look },
                            onFaces = { screen = Screen.Faces },
                            onDocs = { screen = Screen.Docs },
                            onShop = { q -> shopQuery = q; screen = Screen.Shop },
                            onInvest = { p -> tradePrompt = p; screen = Screen.Trade },
                            onExpenses = { screen = Screen.Expenses },
                            onOperate = { g -> com.agentos.shell.tools.ScreenAgent.start(applicationContext, g) },
                            onOpenApp = { id -> currentAppId = id; screen = Screen.AppView }
                        )
                        Screen.Now    -> NowScreen(m, onReconnect = { screen = Screen.Reconnect }, onOutbox = { screen = Screen.Outbox }) { screen = Screen.Home }
                        Screen.Reconnect -> ReconnectScreen(m) { screen = Screen.Now }
                        Screen.People -> PeopleScreen(m) { screen = Screen.Home }
                        Screen.Memory -> MemoryGraphScreen(m, onBack = { screen = Screen.Home }, onSettings = { screen = Screen.MemorySettings }, onMission = { missionGoal = ""; screen = Screen.Mission }, onNetwork = { networkQuery = ""; screen = Screen.Network })
                        Screen.Mission -> MissionScreen(m, missionGoal) { missionGoal = ""; screen = Screen.Home }
                        Screen.MemorySettings -> MemoryScreen(m) { screen = Screen.Memory }
                        Screen.Apps   -> AppsScreen(m, onManual = { agentPaused = true; screen = Screen.Manual }) { screen = Screen.Home }
                        Screen.Checklist -> ChecklistScreen(m) { screen = Screen.Home }
                        Screen.Outreach -> OutreachScreen(m) { screen = Screen.Manual }
                        Screen.Research -> ResearchScreen(m, researchTopic, onWorkspace = { screen = Screen.Cowork }) { researchTopic = ""; screen = Screen.Home }
                        Screen.Cowork -> CoworkScreen(m) { screen = Screen.Research }
                        Screen.Job -> JobScreen(m, jobTopic) { jobTopic = ""; screen = Screen.Home }
                        Screen.Network -> NetworkScreen(m, networkQuery) { networkQuery = ""; screen = Screen.Home }
                        Screen.Look -> LookScreen(m) { screen = Screen.Home }
                        Screen.Faces -> FaceScreen(m) { screen = Screen.Home }
                        Screen.Docs -> DocsScreen(m) { screen = Screen.Home }
                        Screen.Shop -> ShopScreen(m, shopQuery) { shopQuery = ""; screen = Screen.Home }
                        Screen.Trade -> TradeScreen(m, tradePrompt) { tradePrompt = ""; screen = Screen.Home }
                        Screen.Converse -> ConverseScreen(m) { screen = Screen.Home }
                        Screen.Outbox -> OutboxScreen(m) { screen = Screen.Now }
                        Screen.Expenses -> ExpensesScreen(m) { screen = Screen.Home }
                        Screen.Compose -> ComposeScreen(m, composePlatform, composeTopic) { screen = Screen.Home }
                        Screen.EmailCompose -> EmailComposeScreen(m, emailTo, emailTopic) { screen = Screen.Home }
                        Screen.SpicyPost -> SpicyPostScreen(m, spicyTopic) { screen = Screen.Home }
                        Screen.Architect -> ArchitectScreen(m, onBack = { screen = Screen.Home }, onOpenApp = { id -> currentAppId = id; screen = Screen.AppView })
                        Screen.AppView -> AppViewScreen(m, currentAppId) { screen = Screen.Architect }
                        Screen.Manual -> ManualModeScreen(
                            m,
                            onResume = { agentPaused = false; screen = Screen.Home },
                            onChecklist = { screen = Screen.Checklist },
                            onOutreach = { screen = Screen.Outreach }
                        )
                    }
                }
                if (screen in mainScreens) {
                    Surface(color = T.bg, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                            SlyBottomNav(current = screen,
                                nowCount = com.agentos.shell.tools.NotificationStore.notes.size,
                                onBrainHold = { screen = Screen.Converse }) { target -> screen = target }
                        }
                    }
                }
              }
              BusyDog()   // non-blocking "generating" animation, app-wide
              }
            }
        }
    }

    /** Hide the status bar and navigation bar — SlyOS owns the whole screen. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // If the lock-screen "Speak" notification is tapped while SlyOS is already running, restart the
    // activity so it lands on Home and fires voice capture.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("start_voice", false) || intent.getBooleanExtra("open_reconnect", false)) { setIntent(intent); recreate() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
