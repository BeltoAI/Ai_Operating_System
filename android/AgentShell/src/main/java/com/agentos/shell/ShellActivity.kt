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
import androidx.compose.ui.unit.dp
import com.agentos.shell.screens.*
import com.agentos.shell.theme.T
import kotlinx.coroutines.delay

/** The boot face of AgentOS. A single activity hosting the screen state machine. */
enum class Screen { Boot, Lock, Home, Now, People, Memory, MemorySettings, Apps, Compose, SpicyPost, Checklist, Outreach, Research, Architect, AppView, Manual, Reconnect, Setup }

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
            var currentAppId by remember { mutableStateOf(0L) }
            var spicyTopic by remember { mutableStateOf("") }
            var researchTopic by remember { mutableStateOf("") }

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
                            onArchitect = { screen = Screen.Architect },
                            onSpicy = { t -> spicyTopic = t; screen = Screen.SpicyPost },
                            onResearch = { t -> researchTopic = t; screen = Screen.Research },
                            onOpenApp = { id -> currentAppId = id; screen = Screen.AppView }
                        )
                        Screen.Now    -> NowScreen(m, onReconnect = { screen = Screen.Reconnect }) { screen = Screen.Home }
                        Screen.Reconnect -> ReconnectScreen(m) { screen = Screen.Now }
                        Screen.People -> PeopleScreen(m) { screen = Screen.Home }
                        Screen.Memory -> MemoryGraphScreen(m, onBack = { screen = Screen.Home }, onSettings = { screen = Screen.MemorySettings })
                        Screen.MemorySettings -> MemoryScreen(m) { screen = Screen.Memory }
                        Screen.Apps   -> AppsScreen(m, onManual = { agentPaused = true; screen = Screen.Manual }) { screen = Screen.Home }
                        Screen.Checklist -> ChecklistScreen(m) { screen = Screen.Home }
                        Screen.Outreach -> OutreachScreen(m) { screen = Screen.Manual }
                        Screen.Research -> ResearchScreen(m, researchTopic) { researchTopic = ""; screen = Screen.Home }
                        Screen.Compose -> ComposeScreen(m, composePlatform, composeTopic) { screen = Screen.Home }
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
                                nowCount = com.agentos.shell.tools.NotificationStore.notes.size) { target -> screen = target }
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
