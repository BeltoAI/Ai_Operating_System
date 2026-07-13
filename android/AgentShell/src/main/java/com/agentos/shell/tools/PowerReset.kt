package com.agentos.shell.tools

import android.content.Context

/**
 * THE KILL SWITCH. If a Power ever misbehaves, this stops every locally-running server, forgets every
 * installed Power (so nothing is injected into the brain or callable any more), and clears the skills they
 * added. Your own data — brain, chats, settings — is left untouched; only the agents you added are wiped.
 */
object PowerReset {
    fun resetAll(ctx: Context): String {
        var stopped = ""
        try {
            if (TermuxBridge.isInstalled(ctx)) {
                // Kill the common local servers a Power might have started.
                stopped = TermuxBridge.run(
                    ctx,
                    "pkill -f 'rembg s' 2>/dev/null; pkill -f 'uvicorn' 2>/dev/null; pkill -f 'gunicorn' 2>/dev/null; " +
                        "pkill -f 'http.server' 2>/dev/null; pkill -f 'n8n' 2>/dev/null; pkill -f 'ollama serve' 2>/dev/null; " +
                        "pkill -f 'node ' 2>/dev/null; echo 'servers stopped'",
                    20_000
                )
            }
        } catch (e: Exception) { /* best effort */ }
        val n = PowerRegistry.count(ctx)
        PowerRegistry.clear(ctx)
        return "Reset ✓ — removed $n power" + (if (n == 1) "" else "s") +
            ", stopped their local servers, and cleared what they added to the brain. Your data is untouched."
    }
}
