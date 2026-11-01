package com.absi.ebb.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

class ProgressRulesTest {

    @Test
    fun `a day runs from 4 am to 4 am`() {
        assertEquals(ProgressRules.dayOf(10 * DAY + 4 * HOUR, 0), ProgressRules.dayOf(11 * DAY + 3 * HOUR, 0))
        assertEquals(ProgressRules.dayOf(10 * DAY + 3 * HOUR, 0) + 1, ProgressRules.dayOf(10 * DAY + 4 * HOUR, 0))
    }

    @Test
    fun `the day boundary follows the phone's time zone`() {
        val threeAmLocal = 10 * DAY + 3 * HOUR - 2 * HOUR
        assertEquals(ProgressRules.dayOf(10 * DAY + 3 * HOUR, 0), ProgressRules.dayOf(threeAmLocal, 2 * HOUR))
    }

    @Test
    fun `a daily win needs at least 15 percent and 5 minutes less than average`() {
        val usual = listOf(60 * MIN, 60 * MIN, 60 * MIN)

        assertTrue(ProgressRules.isDailyWin(51 * MIN, usual))
        assertFalse(ProgressRules.isDailyWin(52 * MIN, usual))
        assertFalse("10 minutes down from 12 is under 5 minutes saved", ProgressRules.isDailyWin(8 * MIN, listOf(12 * MIN, 12 * MIN, 12 * MIN)))
    }

    @Test
    fun `a daily win needs three days of history`() {
        assertFalse(ProgressRules.isDailyWin(0, listOf(60 * MIN, 60 * MIN)))
        assertTrue(ProgressRules.isDailyWin(0, listOf(60 * MIN, 60 * MIN, 60 * MIN)))
    }

    @Test
    fun `the average uses only the last seven days`() {
        val history = listOf(600 * MIN) + List(7) { 60 * MIN }

        assertEquals(60 * MIN, ProgressRules.usualMs(history))
    }

    @Test
    fun `reclaimed time is what you saved against your usual and never negative`() {
        val usual = listOf(60 * MIN, 60 * MIN, 60 * MIN)

        assertEquals(18 * MIN, ProgressRules.reclaimedMs(42 * MIN, usual))
        assertEquals(0, ProgressRules.reclaimedMs(90 * MIN, usual))
        assertEquals(0, ProgressRules.reclaimedMs(10 * MIN, emptyList()))
    }

    @Test
    fun `a streak counts consecutive days up to the last finished day`() {
        val streak = ProgressRules.streak(setOf(3, 4, 5, 8, 9), lastDay = 9)

        assertEquals(2, streak.current)
        assertEquals(3, streak.best)
    }

    @Test
    fun `a missed day ends the current streak but keeps the best`() {
        val streak = ProgressRules.streak(setOf(3, 4, 5), lastDay = 7)

        assertEquals(0, streak.current)
        assertEquals(3, streak.best)
    }

    @Test
    fun `milestones land on 3, 7, 14 and 30 days`() {
        assertEquals(listOf(3, 7, 14, 30), (1..40).filter(ProgressRules::isMilestone))
    }

    @Test
    fun `percent less rounds and handles no earlier usage`() {
        assertEquals(31, ProgressRules.percentLess(353 * MIN, 510 * MIN))
        assertEquals(0, ProgressRules.percentLess(10 * MIN, 0))
        assertEquals(-8, ProgressRules.percentLess(54 * MIN, 50 * MIN))
    }
}
