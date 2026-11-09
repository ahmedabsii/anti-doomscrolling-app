package com.absi.ebb.progress

import kotlin.math.roundToInt

/** How wins, streaks and time reclaimed are worked out, with no Android code. */
object ProgressRules {

    private const val DAY_MS = 24 * 60 * 60_000L
    private const val WINDOW_DAYS = 7
    private const val MIN_HISTORY_DAYS = 3
    private const val MIN_SAVED_MS = 5 * 60_000L
    private const val WIN_PERCENT = 85
    private val MILESTONES = setOf(3, 7, 14, 30)

    data class Streak(val current: Int, val best: Int)

    /** The local day, from midnight to midnight. Its number counts days since 1 January 1970, like LocalDate.toEpochDay. */
    fun dayOf(wallMs: Long, zoneOffsetMs: Long): Long =
        Math.floorDiv(wallMs + zoneOffsetMs, DAY_MS)

    fun usualMs(earlierDays: List<Long>): Long {
        val window = earlierDays.takeLast(WINDOW_DAYS)
        return if (window.isEmpty()) 0 else window.sum() / window.size
    }

    fun reclaimedMs(dayMs: Long, earlierDays: List<Long>): Long =
        (usualMs(earlierDays) - dayMs).coerceAtLeast(0)

    fun isDailyWin(dayMs: Long, earlierDays: List<Long>): Boolean {
        if (earlierDays.size < MIN_HISTORY_DAYS) return false
        val usual = usualMs(earlierDays)
        return usual - dayMs >= MIN_SAVED_MS && dayMs * 100 <= usual * WIN_PERCENT
    }

    fun streak(winDays: Set<Long>, lastDay: Long): Streak {
        var current = 0
        while (lastDay - current in winDays) current++
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in winDays.sorted()) {
            run = if (previous == day - 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return Streak(current, best)
    }

    fun isMilestone(days: Int): Boolean = days in MILESTONES

    fun percentLess(nowMs: Long, beforeMs: Long): Int =
        if (beforeMs <= 0) 0 else ((beforeMs - nowMs) * 100.0 / beforeMs).roundToInt()
}
