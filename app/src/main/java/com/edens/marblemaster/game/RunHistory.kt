package com.edens.marblemaster.game

/** One finished run as kept in the local run history. */
data class RunRecord(val score: Int, val level: Int, val timestamp: Long)

/**
 * The last [capacity] finished runs, newest first, so the scores panel can show how a player is
 * progressing rather than only their best ten. Pure Kotlin: the view loads and serializes it.
 */
class RunHistory(val capacity: Int = 20) {

    private val entries = ArrayList<RunRecord>()

    /** Runs newest first. */
    val runs: List<RunRecord> get() = entries

    fun add(score: Int, level: Int, timestamp: Long) {
        if (capacity <= 0) return
        entries.add(0, RunRecord(score.coerceAtLeast(0), level.coerceAtLeast(1), timestamp))
        while (entries.size > capacity) entries.removeAt(entries.size - 1)
    }

    /** Compact storage form: `score,level,timestamp;...` newest first. */
    fun serialize(): String = entries.joinToString(";") {
        "${it.score},${it.level},${it.timestamp}"
    }

    fun load(raw: String?) {
        entries.clear()
        if (raw.isNullOrBlank()) return
        for (part in raw.split(';')) {
            val fields = part.split(',')
            val score = fields.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val level = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            val timestamp = fields.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            entries.add(RunRecord(score.coerceAtLeast(0), level.coerceAtLeast(1), timestamp))
        }
        entries.sortByDescending { it.timestamp }
        while (entries.size > capacity) entries.removeAt(entries.size - 1)
    }
}
