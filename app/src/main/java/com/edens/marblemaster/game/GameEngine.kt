package com.edens.marblemaster.game

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
 *  - Four scales under the field tip with the weight placed on each side. Extreme imbalance
 *    tosses every marble off the lighter pan; they arc through the air and fall back onto the
 *    board as special marbles. Steep tilts make marbles slide.
 *  - Special marbles (see [PowerKind]) occasionally arrive in the queue: bombs detonate on
 *    landing, wild marbles match any colour, heavy marbles always force a launch.
 *  - A marble that would rest in the top row spills into the pan's other column while that
 *    column has room below the top row. A spill costs points and uses up the column's single
 *    spill reserve, which only refills once the stack clears back out of the danger zone.
 *    Game over when a topped-out column has no spill left to spend.
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

    /** Consecutive scoring clears; a drop that clears nothing or the time window ends it. */
    var streak = 0
        private set

    /** Seconds left on the current streak before it lapses. */
    var streakTimeLeft = 0f
        private set

    /** Seconds left to choose a column; at 0 the marble drops itself into a random one. */
    var timeLeft = Config.dropTimerSeconds(1)
        private set

    /** Number of powerups fired this run. */
    var powerupsUsed = 0
        private set

    /** Marbles currently flying off a scale pan; they land again as special marbles. */
    val airborne = ArrayList<AirborneMarble>()

    /** Marbles rescued by a spill this run. */
    var spills = 0
        private set

    /** Columns that have already spent their one spill; see [Config.SPILL_RECHARGE_ROOM]. */
    private val spillSpent = BooleanArray(Config.COLS)

    private var nextId = 1
    private var clearAwards = 0
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
        streak = 0
        streakTimeLeft = 0f
        clearAwards = 0
        timeLeft = Config.dropTimerSeconds(level)
        powerupsUsed = 0
        spills = 0
        spillSpent.fill(false)
        airborne.clear()
        pendingEvents.clear()
    }

    fun drainEvents(): List<GameEvent> {
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ------------------------------------------------------------------ input

    /**
     * Taps while a marble is still falling are ignored and must not eat a queued marble.
     * Returns true only when a marble actually started falling.
     */
    fun drop(col: Int): Boolean {
        if (queue.isEmpty()) return false
        val q = queue[0]
        if (!spawn(col, q.color, q.weight, q.power)) return false
        queue.removeAt(0)
        queue.add(randomQueued())
        timeLeft = Config.dropTimerSeconds(level)
        return true
    }

    /** Drop a specific marble (used by tests and demos). */
    fun drop(col: Int, color: Int, weight: Int, power: PowerKind = PowerKind.NONE) {
        spawn(col, color, weight, power)
    }

    private fun spawn(col: Int, color: Int, weight: Int, power: PowerKind): Boolean {
        if (gameOver || active != null || col !in 0 until Config.COLS) return false
        val m = Marble(
            nextId++, color, weight, row = -1, col = col,
            visualRow = -1f, visualCol = col.toFloat(), power = power
        )
        active = m
        activeRowF = -1f
        activeVy = 0f
        return true
    }

    // ------------------------------------------------------------------ update

    fun update(dt: Float) {
        if (gameOver) return
        val d = min(dt, 0.05f)

        // the player only has a few seconds to pick a column before the marble drops itself
        if (active == null) {
            timeLeft = max(0f, timeLeft - d)
            if (timeLeft == 0f) drop(rng.nextInt(Config.COLS))
        }

        // a streak only survives while the next clear arrives inside the window
        if (streakTimeLeft > 0f) {
            streakTimeLeft = max(0f, streakTimeLeft - d)
            if (streakTimeLeft == 0f) streak = 0
        }

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

        updateAirborne(d)
        updateSeesaws(d)
        animateMarbles(d)
        processSlides()
        if (active == null) resolveMatches()
        updateLevel()
        rechargeSpills()
        checkGameOver()
    }

    // ------------------------------------------------------------------ placement

    private fun place(m: Marble, row: Int) {
        m.row = row
        m.visualRow = row.toFloat()
        m.visualCol = m.col.toFloat()
        grid[row][m.col] = m

        val awardsBefore = clearAwards
        if (m.power == PowerKind.BOMB) {
            detonate(m)
            resolveMatches()
        } else {
            triggerLaunch(m)
            resolveMatches()
        }
        // placing a marble that matches nothing ends the run of consecutive clears
        if (clearAwards == awardsBefore) breakStreak()
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

    /**
     * A scale tips sharply enough that its lighter pan empties: every marble resting on that
     * side is thrown into the air and later falls back as a special marble.
     */
    private fun triggerLaunch(landed: Marble) {
        val ss = seesawFor(landed.col) ?: return
        val imb = ss.imbalance(grid)
        if (imb == 0) return
        // iron marbles slam hard enough to fire the other end no matter what
        val forced = landed.power == PowerKind.HEAVY
        if (!forced && abs(imb) < Config.LAUNCH_THRESHOLD) return

        // imbalance = right - left; positive means the right end is heavier, so the LEFT is light
        val lighterIsRight = imb < 0
        val lighterCols = ss.columns.filter { (it - ss.pivotCol > 0f) == lighterIsRight }
        val tossed = ArrayList<Marble>()
        for (c in lighterCols) {
            for (r in 0 until Config.ROWS) grid[r][c]?.let { tossed.add(it) }
        }
        if (tossed.isEmpty()) return

        // the pan tips over: everything on it leaves the board at once
        for (m in tossed) grid[m.row][m.col] = null
        for (c in lighterCols) compactColumn(c)

        val dir = if (lighterIsRight) 1f else -1f
        val punch = min(
            max(abs(imb).toFloat(), Config.LAUNCH_THRESHOLD.toFloat()),
            Config.LAUNCH_PUNCH_CAP
        )
        for (v in tossed) {
            var bonus = Config.LAUNCH_BASE_BONUS * v.weight + Config.LAUNCH_LEVEL_BONUS * level
            if (forced) bonus *= Config.HEAVY_LAUNCH_MULTIPLIER
            score += bonus

            // spread the marbles of a pan so they do not all follow the same arc
            val lift = 0.85f + rng.nextFloat() * 0.3f
            val push = 0.7f + rng.nextFloat() * 0.6f
            val vx = dir * (Config.LAUNCH_SIDE_BASE + Config.LAUNCH_SIDE_GAIN * punch) * push
            val vy = -(Config.LAUNCH_UP_BASE + Config.LAUNCH_UP_GAIN * punch) * lift
            val returnPower = randomReturnPower()
            airborne.add(
                AirborneMarble(
                    color = v.color, weight = v.weight, power = returnPower,
                    rowF = v.row.toFloat(), colF = v.col.toFloat(), vx = vx, vy = vy,
                    spin = rng.nextFloat() * 360f
                )
            )
            pendingEvents.add(
                GameEvent.Launched(
                    color = v.color, weight = v.weight, row = v.row, col = v.col,
                    vx = vx, vy = vy, bonus = bonus,
                    spawnStar = rngBonus.nextFloat() < Config.BONUS_STAR_CHANCE,
                    power = v.power, returnPower = returnPower
                )
            )
        }
        if (forced) powerupsUsed++
    }

    /** Marbles thrown off a pan always come back as one of the three special kinds. */
    private fun randomReturnPower(): PowerKind = when (rng.nextInt(3)) {
        0 -> PowerKind.BOMB
        1 -> PowerKind.WILD
        else -> PowerKind.HEAVY
    }

    // ------------------------------------------------------------------ airborne

    /** Ballistic flight for tossed marbles; each one drops back into the first open cell it meets. */
    private fun updateAirborne(d: Float) {
        if (airborne.isEmpty()) return
        var landedAny = false
        val it = airborne.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.life += d
            a.vy += Config.AIR_GRAVITY * d
            a.rowF += a.vy * d
            a.colF += a.vx * d
            a.spin += 220f * d

            // bounce off the side walls so nothing sails off the board
            if (a.colF < 0f) {
                a.colF = 0f
                a.vx = abs(a.vx) * Config.AIR_WALL_BOUNCE
            } else if (a.colF > (Config.COLS - 1).toFloat()) {
                a.colF = (Config.COLS - 1).toFloat()
                a.vx = -abs(a.vx) * Config.AIR_WALL_BOUNCE
            }

            val col = Math.round(a.colF).coerceIn(0, Config.COLS - 1)
            val rest = restRow(col)
            if (a.vy > 0f && rest >= 0 && a.rowF >= rest) {
                it.remove()
                landAirborne(a, col, rest)
                landedAny = true
            } else if (a.life > Config.AIR_MAX_LIFE) {
                it.remove()
            }
        }
        if (landedAny) resolveMatches()
    }

    /**
     * A tossed marble rejoins the board carrying its new special ability. Returning marbles do
     * not trigger another toss themselves, so a scale can never launch forever.
     */
    private fun landAirborne(a: AirborneMarble, col: Int, row: Int) {
        val weight = if (a.power == PowerKind.HEAVY) Config.HEAVY_WEIGHT else a.weight
        val m = Marble(
            nextId++, a.color, weight, row, col,
            visualRow = row - 0.75f, visualCol = a.colF, power = a.power
        )
        grid[row][col] = m
        pendingEvents.add(GameEvent.Landed(row, col, m.color, m.weight, m.power))
        if (m.power == PowerKind.BOMB) detonate(m)
    }

    // ------------------------------------------------------------------ powerups

    /** Bomb marble: clears a square centred on its landing cell, itself included. */
    private fun detonate(bomb: Marble) {
        val r = Config.BOMB_RADIUS
        val cleared = ArrayList<MatchCell>()
        var points = 0
        for (dr in -r..r) {
            for (dc in -r..r) {
                val rr = bomb.row + dr
                val cc = bomb.col + dc
                if (rr !in 0 until Config.ROWS || cc !in 0 until Config.COLS) continue
                val m = grid[rr][cc] ?: continue
                grid[rr][cc] = null
                cleared.add(MatchCell(rr, cc, m.color, m.power))
                points += Config.BOMB_POINTS_PER_CELL * m.weight
            }
        }
        if (cleared.isEmpty()) return
        points = awardClear(points)
        marblesCleared += cleared.size
        powerupsUsed++
        for (c in 0 until Config.COLS) compactColumn(c)
        pendingEvents.add(GameEvent.Blast(PowerKind.BOMB, bomb.row, bomb.col, cleared, points, streak))
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

    /** One horizontal run of matching marbles, with the colour it is matched as. */
    private class ColorRun(val color: Int, val cells: List<Pair<Int, Int>>)

    private fun resolveMatches() {
        var chain = 0
        while (true) {
            val runs = findHorizontalRuns()
            if (runs.isEmpty()) break
            chain++
            lastChain = chain

            val group = LinkedHashSet<Pair<Int, Int>>()
            val colors = HashSet<Int>()
            for (run in runs) {
                colors.add(run.color)
                for (cell in run.cells) expandFlood(cell.first, cell.second, run.color, group)
            }

            val matchCells = group.map { (r, c) ->
                val m = grid[r][c]!!
                MatchCell(r, c, m.color, m.power)
            }
            var points = Config.MATCH_POINTS * group.size * chain
            if (group.size >= Config.MATCH_MIN + 1) points += Config.LARGE_GROUP_BONUS * (group.size - Config.MATCH_MIN)
            points = awardClear(points)
            marblesCleared += group.size

            for ((r, c) in group) grid[r][c] = null
            for (c in 0 until Config.COLS) compactColumn(c)
            pendingEvents.add(GameEvent.Match(matchCells, colors, points, chain, streak))
        }
    }

    /**
     * Registers one scoring clear, extends the streak and pays its bonus. Returns the total
     * points for the clear, including the streak share.
     */
    private fun awardClear(points: Int): Int {
        if (streakTimeLeft <= 0f) streak = 0
        streak++
        streakTimeLeft = Config.STREAK_WINDOW
        clearAwards++
        val steps = min(streak - 1, Config.STREAK_MAX_BONUS_STEPS)
        val total = points + (points * Config.STREAK_BONUS_PER_STEP * steps).toInt()
        score += total
        return total
    }

    private fun breakStreak() {
        streak = 0
        streakTimeLeft = 0f
    }

    /**
     * 3+ marbles of one colour side by side in a row. Vertical runs intentionally excluded.
     * Wild marbles take on the colour of the run they touch.
     */
    private fun findHorizontalRuns(): List<ColorRun> {
        val runs = ArrayList<ColorRun>()
        for (r in 0 until Config.ROWS) {
            var c = 0
            while (c < Config.COLS) {
                if (grid[r][c] == null) {
                    c++
                    continue
                }
                // anchor on the first concrete colour; leading wilds join that colour
                var anchor = c
                while (anchor < Config.COLS && grid[r][anchor]?.power == PowerKind.WILD) anchor++
                var color = -1
                if (anchor < Config.COLS) color = grid[r][anchor]?.color ?: -1
                if (color < 0) {
                    c = anchor + 1 // nothing but wilds (and gaps) out to the edge
                    continue
                }
                var end = anchor
                while (end < Config.COLS) {
                    val m = grid[r][end] ?: break
                    if (m.power != PowerKind.WILD && m.color != color) break
                    end++
                }
                if (end - c >= Config.MATCH_MIN) {
                    val cells = ArrayList<Pair<Int, Int>>(end - c)
                    for (i in c until end) cells.add(r to i)
                    runs.add(ColorRun(color, cells))
                }
                c = max(end, c + 1)
            }
        }
        return runs
    }

    /** Chain along every orthogonally/diagonally touching marble of the same colour (or wild). */
    private fun expandFlood(r: Int, c: Int, color: Int, out: MutableSet<Pair<Int, Int>>) {
        val stack = ArrayList<Pair<Int, Int>>()
        stack.add(r to c)
        while (stack.isNotEmpty()) {
            val (cr, cc) = stack.removeAt(stack.size - 1)
            if (cr !in 0 until Config.ROWS || cc !in 0 until Config.COLS) continue
            if ((cr to cc) in out) continue
            val m = grid[cr][cc] ?: continue
            if (m.power != PowerKind.WILD && m.color != color) continue
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
            // the clock shortens with the level, so trim any time already on it
            timeLeft = min(timeLeft, Config.dropTimerSeconds(level))
            pendingEvents.add(GameEvent.LevelUp(level))
        }
    }

    /**
     * Overloaded columns spill instead of ending the run: a marble resting in the top row rolls
     * into the pan's other column while that column still has room below the top row. The rescue
     * is not free — it costs points and spends the column's reserve until the stack clears.
     */
    private fun checkGameOver() {
        if (gameOver || active != null) return
        var spilled = false
        for (c in 0 until Config.COLS) {
            if (grid[0][c] == null) continue
            val target = if (spillSpent[c]) -1 else spillTarget(c)
            if (target < 0) {
                gameOver = true
                pendingEvents.add(GameEvent.GameOver)
                return
            }
            val m = grid[0][c]!!
            val row = restRow(target)
            grid[0][c] = null
            m.col = target
            m.row = row
            grid[row][target] = m
            spillSpent[c] = true
            val penalty = min(Config.spillPenalty(level), score)
            score -= penalty
            spills++
            pendingEvents.add(GameEvent.Spill(c, target, row, m.color, m.weight, m.power, penalty))
            spilled = true
        }
        if (spilled) resolveMatches()
    }

    /** A column earns its spill back once its stack has dropped clear of the danger zone. */
    private fun rechargeSpills() {
        for (c in 0 until Config.COLS) {
            if (spillSpent[c] && restRow(c) >= Config.SPILL_RECHARGE_ROOM) spillSpent[c] = false
        }
    }

    /** True when a marble topping out [col] right now would spill instead of ending the run. */
    fun canSpill(col: Int): Boolean =
        col in 0 until Config.COLS && !spillSpent[col] && spillTarget(col) >= 0

    /**
     * The column that can take a spilled marble: the other column of the same scale, or either
     * side of the centre pillar. A target needs room below the top row so a spill can never just
     * pass the same problem back across the beam.
     */
    private fun spillTarget(col: Int): Int {
        val candidates = if (col == Config.PILLAR_COL) {
            listOf(col - 1, col + 1)
        } else {
            seesawFor(col)?.columns?.filter { it != col } ?: emptyList()
        }
        return candidates
            .filter { it in 0 until Config.COLS && restRow(it) >= 1 }
            .maxByOrNull { restRow(it) } ?: -1
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
        val power = if (rng.nextFloat() < Config.POWERUP_CHANCE) {
            when (rng.nextInt(3)) {
                0 -> PowerKind.BOMB
                1 -> PowerKind.WILD
                else -> PowerKind.HEAVY
            }
        } else {
            PowerKind.NONE
        }
        return when (power) {
            // bombs and wilds are colourless until they act
            PowerKind.BOMB -> QueuedMarble(-1, weight, PowerKind.BOMB)
            PowerKind.WILD -> QueuedMarble(-1, weight, PowerKind.WILD)
            PowerKind.HEAVY -> QueuedMarble(rng.nextInt(colorCount()), Config.HEAVY_WEIGHT, PowerKind.HEAVY)
            PowerKind.NONE -> QueuedMarble(rng.nextInt(colorCount()), weight)
        }
    }
}
