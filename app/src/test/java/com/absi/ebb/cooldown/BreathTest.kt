package com.absi.ebb.cooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathTest {

    @Test
    fun `the breath is full after four seconds and empty after ten`() {
        assertEquals(0f, breathLevel(0), 0.001f)
        assertEquals(1f, breathLevel(4_000), 0.001f)
        assertEquals(0f, breathLevel(10_000), 0.001f)
    }

    @Test
    fun `the out breath takes longer than the in breath`() {
        assertTrue(isBreathingIn(3_999))
        assertFalse(isBreathingIn(4_000))
        assertFalse(isBreathingIn(9_999))
        assertTrue(isBreathingIn(10_000))
    }

    @Test
    fun `the breath only ever moves one way in each half`() {
        val rising = (0L..4_000L step 100).map(::breathLevel)
        val falling = (4_000L..10_000L step 100).map(::breathLevel)
        assertEquals(rising.sorted(), rising)
        assertEquals(falling.sortedDescending(), falling)
    }
}
