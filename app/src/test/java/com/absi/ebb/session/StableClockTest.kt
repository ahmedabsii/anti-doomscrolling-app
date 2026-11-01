package com.absi.ebb.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StableClockTest {

    @Test
    fun `changing the phone clock within a boot does not move time`() {
        val anchor = StableClock.anchor(stored = null, bootCount = 3, wallMs = 10_000, elapsedMs = 1_000)

        val afterClockChange = StableClock.anchor(
            stored = anchor,
            bootCount = 3,
            wallMs = 99_999_999,
            elapsedMs = 2_000,
        )

        assertEquals(anchor, afterClockChange)
        assertEquals(11_000, StableClock.now(afterClockChange, elapsedMs = 2_000))
    }

    @Test
    fun `a restart uses real time again`() {
        val beforeRestart = StableClock.anchor(stored = null, bootCount = 3, wallMs = 10_000, elapsedMs = 1_000)

        val afterRestart = StableClock.anchor(
            stored = beforeRestart,
            bootCount = 4,
            wallMs = 500_000,
            elapsedMs = 2_000,
        )

        assertEquals(500_000, StableClock.now(afterRestart, elapsedMs = 2_000))
    }
}
