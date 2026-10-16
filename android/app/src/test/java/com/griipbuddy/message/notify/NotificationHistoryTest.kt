package com.griipbuddy.message.notify

import com.griipbuddy.message.notify.NotificationHistory.Line
import org.junit.Assert.assertEquals
import org.junit.Test

/** T4.4: a notification shows the conversation's recent messages, bounded, and forgets them when cleared. */
class NotificationHistoryTest {
    private val h = NotificationHistory(perConversation = 3, maxConversations = 2)

    @Test
    fun keepsTheLatestFewOldestFirst() {
        for (i in 1..5) h.add("c1", Line("Sam", "m$i", i.toLong()))
        assertEquals(listOf("m4", "m5", "m6"), h.add("c1", Line("Sam", "m6", 6)).map { it.text })
        assertEquals(listOf("m5", "m6", "m7"), h.add("c1", Line("Sam", "m7", 7)).map { it.text })
    }

    @Test
    fun clearingAConversationStartsItAfresh() {
        h.add("c1", Line("Sam", "old", 1))
        h.clear("c1")
        assertEquals(listOf("new"), h.add("c1", Line("Sam", "new", 2)).map { it.text })
    }

    @Test
    fun conversationsAreSeparateAndBounded() {
        h.add("c1", Line("Sam", "a", 1))
        h.add("c2", Line("Ana", "b", 2))
        h.add("c3", Line("Lee", "c", 3)) // evicts c1, the least recently notified
        assertEquals(listOf("a2"), h.add("c1", Line("Sam", "a2", 4)).map { it.text })
        assertEquals(listOf("c", "c2"), h.add("c3", Line("Lee", "c2", 5)).map { it.text })
    }

    @Test
    fun clearAllForgetsEverything() {
        h.add("c1", Line("Sam", "a", 1))
        h.add("c2", Line("Ana", "b", 2))
        h.clearAll()
        assertEquals(listOf("x"), h.add("c2", Line("Ana", "x", 3)).map { it.text })
    }
}
