package com.agentos.shell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentos.shell.tools.InteractionStore
import com.agentos.shell.tools.MemoryStore

/**
 * Owner-granted Accessibility service. Two jobs:
 *  1) READ — logs on-screen text into InteractionStore for recall (unchanged).
 *  2) ACT (P1) — with gestures enabled, it can read the live node tree and tap / type / scroll on the
 *     user's behalf for the action layer (ScreenAgent). It only ever acts while the screen is on and the
 *     target app is foreground; never captures password CONTENTS into the log (but the agent CAN type into
 *     password fields to fill logins you initiate); the Total Recall log itself stays on-device.
 *
 * Enabled by the user in Settings > Accessibility; gated again by the in-app toggle.
 */
class InteractionLogService : AccessibilityService() {

    private var lastCapture = 0L
    private val minIntervalMs = 700L     // throttle: at most ~1 capture/app-change/second

    // ── P1 action layer ──
    data class ScreenNode(
        val index: Int, val role: String, val text: String,
        val clickable: Boolean, val editable: Boolean, val bounds: Rect,
        val checkable: Boolean = false, val checked: Boolean = false, val scrollable: Boolean = false
    )
    // The last snapshot the planner saw — actions reference nodes by index into this list.
    @Volatile private var snapshot: List<Pair<AccessibilityNodeInfo, ScreenNode>> = emptyList()

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** A structured, actionable dump of the current screen + a fresh snapshot for tapping by index. */
    @Synchronized
    fun readScreen(): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<Pair<AccessibilityNodeInfo, ScreenNode>>()
        try { walkActionable(root, out) } catch (e: Exception) {}
        snapshot = out
        return out.map { it.second }
    }

    fun currentPackage(): String = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (e: Exception) { "" }

    /** First meaningful text/description from a clickable container's descendants — surfaces labels like
     *  "Message"/"Send" that apps render on a non-clickable child of the tappable button. */
    private fun descendantLabel(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 3) return ""
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            if (c.isPassword) continue
            val t = (c.text ?: c.contentDescription)?.toString()?.trim().orEmpty()
            if (t.length in 1..40) return t
            val deeper = descendantLabel(c, depth + 1)
            if (deeper.isNotEmpty()) return deeper
        }
        return ""
    }

    private fun walkActionable(node: AccessibilityNodeInfo?, out: ArrayList<Pair<AccessibilityNodeInfo, ScreenNode>>, depth: Int = 0) {
        if (node == null || depth > 90 || out.size > 200) return
        if (node.isPassword) {
            // Include password fields so the agent can FILL them (logins/sign-ups), but NEVER expose or log
            // their contents — the label is masked and the actual text is never read.
            if (node.isEditable) {
                val r = Rect(); node.getBoundsInScreen(r)
                out.add(node to ScreenNode(out.size, "password", "[password field]", true, true, r))
            }
        } else {
            var txt = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val clickable = node.isClickable; val editable = node.isEditable
            val checkable = node.isCheckable; val scrollable = node.isScrollable
            // ICON-ONLY BUTTONS (comment, share, like, menu…) often have no text/label. Fall back to the
            // view's resource-id name (e.g. .../comment_button → "comment button") so the agent can still
            // identify and tap them — works across all apps.
            if (txt.isEmpty() && (clickable || checkable)) {
                val idn = try { node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim().orEmpty() } catch (e: Exception) { "" }
                if (idn.length in 2..40) txt = idn
            }
            // Many apps (LinkedIn "Message", chat "Send") put the VISIBLE label on a NON-clickable child of a
            // clickable container, so the button itself reads as empty. Pull the label up from a descendant so
            // the agent can identify + tap it by name — deterministic, no vision needed. Works across apps.
            if (txt.isEmpty() && clickable) {
                txt = descendantLabel(node, 0)
            }
            if ((clickable || editable || checkable || scrollable || txt.isNotEmpty()) && (txt.isNotEmpty() || clickable || checkable || scrollable)) {
                val r = Rect(); node.getBoundsInScreen(r)
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val role = when {
                    checkable || cls.contains("Switch") || cls.contains("Toggle") || cls.contains("CheckBox") -> "switch"
                    editable -> "field"
                    scrollable -> "list"
                    clickable -> "button"
                    else -> "text"
                }
                out.add(node to ScreenNode(out.size, role, txt.take(90), clickable, editable, r, checkable, node.isChecked, scrollable))
            }
        }
        for (i in 0 until node.childCount) walkActionable(node.getChild(i), out, depth + 1)
    }

    /** Tap the actionable node at [index] (from the last readScreen). Returns success. */
    fun tapNode(index: Int): Boolean {
        val n = snapshot.getOrNull(index)?.first ?: return false
        // Click the node, or the nearest clickable ancestor.
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent; hops++
        }
        // Fallback: tap the center of its bounds.
        val b = snapshot.getOrNull(index)?.second?.bounds ?: return false
        return tapAt(b.centerX().toFloat(), b.centerY().toFloat())
    }

    fun setText(index: Int, text: String): Boolean {
        val n = snapshot.getOrNull(index)?.first ?: return false
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** Long-press a node (menus, drag handles, multi-select). Falls back to a held gesture on its center. */
    fun longPress(index: Int): Boolean {
        val n = snapshot.getOrNull(index)?.first
        if (n != null) {
            var cur: AccessibilityNodeInfo? = n; var hops = 0
            while (cur != null && hops < 6) { if (cur.isClickable || cur.isLongClickable) { if (cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true }; cur = cur.parent; hops++ }
        }
        val b = snapshot.getOrNull(index)?.second?.bounds ?: return false
        val p = Path().apply { moveTo(b.centerX().toFloat(), b.centerY().toFloat()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 650)).build(), null, null)
    }

    /** Directional swipe across the whole screen — for sliders, carousels, dismissing, pull-to-refresh. */
    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics; val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir.lowercase()) {
            "up" -> { p.moveTo(w / 2, h * 0.7f); p.lineTo(w / 2, h * 0.25f) }
            "down" -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return false
        }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 260)).build(), null, null)
    }

    /** Fire the keyboard's action (search/next/done/go) on the focused field. */
    fun imeEnter(): Boolean {
        return try {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && Build.VERSION.SDK_INT >= 30)
                focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            else false
        } catch (e: Exception) { false }
    }

    /** Current checked state of a node index (for verifying a toggle actually flipped). */
    fun checkedOf(index: Int): Boolean? = try { snapshot.getOrNull(index)?.first?.isChecked } catch (e: Exception) { null }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** Drag from one point to another (chess moves, sliders, reordering). Coords are absolute pixels. */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 400): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build(), null, null)
    }

    val screenW: Int get() = resources.displayMetrics.widthPixels
    val screenH: Int get() = resources.displayMetrics.heightPixels

    /** Capture the screen as JPEG base64 (downscaled) so a vision model can SEE canvas apps & game boards
     *  that expose no accessibility nodes. API 30+. Result delivered to [cb] (null on failure). */
    fun captureScreenshot(cb: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            hb.close()
                            if (hw == null) { android.util.Log.w("SlyOS", "OP screenshot: wrapHardwareBuffer null"); cb(null); return }
                            // A HARDWARE bitmap can't be scaled or compressed directly — copy to a software
                            // (ARGB_8888) bitmap first. This was the bug: compress on a hardware bitmap fails.
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (soft == null) { android.util.Log.w("SlyOS", "OP screenshot: software copy null"); cb(null); return }
                            val maxW = 950
                            val scaled = if (soft.width > maxW) {
                                val h = (soft.height.toFloat() * maxW / soft.width).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(soft, maxW, h, true)
                            } else soft
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 78, bos)
                            android.util.Log.i("SlyOS", "OP screenshot ok ${scaled.width}x${scaled.height} ${bos.size()}b")
                            cb(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                        } catch (e: Throwable) { android.util.Log.w("SlyOS", "OP screenshot process fail: ${e.message}"); cb(null) }
                    }
                    override fun onFailure(errorCode: Int) { android.util.Log.w("SlyOS", "OP screenshot onFailure code=$errorCode"); cb(null) }
                })
        } catch (e: Throwable) {
            val m = e.message ?: ""
            if (m.contains("capability", true)) screenshotBlocked = true
            android.util.Log.w("SlyOS", "OP screenshot throw: $m"); cb(null)
        }
    }

    fun scroll(down: Boolean): Boolean {
        val h = resources.displayMetrics.heightPixels.toFloat(); val w = resources.displayMetrics.widthPixels / 2f
        val p = Path().apply { if (down) { moveTo(w, h * 0.7f); lineTo(w, h * 0.3f) } else { moveTo(w, h * 0.3f); lineTo(w, h * 0.7f) } }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        return dispatchGesture(g, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // REFLEX LEARN recording — capture the user's own taps/typing to teach a repeatable skill.
        // Ignore taps inside SlyOS itself (the Start button, navigating back to Stop) — only the real task.
        if (com.agentos.shell.tools.ReflexLearn.recording && event.packageName?.toString() != packageName) {
            try {
                val src = event.source
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> if (src != null && !src.isPassword) {
                        val b = Rect(); src.getBoundsInScreen(b)
                        com.agentos.shell.tools.ReflexLearn.onClick(
                            src.viewIdResourceName ?: "",
                            (src.text ?: src.contentDescription)?.toString() ?: "",
                            src.contentDescription?.toString() ?: "",
                            src.className?.toString() ?: "", b.centerX(), b.centerY())
                    }
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (src != null && !src.isPassword) com.agentos.shell.tools.ReflexLearn.onType(
                        src.viewIdResourceName ?: "",
                        src.contentDescription?.toString() ?: "",
                        src.className?.toString() ?: "",
                        event.text?.joinToString(" ")?.trim() ?: "")
                }
            } catch (e: Exception) {}
        }
        // AUTO-ANSWER incoming WhatsApp/VoIP calls and hand them to the AI (experimental, off by default).
        try {
            if (MemoryStore.answerCalls(applicationContext) &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                 event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                 event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)) {
                val fg = event.packageName?.toString() ?: ""
                // Diagnostic: log EVERY relevant event's package so we can see what an incoming call surfaces as.
                android.util.Log.i("SlyOS-Call", "evt type=${event.eventType} pkg=$fg running=${CallAgentService.running}")
                val wa = fg.contains("whatsapp", true)
                // The call UI may be hosted by the system, not WhatsApp — treat these as "maybe a call" and scan.
                val callHost = wa || fg.contains("systemui", true) || fg.contains("telecom", true) ||
                    fg.contains("incallui", true) || fg.contains("dialer", true) || fg.contains("server.telecom", true)
                if (callHost && !CallAgentService.running) {
                    handleWhatsAppCall()
                } else if (CallAgentService.running && fg.isNotEmpty() && fg != packageName && !wa && !fg.contains("systemui", true)) {
                    android.util.Log.i("SlyOS-Call", "left call UI (now $fg) → stopping agent")
                    CallAgentService.stop(applicationContext)
                }
            }
        } catch (e: Throwable) { android.util.Log.e("SlyOS-Call", "trigger", e) }
        // Auto-start Chess Coach when a chess app comes to the foreground (if the user armed it).
        // PERSONAL-ONLY: never runs in public builds.
        try {
            if (com.agentos.shell.BuildConfig.ENABLE_CHESS && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val fg = event.packageName?.toString() ?: ""
                if (Regex("(?i)chess|lichess").containsMatchIn(fg) && !com.agentos.shell.ChessCoachService.running) {
                    val p = applicationContext.getSharedPreferences("slyos", android.content.Context.MODE_PRIVATE)
                    if (p.getBoolean("chess_autostart", false) && android.provider.Settings.canDrawOverlays(applicationContext))
                        com.agentos.shell.ChessCoachService.start(applicationContext, p.getInt("chess_elo", 1500), "a")
                }
            }
        } catch (e: Throwable) {}
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

    // ── WhatsApp call auto-answer ─────────────────────────────────────────────────────────────────
    // Detect the incoming-call UI, tap Answer (or slide up), switch to speaker so the mic can hear the
    // caller and the AI's voice reaches them, then start the headless CallAgent loop. WhatsApp's UI text
    // varies by version/locale, so this uses broad heuristics and is tuned on-device.
    @Volatile private var lastAnswerAttempt = 0L

    /** All node trees currently on screen: the active window plus every interactive window (the incoming
     *  call often lives in its OWN window, not the active one). */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (e: Exception) {}
        try { for (w in windows) { try { w.root?.let { roots.add(it) } } catch (e: Exception) {} } } catch (e: Exception) {}
        return roots
    }

    private fun handleWhatsAppCall() {
        if (CallAgentService.running) return
        val now = System.currentTimeMillis()
        if (now - lastAnswerAttempt < 2500) return          // throttle scans across rapid content events
        lastAnswerAttempt = now

        val roots = allRoots()
        android.util.Log.i("SlyOS-Call", "scanning ${roots.size} window(s) for the Answer button")
        val rx = Regex("(?i)\\b(answer|accept)\\b")
        var answer: AccessibilityNodeInfo? = null
        for (r in roots) { answer = findClickableMatching(r, rx); if (answer != null) break }

        if (answer != null) {
            android.util.Log.i("SlyOS-Call", "found Answer: '${(answer.text ?: answer.contentDescription)}' — tapping")
            clickNode(answer)
        } else {
            // No labelled Answer button — dump what IS on screen so we can tune the matcher, then try slide-up.
            val labels = StringBuilder()
            for (r in roots) collectLabels(r, labels, 0)
            android.util.Log.w("SlyOS-Call", "NO answer button. on-screen labels = [${labels.toString().take(500)}]")
            var slide: AccessibilityNodeInfo? = null
            for (r in roots) { slide = findNodeMatching(r, Regex("(?i)(swipe|slide|up).{0,14}(up|answer)")); if (slide != null) break }
            if (slide != null) { android.util.Log.i("SlyOS-Call", "slide-to-answer → swiping up"); swipe("up") }
            else { android.util.Log.w("SlyOS-Call", "giving up on this event"); return }
        }
        val caller = readCallerName(roots)

        // Give the call a beat to connect, then force speaker + hand off to the AI.
        android.os.Handler(mainLooper).postDelayed({
            try {
                var spk: AccessibilityNodeInfo? = null
                for (r in allRoots()) { spk = findClickableMatching(r, Regex("(?i)speaker")); if (spk != null) break }
                if (spk != null && !isOn(spk)) { android.util.Log.i("SlyOS-Call", "tapping Speaker"); clickNode(spk) }
                else android.util.Log.i("SlyOS-Call", "no Speaker button found (using AudioManager)")
            } catch (e: Exception) {}
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.let {
                    it.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    it.isSpeakerphoneOn = true
                }
            } catch (e: Exception) {}
            android.util.Log.i("SlyOS-Call", "starting CallAgent for '$caller'")
            CallAgentService.start(applicationContext, caller)
        }, 1800)
    }

    /** Collect visible text/desc labels (for diagnosing the call screen). */
    private fun collectLabels(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40 || sb.length > 600) return
        val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
        if (t.isNotEmpty() && t.length <= 40) sb.append(t).append(if (n.isClickable) "(clk)" else "").append(" | ")
        for (i in 0 until n.childCount) collectLabels(n.getChild(i), sb, depth + 1)
    }

    /** Grab a likely caller name from the WhatsApp call screen (skips UI labels). "" if none found. */
    private fun readCallerName(roots: List<AccessibilityNodeInfo>): String {
        val skip = Regex("(?i)answer|accept|decline|reject|speaker|mute|video|voice call|whats\\s?app|calling|ringing|end call|swipe|slide|add|hold|minimi[sz]e|encrypted|\\d{1,2}:\\d{2}|\\b(mon|tue|wed|thu|fri|sat|sun)\\b|\\b(am|pm)\\b|slyos|notification|battery|wifi|signal")
        fun dfs(n: AccessibilityNodeInfo?, depth: Int): String {
            if (n == null || depth > 40) return ""
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.length in 2..40 && !skip.containsMatchIn(t) && !t.any { it.isDigit() && t.count { c -> c.isDigit() } > 6 }) return t
            for (i in 0 until n.childCount) { val r = dfs(n.getChild(i), depth + 1); if (r.isNotEmpty()) return r }
            return ""
        }
        return try { for (r in roots) { val name = dfs(r, 0); if (name.isNotEmpty()) return name }; "" } catch (e: Exception) { "" }
    }

    private fun isOn(n: AccessibilityNodeInfo): Boolean = try { n.isChecked || n.isSelected } catch (e: Exception) { false }

    /** DFS for a node whose text/desc matches [rx] AND is clickable (or has a clickable ancestor). */
    private fun findClickableMatching(node: AccessibilityNodeInfo?, rx: Regex, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 80) return null
        val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (label.isNotEmpty() && rx.containsMatchIn(label)) {
            var cur: AccessibilityNodeInfo? = node; var hops = 0
            while (cur != null && hops < 6) { if (cur.isClickable) return cur; cur = cur.parent; hops++ }
        }
        for (i in 0 until node.childCount) findClickableMatching(node.getChild(i), rx, depth + 1)?.let { return it }
        return null
    }

    /** DFS for any node whose text/desc matches [rx] (for hints like "swipe up to answer"). */
    private fun findNodeMatching(node: AccessibilityNodeInfo?, rx: Regex, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 80) return null
        val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (label.isNotEmpty() && rx.containsMatchIn(label)) return node
        for (i in 0 until node.childCount) findNodeMatching(node.getChild(i), rx, depth + 1)?.let { return it }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val b = Rect(); n.getBoundsInScreen(b)
        return tapAt(b.centerX().toFloat(), b.centerY().toFloat())
    }

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

    companion object {
        // Set while the service is connected, so ScreenAgent can drive taps/typing. Null if the user
        // hasn't granted Accessibility — the action layer degrades gracefully to "not available".
        @Volatile var instance: InteractionLogService? = null
        // Set true if takeScreenshot() reports the service lacks the capability — means the user must
        // re-toggle the accessibility service so the new canTakeScreenshot config takes effect.
        @Volatile var screenshotBlocked: Boolean = false
    }
}
