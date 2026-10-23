package com.edens.marblemaster.game

/** Special abilities a marble can carry. */
enum class PowerKind {
    /** Ordinary marble. */
    NONE,

    /** Detonates on landing, clearing a square of the board around itself. */
    BOMB,

    /** Rainbow marble: stands in for any colour when runs and groups are matched. */
    WILD,

    /** Iron marble: always weight 9 and always makes a dropped scale toss its far pan. */
    HEAVY
}

class Marble(
    val id: Int,
    val color: Int,
    val weight: Int,
    var row: Int,
    var col: Int,
    var visualRow: Float,
    var visualCol: Float,
    val power: PowerKind = PowerKind.NONE
)

data class QueuedMarble(val color: Int, val weight: Int, val power: PowerKind = PowerKind.NONE)

/**
 * A marble tossed off a scale pan. It follows a ballistic arc in grid-cell units and is
 * put back on the board as a special marble when it lands.
 */
class AirborneMarble(
    val color: Int,
    var weight: Int,
    var power: PowerKind,
    var rowF: Float,
    var colF: Float,
    var vx: Float,
    var vy: Float,
    var spin: Float = 0f,
    var life: Float = 0f
)

/** One end-to-end beam. Columns are relative to the whole grid. */
class Seesaw(val columns: List<Int>, val pivotCol: Float) {
    /** Current and target tilt, in cell-widths per column. Positive = right end lower. */
    var slope = 0f
    var targetSlope = 0f

    fun contains(col: Int) = col in columns

    /** Signed weight difference: positive = right side heavier. Beam pivots carry no torque. */
    fun imbalance(grid: Array<Array<Marble?>>): Int {
        var left = 0
        var right = 0
        for (c in columns) {
            val side = c - pivotCol
            if (side == 0f) continue
            for (r in 0 until Config.ROWS) {
                val m = grid[r][c] ?: continue
                if (side < 0f) left += m.weight else right += m.weight
            }
        }
        return right - left
    }

    /** Downward pixel offset for a column in grid-cell units. */
    fun offsetFor(col: Int): Float = (col - pivotCol) * slope

    fun degAngle(): Float = Math.toDegrees(Math.atan(slope.toDouble())).toFloat()
}

data class MatchCell(
    val row: Int,
    val col: Int,
    val color: Int,
    val power: PowerKind = PowerKind.NONE
)

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
        val spawnStar: Boolean,
        val power: PowerKind = PowerKind.NONE,
        /** The special marble this one becomes when it falls back onto the board. */
        val returnPower: PowerKind = PowerKind.NONE
    ) : GameEvent()

    /** A launched marble has fallen back onto the board as a special marble. */
    data class Landed(
        val row: Int,
        val col: Int,
        val color: Int,
        val weight: Int,
        val power: PowerKind
    ) : GameEvent()

    /** A powerup fired: [cells] were destroyed by the ability at ([row], [col]). */
    data class Blast(
        val power: PowerKind,
        val row: Int,
        val col: Int,
        val cells: List<MatchCell>,
        val points: Int
    ) : GameEvent()

    data class Slid(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int) : GameEvent()
    data class LevelUp(val level: Int) : GameEvent()
    object GameOver : GameEvent()
}
