package com.marblemadness.swing.game

class Marble(
    val id: Int,
    val color: Int,
    val weight: Int,
    var row: Int,
    var col: Int,
    var visualRow: Float,
    var visualCol: Float
)

data class QueuedMarble(val color: Int, val weight: Int)

/** One end-to-end seesaw beam. Columns are relative to the whole grid. */
class Seesaw(val columns: List<Int>, val pivotCol: Int) {
    /** Current and target tilt, in cell-widths per column. Positive = right end lower. */
    var slope = 0f
    var targetSlope = 0f

    fun contains(col: Int) = col in columns

    /** Signed weight difference: positive = right side heavier. Marbles on the pivot add no torque. */
    fun imbalance(grid: Array<Array<Marble?>>): Int {
        var left = 0
        var right = 0
        for (c in columns) {
            if (c == pivotCol) continue
            for (r in 0 until Config.ROWS) {
                val m = grid[r][c] ?: continue
                if (c < pivotCol) left += m.weight else right += m.weight
            }
        }
        return right - left
    }

    /** Downward pixel offset for a column in grid-cell units. */
    fun offsetFor(col: Int): Float = (col - pivotCol) * slope

    fun degAngle(): Float = Math.toDegrees(Math.atan(slope.toDouble())).toFloat()
}

data class MatchCell(val row: Int, val col: Int, val color: Int)

sealed class GameEvent {
    data class Match(val cells: List<MatchCell>, val colors: Set<Int>, val points: Int, val chain: Int) : GameEvent()
    data class Launched(
        val color: Int,
        val weight: Int,
        val row: Int,
        val col: Int,
        val vx: Float,
        val vy: Float,
        val bonus: Int,
        val spawnStar: Boolean
    ) : GameEvent()

    data class Slid(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int) : GameEvent()
    data class LevelUp(val level: Int) : GameEvent()
    object GameOver : GameEvent()
}
