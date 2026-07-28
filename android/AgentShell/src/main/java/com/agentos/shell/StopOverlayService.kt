package com.agentos.shell

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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

/**
 * A STOP button that is on top of whatever SlyOS is currently driving.
 *
 * A stop already existed — an ongoing notification with a STOP action, swipe-to-dismiss-to-stop,
 * and live per-step progress. It was only ever in the notification shade, which is exactly the
 * place you cannot reach while the agent is tapping and typing in another app: pulling the shade
 * down fights the automation for the same screen, and the agent carries on underneath it.
 *
 * So the stop moves to where the problem is. Always on top, one tap, no shade, no app switch.
 *
 * Written in plain Android views rather than Compose on purpose. This has to appear during a
 * screen-automation run, when the app may be backgrounded and the process under memory pressure —
 * three views and a TextView start instantly and cannot fail for a reason that needs debugging
 * later, which is the whole point of a stop button.
 */
class StopOverlayService : Service() {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var label: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as? WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(18), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0xF2D64545.toInt())   // solid red — this is the one control that must be found
            }
            // Tapping stops everything the agent is doing. Deliberately the whole pill rather than a
            // small icon: someone reaching to stop an app that is typing on their behalf is not
            // aiming carefully.
            setOnClickListener {
                try {
                    sendBroadcast(Intent(this@StopOverlayService, StopActionReceiver::class.java))
                } catch (e: Exception) {}
                stop(this@StopOverlayService)
            }
        }

        root.addView(TextView(this).apply {
            text = "■"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
        })
        label = TextView(this).apply {
            text = "  STOP"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Top-right: out of the way of the keyboard and of most action buttons, which live at
            // the bottom of a screen the agent is likely to be working through.
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(64)
        }

        try { wm?.addView(root, params); view = root } catch (e: Exception) { stopSelf() }
    }

    /** Update the pill with what the agent is doing right now. */
    private fun setStep(text: String) {
        main.post { label?.text = if (text.isBlank()) "  STOP" else "  STOP · $text" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_STEP)?.let { setStep(it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { view?.let { wm?.removeView(it) } } catch (e: Exception) {}
        view = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_STEP = "step"

        /** Whether the overlay can be shown at all — it needs draw-over-apps. */
        fun canShow(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        fun show(ctx: Context, step: String = "") {
            if (!canShow(ctx)) return
            try {
                ctx.startService(Intent(ctx, StopOverlayService::class.java).putExtra(EXTRA_STEP, step))
            } catch (e: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, StopOverlayService::class.java)) } catch (e: Exception) {}
        }
    }
}
