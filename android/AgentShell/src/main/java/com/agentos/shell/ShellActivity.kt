package com.agentos.shell

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentos.shell.screens.*
import com.agentos.shell.theme.T
import kotlinx.coroutines.delay

/** The boot face of AgentOS. A single activity hosting the screen state machine. */
enum class Screen { Boot, Lock, Home, Now, People, Memory, MemorySettings, Apps, Compose, SpicyPost, Checklist, Outreach, Research, Architect, AppView, Manual }

class ShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        if (com.agentos.shell.tools.MemoryStore.telegramBot(this) && com.agentos.shell.tools.TelegramClient.configured())
            TelegramService.start(this)
        setContent {
            var screen by remember { mutableStateOf(Screen.Boot) }
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

            Surface(Modifier.fillMaxSize(), color = T.bg) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (fadeIn(tween(600)) togetherWith fadeOut(tween(400)))
                    },
                    label = "screen"
                ) { s ->
                    val m = Modifier.fillMaxSize().background(T.bg).padding(24.dp)
                    when (s) {
                        Screen.Boot   -> BootScreen(m)
                        Screen.Lock   -> LockScreen(m, onEnter = { screen = Screen.Home })
                        Screen.Home   -> HomeScreen(
                            m,
                            paused = agentPaused,
                            onOpen = { screen = it },
                            onManual = { agentPaused = true; screen = Screen.Manual },
                            onCompose = { p, t -> composePlatform = p; composeTopic = t; screen = Screen.Compose },
                            onArchitect = { screen = Screen.Architect },
                            onSpicy = { t -> spicyTopic = t; screen = Screen.SpicyPost },
                            onResearch = { t -> researchTopic = t; screen = Screen.Research }
                        )
                        Screen.Now    -> NowScreen(m) { screen = Screen.Home }
                        Screen.People -> PeopleScreen(m) { screen = Screen.Home }
                        Screen.Memory -> MemoryGraphScreen(m, onBack = { screen = Screen.Home }, onSettings = { screen = Screen.MemorySettings })
                        Screen.MemorySettings -> MemoryScreen(m) { screen = Screen.Memory }
                        Screen.Apps   -> AppsScreen(m) { screen = Screen.Home }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
