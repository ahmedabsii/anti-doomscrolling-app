package com.absi.ebb.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramScreensTest {

    private data class Recorded(val chats: Boolean, val screen: String, val ids: Set<String>)

    // Screens recorded on a real phone. Only view names are in the file, never any text.
    private val recorded = javaClass.getResource("/instagram-454-screens.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (expected, screen, ids) = line.split(" | ")
            Recorded(expected == "chats", screen, ids.split(" ").toSet())
        }

    @Test
    fun `every recorded screen is told apart correctly`() {
        assertEquals(9, recorded.size)
        recorded.forEach { (chats, screen, ids) ->
            assertEquals(screen, chats, InstagramScreens.isChats { it in ids })
        }
    }

    @Test
    fun `a reel is never a chat, even with a conversation behind it`() {
        val chat = recorded.first { it.screen == "chat" }.ids
        assertFalse(InstagramScreens.isChats { it in chat + "clips_viewer_view_pager" })
    }

    @Test
    fun `a screen with no known views counts as not a chat`() {
        assertFalse(InstagramScreens.isChats { false })
        assertTrue(InstagramScreens.isChats { it == "direct_thread_header" })
    }
}
