package com.edens.marblemaster.game

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    private val audio = GameAudio(context)
    private val prefs = context.getSharedPreferences("swing", Context.MODE_PRIVATE)
    private val highScores = HighScoreTable().apply {
        load(prefs.getString(KEY_HIGH_SCORES, null))
        // carry over the single best score saved by earlier versions
        if (scores.isEmpty()) {
            val legacyBest = prefs.getInt(KEY_LEGACY_BEST, 0)
            if (legacyBest > 0) record(legacyBest, 0L)
        }
    }
    private val best: Int get() = highScores.best()

    private val progress = PlayerProgress().apply { load(prefs.getString(KEY_PROGRESS, null)) }
    private val daily = DailyProgress().apply { load(prefs.getString(KEY_DAILY, null)) }
    private val history = RunHistory().apply { load(prefs.getString(KEY_HISTORY, null)) }

    /** True while the current run is the day's seeded board rather than an endless run. */
    private var dailyRun = false

    private var musicEnabled = prefs.getBoolean(KEY_MUSIC, true)
    private var effectsEnabled = prefs.getBoolean(KEY_EFFECTS, true)
    private var hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true)

    init {
        audio.setEffectsEnabled(effectsEnabled)
        // leaving the music enabled here would start the loop before the activity is visible
        if (!musicEnabled) audio.setMusicEnabled(false)
    }

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
    private var pressButton = -1
    private var pressGoal = -1
    private var tutorialPage = 0
    private var tutorialSeen = prefs.getBoolean(KEY_TUTORIAL, false)
    private var tutorialReturn = Modal.NONE
    private var scoreTab = 0
    private var pressStartX = 0f
    private var pressStartY = 0f
    private val buttonRects = Array(Button.values().size) { RectF() }
    private var modal = Modal.NONE
    private var scoreRecorded = false
    private var lastRank = 0

    private val stars = ArrayList<BonusStar>()
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private var gameOverFlash = 0f
    private var pulse = 0f

    private val rnd = Random.Default

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
    private val dangerPaint = Paint().apply { color = 0xFFFF7043.toInt() }
    private val lockedFill = Paint().apply { color = 0xFF0C1018.toInt(); alpha = 225 }
    private val lockedHatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39465C.toInt(); strokeWidth = 1.2f * density
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D6E63.toInt() }
    private val beamEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val pillarPaint = Paint().apply { color = 0xFF455A64.toInt() }
    private val marbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val bombFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt() }
    private val fuseGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val heavyRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD54F.toInt(); strokeWidth = 3f * density
    }
    private val wildCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2631.toInt() }
    private val wildRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFECEFF1.toInt(); strokeWidth = 2f * density
    }
    private val marbleArcRect = RectF()
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
    private val timerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val timerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val scoresFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val scoresStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2333.toInt() }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A4A66.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val panelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val dailyKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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
        if (!engine.gameOver) audio.startMusic()
        if (running) return
        running = true
        lastFrame = System.nanoTime()
        postOnAnimation(frameCallback)
    }

    fun onDeactivated() {
        running = false
        removeCallbacks(frameCallback)
        audio.pause()
        saveProgress()
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
        maybeShowTutorial()
        onActivated()
    }

    /** The walkthrough is shown once per install, until it is dismissed. */
    private fun maybeShowTutorial() {
        if (tutorialSeen) return
        tutorialPage = 0
        tutorialReturn = Modal.NONE
        modal = Modal.TUTORIAL
    }

    private fun openTutorial(from: Modal) {
        tutorialPage = 0
        tutorialReturn = from
        modal = Modal.TUTORIAL
    }

    private fun dismissTutorial() {
        tutorialSeen = true
        prefs.edit().putBoolean(KEY_TUTORIAL, true).apply()
        modal = tutorialReturn
        tutorialReturn = Modal.NONE
    }

    override fun onDetachedFromWindow() {
        onDeactivated()
        audio.release()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wF = w.toFloat()
        val hF = h.toFloat()
        // the extra band under the score keeps room for the per-run objectives
        hudH = (hF * 0.10f).coerceAtLeast(96f * density)
        val floorH = hF * 0.15f
        val marginX = 6f * density
        cell = min((wF - 2 * marginX) / Config.COLS, (hF - hudH - floorH) / Config.ROWS)
        gridX = (wF - cell * Config.COLS) / 2f
        gridY = hudH + 4f * density
        beamY = gridY + Config.ROWS * cell + cell * 0.45f
        val buttonGap = 6f * density
        val rowWidth = min(wF * 0.94f, 360f * density)
        val buttonWidth = (rowWidth - buttonGap * (buttonRects.size - 1)) / buttonRects.size
        val buttonHeight = 38f * density
        val buttonBottom = hF - 10f * density
        val rowLeft = (wF - rowWidth) / 2f
        for ((i, rect) in buttonRects.withIndex()) {
            val left = rowLeft + i * (buttonWidth + buttonGap)
            rect.set(left, buttonBottom - buttonHeight, left + buttonWidth, buttonBottom)
        }
        textPaint.textSize = 22f * density
        textDimPaint.textSize = 13f * density
        centerTextPaint.textSize = 22f * density
        starEdge.strokeWidth = 1.5f * density
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (modal != Modal.NONE) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                when (modal) {
                    Modal.SETTINGS -> {
                        val setting = settingAt(event.x, event.y)
                        if (setting != null) toggleSetting(setting) else modal = Modal.NONE
                    }
                    Modal.DAILY -> {
                        val (_, play) = dailyLayout()
                        if (play.contains(event.x, event.y)) startDailyRun() else modal = Modal.NONE
                    }
                    Modal.STATS -> {
                        if (statsHelpRect().contains(event.x, event.y)) {
                            openTutorial(Modal.STATS)
                        } else {
                            modal = Modal.NONE
                        }
                    }
                    Modal.SCORES -> {
                        val tab = scoreTabAt(event.x, event.y)
                        if (tab >= 0) scoreTab = tab else modal = Modal.NONE
                    }
                    Modal.TUTORIAL -> {
                        val (_, button) = tutorialLayout()
                        if (button.contains(event.x, event.y)) {
                            if (tutorialPage < tutorialPages.lastIndex) {
                                tutorialPage++
                            } else {
                                dismissTutorial()
                            }
                        } else {
                            dismissTutorial()
                        }
                    }
                    else -> modal = Modal.NONE
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressStartX = event.x
                pressStartY = event.y
                pressButton = buttonAt(event.x, event.y)
                pressGoal = if (pressButton >= 0) -1 else goalAt(event.x, event.y)
                pressCol = if (pressButton >= 0 || pressGoal >= 0) -1 else colAt(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressCol >= 0 || pressButton >= 0 || pressGoal >= 0) {
                    val dx = event.x - pressStartX
                    val dy = event.y - pressStartY
                    val slop = touchSlop.toFloat()
                    if (dx * dx + dy * dy > slop * slop) {
                        pressButton = -1
                        pressGoal = -1
                        pressCol = colAt(event.x)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (engine.gameOver) {
                    restartGame()
                } else if (pressButton >= 0 && buttonAt(event.x, event.y) == pressButton) {
                    modal = when (Button.values()[pressButton]) {
                        Button.DAILY -> Modal.DAILY
                        Button.SCORES -> Modal.SCORES
                        Button.STATS -> Modal.STATS
                        Button.SETTINGS -> Modal.SETTINGS
                    }
                } else if (pressGoal >= 0 && goalAt(event.x, event.y) == pressGoal) {
                    explainGoal(pressGoal)
                } else if (pressButton < 0 && pressGoal < 0) {
                    val col = colAt(event.x)
                    if (col >= 0) {
                        if (engine.drop(col)) {
                            audio.play(SoundEffect.DROP)
                        } else if (engine.isClosed(col)) {
                            val cx = gridX + (col + 0.5f) * cell
                            addPopup(cx, gridY + cell * 2.2f, "LOCKED", 0xFFE53935.toInt(), 0.7f)
                        }
                    }
                }
                pressCol = -1
                pressButton = -1
                pressGoal = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressCol = -1
                pressButton = -1
                pressGoal = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun buttonAt(x: Float, y: Float): Int {
        if (engine.gameOver) return -1
        for ((i, rect) in buttonRects.withIndex()) if (rect.contains(x, y)) return i
        return -1
    }

    /** Index of the goal chip under (x, y) in the HUD strip, or -1 when the touch misses it. */
    private fun goalAt(x: Float, y: Float): Int {
        if (engine.gameOver || engine.objectives.isEmpty()) return -1
        val boardW = Config.COLS * cell
        if (x < gridX || x > gridX + boardW) return -1
        if (y < hudH - 28f * density || y > hudH) return -1
        val index = ((x - gridX) / (boardW / engine.objectives.size)).toInt()
        return if (index in engine.objectives.indices) index else -1
    }

    /** Spells out a goal chip the player tapped. */
    private fun explainGoal(index: Int) {
        val goal = engine.objectives.getOrNull(index) ?: return
        addPopup(width / 2f, height * 0.30f, goal.kind.title, 0xFF90CAF9.toInt(), 1.8f)
        addPopup(
            width / 2f, height * 0.30f + 24f * density,
            if (goal.done) "DONE · +${Config.OBJECTIVE_REWARD}" else "${goal.progress} / ${goal.target}",
            0xFFFFD54F.toInt(), 1.8f
        )
    }

    private fun settingAt(x: Float, y: Float): Setting? {
        val (_, rows) = settingsLayout()
        for ((rect, setting) in rows) if (rect.contains(x, y)) return setting
        return null
    }

    private fun colAt(x: Float): Int {
        val c = ((x - gridX) / cell).toInt()
        return if (c in 0 until Config.COLS) c else -1
    }

    private fun restartGame() {
        dailyRun = false
        engine.restart()
        startRun()
    }

    /** Starts the day's seeded board; it is scored separately from the endless ladder. */
    private fun startDailyRun() {
        dailyRun = true
        engine.restart(DailyProgress.seedFor(todayKey()))
        modal = Modal.NONE
        startRun()
    }

    private fun startRun() {
        audio.startMusic()
        stars.clear()
        particles.clear()
        popups.clear()
        gameOverFlash = 0f
        scoreRecorded = false
        lastRank = 0
    }

    private fun todayKey(): String = dailyKeyFormat.format(Date())

    private fun yesterdayKey(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return dailyKeyFormat.format(calendar.time)
    }

    /** Keeps the last runs so the scores panel can show progress over time. */
    private fun recordHistory() {
        history.add(engine.score, engine.level, System.currentTimeMillis())
        prefs.edit().putString(KEY_HISTORY, history.serialize()).apply()
    }

    /** Files the daily result (and the day streak) once the daily run ends. */
    private fun recordDaily() {
        if (!dailyRun) return
        daily.record(todayKey(), yesterdayKey(), engine.score)
        prefs.edit().putString(KEY_DAILY, daily.serialize()).apply()
    }

    /** Files the finished run in the local table exactly once per game. */
    private fun recordScore() {
        if (scoreRecorded) return
        scoreRecorded = true
        // a seeded daily board has its own best; the endless ladder stays a fair contest
        if (dailyRun) return
        lastRank = highScores.record(engine.score, System.currentTimeMillis())
        prefs.edit()
            .putString(KEY_HIGH_SCORES, highScores.serialize())
            .remove(KEY_LEGACY_BEST)
            .apply()
    }

    private fun saveProgress() {
        prefs.edit().putString(KEY_PROGRESS, progress.serialize()).apply()
    }

    /** Celebrates achievements that just became true and keeps the new totals on disk. */
    private fun unlock(fresh: List<Achievement>) {
        if (fresh.isEmpty()) return
        fresh.forEachIndexed { i, achievement ->
            addPopup(
                width / 2f,
                height * 0.30f + i * 26f * density,
                "ACHIEVEMENT · ${achievement.title}",
                0xFFFFD54F.toInt(),
                1.8f
            )
        }
        audio.play(SoundEffect.LEVEL_UP)
        haptic()
        saveProgress()
    }

    // ------------------------------------------------------------------ update

    private fun step(dt: Float) {
        if (modal != Modal.NONE) return // panels are modal; freeze the run behind them
        pulse = (pulse + dt * 4f) % 6.2831855f
        engine.update(dt)
        for (ev in engine.drainEvents()) handleEvent(ev)
        progress.recordScore(engine.score)
        unlock(progress.check())

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
            recordScore()
        }
    }

    private fun handleEvent(ev: GameEvent) {
        when (ev) {
            is GameEvent.Match -> {
                for (mc in ev.cells) spawnClearParticles(mc)
                progress.recordClear(ev.cells.size)
                unlock(progress.check())
                ev.cells.firstOrNull()?.let { mc ->
                    val (px, py) = cellCenter(mc.row.toFloat(), mc.col.toFloat())
                    val label = if (ev.chain > 1) "+${ev.points} x${ev.chain}" else "+${ev.points}"
                    addPopup(px, py, label, marbleColorOf(mc.color, mc.power))
                    if (ev.streak >= 2) {
                        addPopup(px, py - cell * 0.6f, "STREAK x${ev.streak}", 0xFFFFD54F.toInt(), 0.8f)
                    }
                }
                audio.play(if (ev.chain > 1) SoundEffect.MATCH_CHAIN else SoundEffect.MATCH)
                haptic()
            }
            is GameEvent.Blast -> {
                for (mc in ev.cells) spawnClearParticles(mc)
                progress.recordClear(ev.cells.size)
                unlock(progress.check())
                val (px, py) = cellCenter(ev.row.toFloat(), ev.col.toFloat())
                addPopup(px, py - cell * 0.6f, "BOOM +${ev.points}", 0xFFFF7043.toInt(), 1.1f)
                audio.play(SoundEffect.BLAST)
                haptic()
            }
            is GameEvent.Launched -> {
                progress.recordToss()
                unlock(progress.check())
                val (px, py) = cellCenter(ev.row.toFloat(), ev.col.toFloat())
                addPopup(px, py - cell, "BONUS +${ev.bonus}", 0xFFFFD54F.toInt())
                if (ev.spawnStar) {
                    stars.add(
                        BonusStar(px, py, px, py, 40f * density, hudH * 0.4f, 0f, Config.BONUS_STAR_VALUE)
                    )
                }
                audio.play(SoundEffect.LAUNCH)
                haptic()
            }
            is GameEvent.Landed -> {
                val (px, py) = cellCenter(ev.row.toFloat(), ev.col.toFloat())
                val label = when (ev.power) {
                    PowerKind.BOMB -> "BOMB!"
                    PowerKind.WILD -> "WILD!"
                    PowerKind.HEAVY -> "HEAVY!"
                    PowerKind.NONE -> "SPECIAL"
                }
                addPopup(px, py - cell * 0.6f, label, marbleColorOf(ev.color, ev.power), 0.7f)
                audio.play(SoundEffect.LAND)
            }
            is GameEvent.LevelUp -> {
                progress.recordLevel(ev.level)
                unlock(progress.check())
                addPopup(width / 2f, height * 0.35f, "LEVEL ${ev.level}", 0xFF4FC3F7.toInt(), 1.4f)
                audio.play(SoundEffect.LEVEL_UP)
            }
            is GameEvent.GameOver -> {
                audio.play(SoundEffect.GAME_OVER)
                progress.recordRun(engine.score, engine.level)
                unlock(progress.check())
                saveProgress()
                recordScore()
                recordDaily()
                recordHistory()
            }
            is GameEvent.Spill -> {
                progress.recordSpill()
                val (px, py) = cellCenter(ev.row.toFloat(), ev.toCol.toFloat())
                val label = if (ev.penalty > 0) "SPILL -${ev.penalty}" else "SPILL!"
                addPopup(px, py - cell * 0.6f, label, 0xFFFFB74D.toInt())
                addPopup(px, py - cell, "NO RESERVE LEFT", 0xFFE53935.toInt(), 0.8f)
                audio.play(SoundEffect.LAND)
                haptic()
            }
            is GameEvent.ObjectiveDone -> {
                addPopup(
                    width / 2f, height * 0.34f,
                    "GOAL · ${ev.objective.kind.title}", 0xFF66BB6A.toInt(), 1.6f
                )
                addPopup(
                    width / 2f, height * 0.34f + 24f * density,
                    "+${ev.reward} PTS", 0xFFFFD54F.toInt(), 1.6f
                )
                audio.play(SoundEffect.LEVEL_UP)
                haptic()
            }
            is GameEvent.ColumnClosed -> {
                val cx = gridX + (ev.col + 0.5f) * cell
                addPopup(cx, gridY + cell * 2.2f, "COLUMN LOCKED", 0xFFE53935.toInt(), 1.4f)
                haptic()
            }
            is GameEvent.Slid -> Unit
        }
    }

    private fun spawnClearParticles(mc: MatchCell) {
        val color = marbleColorOf(mc.color, mc.power)
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

    private fun marbleColorOf(color: Int, power: PowerKind): Int = when (power) {
        PowerKind.WILD -> 0xFFE1F5FE.toInt()
        PowerKind.BOMB -> 0xFFFF7043.toInt()
        PowerKind.HEAVY -> 0xFFFFD54F.toInt()
        PowerKind.NONE -> MARBLE_COLORS[colorIndex(color)]
    }

    private fun addPopup(x: Float, y: Float, text: String, color: Int, life: Float = 0.9f) {
        popups.add(Popup(x, y, text, color, life, life))
    }

    private fun haptic() {
        if (!hapticsEnabled) return
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
        drawObjectives(canvas)
        drawDropTimer(canvas)
        drawButtons(canvas)
        drawGrid(canvas)
        drawSeesaws(canvas)
        drawMarbles(canvas)
        drawActive(canvas)
        drawFlying(canvas)
        drawParticles(canvas)
        drawStars(canvas)
        drawPopups(canvas)
        if (engine.gameOver) drawGameOver(canvas)
        when (modal) {
            Modal.DAILY -> drawDaily(canvas)
            Modal.TUTORIAL -> drawTutorial(canvas)
            Modal.SCORES -> drawHighScores(canvas)
            Modal.STATS -> drawStats(canvas)
            Modal.SETTINGS -> drawSettings(canvas)
            Modal.NONE -> Unit
        }
    }

    /** Thin bar above the board: when it drains the marble drops itself into a random column. */
    private fun drawDropTimer(canvas: Canvas) {
        if (engine.gameOver || engine.active != null) return
        val fraction = (engine.timeLeft / Config.dropTimerSeconds(engine.level)).coerceIn(0f, 1f)
        val h = 4f * density
        val top = gridY - h - 2f * density
        val left = gridX
        val barWidth = Config.COLS * cell
        val radius = h / 2f
        timerTrackPaint.alpha = 40
        canvas.drawRoundRect(left, top, left + barWidth, top + h, radius, radius, timerTrackPaint)
        if (fraction <= 0f) return
        timerFillPaint.color = if (fraction <= 0.3f) 0xFFFF7043.toInt() else 0xFF4FC3F7.toInt()
        timerFillPaint.alpha = 220
        canvas.drawRoundRect(left, top, left + barWidth * fraction, top + h, radius, radius, timerFillPaint)
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 12f * density
        textDimPaint.textSize = 12f * density
        canvas.drawText("SCORE", pad, pad + 12f * density, textDimPaint)
        textPaint.textSize = 22f * density
        canvas.drawText("${engine.score}", pad, pad + 36f * density, textPaint)
        textDimPaint.textSize = 12f * density
        canvas.drawText("BEST $best", pad, pad + 54f * density, textDimPaint)

        // the right-hand column is right-aligned, otherwise it runs off the screen edge
        textDimPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            if (dailyRun) "LEVEL ${engine.level} · DAILY" else "LEVEL ${engine.level}",
            width - pad, pad + 12f * density, textDimPaint
        )
        textDimPaint.textSize = 11f * density
        canvas.drawText("max wt ${engine.maxWeight()}", width - pad, pad + 28f * density, textDimPaint)
        textDimPaint.textAlign = Paint.Align.LEFT

        // the running streak and how long it has left to be extended
        if (engine.streak >= 2) {
            textDimPaint.textAlign = Paint.Align.RIGHT
            textDimPaint.color = 0xFFFFD54F.toInt()
            textDimPaint.textSize = 11f * density
            canvas.drawText("STREAK x${engine.streak}", width - pad, pad + 46f * density, textDimPaint)
            val barW = 54f * density
            val barH = 2f * density
            val frac = (engine.streakTimeLeft / Config.STREAK_WINDOW).coerceIn(0f, 1f)
            timerFillPaint.color = 0xFFFFD54F.toInt()
            timerFillPaint.alpha = 220
            canvas.drawRect(
                width - pad - barW, pad + 50f * density,
                width - pad - barW + barW * frac, pad + 50f * density + barH,
                timerFillPaint
            )
            timerFillPaint.color = 0xFF4FC3F7.toInt()
            textDimPaint.textAlign = Paint.Align.LEFT
            textDimPaint.color = 0xFF90A4AE.toInt()
        }

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
            drawMarble(canvas, startX + i * spacing, cy, rr, q.color, q.weight, alpha, q.power)
        }
    }

    /**
     * The run's three goals, one column each, with a progress bar that fills as the counter
     * climbs. A met goal turns gold so it reads at a glance without taking board space.
     */
    private fun drawObjectives(canvas: Canvas) {
        if (engine.gameOver || engine.objectives.isEmpty()) return
        val boardW = Config.COLS * cell
        val colW = boardW / engine.objectives.size
        val labelY = hudH - 12f * density
        val barTop = hudH - 8f * density
        val barH = 2f * density
        val barW = colW - 12f * density
        textDimPaint.textAlign = Paint.Align.LEFT
        for ((i, objective) in engine.objectives.withIndex()) {
            val x = gridX + i * colW
            val done = objective.done
            textDimPaint.textSize = 9f * density
            textDimPaint.color = if (done) 0xFFFFD54F.toInt() else 0xFF90A4AE.toInt()
            canvas.drawText(objective.kind.label, x, labelY, textDimPaint)
            textDimPaint.color = if (done) 0xFFFFD54F.toInt() else Color.WHITE
            canvas.drawText(
                if (done) "✓" else "${objective.progress}/${objective.target}",
                x + textDimPaint.measureText(objective.kind.label + "  "), labelY, textDimPaint
            )
            timerTrackPaint.alpha = 40
            canvas.drawRect(x, barTop, x + barW, barTop + barH, timerTrackPaint)
            val frac = objective.progress.toFloat() / objective.target
            timerFillPaint.color = if (done) 0xFFFFD54F.toInt() else 0xFF4FC3F7.toInt()
            timerFillPaint.alpha = 230
            canvas.drawRect(x, barTop, x + barW * frac.coerceIn(0f, 1f), barTop + barH, timerFillPaint)
        }
        timerFillPaint.color = 0xFF4FC3F7.toInt()
        textDimPaint.color = 0xFF90A4AE.toInt()
    }

    /** Bottom row of pills: high scores, lifetime stats and settings. */
    private fun drawButtons(canvas: Canvas) {
        if (engine.gameOver) return
        centerTextPaint.textSize = 10f * density
        for ((i, rect) in buttonRects.withIndex()) {
            if (rect.isEmpty) continue
            scoresFill.alpha = if (pressButton == i) 64 else 28
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, scoresFill)
            scoresStroke.alpha = 110
            canvas.drawRoundRect(rect, radius, radius, scoresStroke)
            centerTextPaint.color = Color.WHITE
            centerTextPaint.alpha = 235
            val label = when (Button.values()[i]) {
                Button.DAILY -> "DAILY"
                Button.SCORES -> "SCORES"
                Button.STATS -> "STATS"
                Button.SETTINGS -> "SETTINGS"
            }
            val baseline = rect.centerY() - (centerTextPaint.descent() + centerTextPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, centerTextPaint)
        }
        centerTextPaint.alpha = 255
        centerTextPaint.color = Color.WHITE
    }

    /** Lifetime totals plus the achievement grid. */
    private fun drawStats(canvas: Canvas) {
        if (modal != Modal.STATS) return
        dimPaint.alpha = 0xE6
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val pad = 18f * density
        val rowH = 26f * density
        val panel = statsPanel()
        val radius = 16f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        panelTextPaint.color = Color.WHITE
        panelTextPaint.isFakeBoldText = true
        panelTextPaint.textSize = 20f * density
        canvas.drawText("PLAYER STATS", panel.centerX(), panel.top + pad + 14f * density, panelTextPaint)
        panelTextPaint.isFakeBoldText = false
        panelTextPaint.textSize = 10f * density
        panelTextPaint.color = 0xFF90A4AE.toInt()
        canvas.drawText(
            "LIFETIME ON THIS DEVICE",
            panel.centerX(), panel.top + pad + 32f * density, panelTextPaint
        )

        val leftX = panel.left + 22f * density
        val rightX = panel.centerX() + 6f * density
        val colW = panel.centerX() - panel.left - 22f * density

        fun stat(x: Float, y: Float, label: String, value: Int) {
            textDimPaint.textSize = 9f * density
            textDimPaint.color = 0xFF90A4AE.toInt()
            textDimPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, x, y, textDimPaint)
            datePaint.textSize = 15f * density
            datePaint.color = Color.WHITE
            canvas.drawText("$value", x + colW, y, datePaint)
        }

        var rowY = panel.top + 74f * density
        stat(leftX, rowY, "BEST LEVEL", progress.bestLevel)
        stat(rightX, rowY, "BEST SCORE", progress.bestScore)
        rowY += rowH
        stat(leftX, rowY, "BIGGEST CLEAR", progress.biggestClear)
        stat(rightX, rowY, "MARBLES CLEARED", progress.totalCleared)
        rowY += rowH
        stat(leftX, rowY, "RUNS PLAYED", progress.runsPlayed)
        stat(rightX, rowY, "MARBLES TOSSED", progress.totalTosses)
        rowY += rowH
        stat(leftX, rowY, "SPILLS", progress.totalSpills)

        val achTop = rowY + 20f * density
        panelTextPaint.textSize = 11f * density
        panelTextPaint.color = 0xFF4FC3F7.toInt()
        canvas.drawText(
            "ACHIEVEMENTS ${progress.unlockedCount}/${Achievement.values().size}",
            panel.centerX(), achTop, panelTextPaint
        )

        val achRowH = 22f * density
        for ((i, achievement) in Achievement.values().withIndex()) {
            val y = achTop + 20f * density + i * achRowH
            val got = progress.isUnlocked(achievement)
            rankPaint.textSize = 12f * density
            rankPaint.color = if (got) 0xFFFFD54F.toInt() else 0xFF5C6B7A.toInt()
            canvas.drawText(achievement.title, leftX, y, rankPaint)
            datePaint.textSize = 9f * density
            datePaint.color = if (got) 0xFFFFD54F.toInt() else 0xFF44515E.toInt()
            canvas.drawText(if (got) "UNLOCKED" else "LOCKED", rightX + colW, y, datePaint)
        }

        panelTextPaint.textSize = 10f * density
        panelTextPaint.color = 0xFF4FC3F7.toInt()
        canvas.drawText("HOW TO PLAY", panel.centerX(), panel.bottom - 36f * density, panelTextPaint)
        panelTextPaint.textSize = 11f * density
        panelTextPaint.color = 0xFF90CAF9.toInt()
        canvas.drawText("TAP TO CLOSE", panel.centerX(), panel.bottom - 14f * density, panelTextPaint)
    }

    private fun statsPanel(): RectF {
        val panelW = min(width * 0.9f, 340f * density)
        val panelH = 386f * density
        return RectF(
            width / 2f - panelW / 2f, (height - panelH) / 2f,
            width / 2f + panelW / 2f, (height + panelH) / 2f
        )
    }

    /** Hit area of the "HOW TO PLAY" link at the bottom of the stats panel. */
    private fun statsHelpRect(): RectF {
        val panel = statsPanel()
        val half = 62f * density
        return RectF(
            panel.centerX() - half, panel.bottom - 50f * density,
            panel.centerX() + half, panel.bottom - 20f * density
        )
    }

    /** Audio and feedback toggles; each row flips on tap. */
    private fun drawSettings(canvas: Canvas) {
        if (modal != Modal.SETTINGS) return
        dimPaint.alpha = 0xE6
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val (panel, rows) = settingsLayout()
        val radius = 16f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        panelTextPaint.color = Color.WHITE
        panelTextPaint.isFakeBoldText = true
        panelTextPaint.textSize = 20f * density
        canvas.drawText("SETTINGS", panel.centerX(), panel.top + 32f * density, panelTextPaint)
        panelTextPaint.isFakeBoldText = false
        panelTextPaint.textSize = 10f * density
        panelTextPaint.color = 0xFF90A4AE.toInt()
        canvas.drawText("AUDIO & FEEDBACK", panel.centerX(), panel.top + 50f * density, panelTextPaint)

        val values = listOf(musicEnabled, effectsEnabled, hapticsEnabled)
        for ((i, entry) in rows.withIndex()) {
            val (rect, setting) = entry
            val on = values[i]
            val cy = rect.centerY()
            rankPaint.textSize = 13f * density
            rankPaint.color = Color.WHITE
            canvas.drawText(
                when (setting) {
                    Setting.MUSIC -> "MUSIC"
                    Setting.EFFECTS -> "SOUND EFFECTS"
                    Setting.HAPTICS -> "VIBRATION"
                },
                rect.left, cy - (rankPaint.descent() + rankPaint.ascent()) / 2f, rankPaint
            )

            val pillW = 52f * density
            val pillH = 24f * density
            val pill = RectF(rect.right - pillW, cy - pillH / 2f, rect.right, cy + pillH / 2f)
            timerFillPaint.color = if (on) 0xFF66BB6A.toInt() else 0xFF44515E.toInt()
            timerFillPaint.alpha = 70
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, timerFillPaint)
            panelTextPaint.textSize = 10f * density
            panelTextPaint.color = if (on) 0xFFA5D6A7.toInt() else 0xFF90A4AE.toInt()
            canvas.drawText(
                if (on) "ON" else "OFF", pill.centerX(),
                cy - (panelTextPaint.descent() + panelTextPaint.ascent()) / 2f,
                panelTextPaint
            )
            canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, cellStroke)
        }
        timerFillPaint.color = 0xFF4FC3F7.toInt()
        timerFillPaint.alpha = 220

        panelTextPaint.textSize = 11f * density
        panelTextPaint.color = 0xFF90CAF9.toInt()
        canvas.drawText("TAP TO CLOSE", panel.centerX(), panel.bottom - 18f * density, panelTextPaint)
    }

    private fun settingsLayout(): Pair<RectF, List<Pair<RectF, Setting>>> {
        val settings = Setting.values()
        val rowH = 46f * density
        val panelW = min(width * 0.88f, 320f * density)
        val panelH = 78f * density + settings.size * rowH + 44f * density
        val panel = RectF(
            width / 2f - panelW / 2f, (height - panelH) / 2f,
            width / 2f + panelW / 2f, (height + panelH) / 2f
        )
        val top = panel.top + 78f * density
        val rows = settings.mapIndexed { i, setting ->
            RectF(
                panel.left + 22f * density, top + i * rowH,
                panel.right - 22f * density, top + (i + 1) * rowH
            ) to setting
        }
        return panel to rows
    }

    private fun toggleSetting(setting: Setting) {
        when (setting) {
            Setting.MUSIC -> {
                musicEnabled = !musicEnabled
                audio.setMusicEnabled(musicEnabled)
                prefs.edit().putBoolean(KEY_MUSIC, musicEnabled).apply()
            }
            Setting.EFFECTS -> {
                effectsEnabled = !effectsEnabled
                audio.setEffectsEnabled(effectsEnabled)
                prefs.edit().putBoolean(KEY_EFFECTS, effectsEnabled).apply()
                if (effectsEnabled) audio.play(SoundEffect.MATCH) // audible confirmation
            }
            Setting.HAPTICS -> {
                hapticsEnabled = !hapticsEnabled
                prefs.edit().putBoolean(KEY_HAPTICS, hapticsEnabled).apply()
                haptic()
            }
        }
    }

    private data class TutorialPage(val title: String, val lines: List<String>)

    /** Three short cards covering the rules a new player has to know. */
    private val tutorialPages = listOf(
        TutorialPage(
            "DROP & MATCH",
            listOf(
                "Tap a column to drop the next marble.",
                "Three or more of a colour in a row clear in",
                "a flood-fill blob.",
                "",
                "The bar above the board is the placement",
                "clock — let it empty and the marble drops",
                "itself into a random column."
            )
        ),
        TutorialPage(
            "GOALS & SCALES",
            listOf(
                "Every run rolls three goals, each worth",
                "150 bonus points. Tap a goal in the strip",
                "above the board to read it in full.",
                "",
                "Weight tips the scales. A heavy enough pan",
                "tosses the light one, and those marbles land",
                "again as powerups."
            )
        ),
        TutorialPage(
            "LOCKED COLUMNS & DAILY",
            listOf(
                "Colours and weights stop growing, then the",
                "board closes in — one column locks at level",
                "${Config.CLOSED_COLUMN_LEVEL}, and more follow as you climb.",
                "Hatched columns take nothing at all.",
                "",
                "DAILY plays one seeded board a day — the",
                "same for everyone, with its own best and streak."
            )
        )
    )

    private fun drawTutorial(canvas: Canvas) {
        if (modal != Modal.TUTORIAL) return
        dimPaint.alpha = 0xF0
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val (panel, button) = tutorialLayout()
        val page = tutorialPages[tutorialPage.coerceIn(0, tutorialPages.lastIndex)]
        val radius = 16f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        panelTextPaint.color = Color.WHITE
        panelTextPaint.isFakeBoldText = true
        panelTextPaint.textSize = 18f * density
        canvas.drawText(page.title, panel.centerX(), panel.top + 40f * density, panelTextPaint)
        panelTextPaint.isFakeBoldText = false
        panelTextPaint.textSize = 9f * density
        panelTextPaint.color = 0xFF90A4AE.toInt()
        canvas.drawText(
            "STEP ${tutorialPage + 1} OF ${tutorialPages.size}",
            panel.centerX(), panel.top + 58f * density, panelTextPaint
        )

        val left = panel.left + 28f * density
        var y = panel.top + 90f * density
        for (line in page.lines) {
            if (line.isNotEmpty()) {
                rankPaint.textSize = 13f * density
                rankPaint.color = 0xFFCFD8DC.toInt()
                canvas.drawText(line, left, y, rankPaint)
            }
            y += 18f * density
        }

        // page dots
        val dotY = panel.top + 234f * density
        val spacing = 12f * density
        val startX = panel.centerX() - spacing * (tutorialPages.size - 1) / 2f
        for (i in tutorialPages.indices) {
            starFill.color = if (i == tutorialPage) 0xFF4FC3F7.toInt() else 0xFF39465C.toInt()
            canvas.drawCircle(startX + i * spacing, dotY, 3f * density, starFill)
        }
        starFill.color = 0xFFFFD54F.toInt()

        scoresFill.alpha = 230
        canvas.drawRoundRect(button, button.height() / 2f, button.height() / 2f, scoresFill)
        centerTextPaint.isFakeBoldText = true
        centerTextPaint.textSize = 13f * density
        centerTextPaint.color = 0xFF0B1016.toInt()
        canvas.drawText(
            if (tutorialPage < tutorialPages.lastIndex) "NEXT" else "START PLAYING",
            button.centerX(),
            button.centerY() - (centerTextPaint.descent() + centerTextPaint.ascent()) / 2f,
            centerTextPaint
        )
        centerTextPaint.isFakeBoldText = false
        centerTextPaint.color = Color.WHITE

        panelTextPaint.textSize = 9f * density
        panelTextPaint.color = 0xFF7C8B9C.toInt()
        canvas.drawText("TAP OUTSIDE TO SKIP", panel.centerX(), panel.bottom - 16f * density, panelTextPaint)
    }

    private fun tutorialLayout(): Pair<RectF, RectF> {
        val panelW = min(width * 0.9f, 340f * density)
        val panelH = 340f * density
        val panel = RectF(
            width / 2f - panelW / 2f, (height - panelH) / 2f,
            width / 2f + panelW / 2f, (height + panelH) / 2f
        )
        val playH = 46f * density
        val button = RectF(
            panel.left + 28f * density, panel.top + 252f * density,
            panel.right - 28f * density, panel.top + 252f * density + playH
        )
        return panel to button
    }

    /** The daily challenge: today's seeded board, today's best and the day streak. */
    private fun drawDaily(canvas: Canvas) {
        if (modal != Modal.DAILY) return
        dimPaint.alpha = 0xE6
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val (panel, play) = dailyLayout()
        val radius = 16f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        panelTextPaint.color = Color.WHITE
        panelTextPaint.isFakeBoldText = true
        panelTextPaint.textSize = 20f * density
        canvas.drawText("DAILY CHALLENGE", panel.centerX(), panel.top + 36f * density, panelTextPaint)
        panelTextPaint.isFakeBoldText = false
        panelTextPaint.textSize = 10f * density
        panelTextPaint.color = 0xFF90A4AE.toInt()
        canvas.drawText("SAME SEEDED BOARD ALL DAY", panel.centerX(), panel.top + 54f * density, panelTextPaint)

        panelTextPaint.textSize = 13f * density
        panelTextPaint.color = 0xFF4FC3F7.toInt()
        canvas.drawText(
            dateFormat.format(Date()).uppercase(Locale.getDefault()),
            panel.centerX(), panel.top + 84f * density, panelTextPaint
        )

        val untilReset = DailyProgress.formatCountdown(
            DailyProgress.millisUntilNextBoard(System.currentTimeMillis())
        )
        panelTextPaint.textSize = 10f * density
        panelTextPaint.color = 0xFF7C8B9C.toInt()
        canvas.drawText(
            "NEW BOARD IN $untilReset",
            panel.centerX(), panel.top + 104f * density, panelTextPaint
        )

        val played = daily.playedOn(todayKey())
        val streak = daily.currentStreak(todayKey(), yesterdayKey())
        val yesterday = daily.scoreOnPreviousDay(yesterdayKey())
        val rows = listOf(
            "TODAY'S BEST" to if (played) "${daily.todayBest}" else "NOT PLAYED YET",
            "YESTERDAY" to if (yesterday > 0) "$yesterday" else "—",
            "STREAK" to if (streak > 0) "$streak ${if (streak == 1) "DAY" else "DAYS"}" else "—",
            "BEST STREAK" to "${daily.bestStreak} DAYS",
            "ALL-TIME BEST" to "${daily.bestEver}"
        )
        val left = panel.left + 24f * density
        val right = panel.right - 24f * density
        var y = panel.top + 130f * density
        for ((label, value) in rows) {
            rankPaint.textSize = 12f * density
            rankPaint.color = 0xFF90A4AE.toInt()
            canvas.drawText(label, left, y, rankPaint)
            datePaint.textSize = 13f * density
            datePaint.color = Color.WHITE
            canvas.drawText(value, right, y, datePaint)
            y += 26f * density
        }

        scoresFill.alpha = 230
        canvas.drawRoundRect(play, play.height() / 2f, play.height() / 2f, scoresFill)
        centerTextPaint.isFakeBoldText = true
        centerTextPaint.textSize = 13f * density
        centerTextPaint.color = 0xFF0B1016.toInt()
        canvas.drawText(
            if (played) "PLAY AGAIN" else "PLAY TODAY'S BOARD",
            play.centerX(),
            play.centerY() - (centerTextPaint.descent() + centerTextPaint.ascent()) / 2f,
            centerTextPaint
        )
        centerTextPaint.isFakeBoldText = false
        centerTextPaint.color = Color.WHITE

        panelTextPaint.textSize = 11f * density
        panelTextPaint.color = 0xFF90CAF9.toInt()
        canvas.drawText("TAP OUTSIDE TO CLOSE", panel.centerX(), panel.bottom - 16f * density, panelTextPaint)
    }

    private fun dailyLayout(): Pair<RectF, RectF> {
        val panelW = min(width * 0.9f, 340f * density)
        val panelH = 340f * density
        val panel = RectF(
            width / 2f - panelW / 2f, (height - panelH) / 2f,
            width / 2f + panelW / 2f, (height + panelH) / 2f
        )
        val playH = 46f * density
        val play = RectF(
            panel.left + 24f * density, panel.bottom - playH - 38f * density,
            panel.right - 24f * density, panel.bottom - 38f * density
        )
        return panel to play
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
        // near-full columns glow so a pan that is about to spill is readable at a glance:
        // amber while the column still has its spill reserve, red once that reserve is gone
        // columns the late levels closed are struck out: nothing new can enter them
        if (!engine.gameOver) {
            for (c in 0 until Config.COLS) {
                if (!engine.isClosed(c)) continue
                val x = gridX + c * cell
                canvas.drawRect(
                    x + 1.5f, gridY + 1.5f,
                    x + cell - 1.5f, gridY + Config.ROWS * cell - 1.5f,
                    lockedFill
                )
                for (r in 0 until Config.ROWS) {
                    val y = gridY + r * cell
                    canvas.drawLine(x + 2f, y + cell - 2f, x + cell - 2f, y + 2f, lockedHatch)
                }
            }
        }
        if (!engine.gameOver) {
            val glow = sin(pulse) * 0.5f + 0.5f
            for (c in 0 until Config.COLS) {
                if (topOfColumn(c) > 2f) continue
                val canSpill = engine.canSpill(c)
                dangerPaint.color = if (canSpill) 0xFFFF7043.toInt() else 0xFFE53935.toInt()
                dangerPaint.alpha =
                    (if (canSpill) 45 + 75 * glow else 60 + 110 * glow).toInt().coerceIn(0, 255)
                val x = gridX + c * cell
                canvas.drawRect(
                    x + 1.5f, gridY + 1.5f,
                    x + cell - 1.5f, gridY + cell * 2f - 1.5f,
                    dangerPaint
                )
            }
        }
        if (pressCol in 0 until Config.COLS && !engine.gameOver && !engine.isClosed(pressCol)) {
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
                drawMarble(canvas, x, y, cell * 0.42f, m.color, m.weight, 255, m.power)
            }
        }
    }

    private fun drawActive(canvas: Canvas) {
        val m = engine.active ?: return
        if (m.visualRow <= -1f) return
        val x = gridX + (m.visualCol + 0.5f) * cell
        val y = gridY + (m.visualRow + 0.5f) * cell + slopeOffsetPx(m.col)
        drawMarble(canvas, x, y, cell * 0.42f, m.color, m.weight, 255, m.power)
    }

    private fun colorIndex(i: Int): Int = ((i % MARBLE_COLORS.size) + MARBLE_COLORS.size) % MARBLE_COLORS.size

    private fun drawMarble(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        colorIdx: Int, weight: Int, alpha: Int, power: PowerKind = PowerKind.NONE
    ) {
        val idx = colorIndex(colorIdx)
        when (power) {
            PowerKind.WILD -> drawWildBody(canvas, cx, cy, radius, alpha)
            PowerKind.BOMB -> {
                bombFill.alpha = alpha
                canvas.drawCircle(cx, cy, radius, bombFill)
                marbleStroke.color = 0xFFFF7043.toInt()
                marbleStroke.alpha = alpha
                marbleStroke.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, radius, marbleStroke)
                fuseGlow.alpha = alpha
                canvas.drawCircle(cx + radius * 0.34f, cy - radius * 0.58f, radius * 0.2f, fuseGlow)
            }
            else -> {
                val fill = marbleFills[idx]
                val oldAlpha = fill.alpha
                fill.alpha = alpha
                marbleStroke.color = MARBLE_DARK[idx]
                marbleStroke.alpha = alpha
                marbleStroke.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, radius, fill)
                canvas.drawCircle(cx, cy, radius, marbleStroke)
                glossPaint.alpha = (alpha * 0.65f).toInt()
                canvas.drawCircle(cx - radius * 0.32f, cy - radius * 0.36f, radius * 0.22f, glossPaint)
                fill.alpha = oldAlpha
            }
        }
        if (power == PowerKind.HEAVY) {
            heavyRing.alpha = alpha
            canvas.drawCircle(cx, cy, radius * 0.82f, heavyRing)
        }
        numberPaint.textSize = radius * (if (power == PowerKind.WILD) 0.7f else 0.9f)
        numberPaint.alpha = alpha
        val textY = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText("$weight", cx, textY, numberPaint)
        marbleStroke.alpha = 255
        marbleStroke.style = Paint.Style.STROKE
        glossPaint.alpha = 255
        numberPaint.alpha = 255
    }

    /** Rainbow pinwheel: the wild marble takes on any colour it is matched with. */
    private fun drawWildBody(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        marbleArcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        for (i in MARBLE_COLORS.indices) {
            val p = marbleFills[i]
            p.alpha = alpha
            canvas.drawArc(marbleArcRect, i * 60f, 61f, true, p)
        }
        wildCore.alpha = alpha
        canvas.drawCircle(cx, cy, radius * 0.52f, wildCore)
        wildRing.alpha = alpha
        canvas.drawCircle(cx, cy, radius, wildRing)
        for (p in marbleFills) p.alpha = 255
    }

    /** Marbles tossed off a scale pan, drawn from the engine's ballistic flight state. */
    private fun drawFlying(canvas: Canvas) {
        for (a in engine.airborne) {
            val x = gridX + (a.colF + 0.5f) * cell
            val y = gridY + (a.rowF + 0.5f) * cell
            canvas.save()
            canvas.rotate(a.spin, x, y)
            drawMarble(canvas, x, y, cell * 0.42f, a.color, a.weight, 255, a.power)
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
        if (gameOverFlash <= 0.5f) return

        centerTextPaint.textSize = 38f * density
        centerTextPaint.color = Color.WHITE
        canvas.drawText("GAME OVER", width / 2f, height * 0.33f, centerTextPaint)
        centerTextPaint.textSize = 24f * density
        canvas.drawText("Score ${engine.score}", width / 2f, height * 0.40f, centerTextPaint)
        centerTextPaint.textSize = 16f * density
        canvas.drawText("Best $best", width / 2f, height * 0.445f, centerTextPaint)

        drawGoalCard(canvas, height * 0.575f)

        if (lastRank > 0) {
            centerTextPaint.textSize = 14f * density
            centerTextPaint.color = 0xFFFFD54F.toInt()
            val label = if (lastRank == 1) "NEW HIGH SCORE!" else "HIGH SCORE #$lastRank"
            canvas.drawText(label, width / 2f, height * 0.70f, centerTextPaint)
            centerTextPaint.color = Color.WHITE
        } else if (dailyRun) {
            centerTextPaint.textSize = 14f * density
            centerTextPaint.color = 0xFFFFD54F.toInt()
            val days = if (daily.streak == 1) "1 DAY" else "${daily.streak} DAYS"
            canvas.drawText(
                "DAILY STREAK $days · BEST ${daily.todayBest}",
                width / 2f, height * 0.70f, centerTextPaint
            )
            centerTextPaint.color = Color.WHITE
        }
        centerTextPaint.textSize = 15f * density
        centerTextPaint.color = 0xFF90CAF9.toInt()
        canvas.drawText("Tap to play again", width / 2f, height * 0.77f, centerTextPaint)
        centerTextPaint.color = Color.WHITE
    }

    /** End-of-run card: how each of the run's goals finished, and the bonus they paid. */
    private fun drawGoalCard(canvas: Canvas, centerY: Float) {
        val goals = engine.objectives
        if (goals.isEmpty()) return
        val rowH = 22f * density
        val headerH = 32f * density
        val pad = 12f * density
        val panelW = min(width * 0.88f, 330f * density)
        val panelH = headerH + goals.size * rowH + pad
        val panel = RectF(
            width / 2f - panelW / 2f, centerY - panelH / 2f,
            width / 2f + panelW / 2f, centerY + panelH / 2f
        )
        val radius = 14f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        val left = panel.left + 16f * density
        val right = panel.right - 16f * density
        rankPaint.textSize = 12f * density
        rankPaint.color = 0xFF4FC3F7.toInt()
        canvas.drawText(
            "GOALS ${engine.objectivesDone()}/${goals.size}",
            left, panel.top + 22f * density, rankPaint
        )
        val earned = engine.objectivesDone() * Config.OBJECTIVE_REWARD
        datePaint.textSize = 12f * density
        datePaint.color = 0xFFFFD54F.toInt()
        canvas.drawText(
            if (earned > 0) "+$earned PTS" else "NO BONUS",
            right, panel.top + 22f * density, datePaint
        )

        for ((i, goal) in goals.withIndex()) {
            val y = panel.top + headerH + i * rowH + rowH * 0.72f
            rankPaint.textSize = 12f * density
            rankPaint.color = if (goal.done) 0xFFE0E0E0.toInt() else 0xFF7C8B9C.toInt()
            canvas.drawText(goal.kind.title, left, y, rankPaint)
            datePaint.textSize = 12f * density
            datePaint.color = if (goal.done) 0xFFFFD54F.toInt() else 0xFF7C8B9C.toInt()
            canvas.drawText(
                if (goal.done) "✓ +${Config.OBJECTIVE_REWARD}" else "${goal.progress}/${goal.target}",
                right, y, datePaint
            )
        }
    }

    /** Modal top-ten list of this device's best runs. */
    private fun drawHighScores(canvas: Canvas) {
        if (modal != Modal.SCORES) return
        dimPaint.alpha = 0xE6
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val pad = 18f * density
        val rowH = 26f * density
        val headerH = 80f * density
        val footerH = 38f * density
        val list = highScores.scores
        val panel = scorePanel()
        val radius = 16f * density
        canvas.drawRoundRect(panel, radius, radius, panelFill)
        canvas.drawRoundRect(panel, radius, radius, panelStroke)

        panelTextPaint.color = Color.WHITE
        panelTextPaint.isFakeBoldText = true
        panelTextPaint.textSize = 20f * density
        canvas.drawText("HIGH SCORES", panel.centerX(), panel.top + pad + 14f * density, panelTextPaint)
        panelTextPaint.isFakeBoldText = false

        // TOP TEN / HISTORY tabs
        for ((i, rect) in scoreTabRects().withIndex()) {
            val active = i == scoreTab
            panelTextPaint.textSize = 11f * density
            panelTextPaint.color = if (active) 0xFF4FC3F7.toInt() else 0xFF7C8B9C.toInt()
            canvas.drawText(
                if (i == 0) "TOP TEN" else "HISTORY",
                rect.centerX(), rect.centerY() + 4f * density, panelTextPaint
            )
            if (active) {
                scoresStroke.alpha = 170
                canvas.drawLine(
                    rect.left + 10f * density, rect.bottom,
                    rect.right - 10f * density, rect.bottom, scoresStroke
                )
                scoresStroke.alpha = 110
            }
        }

        val rowTop = panel.top + headerH
        if (scoreTab == 1) {
            drawHistoryRows(canvas, panel, rowTop, rowH)
        } else if (list.isEmpty()) {
            panelTextPaint.textSize = 14f * density
            panelTextPaint.color = 0xFF90A4AE.toInt()
            canvas.drawText(
                "No runs yet — drop some marbles",
                panel.centerX(), rowTop + rowH * 0.68f, panelTextPaint
            )
        } else {
            val left = panel.left + 24f * density
            val right = panel.right - 24f * density
            for ((i, hs) in list.withIndex()) {
                val y = rowTop + rowH * i + rowH * 0.68f
                val top = i == 0
                rankPaint.textSize = 12f * density
                rankPaint.color = if (top) 0xFFFFD54F.toInt() else 0xFF90A4AE.toInt()
                canvas.drawText("${i + 1}.", left, y, rankPaint)
                scorePaint.textSize = 17f * density
                scorePaint.color = if (top) 0xFFFFD54F.toInt() else Color.WHITE
                canvas.drawText("${hs.score}", left + 26f * density, y, scorePaint)
                datePaint.textSize = 11f * density
                datePaint.color = 0xFF90A4AE.toInt()
                canvas.drawText(formatDate(hs.timestamp), right, y - density, datePaint)
            }
        }

        panelTextPaint.textSize = 11f * density
        panelTextPaint.color = 0xFF90CAF9.toInt()
        canvas.drawText(
            "TAP TO CLOSE",
            panel.centerX(), panel.bottom - footerH * 0.45f, panelTextPaint
        )
    }

    /** Last runs, newest first: score, level reached and the date. */
    private fun drawHistoryRows(canvas: Canvas, panel: RectF, rowTop: Float, rowH: Float) {
        val runs = history.runs.take(10)
        val left = panel.left + 24f * density
        val right = panel.right - 24f * density
        if (runs.isEmpty()) {
            panelTextPaint.textSize = 14f * density
            panelTextPaint.color = 0xFF90A4AE.toInt()
            canvas.drawText(
                "No runs yet — drop some marbles",
                panel.centerX(), rowTop + rowH * 0.68f, panelTextPaint
            )
            return
        }
        for ((i, run) in runs.withIndex()) {
            val y = rowTop + rowH * i + rowH * 0.68f
            rankPaint.textSize = 12f * density
            rankPaint.color = 0xFF90A4AE.toInt()
            canvas.drawText("${i + 1}.", left, y, rankPaint)
            scorePaint.textSize = 16f * density
            scorePaint.color = Color.WHITE
            canvas.drawText("${run.score}", left + 28f * density, y, scorePaint)
            datePaint.textSize = 11f * density
            datePaint.color = 0xFF4FC3F7.toInt()
            canvas.drawText("L${run.level}", right - 82f * density, y - density, datePaint)
            datePaint.textSize = 10f * density
            datePaint.color = 0xFF90A4AE.toInt()
            canvas.drawText(formatDate(run.timestamp), right, y - density, datePaint)
        }
    }

    private fun scorePanel(): RectF {
        val rowH = 26f * density
        val headerH = 80f * density
        val footerH = 38f * density
        val panelW = min(width * 0.88f, 320f * density)
        val panelH = headerH + 10 * rowH + footerH
        return RectF(
            width / 2f - panelW / 2f, (height - panelH) / 2f,
            width / 2f + panelW / 2f, (height + panelH) / 2f
        )
    }

    private fun scoreTabRects(): List<RectF> {
        val panel = scorePanel()
        val inner = panel.width() - 32f * density
        val tabW = inner / 2f
        val top = panel.top + 44f * density
        val bottom = top + 26f * density
        return listOf(
            RectF(panel.left + 16f * density, top, panel.left + 16f * density + tabW, bottom),
            RectF(panel.left + 16f * density + tabW, top, panel.right - 16f * density, bottom)
        )
    }

    private fun scoreTabAt(x: Float, y: Float): Int =
        scoreTabRects().indexOfFirst { it.contains(x, y) }

    private fun formatDate(timestamp: Long): String =
        if (timestamp <= 0L) "" else dateFormat.format(Date(timestamp))

    private enum class Button { DAILY, SCORES, STATS, SETTINGS }
    private enum class Modal { NONE, TUTORIAL, DAILY, SCORES, STATS, SETTINGS }
    private enum class Setting { MUSIC, EFFECTS, HAPTICS }

    companion object {
        private const val KEY_HIGH_SCORES = "high_scores"
        private const val KEY_LEGACY_BEST = "best"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_DAILY = "daily"
        private const val KEY_HISTORY = "history"
        private const val KEY_TUTORIAL = "tutorial_seen"
        private const val KEY_MUSIC = "music"
        private const val KEY_EFFECTS = "effects"
        private const val KEY_HAPTICS = "haptics"
    }
}
