package com.agentos.shell.tools

/**
 * A one-shot capture of what was on the user's screen when they tapped the floating Brain over another
 * app. HomeScreen reads it as extra context for the very next question, then clears it — so "what does
 * this mean?" or "reply to this" is answered with the actual screen in mind.
 */
object ScreenSnap {
    @Volatile var text: String = ""
    @Volatile var pkg: String = ""

    fun take(): Pair<String, String> {
        val t = text; val p = pkg
        text = ""; pkg = ""
        return t to p
    }
}
