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
    @Volatile private var side = 'w'
    @Volatile private var lastFen = ""
    @Volatile private var alive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        elo = intent?.getIntExtra("elo", 1500) ?: 1500
        side = (intent?.getStringExtra("side") ?: "w").firstOrNull() ?: 'w'
        if (arrow == null) { wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager; addOverlays(); loop() }
        return START_STICKY
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addOverlays() {
        // 1) Full-screen, NON-touchable arrow layer — the user can still play underneath.
        arrow = ArrowView(this)
        val ap = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(arrow, ap) } catch (e: Exception) {}

        // 2) Bottom control bar — touchable: move text, Elo slider, side toggle, stop.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60E1116"))
            setPadding(28, 18, 28, 28)
        }
        moveLabel = TextView(this).apply { text = "Chess Coach — reading board…"; setTextColor(Color.WHITE); textSize = 16f }
        root.addView(moveLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 0) }
        eloLabel = TextView(this).apply { text = "Elo $elo"; setTextColor(Color.parseColor("#00FF88")); textSize = 13f }
        row.addView(eloLabel)
        val seek = SeekBar(this).apply {
            max = 3100; progress = (elo - 500).coerceIn(0, 3100)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(24, 0, 24, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { elo = 500 + p; eloLabel?.text = "Elo $elo"; lastFen = "" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(seek)
        val sideBtn = Button(this).apply { text = if (side == 'w') "W" else "B"; setOnClickListener { side = if (side == 'w') 'b' else 'w'; text = if (side == 'w') "W" else "B"; lastFen = "" } }
        row.addView(sideBtn)
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stopSelf() } }
        row.addView(stop)
        root.addView(row)
        bar = root
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        bp.gravity = Gravity.BOTTOM
        try { wm.addView(bar, bp) } catch (e: Exception) {}
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
        alive = false; scope.coroutineContext[Job]?.cancel()
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
        fun start(ctx: Context, elo: Int, side: String) {
            try { ctx.startService(Intent(ctx, ChessCoachService::class.java).putExtra("elo", elo).putExtra("side", side)) } catch (e: Exception) {}
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, ChessCoachService::class.java)) } catch (e: Exception) {} }
    }
}
