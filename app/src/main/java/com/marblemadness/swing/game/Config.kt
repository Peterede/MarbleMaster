package com.marblemadness.swing.game

/** Tunable constants for Swing (Marble Madness). Pure logic, no Android deps. */
object Config {
    const val COLS = 7
    const val ROWS = 12

    /** Two seesaws of 3 columns each; column 3 rests on a fixed centre pillar. */
    val SEESAW_COLUMNS = listOf(listOf(0, 1, 2), listOf(4, 5, 6))
    val SEESAW_PIVOTS = listOf(1, 5)
    const val PILLAR_COL = 3

    /** Maximum tilt in cell-widths per column (~29 degrees). */
    const val MAX_SLOPE = 0.55f
    const val SLOPE_GAIN = 0.055f
    const val SLOPE_RETURN = 0.02f
    const val SLOPE_LERP_RATE = 6.0f

    /** Weight imbalance that launches the light side into the air. */
    const val LAUNCH_THRESHOLD = 8

    /** Tilt beyond which marbles slide toward the lower end. */
    const val SLIDE_SLOPE = 0.45f
    const val SLIDE_COOLDOWN = 0.40f

    /** Falling player marble physics, in cells / seconds. */
    const val DROP_GRAVITY = 30.0f
    const val DROP_MAX_SPEED = 42.0f

    /** Visual smoothing rates for settled marbles. */
    const val SETTLE_RATE = 14.0f
    const val SLIDE_VISUAL_RATE = 18.0f

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
}
