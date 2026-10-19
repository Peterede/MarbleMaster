package com.marblemadness.swing.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val MARBLE_COLORS = intArrayOf(
    0xFFEF5350.toInt(), // red
    0xFF42A5F5.toInt(), // blue
    0xFFFFCA28.toInt(), // yellow
    0xFF66BB6A.toInt(), // green
    0xFFAB47BC.toInt(), // purple
    0xFFFF8A65.toInt()  // orange
)
private val MARBLE_DARK = intArrayOf(
    0xFFB71C1C.toInt(), 0xFF0D47A1.toInt(), 0xFFF57F17.toInt(),
    0xFF1B5E20.toInt(), 0xFF4A148C.toInt(), 0xFFBF360C.toInt()
)

class GameView(context: Context) : View(context) {

    private val engine = GameEngine()
    private val prefs = context.getSharedPreferences("swing", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var cell = 40f
    private var gridX = 0f
    private var gridY = 0f
    private var hudH = 0f
    private var beamY = 0f

    private var lastFrame = 0L
    private var running = false
    private var pressCol = -1
    private var pressStartX = 0f
    private var pressStartY = 0f

    private val flying = ArrayList<FlyingMarble>()
    private val stars = ArrayList<BonusStar>()
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private var gameOverFlash = 0f

    private val rnd = Random.Default

    private class FlyingMarble(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val color: Int, val weight: Int, var rot: Float, var life: Float
    )

    private class BonusStar(
        var x: Float, var y: Float,
        val sx: Float, val sy: Float,
        val tx: Float, val ty: Float,
        var t: Float, val value: Int
    )

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val color: Int, var life: Float, val maxLife: Float, val size: Float
    )

    private class Popup(val x: Float, val y: Float, val text: String, val color: Int, var life: Float, val maxLife: Float)

    // ------------------------------------------------------------------ paints

