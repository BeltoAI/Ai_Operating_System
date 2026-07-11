package com.agentos.shell

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.agentos.shell.tools.ChessBoard
import com.agentos.shell.tools.ChessEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CHESS COACH — a live training overlay. Open your chess game, start the coach, and it reads the board every
 * turn (via accessibility), asks Stockfish for the best move at your chosen strength, and DRAWS an arrow on
 * the board plus the move in SAN. You make the move yourself, so nothing can misfire. Elo is adjustable live
 * (500–3600). Built for training/analysis/bots — not for cheating rated games against humans.
 */
class ChessCoachService : Service() {
    private lateinit var wm: WindowManager
    private var arrow: ArrowView? = null
    private var bar: View? = null
    private var moveLabel: TextView? = null
    private var eloLabel: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @Volatile private var elo = 1500
    @Volatile private var side = 'a'      // 'a' = auto-detect from board orientation
    @Volatile private var lastFen = ""
    @Volatile private var alive = true
    private var barParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            elo = intent?.getIntExtra("elo", 1500) ?: 1500
            side = (intent?.getStringExtra("side") ?: "a").firstOrNull() ?: 'a'
            if (arrow == null) { running = true; wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager; addOverlays(); loop() }
        } catch (e: Throwable) {
            android.util.Log.e("SlyOS", "ChessCoach start failed: ${e.message}", e)
            running = false; try { stopSelf() } catch (x: Exception) {}
        }
        return START_NOT_STICKY   // never auto-restart into a crash loop
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addOverlays() {
      try {
        // 1) Full-screen, NON-touchable arrow layer — the user can still play underneath.
        arrow = ArrowView(this)
        val ap = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(arrow, ap) } catch (e: Exception) {}

        // 2) A COMPACT, DRAGGABLE pill (never spans the screen, so it can't cover the game's buttons).
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#F00C1014")); setStroke(dp(1), Color.parseColor("#3300FF88"))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val grip = TextView(this).apply { text = "⠿  Chess Coach"; setTextColor(Color.parseColor("#7A8A99")); textSize = 12f }
        top.addView(grip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this).apply { text = "✕"; setTextColor(Color.parseColor("#9AA")); textSize = 15f; setPadding(dp(10), 0, dp(4), 0); setOnClickListener { stopSelf() } }
        top.addView(close)
        pill.addView(top)
        moveLabel = TextView(this).apply { text = "Reading board…"; setTextColor(Color.WHITE); textSize = 17f; setPadding(0, dp(4), 0, dp(6)) }
        pill.addView(moveLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eloLabel = TextView(this).apply { text = "$elo"; setTextColor(Color.parseColor("#00FF88")); textSize = 12f; minWidth = dp(38) }
        row.addView(eloLabel)
        val seek = SeekBar(this).apply {
            max = 3100; progress = (elo - 500).coerceIn(0, 3100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { elo = 500 + p; eloLabel?.text = "$elo"; lastFen = "" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sideBtn = TextView(this).apply {
            text = "Auto"; setTextColor(Color.parseColor("#00FF88")); textSize = 12f; setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { side = when (side) { 'a' -> 'w'; 'w' -> 'b'; else -> 'a' }; text = when (side) { 'w' -> "White"; 'b' -> "Black"; else -> "Auto" }; lastFen = "" }
        }
        row.addView(sideBtn)
        pill.addView(row)
        bar = pill

        val bp = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = dp(12); bp.y = dp(40)
        barParams = bp
        // Drag by the grip handle → move the pill anywhere so it never blocks a button.
        var dx = 0; var dy = 0; var rx = 0f; var ry = 0f
        grip.setOnTouchListener { _, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> { dx = bp.x; dy = bp.y; rx = ev.rawX; ry = ev.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { bp.x = dx + (ev.rawX - rx).toInt(); bp.y = dy + (ev.rawY - ry).toInt(); try { wm.updateViewLayout(pill, bp) } catch (e: Exception) {}; true }
                else -> false
            }
        }
        try { wm.addView(bar, bp) } catch (e: Exception) {}
      } catch (e: Throwable) { android.util.Log.e("SlyOS", "ChessCoach overlay build failed: ${e.message}", e); running = false; try { stopSelf() } catch (x: Exception) {} }
    }

    private fun loop() = scope.launch {
        while (alive) {
            try {
                val svc = InteractionLogService.instance
                if (svc == null) { moveLabel?.text = "Enable SlyOS Accessibility to read the board."; delay(2000); continue }
                val nodes = withContext(Dispatchers.IO) { svc.readScreen() }
                val board = ChessBoard.parse(nodes, side)
                if (board == null) { moveLabel?.text = "No board detected — open your chess game."; delay(1800); continue }
                if (board.fen != lastFen) {
                    lastFen = board.fen
                    moveLabel?.text = "Thinking… (Elo $elo)"
                    val mv = withContext(Dispatchers.IO) { ChessEngine.bestMove(board.fen, elo) }
                    if (mv != null) {
                        moveLabel?.text = "Best: ${mv.san}   ${mv.evalText}   ·   ${mv.from}→${mv.to}"
                        arrow?.setArrow(board.coords[mv.from], board.coords[mv.to])
                    } else moveLabel?.text = "Couldn't reach the engine — check your connection."
                }
            } catch (e: Exception) {}
            delay(1600)
        }
    }

    override fun onDestroy() {
        alive = false; running = false; scope.coroutineContext[Job]?.cancel()
        try { arrow?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { bar?.let { wm.removeView(it) } } catch (e: Exception) {}
        arrow = null; bar = null
        super.onDestroy()
    }

    /** Transparent full-screen view that draws a hint arrow between two screen points. */
    private class ArrowView(ctx: Context) : View(ctx) {
        private var from: Pair<Int, Int>? = null
        private var to: Pair<Int, Int>? = null
        private val line = Paint().apply { color = Color.parseColor("#CC00FF88"); strokeWidth = 16f; style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        private val head = Paint().apply { color = Color.parseColor("#CC00FF88"); style = Paint.Style.FILL; isAntiAlias = true }
        fun setArrow(f: Pair<Int, Int>?, t: Pair<Int, Int>?) { from = f; to = t; postInvalidate() }
        override fun onDraw(canvas: Canvas) {
            val f = from; val t = to
            if (f == null || t == null) return
            val x1 = f.first.toFloat(); val y1 = f.second.toFloat(); val x2 = t.first.toFloat(); val y2 = t.second.toFloat()
            canvas.drawLine(x1, y1, x2, y2, line)
            val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val len = 46.0
            val p = Path()
            p.moveTo(x2, y2)
            p.lineTo((x2 - len * Math.cos(ang - Math.PI / 7)).toFloat(), (y2 - len * Math.sin(ang - Math.PI / 7)).toFloat())
            p.lineTo((x2 - len * Math.cos(ang + Math.PI / 7)).toFloat(), (y2 - len * Math.sin(ang + Math.PI / 7)).toFloat())
            p.close()
            canvas.drawPath(p, head)
        }
    }

    companion object {
        @Volatile var running = false
        fun start(ctx: Context, elo: Int, side: String) {
            try { ctx.startService(Intent(ctx, ChessCoachService::class.java).putExtra("elo", elo).putExtra("side", side)) } catch (e: Exception) {}
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, ChessCoachService::class.java)) } catch (e: Exception) {} }
    }
}
