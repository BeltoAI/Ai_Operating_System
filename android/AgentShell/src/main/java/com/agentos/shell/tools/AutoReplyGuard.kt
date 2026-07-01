package com.agentos.shell.tools

/**
 * Safety rail against runaway auto-replies. Even with per-message cooldowns, a fast back-and-forth
 * (or a misbehaving app that keeps re-posting) could make SlyOS fire many autonomous sends and burn
 * tokens. This caps auto-SENDS per contact and globally within a rolling hour. When the cap is hit we
 * fall back to staging a draft for the user instead of sending — nothing is lost, it just stops the
 * loop. In-memory only (resets on restart), which errs on the safe side.
 */
object AutoReplyGuard {
    private const val WINDOW_MS = 60 * 60 * 1000L   // 1 hour
    private const val PER_CONTACT = 6
    private const val GLOBAL = 30

    private val perContact = HashMap<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()

    private fun prune(q: ArrayDeque<Long>, now: Long) {
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
    }

    /** True if an autonomous send to [contact] is allowed right now (and records it if so). */
    @Synchronized
    fun allow(contact: String): Boolean {
        val now = System.currentTimeMillis()
        prune(global, now)
        val q = perContact.getOrPut(contact.ifBlank { "?" }) { ArrayDeque() }
        prune(q, now)
        if (global.size >= GLOBAL || q.size >= PER_CONTACT) return false
        q.addLast(now); global.addLast(now)
        return true
    }
}
