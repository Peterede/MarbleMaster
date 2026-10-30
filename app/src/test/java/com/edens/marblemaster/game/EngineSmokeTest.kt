package com.edens.marblemaster.game

import java.util.Random

/**
 * Plain-JVM smoke test for GameEngine (no JUnit needed).
 * Run (from app/src/main/java/com/edens/marblemaster/game):
 *   kotlinc Config.kt Models.kt Objectives.kt GameEngine.kt AudioSynth.kt HighScores.kt \
 *     PlayerProgress.kt EngineSmokeTest.kt -include-runtime -d test.jar && java -jar test.jar
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

    // --- the opening queue always carries iron ---
    run {
        val e = GameEngine(Random(9))
        check("the opening queue holds three marbles", e.queue.size == Config.QUEUE_SIZE)
        check(
            "the second opening marble is iron",
            e.queue[1].power == PowerKind.HEAVY && e.queue[1].weight == Config.HEAVY_WEIGHT
        )
        e.restart()
        check(
            "every restart deals the iron marble again",
            e.queue[1].power == PowerKind.HEAVY && e.queue[1].weight == Config.HEAVY_WEIGHT
        )
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

    // --- placement timer shrinks as the level climbs ---
    run {
        check("level 1 allows the full time", Config.dropTimerSeconds(1) == Config.DROP_TIMER_SECONDS)
        check("higher levels allow less time", Config.dropTimerSeconds(2) < Config.dropTimerSeconds(1))
        check("the clock keeps shrinking each level", (3..10).all { Config.dropTimerSeconds(it) < Config.dropTimerSeconds(it - 1) })
        check("the clock never drops below the floor", (1..1000).all { Config.dropTimerSeconds(it) >= Config.DROP_TIMER_MIN_SECONDS })
        check("the floor is reached late in the run", Config.dropTimerSeconds(999) == Config.DROP_TIMER_MIN_SECONDS)
    }

    // --- consecutive clears build a streak bonus ---
    run {
        val e = GameEngine(Random(31))
        placeDirect(e, 11, 0, 0, 1)
        placeDirect(e, 11, 1, 0, 1)
        placeDirect(e, 11, 2, 0, 1)
        e.update(1f / 60f)
        val first = e.drainEvents().filterIsInstance<GameEvent.Match>().first()
        check("a clear starts the streak at one", e.streak == 1 && first.streak == 1)
        check("a lone clear pays the base points", first.points == Config.MATCH_POINTS * 3)

        placeDirect(e, 11, 0, 2, 1)
        placeDirect(e, 11, 1, 2, 1)
        placeDirect(e, 11, 2, 2, 1)
        e.update(1f / 60f)
        val second = e.drainEvents().filterIsInstance<GameEvent.Match>().first()
        check("the next clear extends the streak", e.streak == 2 && second.streak == 2)
        check("the second clear pays the streak bonus", second.points > Config.MATCH_POINTS * 3)

        e.drop(4, 5, 1)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        check("a drop that clears nothing breaks the streak", e.streak == 0)
    }

    // --- per-run objectives ---
    run {
        val e = GameEngine(Random(51))
        check("three goals are rolled", e.objectives.size == Config.OBJECTIVE_COUNT)
        check(
            "goals are distinct",
            e.objectives.map { it.kind }.toSet().size == Config.OBJECTIVE_COUNT
        )
        check("goals start unearned", e.objectives.none { it.done } && e.objectivesDone() == 0)

        // every kind is pushed past its target below, so whichever three were rolled must finish

        // two-step cascade: clearing the A row drops the B marbles into their own run
        repeat(2) {
            placeDirect(e, 11, 0, 0, 1)
            placeDirect(e, 11, 1, 0, 1)
            placeDirect(e, 11, 2, 0, 1)
            placeDirect(e, 11, 3, 3, 1)
            placeDirect(e, 10, 1, 3, 1)
            placeDirect(e, 10, 2, 3, 1)
            var guard = 0
            while (e.active != null && guard++ < 600) e.update(1f / 60f)
            e.update(1f / 60f)
        }

        // a 3x3 blob of one colour clears as a single nine-marble group
        for (r in 9..11) for (c in 2..4) placeDirect(e, r, c, 5, 1)
        e.update(1f / 60f)

        // three bombs, each dropped onto a marble so it actually fires
        for (col in listOf(6, 5, 7)) {
            placeDirect(e, 11, col, col, 1)
            e.drop(col, -1, 1, PowerKind.BOMB)
            var guard = 0
            while (e.active != null && guard++ < 600) e.update(1f / 60f)
            e.update(1f / 60f)
        }

        // climb to level 6 the plain way
        repeat(12) {
            placeDirect(e, 11, 0, 2, 1)
            placeDirect(e, 11, 1, 2, 1)
            placeDirect(e, 11, 2, 2, 1)
            var guard = 0
            while (e.active != null && guard++ < 600) e.update(1f / 60f)
            e.update(1f / 60f)
        }
        check("the run climbed to level six", e.level >= 6)

        // a pan toss: six light marbles on the left, two irons on the right
        for (i in 0 until 6) placeDirect(e, 11 - i, 0, i, 1)
        placeDirect(e, 11, 1, 4, 9)
        e.drop(1, 4, Config.HEAVY_WEIGHT)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        check("the light pan was tossed", e.airborne.isNotEmpty())

        check(
            "every rolled goal was met by that run",
            e.objectivesDone() == Config.OBJECTIVE_COUNT && e.objectives.all { it.done }
        )
        check("a met goal rests on its target", e.objectives.all { it.progress == it.target })
    }

    // --- objectives reset with the run ---
    run {
        val e = GameEngine(Random(52))
        repeat(40) { e.update(0.1f) }
        e.restart()
        check("a restart clears goal progress", e.objectives.none { it.done } && e.objectivesDone() == 0)
        check("a restart keeps three goals", e.objectives.size == Config.OBJECTIVE_COUNT)
    }

    // --- the late levels close columns ---
    run {
        val e = GameEngine(Random(61))

        // three neighbouring open columns, so a clear is always possible
        fun openTriple(): List<Int> {
            for (start in 0..(Config.COLS - 3)) {
                val cols = (start until start + 3).toList()
                if (cols.none { e.isClosed(it) }) return cols
            }
            error("no three open columns in a row")
        }

        fun clearTriple() {
            for (c in openTriple()) placeDirect(e, 11, c, 2, 1)
            e.update(1f / 60f)
        }

        check("columns start open", e.closedColumnCount() == 0)
        check("out-of-range columns are not locked", !e.isClosed(-1) && !e.isClosed(Config.COLS))

        // each level is 12 cleared marbles, i.e. four three-marble clears
        repeat((Config.CLOSED_COLUMN_LEVEL - 1) * 4) { clearTriple() }
        val locks = e.drainEvents().filterIsInstance<GameEvent.ColumnClosed>()
        check("level ten locks a column", e.level >= Config.CLOSED_COLUMN_LEVEL && e.closedColumnCount() == 1)
        check("the lock is announced", locks.size == 1 && e.isClosed(locks[0].col))

        repeat((Config.SECOND_CLOSED_COLUMN_LEVEL - Config.CLOSED_COLUMN_LEVEL) * 4) { clearTriple() }
        check("level fifteen locks a second column", e.closedColumnCount() == 2)
        check("the board always keeps a clearable run", openTriple().size == 3)

        repeat((Config.THIRD_CLOSED_COLUMN_LEVEL - Config.SECOND_CLOSED_COLUMN_LEVEL) * 4) { clearTriple() }
        check("level twenty locks a third column", e.closedColumnCount() == 3)
        check("three locked columns still leave a clearable run", openTriple().size == 3)
        check("the board never locks more than three columns", e.closedColumnCount() <= 3)

        val closed = (0 until Config.COLS).filter { e.isClosed(it) }
        check("a locked column refuses drops", closed.all { !e.drop(it) })
        check("the queue survives a refused drop", e.queue.size == Config.QUEUE_SIZE)
        val open = (0 until Config.COLS).first { !e.isClosed(it) }
        check("an open column still takes a drop", e.drop(open))

        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        check("the run is still alive after the locks", !e.gameOver)

        e.restart()
        check("a restart reopens every column", e.closedColumnCount() == 0)
    }

    // --- daily challenge bookkeeping ---
    run {
        val d = DailyProgress()
        check("a fresh install has no daily run", !d.playedOn("2026-10-29") && d.streak == 0)
        check("the first daily starts the streak", d.record("2026-10-29", "2026-10-28", 500) && d.streak == 1)
        check("the day's best is kept", d.todayBest == 500 && d.bestEver == 500)
        check("replaying the day does not extend the streak", !d.record("2026-10-29", "2026-10-28", 900))
        check("a better replay raises the day's best", d.todayBest == 900 && d.bestEver == 900)
        check("the next day continues the streak", d.record("2026-10-30", "2026-10-29", 300) && d.streak == 2)
        check("a missed day resets the streak", d.record("2026-11-02", "2026-11-01", 100) && d.streak == 1)
        check("the best streak is remembered", d.bestStreak == 2)
        check("a lapsed streak reads as zero", d.currentStreak("2026-11-05", "2026-11-04") == 0)
        check("yesterday still counts as current", d.currentStreak("2026-11-03", "2026-11-02") == 1)

        val restored = DailyProgress()
        restored.load(d.serialize())
        check("daily progress survives a round trip", restored.serialize() == d.serialize())
        restored.load("date=;today=oops;streak=-3;bestStreak=x")
        check("malformed daily progress falls back safely", restored.streak == 0 && restored.todayBest == 0)
        check("a wiped daily reads as never played", !restored.playedOn("2026-10-29"))

        check("the seed is stable for a day", DailyProgress.seedFor("2026-10-29") == DailyProgress.seedFor("2026-10-29"))
        check("the seed changes by day", DailyProgress.seedFor("2026-10-29") != DailyProgress.seedFor("2026-10-30"))

        val day = DailyProgress()
        day.record("2026-10-27", "2026-10-26", 700)
        check("yesterday is empty on the first day", day.scoreOnPreviousDay("2026-10-26") == 0)
        day.record("2026-10-28", "2026-10-27", 900)
        check("yesterday's score is kept", day.scoreOnPreviousDay("2026-10-27") == 700)
        check("a stale day is not shown as yesterday", day.scoreOnPreviousDay("2026-10-29") == 0)
        val dayRoundTrip = DailyProgress()
        dayRoundTrip.load(day.serialize())
        check("the previous day survives a round trip", dayRoundTrip.scoreOnPreviousDay("2026-10-27") == 700)

        check("the countdown shows hours and minutes", DailyProgress.formatCountdown(7 * 3_600_000L + 12 * 60_000L) == "7H 12M")
        check("the countdown drops hours under an hour", DailyProgress.formatCountdown(42 * 60_000L) == "42M")
        check("the countdown never reads zero", DailyProgress.formatCountdown(5_000L) == "1M")
        check(
            "the next board is under a day away",
            DailyProgress.millisUntilNextBoard(1_760_000_000_000L) in 1..86_400_000L
        )
    }

    // --- run history over time ---
    run {
        val h = RunHistory(capacity = 3)
        check("empty history has no runs", h.runs.isEmpty())
        h.add(100, 2, 1_000L)
        h.add(200, 4, 2_000L)
        check("newest run comes first", h.runs.map { it.score } == listOf(200, 100))
        h.add(300, 6, 3_000L)
        h.add(400, 8, 4_000L)
        check("history keeps its capacity", h.runs.size == 3)
        check("capacity drops the oldest run", h.runs.map { it.score } == listOf(400, 300, 200))
        check("levels are kept with the score", h.runs.first().level == 8)

        val restored = RunHistory(capacity = 3)
        restored.load(h.serialize())
        check("history survives a round trip", restored.runs == h.runs)
        restored.load("10,2,100;oops;30,3")
        check("malformed runs are skipped", restored.runs.map { it.score } == listOf(10, 30))
        restored.load(null)
        check("missing history loads empty", restored.runs.isEmpty())
    }

    // --- a seeded daily board replays identically ---
    run {
        val a = GameEngine(Random(71))
        val b = GameEngine(Random(72))
        a.restart(4242L)
        b.restart(4242L)
        check("the same seed deals the same queue", a.queue == b.queue)
        check("the same seed rolls the same goals", a.objectives.map { it.kind } == b.objectives.map { it.kind })
        check("the same seed deals the same weights", a.queue.map { it.weight } == b.queue.map { it.weight })

        val other = GameEngine(Random(73))
        other.restart(4243L)
        check(
            "a different seed changes the board",
            other.queue != a.queue || other.objectives.map { it.kind } != a.objectives.map { it.kind }
        )

        for (e in listOf(a, b)) {
            var guard = 0
            while (!e.gameOver && guard++ < 400) {
                e.drop(guard % Config.COLS)
                repeat(20) { e.update(1f / 60f) }
            }
        }
        check(
            "the same seed plays out the same run",
            a.score == b.score && a.level == b.level && a.gameOver == b.gameOver
        )
        println("  (seeded daily: gameOver=${a.gameOver} score=${a.score} level=${a.level})")
    }

    // --- a replay deals the same board even after climbing levels ---
    run {
        val fresh = GameEngine(Random(75))
        fresh.restart(4242L)

        val played = GameEngine(Random(76))
        played.restart(4242L)
        fun clearTriple() {
            for (c in 0..2) placeDirect(played, 11, c, 2, 1)
            played.update(1f / 60f)
        }
        repeat(8) { clearTriple() } // 24 marbles cleared: level three
        check("the replay test reaches a later level", played.level >= 3)

        played.restart(4242L)
        check("a replay deals the same queue regardless of the last run", played.queue == fresh.queue)
        check(
            "a replay rolls the same goals regardless of the last run",
            played.objectives.map { it.kind } == fresh.objectives.map { it.kind }
        )
    }

    // --- an overloaded column spills instead of ending the run ---
    run {
        val e = GameEngine(Random(41))
        // the pillar never tilts, so it makes a clean test bed for the spill rule
        for (r in 1 until Config.ROWS) placeDirect(e, r, Config.PILLAR_COL, 0, 1)
        e.addScore(500)
        e.drop(Config.PILLAR_COL, 3, 1)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        val events = e.drainEvents()
        val spill = events.filterIsInstance<GameEvent.Spill>().firstOrNull()
        check("a top-row marble spills sideways", spill != null && !e.gameOver)
        check(
            "the spill lands beside the pillar",
            spill != null && spill.toCol in listOf(Config.PILLAR_COL - 1, Config.PILLAR_COL + 1)
        )
        check("the spill keeps the marble's colour", spill != null && spill.color == 3)
        check("the top row is clear after the spill", (0 until Config.COLS).all { e.grid[0][it] == null })
        check("the spilled marble stays on the board", countMarbles(e) == Config.ROWS)
        check(
            "the spill costs points",
            spill != null && spill.penalty == Config.spillPenalty(1) && e.score == 500 - spill.penalty
        )
        check("the spill is counted", e.spills == 1)
        check("the column spends its spill reserve", !e.canSpill(Config.PILLAR_COL))
    }

    // --- a spill penalty can never push the score below zero ---
    run {
        val e = GameEngine(Random(45))
        for (r in 1 until Config.ROWS) placeDirect(e, r, Config.PILLAR_COL, 0, 1)
        e.addScore(5) // less than the level-1 spill penalty
        e.drop(Config.PILLAR_COL, 3, 1)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        check("a spill floors the score at zero", e.score == 0)
    }

    // --- a topped-out column cannot spill twice without clearing ---
    run {
        val e = GameEngine(Random(43))
        for (r in 1 until Config.ROWS) placeDirect(e, r, Config.PILLAR_COL, 0, 1)
        e.drop(Config.PILLAR_COL, 3, 1)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        check("the first spill is rescued", !e.gameOver && e.spills == 1)
        e.drainEvents()

        e.drop(Config.PILLAR_COL, 5, 1)
        guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        check("topping out the same column again ends the run", e.gameOver)
        check("the second topping out reports game over", e.drainEvents().any { it is GameEvent.GameOver })
    }

    // --- clearing the stack back out of the danger zone refills the reserve ---
    run {
        val e = GameEngine(Random(44))
        for (r in 1 until Config.ROWS) placeDirect(e, r, Config.PILLAR_COL, 0, 1)
        e.drop(Config.PILLAR_COL, 3, 1)
        var guard = 0
        while (e.active != null && guard++ < 600) e.update(1f / 60f)
        e.update(1f / 60f)
        check("reserve is spent after the spill", !e.canSpill(Config.PILLAR_COL))
        // a clear takes the stack well below the danger zone
        for (r in Config.SPILL_RECHARGE_ROOM until Config.ROWS) e.grid[r][Config.PILLAR_COL] = null
        e.update(1f / 60f)
        check("clearing the column refills the reserve", e.canSpill(Config.PILLAR_COL))
    }

    // --- spill penalties grow with the level ---
    run {
        check("a spill costs more each level", (2..10).all { Config.spillPenalty(it) > Config.spillPenalty(it - 1) })
        check("a level-one spill is cheap but real", Config.spillPenalty(1) > 0)
    }

    // --- a pan with no room left ends the run ---
    run {
        val e = GameEngine(Random(42))
        // three different colours keep the full columns from matching horizontally
        for (r in 0 until Config.ROWS) {
            placeDirect(e, r, 3, 0, 1)
            placeDirect(e, r, 4, 1, 1)
            placeDirect(e, r, 5, 2, 1)
        }
        e.update(1f / 60f)
        val events = e.drainEvents()
        check("a full pan with no room ends the run", e.gameOver)
        check("the run ends with a game-over event", events.any { it is GameEvent.GameOver })
    }

    // --- lifetime stats and achievements ---
    run {
        val p = PlayerProgress()
        check("a fresh profile has no achievements", p.check().isEmpty() && p.unlockedCount == 0)
        p.recordToss()
        check("the first toss unlocks Blast Off", p.check() == listOf(Achievement.BLAST_OFF))
        p.recordClear(9)
        check("an eight-plus clear unlocks Blob Buster", p.check() == listOf(Achievement.BLOB_BUSTER))
        check("clear totals accumulate", p.totalCleared == 9 && p.biggestClear == 9)
        p.recordLevel(5)
        check("reaching level five unlocks High Climber", p.check() == listOf(Achievement.HIGH_CLIMBER))
        p.recordRun(1200, 5)
        check("a run records score, level and count", p.runsPlayed == 1 && p.bestScore == 1200)
        check(
            "a run can unlock several achievements at once",
            p.check().containsAll(listOf(Achievement.FIRST_RUN, Achievement.POINT_HOARDER))
        )
        p.recordSpill()
        p.recordSpill()
        check("spills accumulate in the lifetime stats", p.totalSpills == 2)
        p.recordClear(100)
        check(
            "a huge clear climbs the Blob Buster tiers and unlocks Century",
            p.check() == listOf(
                Achievement.BLOB_BUSTER_2, Achievement.BLOB_BUSTER_3, Achievement.CENTURY
            )
        )

        val restored = PlayerProgress()
        restored.load(p.serialize())
        check("progress survives a round trip", restored.serialize() == p.serialize())
        check("the spill count survives a round trip", restored.totalSpills == 2)
        check("unlocked achievements survive a round trip", restored.unlockedAchievements == p.unlockedAchievements)
    }

    // --- achievements come in tiers ---
    run {
        check(
            "every achievement family is numbered from tier one",
            Achievement.groups.all { family -> family.map { it.tier } == (1..family.size).toList() }
        )
        check(
            "the families and tiers add up",
            Achievement.groups.size == 7 && Achievement.values().size == 19
        )

        val p = PlayerProgress()
        repeat(50) { p.recordToss() }
        check(
            "toss tiers unlock in order",
            p.check() == listOf(Achievement.BLAST_OFF, Achievement.BLAST_OFF_2)
        )
        repeat(200) { p.recordToss() }
        check("the top toss tier needs 250", p.check() == listOf(Achievement.BLAST_OFF_3))

        val q = PlayerProgress()
        q.recordDailyStreak(3)
        check("a three-day streak unlocks Streak Keeper", q.check() == listOf(Achievement.STREAK_KEEPER))
        q.recordDailyStreak(7)
        check(
            "a seven-day streak unlocks the second tier",
            q.check() == listOf(Achievement.STREAK_KEEPER_2)
        )
        q.recordDailyStreak(2)
        check("a shorter streak does not lower the best", q.bestDailyStreak == 7)
        check("a tier title spells its stage", Achievement.CENTURY_3.title == "Century III")

        val restored = PlayerProgress()
        restored.load(q.serialize())
        check("the daily streak survives a round trip", restored.bestDailyStreak == 7)
        check(
            "tiered achievements survive a round trip",
            restored.unlockedAchievements == q.unlockedAchievements
        )
    }

    run {
        val p = PlayerProgress()
        p.load("runs=3;bestLevel=0;cleared=oops;ach=BLAST_OFF,NOT_REAL")
        check("malformed stats fall back safely", p.runsPlayed == 3 && p.bestLevel == 1 && p.totalCleared == 0)
        check("malformed spill counts fall back safely", p.totalSpills == 0)
        check("unknown achievements are ignored", p.unlockedAchievements == setOf(Achievement.BLAST_OFF))
        p.load(null)
        check("missing progress loads a fresh profile", p.runsPlayed == 0 && p.unlockedCount == 0)
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
