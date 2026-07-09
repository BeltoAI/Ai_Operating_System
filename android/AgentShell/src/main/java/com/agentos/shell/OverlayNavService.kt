package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MessageStore

/**
 * The persistent SlyOS nav panel — the SAME bar as inside the app (Home · Now · Brain · Research · Apps),
 * rendered in a floating window over every app via a ComposeView. Tapping a tab opens SlyOS on that
 * screen. Tapping the centre Brain over another app has SlyOS read + explain the current screen and store
 * it in the brain (needs Accessibility). Opt-in; runs as a foreground service.
 */
class OverlayNavService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val vmStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var wm: WindowManager? = null
    private var bar: View? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Hide the floating bar while SlyOS itself is in the foreground (it has its own nav); show it over
     *  every other app. Driven by ShellActivity's onResume/onPause. */
    fun setBarVisible(visible: Boolean) {
        Handler(Looper.getMainLooper()).post {
            try { bar?.visibility = if (visible) View.VISIBLE else View.GONE } catch (e: Exception) {}
            if (!visible) try { bubble?.let { wm?.removeView(it) }; bubble = null } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (bar == null) addBar()
        running = true
        return START_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ensureForeground() {
        val ch = "slyos_nav"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(ch, "SlyOS nav panel", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("SlyOS nav panel is on")
            .setContentText("Tap Brain over any app to have SlyOS read the screen.")
            .build()
        startForeground(9971, n)
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun addBar() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayNavService)
            setViewTreeViewModelStoreOwner(this@OverlayNavService)
            setViewTreeSavedStateRegistryOwner(this@OverlayNavService)
            setContent { MaterialTheme { BarContent() } }
        }
        // WRAP_CONTENT window: the overlay only occupies the pill itself, so the whole rest of the screen
        // stays fully usable (no more full-width bar eating content/taps). Floats just above the system nav.
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(52) }
        // Start hidden: the panel is enabled from inside SlyOS, so it should only appear once the user
        // switches to another app (ShellActivity.onPause shows it; onResume hides it again).
        view.visibility = View.GONE
        try { wm?.addView(view, p); bar = view } catch (e: Exception) {}
    }

    /** A glassy, Instagram-style floating pill with the SlyOS tabs (icons only). Wrap-content so it never
     *  covers more than itself. Swipe it UP or DOWN to close. Center Brain: TAP = explain this screen in a
     *  bubble (without leaving the app); LONG-PRESS = open SlyOS with the mic to ask a specific question. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BarContent() {
        val dragY = remember { androidx.compose.runtime.mutableStateOf(0f) }
        Row(
            Modifier
                .offset { IntOffset(0, dragY.value.toInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (kotlin.math.abs(dragY.value) > 120f) stop(this@OverlayNavService) else dragY.value = 0f },
                        onDragCancel = { dragY.value = 0f }
                    ) { _, dy -> dragY.value += dy }
                }
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0xE8F3F1EB))   // glassy light — reads over any app
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconOnly(Icons.Filled.Home) { launchSly(Screen.Home) }
            iconOnly(Icons.Filled.Bolt) { launchSly(Screen.Now) }
            Box(
                Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0x14000000))
                    .combinedClickable(onClick = { analyzeScreen() }, onLongClick = { onBrain() })
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Memory, "Brain", tint = T.accent, modifier = Modifier.size(27.dp)) }
            iconOnly(Icons.Filled.Science) { launchSly(Screen.Research) }
            iconOnly(Icons.Filled.Apps) { launchSly(Screen.Apps) }
        }
    }

    @Composable
    private fun iconOnly(icon: ImageVector, onClick: () -> Unit) {
        Box(Modifier.clip(CircleShape).clickable { onClick() }.padding(12.dp)) {
            Icon(icon, null, tint = Color(0xFF1A1714), modifier = Modifier.size(26.dp))
        }
    }

    /** Capture the current screen (if Accessibility is on), then open SlyOS with the mic so the user can
     *  ASK about it — answered in the full app with the screen text as context. */
    private fun onBrain() {
        val svc = InteractionLogService.instance
        if (svc != null) {
            val pkg = svc.currentPackage()
            val dump = try { svc.readScreen().joinToString("\n") { it.text }.trim() } catch (e: Exception) { "" }
            com.agentos.shell.tools.ScreenSnap.text = dump.take(4000); com.agentos.shell.tools.ScreenSnap.pkg = pkg
        } else { com.agentos.shell.tools.ScreenSnap.text = ""; com.agentos.shell.tools.ScreenSnap.pkg = "" }
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra("nav", "Home"); putExtra("start_voice", true)
            }
            startActivity(i)
        } catch (e: Exception) {}
    }

    private fun launchSly(target: Screen) {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra("nav", target.name)
            }
            startActivity(i)
        } catch (e: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun analyzeScreen() {
        val svc = InteractionLogService.instance
        if (svc == null) { toast("Turn on SlyOS Accessibility so it can read the screen."); return }
        val pkg = svc.currentPackage()
        val dump = try { svc.readScreen().joinToString("\n") { it.text }.trim() } catch (e: Exception) { "" }
        if (dump.isBlank()) { toast("Nothing readable on this screen."); return }
        toast("Reading this screen…")
        Thread {
            val out = try { AgentClient.explainScreen(pkg, dump) } catch (e: Exception) { "Couldn't read the screen." }
            try { MessageStore.insertOne(applicationContext, "Screen", "Contextual", "system", "system", "Screen ($pkg): $out") } catch (e: Exception) {}
            Handler(Looper.getMainLooper()).post { showBubble(out) }
        }.start()
    }

    private fun showBubble(text: String) {
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFF4EFE6.toInt()); textSize = 14f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(0xF01A1714.toInt()); cornerRadius = dp(18).toFloat() }
            setOnClickListener { try { wm?.removeView(this); bubble = null } catch (e: Exception) {} }
        }
        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(110) }
        try { wm?.addView(tv, p); bubble = tv } catch (e: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { if (bubble === tv) { wm?.removeView(tv); bubble = null } } catch (e: Exception) {}
        }, 15000)
    }

    override fun onDestroy() {
        running = false
        instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        try { bar?.let { wm?.removeView(it) } } catch (e: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        try { vmStore.clear() } catch (e: Exception) {}
        bar = null; bubble = null
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        @Volatile var instance: OverlayNavService? = null
        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayNavService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayNavService::class.java)) }
    }
}
