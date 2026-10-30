package com.edens.marblemaster.game

/**
 * Milestones a player unlocks by playing across runs.
 *
 * Every group has several tiers, so a goal that is met once still has a next rung: [group] is
 * the family shown in the stats panel, [tier] its stage (1-based), and [title] the name the
 * unlock popup uses ("Century", "Century II", ...).
 */
enum class Achievement(
    val group: String,
    val tier: Int,
    val earned: (PlayerProgress) -> Boolean
) {
    FIRST_RUN("First Swing", 1, { it.runsPlayed >= 1 }),

    BLAST_OFF("Blast Off", 1, { it.totalTosses >= 1 }),
    BLAST_OFF_2("Blast Off", 2, { it.totalTosses >= 50 }),
    BLAST_OFF_3("Blast Off", 3, { it.totalTosses >= 250 }),

    BLOB_BUSTER("Blob Buster", 1, { it.biggestClear >= 8 }),
    BLOB_BUSTER_2("Blob Buster", 2, { it.biggestClear >= 12 }),
    BLOB_BUSTER_3("Blob Buster", 3, { it.biggestClear >= 20 }),

    HIGH_CLIMBER("High Climber", 1, { it.bestLevel >= 5 }),
    HIGH_CLIMBER_2("High Climber", 2, { it.bestLevel >= 10 }),
    HIGH_CLIMBER_3("High Climber", 3, { it.bestLevel >= 15 }),

    CENTURY("Century", 1, { it.totalCleared >= 100 }),
    CENTURY_2("Century", 2, { it.totalCleared >= 500 }),
    CENTURY_3("Century", 3, { it.totalCleared >= 2000 }),

    POINT_HOARDER("Point Hoarder", 1, { it.bestScore >= 1000 }),
    POINT_HOARDER_2("Point Hoarder", 2, { it.bestScore >= 5000 }),
    POINT_HOARDER_3("Point Hoarder", 3, { it.bestScore >= 15000 }),

    STREAK_KEEPER("Streak Keeper", 1, { it.bestDailyStreak >= 3 }),
    STREAK_KEEPER_2("Streak Keeper", 2, { it.bestDailyStreak >= 7 }),
    STREAK_KEEPER_3("Streak Keeper", 3, { it.bestDailyStreak >= 30 });

    /** Display name for this tier: the group name, with a roman numeral above tier one. */
    val title: String get() = if (tier == 1) group else "$group ${"I".repeat(tier)}"

    companion object {
        /** Achievements grouped by family, in declaration order and ordered by tier. */
        val groups: List<List<Achievement>> by lazy {
            values().groupBy { it.group }.values.map { tiers -> tiers.sortedBy { it.tier } }
        }
    }
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

    /** Longest run of consecutive daily boards finished, mirrored from the daily panel. */
    var bestDailyStreak = 0
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

    fun recordDailyStreak(days: Int) {
        if (days > bestDailyStreak) bestDailyStreak = days
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
        append(";dailyStreak=").append(bestDailyStreak)
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
        bestDailyStreak = 0
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
                "dailyStreak" -> bestDailyStreak =
                    value.toIntOrNull()?.coerceAtLeast(0) ?: bestDailyStreak
                "ach" -> {
                    for (name in value.split(',')) {
                        Achievement.values().firstOrNull { it.name == name }?.let { unlocked.add(it) }
                    }
                }
            }
        }
    }
}
