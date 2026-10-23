package com.edens.marblemaster.game

import java.util.Random

/**
 * Plain-JVM smoke test for GameEngine (no JUnit needed).
 * Run: kotlinc GameEngine.kt Models.kt Config.kt HighScores.kt EngineSmokeTest.kt -include-runtime -d test.jar && java -jar test.jar
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean) {
        if (cond) println("PASS  $name") else { println("FAIL  $name"); failures++ }
    }

    // --- horizontal match triggers, vertical does not ---
    run {
        val e = GameEngine(Random(1))
        // build: vertical run of colour 0 in col 0 (rows 9..11) — must NOT trigger
        placeDirect(e, 11, 0, 0, 5)
        placeDirect(e, 10, 0, 0, 5)
        placeDirect(e, 9, 0, 0, 5)
        e.update(1f)
        check("vertical triple does not clear", countMarbles(e) == 3)
        // now colour 0 at (11,1),(11,2) plus existing (11,0) -> horizontal triple clears
        placeDirect(e, 11, 1, 0, 3)
        placeDirect(e, 11, 2, 0, 3)
        e.update(1f)
        val events = e.drainEvents()
        check("horizontal triple clears", countMarbles(e) == 0)
        check("match event emitted", events.any { it is GameEvent.Match })
    }

    // --- flood fill removes touching same colour ---
    run {
        val e = GameEngine(Random(2))
        // L-shape of colour 2: (11,0)(11,1)(11,2) horizontal + (10,2)(9,2) vertical arm touching
        placeDirect(e, 11, 0, 2, 1)
        placeDirect(e, 11, 1, 2, 1)
        placeDirect(e, 11, 2, 2, 1)
        placeDirect(e, 10, 2, 2, 1)
        placeDirect(e, 9, 2, 2, 1)
        // unrelated colour 3 elsewhere
        placeDirect(e, 11, 5, 3, 1)
        e.update(1f)
        val events = e.drainEvents()
        val match = events.filterIsInstance<GameEvent.Match>().firstOrNull()
        check("flood removes 5 touching marbles", match != null && match.cells.size == 5)
        check("unrelated marble survives", countMarbles(e) == 1)
    }

    // --- wild marble stands in for any colour ---
    run {
        val e = GameEngine(Random(12))
        placeDirect(e, 11, 0, 2, 1)
        placePower(e, 11, 1, -1, 1, PowerKind.WILD)
        placeDirect(e, 11, 2, 2, 1)
        e.update(1f)
        val events = e.drainEvents()
        check("wild completes a horizontal triple", countMarbles(e) == 0)
        val match = events.filterIsInstance<GameEvent.Match>().firstOrNull()
        check("wild match clears all three", match != null && match.cells.size == 3)
        check("wild match reports the run colour", match != null && match.colors == setOf(2))
    }

    // --- wild-only run never self-matches ---
    run {
        val e = GameEngine(Random(13))
        placePower(e, 11, 3, -1, 2, PowerKind.WILD)
        placePower(e, 11, 5, -1, 2, PowerKind.WILD)
        placePower(e, 11, 7, -1, 2, PowerKind.WILD)
        e.update(1f)
        check("wilds alone stay on the board", countMarbles(e) == 3)
    }

    // --- wilds hugging the right edge must not overflow the run scan ---
    run {
        val e = GameEngine(Random(18))
        placePower(e, 11, 8, -1, 2, PowerKind.WILD)
        placePower(e, 10, 8, -1, 2, PowerKind.WILD)
        placePower(e, 11, 7, 1, 2)
        e.update(1f)
        check("right-edge wilds do not crash the scan", countMarbles(e) == 3)
    }

    // --- bomb clears a square around its landing cell ---
    run {
        val e = GameEngine(Random(14))
        placeDirect(e, 11, 3, 2, 1)
        placeDirect(e, 10, 3, 5, 1)
        placeDirect(e, 11, 4, 0, 1)
        placeDirect(e, 10, 4, 4, 1) // bomb lands on top of this, at row 9
        placeDirect(e, 11, 5, 3, 1)
        e.drop(4, -1, 5, PowerKind.BOMB)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        val events = e.drainEvents()
        val blast = events.filterIsInstance<GameEvent.Blast>().firstOrNull()
        check("bomb fires a blast", blast != null && blast.power == PowerKind.BOMB)
        check("blast clears the 3x3 neighbourhood", blast != null && blast.cells.size == 3)
        check("blast awards points", e.score > 0)
        check("bomb is consumed and lower marbles survive", countMarbles(e) == 3)
    }

    // --- launch: heavy vs light catapults the light marble ---
    run {
        val e = GameEngine(Random(3))
        // first seesaw is cols 0-1, pivot 0.5. Put light marble weight 1 at col 0.
        placeDirect(e, 11, 0, 4, 1)
        // drop heavy weight 9 into col 1 via engine API so triggerLaunch runs
        forceActive(e, 1, 4, 9)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        val events = e.drainEvents()
        check("launch event emitted", events.any { it is GameEvent.Launched })
        val launched = events.filterIsInstance<GameEvent.Launched>().first()
        check("launched marble flies left", launched.vx < 0)
        check("launched marble is the light one", launched.weight == 1)
        check("launch awards bonus", e.score >= Config.LAUNCH_BASE_BONUS * 1)
    }

    // --- a scale tips its lighter pan: every marble on it is tossed and comes back special ---
    run {
        val e = GameEngine(Random(21))
        // left pan of scale A (col 0) holds three marbles, right pan (col 1) is much heavier
        placeDirect(e, 11, 0, 4, 1)
        placeDirect(e, 10, 0, 2, 2)
        placeDirect(e, 9, 0, 3, 1)
        placeDirect(e, 11, 1, 4, 9)
        e.drop(1, 5, 9)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        val events = e.drainEvents()
        val tossed = events.filterIsInstance<GameEvent.Launched>()
        check("light pan is emptied completely", tossed.size == 3)
        check("every tossed marble returns as a special", tossed.all { it.returnPower != PowerKind.NONE })
        check("tossed marbles are in the air", e.airborne.size == 3)
        check("left pan is clear after the toss", (0 until Config.ROWS).all { r -> e.grid[r][0] == null })

        guard = 0
        while (e.airborne.isNotEmpty() && guard++ < 1200) e.update(1f / 60f)
        val landed = e.drainEvents().filterIsInstance<GameEvent.Landed>()
        check("all tossed marbles fall back", landed.size == 3)
        check("they land as special marbles", landed.all { it.power != PowerKind.NONE })
        check("engine invariant after return", invariant(e))
    }

    // --- heavy marble always launches and doubles the bonus ---
    run {
        val e = GameEngine(Random(15))
        placeDirect(e, 11, 0, 5, 1)
        placeDirect(e, 10, 0, 4, 1)
        e.drop(1, 3, Config.HEAVY_WEIGHT, PowerKind.HEAVY)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        val events = e.drainEvents()
        val launched = events.filterIsInstance<GameEvent.Launched>().firstOrNull()
        check("iron marble forces a launch", launched != null)
        check(
            "forced launch pays double",
            launched != null && launched.bonus ==
                (Config.LAUNCH_BASE_BONUS * 1 + Config.LAUNCH_LEVEL_BONUS * 1) * Config.HEAVY_LAUNCH_MULTIPLIER
        )
        check("powerup counter tracks usage", e.powerupsUsed == 1)
    }

    // --- seesaw tilts with weight ---
    run {
        val e = GameEngine(Random(4))
        placeDirect(e, 11, 0, 5, 4)
        placeDirect(e, 11, 3, 5, 4)
        repeat(20) { e.update(0.1f) }
        check("left-heavy tilts left end down (slope < 0)", e.seesaws[0].slope < -0.05f)
        check("opposite beam tilts the other way", e.seesaws[1].slope > 0.05f)
    }

    // --- four beams, pillar column never tilts ---
    run {
        val e = GameEngine(Random(16))
        check("board has four seesaws", e.seesaws.size == 4)
        check("beams never contain the pillar", e.seesaws.none { it.contains(Config.PILLAR_COL) })
        placeDirect(e, 11, Config.PILLAR_COL, 1, 9)
        repeat(20) { e.update(0.1f) }
        check("pillar column does not tilt a beam", e.seesaws.all { it.slope == 0f })
    }

    // --- powerups are dealt from the queue ---
    run {
        val e = GameEngine(Random(17))
        var specials = 0
        repeat(50) {
            e.restart()
            specials += e.queue.count { it.power != PowerKind.NONE }
        }
        check("powerups appear in the queue", specials > 0)
        println("  (queue powerups: $specials of 150 dealt)")
    }

    // --- full random game reaches a consistent state ---
    run {
        val e = GameEngine(Random(42))
        var steps = 0
        while (!e.gameOver && steps < 20000) {
            e.drop(Random(steps.toLong()).nextInt(Config.COLS))
            repeat(30) { e.update(1f / 60f) }
            steps++
        }
        check("random game terminates or survives 20k drops", true)
        check("score is non-negative", e.score >= 0)
        check("engine invariant: cells match objects", invariant(e))
        println("  (random game: gameOver=${e.gameOver} score=${e.score} level=${e.level} drops=$steps)")
    }

    // --- queue and level progression ---
    run {
        val e = GameEngine(Random(5))
        check("queue starts full", e.queue.size == Config.QUEUE_SIZE)
        e.drop(3)
        check("drop consumes queue", e.queue.size == Config.QUEUE_SIZE && e.active != null)
        val head = e.queue[0]
        e.drop(6)
        check("tap while falling keeps the queue", e.queue[0] == head)
        check("tap while falling keeps the flying marble", e.active?.col == 3)
    }

    // --- placement timer ---
    run {
        val e = GameEngine(Random(7))
        check("timer starts full", e.timeLeft == Config.DROP_TIMER_SECONDS)
        repeat(40) { e.update(0.05f) }
        check("timer drains while the player thinks", e.timeLeft < Config.DROP_TIMER_SECONDS)
        e.drop(4)
        check("choosing a column resets the timer", e.timeLeft == Config.DROP_TIMER_SECONDS)
        repeat(10) { e.update(0.05f) }
        check("timer pauses while a marble is falling", e.timeLeft == Config.DROP_TIMER_SECONDS)
    }

    run {
        val e = GameEngine(Random(8))
        // never touch the board: the game must drop marbles for the player
        repeat(200) { e.update(0.05f) }
        check("an idle board is auto-dropped", e.active != null || countMarbles(e) > 0)
    }

    // --- local high score table ---
    run {
        val t = HighScoreTable()
        check("empty table has no best", t.best() == 0 && t.scores.isEmpty())
        check("first run is rank 1", t.record(120, 1_000L) == 1)
        check("lower run ranks second", t.record(80, 2_000L) == 2)
        check("new best takes rank 1", t.record(300, 3_000L) == 1)
        check("best reflects the top entry", t.best() == 300)
        check("table stays sorted", t.scores.map { it.score } == listOf(300, 120, 80))
        check("equal score ranks behind the older run", t.record(120, 4_000L) == 3)
        check(
            "ties keep the older run first",
            t.scores[1].score == 120 && t.scores[1].timestamp == 1_000L &&
                t.scores[2].score == 120 && t.scores[2].timestamp == 4_000L
        )
        check("zero scores are ignored", t.record(0, 5_000L) == 0 && t.scores.size == 4)
    }

    run {
        val t = HighScoreTable(capacity = 3)
        t.record(50, 1)
        t.record(40, 2)
        t.record(30, 3)
        check("a run below a full table is rejected", t.record(10, 4) == 0)
        check("a run inside a full table is placed", t.record(45, 5) == 2)
        check("table keeps its capacity", t.scores.size == 3)
        check("capacity trims the tail", t.scores.map { it.score } == listOf(50, 45, 40))
    }

    run {
        val t = HighScoreTable()
        t.record(120, 1_000L)
        t.record(80, 2_000L)
        val restored = HighScoreTable()
        restored.load(t.serialize())
        check("scores survive a round trip", restored.scores == t.scores)
        check("serialized form stays compact", t.serialize() == "120,1000;80,2000")
    }

    run {
        val t = HighScoreTable(capacity = 2)
        t.load("10,1;oops;30,3;20,2;40,4")
        check("malformed entries are skipped and order is rebuilt", t.scores.map { it.score } == listOf(40, 30))
        val blank = HighScoreTable()
        blank.load("")
        check("blank storage loads an empty table", blank.scores.isEmpty())
        blank.load(null)
        check("missing storage loads an empty table", blank.scores.isEmpty())
    }

    // --- procedural audio ---
    run {
        for (fx in SoundEffect.values()) {
            val pcm = AudioSynth.effect(fx)
            check("$fx has samples", pcm.isNotEmpty())
            check("$fx is audible", pcm.any { kotlin.math.abs(it.toInt()) > 1000 })
            val seconds = pcm.size.toFloat() / AudioSynth.SAMPLE_RATE
            check("$fx is a short effect", seconds in 0.05f..2.0f)
            check("$fx keeps headroom", pcm.maxOf { kotlin.math.abs(it.toInt()) } < 30000)
        }
        val music = AudioSynth.musicLoop()
        check(
            "music loop is 16 seconds",
            music.size == AudioSynth.MUSIC_SECONDS * AudioSynth.SAMPLE_RATE
        )
        check("music is audible", music.any { kotlin.math.abs(it.toInt()) > 1000 })
        check("music keeps headroom", music.maxOf { kotlin.math.abs(it.toInt()) } < 30000)
        check("music edges are silent", music.first().toInt() == 0 && music.last().toInt() == 0)
    }

    println()
    if (failures == 0) println("ALL TESTS PASSED") else println("$failures TEST(S) FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

private fun countMarbles(e: GameEngine): Int {
    var n = 0
    for (r in 0 until Config.ROWS) for (c in 0 until Config.COLS) if (e.grid[r][c] != null) n++
    return n
}

private fun invariant(e: GameEngine): Boolean {
    for (r in 0 until Config.ROWS) for (c in 0 until Config.COLS) {
        val m = e.grid[r][c] ?: continue
        if (m.row != r || m.col != c) return false
    }
    return true
}

/** Directly inject a resting marble (bypasses drop animation). */
private fun placeDirect(e: GameEngine, row: Int, col: Int, color: Int, weight: Int) {
    placePower(e, row, col, color, weight, PowerKind.NONE)
}

/** Directly inject a resting marble carrying a powerup. */
private fun placePower(e: GameEngine, row: Int, col: Int, color: Int, weight: Int, power: PowerKind = PowerKind.NONE) {
    val m = Marble(0, color, weight, row, col, row.toFloat(), col.toFloat(), power)
    checkCellFree(e, row, col)
    e.grid[row][col] = m
}

/** Drop a specific marble via the public API. */
private fun forceActive(e: GameEngine, col: Int, color: Int, weight: Int) {
    e.drop(col, color, weight)
}

private fun checkCellFree(e: GameEngine, row: Int, col: Int) {
    require(e.grid[row][col] == null) { "cell ($row,$col) occupied" }
}
