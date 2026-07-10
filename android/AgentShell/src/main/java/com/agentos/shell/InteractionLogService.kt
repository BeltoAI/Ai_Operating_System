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
 *     target app is foreground; never captures passwords/secure fields; everything stays on-device.
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

    companion object {
        // Set while the service is connected, so ScreenAgent can drive taps/typing. Null if the user
        // hasn't granted Accessibility — the action layer degrades gracefully to "not available".
        @Volatile var instance: InteractionLogService? = null
        // Set true if takeScreenshot() reports the service lacks the capability — means the user must
        // re-toggle the accessibility service so the new canTakeScreenshot config takes effect.
        @Volatile var screenshotBlocked: Boolean = false
    }
}
