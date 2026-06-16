package com.agentos.shell

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentos.shell.tools.InteractionStore
import com.agentos.shell.tools.MemoryStore

/**
 * Owner-granted Accessibility service. Reads the text rendered on screen across apps and logs it
 * into InteractionStore so the agent can recall it later. Captures only what is visible — never
 * passwords/secure fields, never anything off-screen, and everything stays on the device.
 *
 * Enabled by the user in Settings > Accessibility; gated again by the in-app toggle.
 */
class InteractionLogService : AccessibilityService() {

    private var lastCapture = 0L
    private val minIntervalMs = 700L     // throttle: at most ~1 capture/app-change/second

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!MemoryStore.recallEnabled(applicationContext)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return                      // ignore SlyOS itself
        val now = System.currentTimeMillis()
        if (now - lastCapture < minIntervalMs) return
        lastCapture = now

        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        try { collect(root, sb) } catch (e: Exception) { /* tree can change mid-walk */ }
        val text = sb.toString().trim()
        if (text.isNotEmpty()) {
            val label = appLabel(pkg)
            InteractionStore.record(applicationContext, label, text)
        }
    }

    override fun onInterrupt() {}

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 60 || sb.length > 4000) return
        if (node.isPassword) return                          // never capture secure fields
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length in 2..600 && !sb.contains(t)) {
            sb.append(t).append(" · ")
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}
