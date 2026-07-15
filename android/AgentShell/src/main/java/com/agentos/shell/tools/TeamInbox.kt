package com.agentos.shell.tools

/**
 * Tiny hand-off so a tapped team notification lands ON the right agent. ShellActivity sets [openEmpId]
 * from the notification's "emp" extra; the Research/Team screen reads it once, opens that worker's card,
 * then clears it.
 */
object TeamInbox {
    @Volatile var openEmpId: String? = null
}
