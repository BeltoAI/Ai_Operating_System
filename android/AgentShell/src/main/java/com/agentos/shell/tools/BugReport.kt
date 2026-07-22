package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.agentos.shell.BuildConfig
import org.json.JSONObject

/**
 * DEAD-SIMPLE user bug reporting. A user types what went wrong; we attach lightweight diagnostics (build,
 * device, which background workers look silent) and send it CENTRALLY to Supabase so the developer sees every
 * report in one place — no email account or setup needed by the user. If Supabase isn't configured or the
 * insert fails, we fall back to opening a prefilled email to support. Never throws to the caller.
 *
 * Supabase setup (run once in the project's SQL editor):
 *   create table bug_reports (id bigint generated always as identity primary key, created_at timestamptz default now(),
 *     message text, contact text, version text, device text, android text, diagnostics text);
 *   alter table bug_reports enable row level security;
 *   create policy "anon can report" on bug_reports for insert to anon with check (true);
 */
object BugReport {
    private const val TABLE = "bug_reports"
    private const val SUPPORT_EMAIL = "support@belto.world"

    /** Submit a report. [contact] is optional (so you can reply). Returns a short status line for the UI.
     *  BLOCKING (network) — call from a background thread. */
    fun submit(ctx: Context, message: String, contact: String = ""): String {
        val msg = message.trim()
        if (msg.length < 3) return "Tell me a little more about what went wrong."
        val diag = diagnostics(ctx)
        if (SupabaseClient.configured()) {
            val ok = try {
                SupabaseClient.insertAnon(TABLE, JSONObject()
                    .put("message", msg.take(4000))
                    .put("contact", contact.trim().take(200))
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("android", Build.VERSION.RELEASE)
                    .put("diagnostics", diag.take(6000)))
            } catch (e: Exception) { false }
            if (ok) return "Sent ✓ — thank you. Every report is read."
        }
        return emailFallback(ctx, msg, contact, diag)
    }

    /** No backend (or the send failed) → open the user's email app prefilled to support. */
    private fun emailFallback(ctx: Context, msg: String, contact: String, diag: String): String {
        return try {
            val body = msg + "\n\n---\n" + (if (contact.isNotBlank()) "Reply to: $contact\n" else "") + diag
            ctx.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$SUPPORT_EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, "SlyOS bug report")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opening your email — just tap send."
        } catch (e: Exception) { "Couldn't send automatically — please email $SUPPORT_EMAIL." }
    }

    /** Lightweight, privacy-safe triage info — no message contents, no personal data. */
    private fun diagnostics(ctx: Context): String = try {
        val silent = try { WorkerHealth.silent(ctx).joinToString(", ") { it.worker } } catch (e: Exception) { "" }
        buildString {
            append("build ").append(BuildConfig.VERSION_NAME)
            append(" · ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append("\n")
            if (silent.isNotBlank()) append("workers overdue: ").append(silent).append("\n")
        }
    } catch (e: Exception) { "" }
}
