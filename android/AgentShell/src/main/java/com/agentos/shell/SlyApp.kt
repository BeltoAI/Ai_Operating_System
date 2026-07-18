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
        // GUARANTEE semantic memory: if there's no cloud embedder (no Gemini/OpenAI key) and the on-device
        // embedder isn't downloaded yet, fetch it in the background and switch to it. It's a tiny ~6MB model
        // and embedding is lightweight (no phone heat), so memory can never go dark for a free-brain user.
        Thread {
            try {
                val ctx = applicationContext
                val ES = com.agentos.shell.tools.EmbeddingClient
                val ODE = com.agentos.shell.tools.OnDeviceEmbedder
                val hasCloud = com.agentos.shell.tools.MemoryStore.geminiKey(ctx).isNotBlank() ||
                    com.agentos.shell.tools.MemoryStore.openaiKey(ctx).isNotBlank()
                if (!hasCloud && !ODE.ready(ctx)) {
                    if (ODE.download(ctx)) {
                        com.agentos.shell.tools.MemoryStore.setEmbedProvider(ctx, "local")
                        com.agentos.shell.tools.HealthStore.note("embed_autoenable", true, "on-device (no cloud key)")
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }
}
