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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MessageStore

/**
 * The persistent SlyOS nav panel: a small bar that floats over EVERY app (Back · Home · Brain), plus the
 * "hold the brain over any app" feature — tapping Brain has SlyOS read the current screen via the
 * accessibility service, explain it, show it in a floating bubble, and store it in the brain. Opt-in:
 * only runs after the user enables it and grants "Display over other apps".
 */
class OverlayNavService : Service() {
    private var wm: WindowManager? = null
    private var bar: View? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
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
            .setContentText("Tap the brain over any app to have SlyOS read the screen.")
            .build()
        startForeground(9971, n)
    }

    private fun pill(label: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFFF4EFE6.toInt())
        textSize = 15f
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onTap() }
    }

    private fun addBar() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { setColor(0xF01A1714.toInt()); cornerRadius = dp(26).toFloat() }
        }
        row.addView(pill("‹") { InteractionLogService.instance?.back() })
        row.addView(pill("⌂") { openHome() })
        row.addView(pill("✦ Brain") { analyzeScreen() })
        row.addView(pill("✕") { stop(this) })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(28) }
        try { wm?.addView(row, p); bar = row } catch (e: Exception) {}
    }

    private fun openHome() {
        try {
            startActivity(packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(92) }
        try { wm?.addView(tv, p); bubble = tv } catch (e: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { if (bubble === tv) { wm?.removeView(tv); bubble = null } } catch (e: Exception) {}
        }, 14000)
    }

    override fun onDestroy() {
        running = false
        try { bar?.let { wm?.removeView(it) } } catch (e: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        bar = null; bubble = null
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayNavService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayNavService::class.java)) }
    }
}
