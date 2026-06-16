package com.agentos.shell.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Caches the AI-written "things that matter" so we don't call the API on every lock. */
object BriefStore {
    var lines by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
    private var lastGen = 0L

    fun stale(): Boolean = System.currentTimeMillis() - lastGen > 5 * 60 * 1000L
    fun markGenerated() { lastGen = System.currentTimeMillis() }
}
