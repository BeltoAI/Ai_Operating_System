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
        // FIRST: capture every crash and give the failure log an app context, so failures anywhere in the
        // process (including background threads with no Context in scope) can be recorded.
        try { com.agentos.shell.tools.Fail.installCrashHandler(applicationContext) } catch (e: Exception) {}
        // One-time cleanup: strip screen-recall entries from noisy apps (chess, keyboards, system chrome)
        // that had crowded out real memories. Without this the fix only helps new captures.
        Thread {
            try {
                val n = com.agentos.shell.tools.InteractionStore.purgeNoise(applicationContext)
                if (n > 0) com.agentos.shell.tools.HealthStore.note("recall_purge", true, "removed $n noise entries")
            } catch (e: Exception) {}
        }.start()
        try {
            com.agentos.shell.tools.AgentClient.appContext = applicationContext
            com.agentos.shell.tools.ImageAI.appContext = applicationContext
            com.agentos.shell.tools.AgentClient.discloseAi = com.agentos.shell.tools.MemoryStore.discloseAi(applicationContext)
            // THE PERSONA BUG: AgentClient.styleProfile (your set writing voice/character) is a @Volatile var
            // that was ONLY populated during onboarding (SetupScreen). Every cold start began with it EMPTY,
            // so until you re-ran setup, replies didn't sound like you — the character you set in Settings was
            // silently ignored on most sessions. Load it here so it's applied on EVERY process start.
            com.agentos.shell.tools.AgentClient.styleProfile = com.agentos.shell.tools.MemoryStore.styleProfile(applicationContext)
        } catch (e: Exception) {}
        // MEMORY BACKFILL. The vector index was only ever fed by MessageStore, so documents, photos,
        // on-screen recall, CRM leads and network contacts were invisible to semantic search — the brain
        // physically could not recall them. And with no dedupe, the same text was embedded many times,
        // so a handful of repeated lines crowded out everything else. Both are fixed at the source now;
        // this makes the fix RETROACTIVE for data already on the phone. Throttled to once a day.
        Thread {
            try {
                val ctx = applicationContext
                val p = ctx.getSharedPreferences("slyos_mem_maint", MODE_PRIVATE)
                val last = p.getLong("last_backfill", 0L)
                if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) {
                    val dupes = com.agentos.shell.tools.VectorStore.purgeDuplicates(ctx)
                    val added = com.agentos.shell.tools.VectorStore.ingestAllSources(ctx)
                    p.edit().putLong("last_backfill", System.currentTimeMillis()).apply()
                    com.agentos.shell.tools.HealthStore.note(
                        "memory_backfill", true, "queued $added new · removed $dupes duplicates")
                }
            } catch (e: Exception) {
                try { com.agentos.shell.tools.Fail.log(applicationContext, "Brain", "memory backfill",
                    e.message ?: "failed", "warn") } catch (e2: Exception) {}
            }
        }.start()
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
