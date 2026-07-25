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
                else if (mode == "questions") com.agentos.shell.tools.BrainQuestions.forceRefresh(app)
                else com.agentos.shell.tools.VoiceAudit.run(app, from, msg)
            } catch (t: Throwable) {}
        }.start()
    }
}
