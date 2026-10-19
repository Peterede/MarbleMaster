package com.marblemadness.swing.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Pure game logic for Swing (Marble Madness).
 *
 * Rules implemented:
 *  - Marbles are dropped into columns by the player; each has a colour and a weight.
 *  - 3+ same-coloured marbles connected HORIZONTALLY trigger a reaction. The match and every
 *    same-coloured marble touching it (8 directions) are removed. Vertical runs do NOT trigger.
 *  - Two seesaws under the field tilt with the weight placed on each side. Extreme imbalance
 *    launches the light side out of the field for bonus points. Steep tilts make marbles slide.
 *  - Game over when marbles reach the top row.
 */
class GameEngine(val rng: java.util.Random = java.util.Random()) {

    val grid = Array(Config.ROWS) { arrayOfNulls<Marble>(Config.COLS) }
    val seesaws = Config.SEESAW_COLUMNS.mapIndexed { i, cols ->
        Seesaw(cols, Config.SEESAW_PIVOTS[i])
    }

    val queue = ArrayList<QueuedMarble>(Config.QUEUE_SIZE)
    var active: Marble? = null
        private set
    var activeRowF = 0f
        private set
    var activeVy = 0f
        private set

    var score = 0
        private set
    var level = 1
        private set
    var marblesCleared = 0
        private set
    var gameOver = false
        private set
    var lastChain = 0
        private set

    private var nextId = 1
    private val slideCooldown = FloatArray(Config.COLS)
    private val pendingEvents = ArrayList<GameEvent>()
    private val rngBonus = java.util.Random()

    init {
        restart()
    }

    fun restart() {
        for (r in 0 until Config.ROWS) for (c in 0 until Config.COLS) grid[r][c] = null
        seesaws.forEach { it.slope = 0f; it.targetSlope = 0f }
        slideCooldown.fill(0f)
        queue.clear()
        repeat(Config.QUEUE_SIZE) { queue.add(randomQueued()) }
        active = null
        score = 0
        level = 1
        marblesCleared = 0
        gameOver = false
        lastChain = 0
        pendingEvents.clear()
    }

