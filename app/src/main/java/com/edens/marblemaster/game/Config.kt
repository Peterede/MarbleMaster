package com.edens.marblemaster.game

/** Tunable constants for Swing (Marble Madness). Pure logic, no Android deps. */
object Config {
    const val COLS = 9
    const val ROWS = 12

    /**
     * Four seesaws of two columns each, with a fixed centre pillar at [PILLAR_COL].
     * Pivots sit between a beam's two columns, so every marble adds torque.
     */
    val SEESAW_COLUMNS = listOf(
        listOf(0, 1),
        listOf(2, 3),
        listOf(5, 6),
        listOf(7, 8)
    )
    val SEESAW_PIVOTS = listOf(0.5f, 2.5f, 5.5f, 7.5f)
    const val PILLAR_COL = 4

    /** Maximum tilt in cell-widths per column (~29 degrees). */
    const val MAX_SLOPE = 0.55f
    const val SLOPE_GAIN = 0.055f
    const val SLOPE_RETURN = 0.02f
    const val SLOPE_LERP_RATE = 6.0f

    /** Weight imbalance that makes a scale toss the marbles on its lighter pan. */
    const val LAUNCH_THRESHOLD = 8

    /**
     * Launch arc for marbles thrown off a scale pan. They fly up and fall back onto the
     * board as special marbles; all values are in grid cells / seconds.
     */
    const val AIR_GRAVITY = 30.0f
    const val AIR_WALL_BOUNCE = 0.55f
    const val LAUNCH_UP_BASE = 13.0f
    const val LAUNCH_UP_GAIN = 1.1f
    /** Imbalance used for the arc is capped so huge stacks do not launch marbles off-screen. */
    const val LAUNCH_PUNCH_CAP = 12.0f
    const val LAUNCH_SIDE_BASE = 1.2f
    const val LAUNCH_SIDE_GAIN = 0.12f

    /** Safety valve so a marble can never stay airborne forever. */
    const val AIR_MAX_LIFE = 6.0f

    /** Tilt beyond which marbles slide toward the lower end. */
    const val SLIDE_SLOPE = 0.45f
    const val SLIDE_COOLDOWN = 0.40f

    /** Falling player marble physics, in cells / seconds. */
    const val DROP_GRAVITY = 30.0f
    const val DROP_MAX_SPEED = 42.0f

    /** Visual smoothing rates for settled marbles. */
    const val SETTLE_RATE = 14.0f
    const val SLIDE_VISUAL_RATE = 18.0f

    /**
     * Seconds the player has to pick a column for the next marble. When the clock runs out
     * the marble drops itself into a random column.
     */
    const val DROP_TIMER_SECONDS = 5.0f

    const val QUEUE_SIZE = 3
    const val BASE_COLORS = 5
    const val MAX_COLORS = 6
    const val COLOR_LEVEL = 3
    const val MARBLES_PER_LEVEL = 12

    const val MATCH_MIN = 3
    const val MATCH_POINTS = 10
    const val LARGE_GROUP_BONUS = 20
    const val LAUNCH_BASE_BONUS = 50
    const val LAUNCH_LEVEL_BONUS = 25
    const val BONUS_STAR_CHANCE = 0.35f
    const val BONUS_STAR_VALUE = 100

    /** Chance that a queued marble rolls in as a special powerup. */
    const val POWERUP_CHANCE = 0.14f

    /** Bomb: clears a (2r+1)^2 square centred on where it lands. */
    const val BOMB_RADIUS = 1
    const val BOMB_POINTS_PER_CELL = 12

    /** Heavy: always weight 9 and makes a dropped scale toss its far pan. */
    const val HEAVY_WEIGHT = 9
    const val HEAVY_LAUNCH_MULTIPLIER = 2
}
