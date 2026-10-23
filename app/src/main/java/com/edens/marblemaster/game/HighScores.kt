package com.edens.marblemaster.game

/** One completed run in the local high score table. */
data class HighScore(val score: Int, val timestamp: Long)

/**
 * Top-N list of the player's own runs, kept on the device.
 *
 * Pure Kotlin: the caller loads the table from storage with [load] and writes it
 * back with [serialize]. A score of 0 or less is never recorded, and ties keep the
 * older run ahead of the newer one.
 */
class HighScoreTable(val capacity: Int = MAX_ENTRIES) {

    private val entries = ArrayList<HighScore>()

    /** Scores in descending order, best first. */
    val scores: List<HighScore> get() = entries

    /** Highest recorded score, or 0 when nothing has been recorded yet. */
    fun best(): Int = entries.firstOrNull()?.score ?: 0

    /**
     * Adds a finished run and returns its 1-based rank, or 0 when the score did
     * not make the table.
     */
    fun record(score: Int, timestamp: Long): Int {
        if (score <= 0 || capacity <= 0) return 0
        var index = 0
        while (index < entries.size && entries[index].score >= score) index++
        if (index >= capacity) return 0
        entries.add(index, HighScore(score, timestamp))
        while (entries.size > capacity) entries.removeAt(entries.size - 1)
        return index + 1
    }

    /** Restores a table written by [serialize]; malformed entries are skipped. */
    fun load(raw: String?) {
        entries.clear()
        if (raw.isNullOrBlank()) return
        for (part in raw.split(ENTRY_SEPARATOR)) {
            val fields = part.split(FIELD_SEPARATOR)
            val score = fields.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            if (score <= 0) continue
            val timestamp = fields.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            entries.add(HighScore(score, timestamp))
        }
        entries.sortByDescending { it.score }
        while (entries.size > capacity) entries.removeAt(entries.size - 1)
    }

    /** Compact storage form: `score,timestamp;score,timestamp;...` */
    fun serialize(): String = entries.joinToString(ENTRY_SEPARATOR) {
        "${it.score}$FIELD_SEPARATOR${it.timestamp}"
    }

    companion object {
        const val MAX_ENTRIES = 10

        private const val ENTRY_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ","
    }
}
