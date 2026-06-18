package com.agentos.shell.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Global "something is generating" signal. Every model call increments it; the UI shows a small
 * non-blocking animation while it's > 0. Safe to update from background threads.
 */
object Busy {
    var active by mutableIntStateOf(0)
        private set

    @Synchronized fun start() { active++ }
    @Synchronized fun end() { if (active > 0) active-- }
}
