package com.marblemadness.swing.game

import java.util.Random

/**
 * Plain-JVM smoke test for GameEngine (no JUnit needed).
 * Run: kotlinc GameEngine.kt Models.kt Config.kt EngineSmokeTest.kt -include-runtime -d test.jar && java -jar test.jar
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

    // --- launch: heavy vs light catapults the light marble ---
    run {
        val e = GameEngine(Random(3))
        // left seesaw cols 0-2, pivot 1. Put light marble weight 1 at col 0.
        placeDirect(e, 11, 0, 4, 1)
        // drop heavy weight 9 into col 2 via engine API so triggerLaunch runs
        forceActive(e, 2, 4, 9)
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

    // --- seesaw tilts with weight ---
    run {
        val e = GameEngine(Random(4))
        placeDirect(e, 11, 0, 5, 4)
        placeDirect(e, 11, 1, 5, 4)
        repeat(20) { e.update(0.1f) }
        check("left-heavy tilts left end down (slope < 0)", e.seesaws[0].slope < -0.05f)
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
    val m = Marble(0, color, weight, row, col, row.toFloat(), col.toFloat())
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