    private val bgPaint = Paint().apply { color = 0xFF10131A.toInt() }
    private val cellPaint = Paint().apply { color = 0xFF1A2130.toInt() }
    private val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF252E42.toInt(); strokeWidth = 1.5f * density; style = Paint.Style.STROKE
    }
    private val highlightPaint = Paint().apply { color = 0x224FC3F7 }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D6E63.toInt() }
    private val beamEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val pillarPaint = Paint().apply { color = 0xFF455A64.toInt() }
    private val marbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.LEFT
    }
    private val textDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90A4AE.toInt(); textAlign = Paint.Align.LEFT
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val starFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private val starEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8860B.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val dimPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 10f * density), 0f)
    }

    /** One solid-fill paint per marble colour; alpha is toggled per draw. */
    private val marbleFills = Array(MARBLE_COLORS.size) { i ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARBLE_COLORS[i] }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onActivated() {
        if (running) return
        running = true
        lastFrame = System.nanoTime()
        postOnAnimation(frameCallback)
    }

    fun onDeactivated() {
        running = false
        removeCallbacks(frameCallback)
        saveBest()
    }

    private val frameCallback: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            var dt = (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            if (dt > 0.1f) dt = 0.1f
            step(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = false
        onActivated()
    }

    override fun onDetachedFromWindow() {
        onDeactivated()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wF = w.toFloat()
        val hF = h.toFloat()
        hudH = (hF * 0.10f).coerceAtLeast(72f * density)
        val floorH = hF * 0.15f
        val marginX = 6f * density
        cell = min((wF - 2 * marginX) / Config.COLS, (hF - hudH - floorH) / Config.ROWS)
        gridX = (wF - cell * Config.COLS) / 2f
        gridY = hudH + 4f * density
        beamY = gridY + Config.ROWS * cell + cell * 0.45f
        textPaint.textSize = 22f * density
        textDimPaint.textSize = 13f * density
        centerTextPaint.textSize = 22f * density
        starEdge.strokeWidth = 1.5f * density
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressStartX = event.x
                pressStartY = event.y
                pressCol = colAt(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressCol >= 0) {
                    val dx = event.x - pressStartX
                    val dy = event.y - pressStartY
                    val slop = touchSlop.toFloat()
                    if (dx * dx + dy * dy > slop * slop) pressCol = colAt(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (engine.gameOver) {
                    restartGame()
                } else {
                    val col = colAt(event.x)
                    if (col >= 0) engine.drop(col)
                }
                pressCol = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressCol = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun colAt(x: Float): Int {
        val c = ((x - gridX) / cell).toInt()
        return if (c in 0 until Config.COLS) c else -1
    }

    private fun restartGame() {
        engine.restart()
        flying.clear()
        stars.clear()
        particles.clear()
        popups.clear()
        gameOverFlash = 0f
    }

    private fun saveBest() {
        if (engine.score > best) {
            best = engine.score
            prefs.edit().putInt("best", best).apply()
        }
    }

    // ------------------------------------------------------------------ update

    private fun step(dt: Float) {
        engine.update(dt)
        for (ev in engine.drainEvents()) handleEvent(ev)

        val gravity = 38f * cell
        val itF = flying.iterator()
        while (itF.hasNext()) {
            val f = itF.next()
            f.vy += gravity * dt
            f.x += f.vx * dt
            f.y += f.vy * dt
            f.rot += dt * 14f * if (f.vx >= 0) 1 else -1
            f.life -= dt
            if (f.life <= 0f || f.y > height + cell * 2 || f.x < -cell * 2 || f.x > width + cell * 2) {
                itF.remove()
            }
        }

        val itS = stars.iterator()
        while (itS.hasNext()) {
            val s = itS.next()
            s.t += dt * 1.8f
            val t = min(s.t, 1f)
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            s.x = s.sx + (s.tx - s.sx) * ease
            s.y = s.sy + (s.ty - s.sy) * ease
            if (s.t >= 1f) {
                engine.addScore(s.value)
                addPopup(s.tx, s.ty - 10f * density, "+${s.value} ★", 0xFFFFD54F.toInt())
                itS.remove()
            }
        }

        val itP = particles.iterator()
        while (itP.hasNext()) {
            val p = itP.next()
            p.life -= dt
            p.vy += 25f * cell * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.life <= 0f) itP.remove()
        }

        val itT = popups.iterator()
        while (itT.hasNext()) {
            val p = itT.next()
            p.life -= dt
            if (p.life <= 0f) itT.remove()
        }

        if (engine.gameOver) {
            gameOverFlash = min(gameOverFlash + dt, 1f)
            saveBest()
        }
    }

    private fun handleEvent(ev: GameEvent) {
        when (ev) {
            is GameEvent.Match -> {
                for (mc in ev.cells) {
                    val color = MARBLE_COLORS[colorIndex(mc.color)]
                    val (px, py) = cellCenter(mc.row.toFloat(), mc.col.toFloat())
                    repeat(5) {
                        particles.add(
                            Particle(
                                px, py,
                                (rnd.nextFloat() - 0.5f) * 4f * cell,
                                (rnd.nextFloat() - 0.8f) * 4f * cell,
                                color,
                                life = 0.5f + rnd.nextFloat() * 0.3f,
                                maxLife = 0.8f,
                                size = cell * (0.06f + rnd.nextFloat() * 0.08f)
                            )
                        )
                    }
                }
                ev.cells.firstOrNull()?.let { mc ->
                    val (px, py) = cellCenter(mc.row.toFloat(), mc.col.toFloat())
                    val label = if (ev.chain > 1) "+${ev.points} x${ev.chain}" else "+${ev.points}"
                    addPopup(px, py, label, MARBLE_COLORS[colorIndex(mc.color)])
                }
                haptic()
            }
            is GameEvent.Launched -> {
                val (px, py) = cellCenter(ev.row.toFloat(), ev.col.toFloat())
                flying.add(
                    FlyingMarble(
                        px, py, ev.vx * cell, ev.vy * cell,
                        ev.color, ev.weight, 0f, life = 4f
                    )
                )
                addPopup(px, py - cell, "BONUS +${ev.bonus}", 0xFFFFD54F.toInt())
                if (ev.spawnStar) {
                    stars.add(
                        BonusStar(px, py, px, py, 40f * density, hudH * 0.4f, 0f, Config.BONUS_STAR_VALUE)
                    )
                }
                haptic()
            }
            is GameEvent.LevelUp ->
                addPopup(width / 2f, height * 0.35f, "LEVEL ${ev.level}", 0xFF4FC3F7.toInt(), 1.4f)
            is GameEvent.GameOver -> saveBest()
            is GameEvent.Slid -> Unit
        }
    }

    private fun addPopup(x: Float, y: Float, text: String, color: Int, life: Float = 0.9f) {
        popups.add(Popup(x, y, text, color, life, life))
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun slopeOffsetPx(col: Int): Float {
        val ss = engine.seesaws.firstOrNull { it.contains(col) } ?: return 0f
        return ss.offsetFor(col) * cell
    }

    private fun cellCenter(row: Float, col: Float): Pair<Float, Float> {
        val x = gridX + (col + 0.5f) * cell
        val y = gridY + (row + 0.5f) * cell + slopeOffsetPx(col.toInt().coerceIn(0, Config.COLS - 1))
        return x to y
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawHud(canvas)
        drawGrid(canvas)
        drawSeesaws(canvas)
        drawMarbles(canvas)
        drawActive(canvas)
        drawFlying(canvas)
        drawParticles(canvas)
        drawStars(canvas)
        drawPopups(canvas)
        if (engine.gameOver) drawGameOver(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 12f * density
        textDimPaint.textSize = 12f * density
        canvas.drawText("SCORE", pad, pad + 12f * density, textDimPaint)
        textPaint.textSize = 22f * density
        canvas.drawText("${engine.score}", pad, pad + 36f * density, textPaint)
        textDimPaint.textSize = 12f * density
        canvas.drawText("BEST $best", pad, pad + 54f * density, textDimPaint)

        canvas.drawText("LEVEL ${engine.level}", width - pad, pad + 12f * density, textDimPaint)
        textDimPaint.textSize = 11f * density
        canvas.drawText("max wt ${engine.maxWeight()}", width - pad, pad + 28f * density, textDimPaint)

        // next queue, centred
        val cx = width / 2f
        centerTextPaint.textSize = 10f * density
        canvas.drawText("NEXT", cx, pad + 8f * density, centerTextPaint)
        val r = cell * 0.26f
        val spacing = r * 2.6f
        val startX = cx - spacing + r / 2f
        for ((i, q) in engine.queue.withIndex()) {
            val cy = pad + 14f * density + r
            val rr = if (i == 0) r else r * 0.75f
            val alpha = if (i == 0) 255 else 150
            drawMarble(canvas, startX + i * spacing, cy, rr, q.color, q.weight, alpha)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val rect = RectF()
        val rad = cell * 0.12f
        for (r in 0 until Config.ROWS) {
            for (c in 0 until Config.COLS) {
                val x = gridX + c * cell
                val y = gridY + r * cell
                rect.set(x + 1.5f, y + 1.5f, x + cell - 1.5f, y + cell - 1.5f)
                canvas.drawRoundRect(rect, rad, rad, cellPaint)
                canvas.drawRoundRect(rect, rad, rad, cellStroke)
            }
        }
        if (pressCol in 0 until Config.COLS && !engine.gameOver) {
            val x = gridX + pressCol * cell
            canvas.drawRect(x, gridY, x + cell, gridY + Config.ROWS * cell, highlightPaint)
            val stackTop = topOfColumn(pressCol)
            canvas.drawLine(
                x + cell / 2f, gridY,
                x + cell / 2f,
                gridY + (stackTop - 0.1f) * cell + slopeOffsetPx(pressCol),
                guidePaint
            )
        }
    }

    private fun topOfColumn(col: Int): Float {
        for (r in 0 until Config.ROWS) if (engine.grid[r][col] != null) return r.toFloat()
        return Config.ROWS.toFloat()
    }

    private fun drawSeesaws(canvas: Canvas) {
        val floorY = gridY + Config.ROWS * cell + cell * 0.95f
        val pillarX = gridX + Config.PILLAR_COL * cell + cell * 0.35f
        canvas.drawRect(pillarX, gridY + Config.ROWS * cell, pillarX + cell * 0.3f, floorY, pillarPaint)

        for (ss in engine.seesaws) {
            val first = ss.columns.first()
            val last = ss.columns.last()
            val pivotX = gridX + (ss.pivotCol + 0.5f) * cell

            canvas.save()
            canvas.rotate(ss.degAngle(), pivotX, beamY)
            val left = gridX + first * cell - cell * 0.08f
            val right = gridX + (last + 1) * cell + cell * 0.08f
            val beamH = cell * 0.14f
            val rect = RectF(left, beamY - beamH / 2f, right, beamY + beamH / 2f)
            canvas.drawRoundRect(rect, beamH / 2f, beamH / 2f, beamPaint)
            canvas.drawRoundRect(rect, beamH / 2f, beamH / 2f, beamEdgePaint)
            canvas.restore()

            val tri = Path()
            val triW = cell * 0.5f
            val triH = cell * 0.5f
            tri.moveTo(pivotX - triW / 2f, beamY + beamH / 2f)
            tri.lineTo(pivotX + triW / 2f, beamY + beamH / 2f)
            tri.lineTo(pivotX, beamY + beamH / 2f + triH)
            tri.close()
            canvas.drawPath(tri, pillarPaint)
        }
    }

    private fun drawMarbles(canvas: Canvas) {
        for (r in 0 until Config.ROWS) {
            for (c in 0 until Config.COLS) {
                val m = engine.grid[r][c] ?: continue
                val x = gridX + (m.visualCol + 0.5f) * cell
                val y = gridY + (m.visualRow + 0.5f) * cell + slopeOffsetPx(c)
                drawMarble(canvas, x, y, cell * 0.42f, m.color, m.weight, 255)
            }
        }
    }

    private fun drawActive(canvas: Canvas) {
        val m = engine.active ?: return
        if (m.visualRow <= -1f) return
        val x = gridX + (m.visualCol + 0.5f) * cell
        val y = gridY + (m.visualRow + 0.5f) * cell + slopeOffsetPx(m.col)
        drawMarble(canvas, x, y, cell * 0.42f, m.color, m.weight, 255)
    }

    private fun colorIndex(i: Int): Int = ((i % MARBLE_COLORS.size) + MARBLE_COLORS.size) % MARBLE_COLORS.size

    private fun drawMarble(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        colorIdx: Int, weight: Int, alpha: Int
    ) {
        val idx = colorIndex(colorIdx)
        val fill = marbleFills[idx]
        val oldAlpha = fill.alpha
        val oldStyle = marbleStroke.style
        fill.alpha = alpha
        marbleStroke.color = MARBLE_DARK[idx]
        marbleStroke.alpha = alpha
        marbleStroke.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, marbleStroke)
        glossPaint.alpha = (alpha * 0.65f).toInt()
        canvas.drawCircle(cx - radius * 0.32f, cy - radius * 0.36f, radius * 0.22f, glossPaint)
        numberPaint.textSize = radius * 0.95f
        numberPaint.alpha = alpha
        val textY = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText("$weight", cx, textY, numberPaint)
        fill.alpha = oldAlpha
        marbleStroke.alpha = 255
        marbleStroke.style = oldStyle
        numberPaint.alpha = 255
    }

    private fun drawFlying(canvas: Canvas) {
        for (f in flying) {
            canvas.save()
            canvas.rotate(f.rot, f.x, f.y)
            drawMarble(canvas, f.x, f.y, cell * 0.42f, f.color, f.weight, 255)
            canvas.restore()
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            starFill.alpha = (255f * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            starFill.color = p.color
            canvas.drawCircle(p.x, p.y, p.size, starFill)
        }
        starFill.alpha = 255
        starFill.color = 0xFFFFD54F.toInt()
    }

    private fun drawStars(canvas: Canvas) {
        for (s in stars) {
            val wobble = 1f + 0.15f * sin(s.t * 18f)
            drawStar(canvas, s.x, s.y, cell * 0.28f * wobble)
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path()
        for (i in 0 until 10) {
            val ang = (Math.PI / 5.0) * i - Math.PI / 2
            val rad = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (rad * cos(ang)).toFloat()
            val y = cy + (rad * sin(ang)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, starFill)
        canvas.drawPath(path, starEdge)
    }

    private fun drawPopups(canvas: Canvas) {
        for (p in popups) {
            val a = (255f * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            centerTextPaint.textSize = 18f * density
            centerTextPaint.color = p.color
            centerTextPaint.alpha = a
            canvas.drawText(p.text, p.x, p.y - (1f - p.life.coerceAtMost(1f)) * 30f * density, centerTextPaint)
        }
        centerTextPaint.alpha = 255
        centerTextPaint.color = Color.WHITE
    }

    private fun drawGameOver(canvas: Canvas) {
        val a = (gameOverFlash * 170).toInt().coerceIn(0, 170)
        dimPaint.alpha = a
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (gameOverFlash > 0.5f) {
            centerTextPaint.textSize = 40f * density
            centerTextPaint.color = Color.WHITE
            canvas.drawText("GAME OVER", width / 2f, height * 0.42f, centerTextPaint)
            centerTextPaint.textSize = 22f * density
            canvas.drawText("Score ${engine.score}", width / 2f, height * 0.50f, centerTextPaint)
            centerTextPaint.textSize = 16f * density
            canvas.drawText("Best $best", width / 2f, height * 0.55f, centerTextPaint)
            centerTextPaint.textSize = 15f * density
            centerTextPaint.color = 0xFF90CAF9.toInt()
            canvas.drawText("Tap to play again", width / 2f, height * 0.63f, centerTextPaint)
            centerTextPaint.color = Color.WHITE
        }
    }
}
