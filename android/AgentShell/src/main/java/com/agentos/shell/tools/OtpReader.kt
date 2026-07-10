package com.agentos.shell.tools

import android.content.Context

/**
 * Fetches the latest 2FA / one-time code so the screen agent can auto-fill "enter the code we sent" screens
 * and get past registrations/logins that break at 2FA. Sources, newest first:
 *   1) recent NOTIFICATIONS — covers SMS codes (they arrive as a Messages notification we already read), and
 *      any app that posts the code as a notification.
 *   2) recent EMAIL — via Gmail, when the code was emailed.
 * Nothing new is required beyond the notification access + Google connection the app already uses.
 */
object OtpReader {
    private val CODEY = Regex("(?i)code|otp|verif|passcode|one[- ]?time|2fa|sign.?in|log.?in|security")

    fun latest(ctx: Context, hint: String = ""): String? {
        // 1) Notifications (SMS + app codes).
        try {
            val notes = NotificationStore.notes
            for (n in notes.take(25)) {
                val body = (n.title + " " + n.text)
                if (CODEY.containsMatchIn(body)) GmailClient.pickCode(body)?.let { return it }
            }
        } catch (e: Exception) {}
        // 2) Email.
        return try { GmailClient.latestCode(ctx, hint) } catch (e: Exception) { null }
    }
}
