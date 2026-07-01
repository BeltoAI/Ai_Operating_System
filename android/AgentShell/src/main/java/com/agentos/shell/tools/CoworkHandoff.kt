package com.agentos.shell.tools

/**
 * One-shot handoff from Home (or anywhere) into the Cowork workspace: stash a prompt, navigate to
 * Cowork, and the screen picks it up on open and auto-runs it. In-memory is fine — it's consumed
 * immediately on the next screen.
 */
object CoworkHandoff {
    @Volatile var pending: String? = null

    /** Take and clear the pending prompt (returns null if none). */
    fun consume(): String? {
        val p = pending; pending = null; return p
    }
}
