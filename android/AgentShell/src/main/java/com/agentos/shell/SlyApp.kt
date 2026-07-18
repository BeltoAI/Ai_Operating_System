package com.agentos.shell

import android.app.Application

/**
 * Process-wide setup. Runs before ANY component (activity, service, worker, receiver), so the app context is
 * always wired even when a background entry point (e.g. the Telegram service on boot) runs before the launcher
 * activity ever opens. BUG FIX: previously appContext was only set in ShellActivity.onCreate, so background LM
 * calls fell back to the empty baked key and failed to use the user's own keys.
 */
class SlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            com.agentos.shell.tools.AgentClient.appContext = applicationContext
            com.agentos.shell.tools.ImageAI.appContext = applicationContext
        } catch (e: Exception) {}
    }
}