    fun drainEvents(): List<GameEvent> {
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ------------------------------------------------------------------ input

    fun drop(col: Int) {
        if (queue.isEmpty()) return
        drop(col, queue[0].color, queue[0].weight)
        queue.removeAt(0)
        queue.add(randomQueued())
    }

    /** Drop a specific marble (used by tests and demos). */
    fun drop(col: Int, color: Int, weight: Int) {
        if (gameOver || active != null || col !in 0 until Config.COLS) return
        val m = Marble(nextId++, color, weight, row = -1, col = col, visualRow = -1f, visualCol = col.toFloat())
        active = m
        activeRowF = -1f
        activeVy = 0f
    }

    // ------------------------------------------------------------------ update

    fun update(dt: Float) {
        if (gameOver) return
        val d = min(dt, 0.05f)

        active?.let { m ->
            activeVy = min(activeVy + Config.DROP_GRAVITY * d, Config.DROP_MAX_SPEED)
            activeRowF += activeVy * d
            m.visualRow = activeRowF
            val rest = restRow(m.col)
            if (activeRowF >= rest) {
                active = null
                place(m, rest)
            }
        }

        updateSeesaws(d)
        animateMarbles(d)
        processSlides()
        if (active == null) resolveMatches()
        checkGameOver()
    }

    // ------------------------------------------------------------------ placement

    private fun place(m: Marble, row: Int) {
        m.row = row
        m.visualRow = row.toFloat()
        m.visualCol = m.col.toFloat()
        grid[row][m.col] = m

        triggerLaunch(m)
        resolveMatches()
        updateLevel()
    }

    private fun restRow(col: Int): Int {
        var count = 0
        for (r in 0 until Config.ROWS) if (grid[r][col] != null) count++
        return Config.ROWS - 1 - count
    }

    // ------------------------------------------------------------------ seesaws

    private fun seesawFor(col: Int): Seesaw? = seesaws.firstOrNull { it.contains(col) }

    private fun updateSeesaws(d: Float) {
        for (ss in seesaws) {
            val imb = ss.imbalance(grid)
            var target = Config.SLOPE_GAIN * imb
            if (imb == 0) target = 0f
            // gentle spring back toward level when unloaded
            target = (target - Config.SLOPE_RETURN * target).coerceIn(-Config.MAX_SLOPE, Config.MAX_SLOPE)
            ss.targetSlope = target
            val t = 1f - Math.exp((-Config.SLOPE_LERP_RATE * d).toDouble()).toFloat()
            ss.slope += (ss.targetSlope - ss.slope) * t
            if (abs(ss.slope) < 0.002f && abs(ss.targetSlope) < 0.002f) ss.slope = 0f
        }
        for (c in 0 until Config.COLS) slideCooldown[c] = max(0f, slideCooldown[c] - d)
    }

    /** A heavy marble slammed onto one end can catapult the light end off the field. */
    private fun triggerLaunch(landed: Marble) {
        val ss = seesawFor(landed.col) ?: return
        val imb = ss.imbalance(grid)
        if (abs(imb) < Config.LAUNCH_THRESHOLD) return

        // imbalance = right - left; positive means the right end is heavier, so the LEFT is light
        val lighterIsRight = imb < 0
        val lighterCols = ss.columns.filter { (it > ss.pivotCol) == lighterIsRight && it != ss.pivotCol }
        var victim: Marble? = null
        for (c in lighterCols) {
            for (r in 0 until Config.ROWS) {
                val m = grid[r][c] ?: continue
                val cur = victim
                if (cur == null || m.weight < cur.weight || (m.weight == cur.weight && r < cur.row)) {
                    victim = m
                }
            }
        }
        val v = victim ?: return

        grid[v.row][v.col] = null
        compactColumn(v.col)
        val dir = if (lighterIsRight) 1f else -1f
        val power = abs(imb).toFloat()
        val vx = dir * (6f + 0.9f * power)
        val vy = -(9f + 1.3f * power)
        val bonus = Config.LAUNCH_BASE_BONUS * v.weight + Config.LAUNCH_LEVEL_BONUS * level
        score += bonus
        pendingEvents.add(
            GameEvent.Launched(
                color = v.color, weight = v.weight, row = v.row, col = v.col,
                vx = vx, vy = vy, bonus = bonus,
                spawnStar = rngBonus.nextFloat() < Config.BONUS_STAR_CHANCE
            )
        )
    }

    // ------------------------------------------------------------------ sliding

    private fun processSlides() {
        var slidAny = false
        for (ss in seesaws) {
            val s = ss.slope
            if (abs(s) < Config.SLIDE_SLOPE) continue
            val dir = sign(s).toInt()
            // slide from the lower end inward so piles cascade toward the deep side
            val order = if (dir > 0) ss.columns.sortedDescending() else ss.columns.sorted()
            for (c in order) {
                if (slideCooldown[c] > 0f) continue
                val target = c + dir
                if (!ss.contains(target)) continue
                val top = topMarble(c) ?: continue
                if (restRow(target) < 0) continue
                val fromRow = top.row
                val fromCol = top.col
                grid[fromRow][fromCol] = null
                val toRow = restRow(target)
                top.col = target
                top.row = toRow
                grid[toRow][target] = top
                slideCooldown[c] = Config.SLIDE_COOLDOWN
                slideCooldown[target] = Config.SLIDE_COOLDOWN
                pendingEvents.add(GameEvent.Slid(fromRow, fromCol, toRow, target))
                slidAny = true
                break // one slide per seesaw per tick keeps motion readable
            }
        }
        if (slidAny) resolveMatches()
    }

    private fun topMarble(col: Int): Marble? {
        for (r in 0 until Config.ROWS) grid[r][col]?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ matching

    private fun resolveMatches() {
        var chain = 0
        while (true) {
            val runCells = findHorizontalRuns()
            if (runCells.isEmpty()) break
            chain++
            lastChain = chain

            val group = LinkedHashSet<Pair<Int, Int>>()
            val colors = HashSet<Int>()
            for (cell in runCells) {
                colors.add(grid[cell.first][cell.second]!!.color)
                expandFlood(cell.first, cell.second, grid[cell.first][cell.second]!!.color, group)
            }

            val matchCells = group.map { (r, c) -> MatchCell(r, c, grid[r][c]!!.color) }
            var points = Config.MATCH_POINTS * group.size * chain
            if (group.size >= Config.MATCH_MIN + 1) points += Config.LARGE_GROUP_BONUS * (group.size - Config.MATCH_MIN)
            score += points
            marblesCleared += group.size

            for ((r, c) in group) grid[r][c] = null
            for (c in 0 until Config.COLS) compactColumn(c)
            pendingEvents.add(GameEvent.Match(matchCells, colors, points, chain))
        }
    }

    /** 3+ marbles of one colour side by side in a row. Vertical runs intentionally excluded. */
    private fun findHorizontalRuns(): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (r in 0 until Config.ROWS) {
            var c = 0
            while (c < Config.COLS) {
                val m = grid[r][c]
                if (m == null) { c++; continue }
                var end = c + 1
                while (end < Config.COLS && grid[r][end]?.color == m.color) end++
                if (end - c >= Config.MATCH_MIN) for (i in c until end) cells.add(r to i)
                c = end
            }
        }
        return cells
    }

    /** Chain along every orthogonally/diagonally touching marble of the same colour. */
    private fun expandFlood(r: Int, c: Int, color: Int, out: MutableSet<Pair<Int, Int>>) {
        val stack = ArrayList<Pair<Int, Int>>()
        stack.add(r to c)
        while (stack.isNotEmpty()) {
            val (cr, cc) = stack.removeAt(stack.size - 1)
            if (cr !in 0 until Config.ROWS || cc !in 0 until Config.COLS) continue
            if ((cr to cc) in out) continue
            val m = grid[cr][cc] ?: continue
            if (m.color != color) continue
            out.add(cr to cc)
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                stack.add((cr + dr) to (cc + dc))
            }
        }
    }

