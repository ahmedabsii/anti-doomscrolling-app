package com.absi.ebb.cooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class BlockMessagesTest {

    private val bank = listOf(BlockMessage("One"), BlockMessage("Two"), BlockMessage("Three", "Seneca", "Letters"))

    @Test
    fun `the same message never appears twice in a row`() {
        val random = Random(7)
        var previous: String? = null
        repeat(1000) {
            val next = BlockMessages.pick(bank, previous, random).text
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun `a bank of one still shows its message`() {
        assertEquals("One", BlockMessages.pick(listOf(BlockMessage("One")), "One").text)
    }
}
