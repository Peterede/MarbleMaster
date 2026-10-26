package com.edens.marblemaster.game

/** Milestones a player unlocks by playing across runs. */
enum class Achievement(val title: String, val earned: (PlayerProgress) -> Boolean) {
    FIRST_RUN("First Swing", { it.runsPlayed >= 1 }),
    BLAST_OFF("Blast Off", { it.totalTosses >= 1 }),
    BLOB_BUSTER("Blob Buster", { it.biggestClear >= 8 }),
    HIGH_CLIMBER("High Climber", { it.bestLevel >= 5 }),
    CENTURY("Century", { it.totalCleared >= 100 }),
    POINT_HOARDER("Point Hoarder", { it.bestScore >= 1000 })
}

/**
 * Lifetime stats and unlocked achievements, kept on the device next to the high score table.
 * All setters are private; the `record*` calls are the only way to move the numbers, and
 * [check] reports the achievements that just became true so the view can celebrate them.
 */
class PlayerProgress {

    var runsPlayed = 0
        private set
    var bestScore = 0
        private set
    var bestLevel = 1
        private set
    var biggestClear = 0
        private set
    var totalCleared = 0
        private set
    var totalTosses = 0
        private set

    /** Marbles rescued by spilling sideways out of a topped-out column. */
    var totalSpills = 0
        private set

    private val unlocked = LinkedHashSet<Achievement>()

    val unlockedCount: Int get() = unlocked.size
    val unlockedAchievements: Set<Achievement> get() = unlocked

    fun isUnlocked(achievement: Achievement): Boolean = achievement in unlocked

    fun recordClear(cells: Int) {
        if (cells <= 0) return
        totalCleared += cells
        if (cells > biggestClear) biggestClear = cells
    }

    fun recordToss(count: Int = 1) {
        if (count <= 0) return
        totalTosses += count
    }

    fun recordSpill() {
        totalSpills++
    }

    fun recordLevel(level: Int) {
        if (level > bestLevel) bestLevel = level
    }

    fun recordScore(score: Int) {
        if (score > bestScore) bestScore = score
    }

    fun recordRun(score: Int, level: Int) {
        runsPlayed++
        recordScore(score)
        recordLevel(level)
    }

    /** Unlocks every achievement whose condition now holds and returns only the new ones. */
    fun check(): List<Achievement> {
        val fresh = ArrayList<Achievement>()
        for (achievement in Achievement.values()) {
            if (achievement in unlocked) continue
            if (!achievement.earned(this)) continue
            unlocked.add(achievement)
            fresh.add(achievement)
        }
        return fresh
    }

    fun serialize(): String = buildString {
        append("runs=").append(runsPlayed)
        append(";bestScore=").append(bestScore)
        append(";bestLevel=").append(bestLevel)
        append(";biggestClear=").append(biggestClear)
        append(";cleared=").append(totalCleared)
        append(";tosses=").append(totalTosses)
        append(";spills=").append(totalSpills)
        append(";ach=").append(unlocked.joinToString(",") { it.name })
    }

    fun load(raw: String?) {
        runsPlayed = 0
        bestScore = 0
        bestLevel = 1
        biggestClear = 0
        totalCleared = 0
        totalTosses = 0
        totalSpills = 0
        unlocked.clear()
        if (raw.isNullOrBlank()) return
        for (part in raw.split(';')) {
            val split = part.indexOf('=')
            if (split <= 0) continue
            val value = part.substring(split + 1)
            when (part.substring(0, split)) {
                "runs" -> runsPlayed = value.toIntOrNull()?.coerceAtLeast(0) ?: runsPlayed
                "bestScore" -> bestScore = value.toIntOrNull()?.coerceAtLeast(0) ?: bestScore
                "bestLevel" -> bestLevel = value.toIntOrNull()?.coerceAtLeast(1) ?: bestLevel
                "biggestClear" -> biggestClear = value.toIntOrNull()?.coerceAtLeast(0) ?: biggestClear
                "cleared" -> totalCleared = value.toIntOrNull()?.coerceAtLeast(0) ?: totalCleared
                "tosses" -> totalTosses = value.toIntOrNull()?.coerceAtLeast(0) ?: totalTosses
                "spills" -> totalSpills = value.toIntOrNull()?.coerceAtLeast(0) ?: totalSpills
                "ach" -> {
                    for (name in value.split(',')) {
                        Achievement.values().firstOrNull { it.name == name }?.let { unlocked.add(it) }
                    }
                }
            }
        }
    }
}