    private fun compactColumn(col: Int) {
        var write = Config.ROWS - 1
        for (r in Config.ROWS - 1 downTo 0) {
            val m = grid[r][col] ?: continue
            grid[r][col] = null
            grid[write][col] = m
            if (write != r) m.row = write // visualRow stays so the marble animates downward
            write--
        }
    }

    private fun animateMarbles(dt: Float) {
        val tRow = 1f - Math.exp((-Config.SETTLE_RATE * dt).toDouble()).toFloat()
        val tCol = 1f - Math.exp((-Config.SLIDE_VISUAL_RATE * dt).toDouble()).toFloat()
        for (r in 0 until Config.ROWS) {
            for (c in 0 until Config.COLS) {
                val m = grid[r][c] ?: continue
                m.visualRow += (m.row - m.visualRow) * tRow
                m.visualCol += (m.col - m.visualCol) * tCol
                if (abs(m.row - m.visualRow) < 0.01f) m.visualRow = m.row.toFloat()
                if (abs(m.col - m.visualCol) < 0.01f) m.visualCol = m.col.toFloat()
            }
        }
    }

    // ------------------------------------------------------------------ meta

    private fun updateLevel() {
        val newLevel = 1 + marblesCleared / Config.MARBLES_PER_LEVEL
        if (newLevel > level) {
            level = newLevel
            pendingEvents.add(GameEvent.LevelUp(level))
        }
    }

    private fun checkGameOver() {
        if (gameOver || active != null) return
        for (c in 0 until Config.COLS) {
            if (grid[0][c] != null) {
                gameOver = true
                pendingEvents.add(GameEvent.GameOver)
                return
            }
        }
    }

    fun colorCount(): Int = min(Config.BASE_COLORS + (level - 1) / 2, Config.MAX_COLORS)

    fun maxWeight(): Int = min(4 + level, 9)

    fun addScore(points: Int) {
        score += points
    }

    private fun randomQueued(): QueuedMarble {
        val mw = maxWeight()
        val raw = rng.nextFloat()
        val shaped = raw * raw // favour lighter marbles
        val weight = 1 + (shaped * (mw - 1)).toInt().coerceIn(0, mw - 1)
        return QueuedMarble(rng.nextInt(colorCount()), weight)
    }
}
