package com.edens.marblemaster.game

import java.util.Calendar

/**
 * The daily challenge: one seeded board per calendar day, with a streak for finishing it.
 *
 * Pure Kotlin so it can be tested on the JVM. The view supplies the date keys from the device
 * clock and keeps the serialized form in SharedPreferences.
 */
class DailyProgress {

    /** Date key (`yyyy-MM-dd`) of the last day a daily run was finished; empty when never played. */
    var lastDate = ""
        private set

    /** Best score recorded on [lastDate]. */
    var todayBest = 0
        private set

    /** Date of the day before [lastDate], and the best score recorded on it. */
    var previousDate = ""
        private set
    var previousBest = 0
        private set

    var bestEver = 0
        private set

    /** Consecutive days finished, counting back from [lastDate]. */
    var streak = 0
        private set

    var bestStreak = 0
        private set

    /** True when a daily run has already been finished on [dateKey]. */
    fun playedOn(dateKey: String): Boolean = dateKey.isNotEmpty() && lastDate == dateKey

    /**
     * Streak as it should read today: the stored run only counts while it is today's or
     * yesterday's, so a missed day makes the streak lapse.
     */
    fun currentStreak(todayKey: String, yesterdayKey: String): Int = when (lastDate) {
        todayKey, yesterdayKey -> streak
        else -> 0
    }

    /** Yesterday's best, or 0 when yesterday was not played. */
    fun scoreOnPreviousDay(yesterdayKey: String): Int =
        if (previousDate.isNotEmpty() && previousDate == yesterdayKey) previousBest else 0

    /**
     * Files a finished daily run. Returns true when this is the first finish on that day, so the
     * view can call out the streak; replaying the same board only raises the day's best.
     */
    fun record(dateKey: String, yesterdayKey: String, score: Int): Boolean {
        if (dateKey.isEmpty()) return false
        val firstToday = lastDate != dateKey
        if (firstToday) {
            if (lastDate.isNotEmpty()) {
                previousDate = lastDate
                previousBest = todayBest
            }
            streak = if (lastDate == yesterdayKey) streak + 1 else 1
            bestStreak = maxOf(bestStreak, streak)
            todayBest = score
        } else if (score > todayBest) {
            todayBest = score
        }
        bestEver = maxOf(bestEver, score)
        lastDate = dateKey
        return firstToday
    }

    fun serialize(): String = buildString {
        append("date=").append(lastDate)
        append(";today=").append(todayBest)
        append(";ever=").append(bestEver)
        append(";streak=").append(streak)
        append(";bestStreak=").append(bestStreak)
        append(";prevDate=").append(previousDate)
        append(";prevBest=").append(previousBest)
    }

    fun load(raw: String?) {
        lastDate = ""
        todayBest = 0
        bestEver = 0
        streak = 0
        bestStreak = 0
        previousDate = ""
        previousBest = 0
        if (raw.isNullOrBlank()) return
        for (part in raw.split(';')) {
            val split = part.indexOf('=')
            if (split <= 0) continue
            val value = part.substring(split + 1)
            when (part.substring(0, split)) {
                "date" -> lastDate = value.filter { it.isDigit() || it == '-' }
                "today" -> todayBest = value.toIntOrNull()?.coerceAtLeast(0) ?: todayBest
                "ever" -> bestEver = value.toIntOrNull()?.coerceAtLeast(0) ?: bestEver
                "streak" -> streak = value.toIntOrNull()?.coerceAtLeast(0) ?: streak
                "bestStreak" -> bestStreak = value.toIntOrNull()?.coerceAtLeast(0) ?: bestStreak
                "prevDate" -> previousDate = value.filter { it.isDigit() || it == '-' }
                "prevBest" -> previousBest = value.toIntOrNull()?.coerceAtLeast(0) ?: previousBest
            }
        }
    }

    companion object {
        /**
         * Stable FNV-1a hash of the date key: every install that plays on the same day gets the
         * same board, queue and objectives, without any network or account.
         */
        fun seedFor(dateKey: String): Long {
            var hash = -3750763034362895579L
            for (ch in dateKey) {
                hash = hash xor ch.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }

        /** Milliseconds until local midnight, when the next daily board unlocks. */
        fun millisUntilNextBoard(nowMillis: Long): Long {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = nowMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            return (calendar.timeInMillis - nowMillis).coerceAtLeast(0L)
        }

        /** Compact countdown for the panel: `7H 12M`, `42M`, and never `0M`. */
        fun formatCountdown(millis: Long): String {
            val minutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)
            return if (minutes >= 60) "${minutes / 60}H ${minutes % 60}M" else "${minutes}M"
        }
    }
}
