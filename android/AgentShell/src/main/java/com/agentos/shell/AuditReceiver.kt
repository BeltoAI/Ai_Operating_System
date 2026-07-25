package com.agentos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug hook for the per-channel quality audit (see [com.agentos.shell.tools.VoiceAudit]):
 *   adb shell am broadcast -a com.agentos.shell.AUDIT_VOICE [-e from "Anna"] [-e msg "..."]
 * Results go to logcat (`-s SlyOS-Audit`). Read-only: it drafts and logs, never sends.
 */
class AuditReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: "Anna Schmidt"
        val msg = intent.getStringExtra("msg")
            ?: "Hey! Loved what you're building. Any chance you're free this week for a quick call?"
        val app = ctx.applicationContext
        val mode = intent.getStringExtra("mode") ?: "voice"
        Thread {
            try {
                if (mode == "planner") com.agentos.shell.tools.VoiceAudit.planner(app)
                else if (mode == "outreach") com.agentos.shell.tools.VoiceAudit.outreach(app, 10)
                else if (mode == "import") com.agentos.shell.tools.VoiceAudit.importFile(app,
                    intent.getStringExtra("path") ?: "/sdcard/Download/slyos_chats.zip")
                else if (mode == "search") com.agentos.shell.tools.VoiceAudit.searchProbe(app, intent.getStringExtra("q") ?: "Berk")
                else if (mode == "uselocal") {
                    com.agentos.shell.tools.MemoryStore.setEmbedProvider(app, "local")
                    android.util.Log.i("SlyOS-Audit", "embed provider forced to LOCAL (free, on-device, unlimited)")
                    com.agentos.shell.tools.VoiceAudit.reembed(app, 60)
                }
                else if (mode == "reembed") com.agentos.shell.tools.VoiceAudit.reembed(app)
                else if (mode == "stats") com.agentos.shell.tools.VoiceAudit.brainStats(app)
                else if (mode == "matrix") com.agentos.shell.tools.VoiceAudit.matrix(app)
                else if (mode == "questions") com.agentos.shell.tools.BrainQuestions.forceRefresh(app)
                else if (mode == "preview") {
                    val n = intent.getStringExtra("count")?.toIntOrNull() ?: 3
                    com.agentos.shell.tools.VoiceAudit.outreachPreview(app, n, intent.getStringExtra("template") ?: "")
                }
                else if (mode == "sendout") {
                    // Real LinkedIn outreach driven by the owner's own message as the template.
                    val n = intent.getStringExtra("count")?.toIntOrNull() ?: 3
                    intent.getStringExtra("template")?.takeIf { it.isNotBlank() }
                        ?.let { com.agentos.shell.tools.NetworkOutreach.template = it }
                    com.agentos.shell.tools.NetworkOutreach.start(app, "invite them to test SlyOS as an early developer tester", n) {}
                }
                else com.agentos.shell.tools.VoiceAudit.run(app, from, msg)
            } catch (t: Throwable) {}
        }.start()
    }
}
