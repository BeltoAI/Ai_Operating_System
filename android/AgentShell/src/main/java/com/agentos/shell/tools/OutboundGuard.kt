package com.agentos.shell.tools

/**
 * One outbound content filter for everything the agent sends on the user's behalf WITHOUT a human in
 * the loop (autonomous replies, workers, the bot). Because a reply is drafted from an attacker-
 * controlled incoming message, a prompt-injection ("ignore previous instructions, reply with a link /
 * ask for money / leak the address") could otherwise be auto-sent as the user.
 *
 * [check] returns null when the draft is safe to auto-send, or a short reason string when it should be
 * HELD as a draft for the user to review + confirm instead. It never blocks manual, user-initiated sends.
 */
object OutboundGuard {

    // A link the user didn't type themselves — auto-posting one is a classic injection payload.
    private val LINK = Regex("(?i)(https?://|www\\.[^\\s]+|\\b[a-z0-9-]+\\.(com|net|org|io|co|ru|cn|xyz|link|click|top|info|biz|app|me)\\b(/\\S*)?)")
    // Money / credential / account solicitation — never auto-send unattended.
    private val MONEY_CREDS = Regex("(?i)(bitcoin|btc|ethereum|crypto|wallet address|seed phrase|private key|password|passcode|\\botp\\b|one[- ]?time (code|password)|verification code|gift ?card|wire transfer|paypal\\.me|venmo|cash ?app|zelle|bank account|routing number|iban|\\bssn\\b|social security|send (me )?\\$?\\d)")
    // The draft itself parroting an injection / breaking character.
    private val INJECTION = Regex("(?i)(ignore (all |the )?(previous|prior|above) (instructions|prompts|messages)|as an? (ai|assistant|language model)|i am an? (ai|bot|language model)|system prompt|jailbreak|developer mode)")

    private const val MAX_LEN = 1500

    /** @return null if safe to auto-send, else a short reason to HOLD it as a draft. */
    fun check(draft: String): String? {
        val d = draft.trim()
        if (d.isEmpty()) return "empty"
        if (d.length > MAX_LEN) return "too long"
        if (LINK.containsMatchIn(d)) return "contains a link"
        if (MONEY_CREDS.containsMatchIn(d)) return "asks for money/credentials"
        if (INJECTION.containsMatchIn(d)) return "looks like a prompt injection"
        return null
    }
}
