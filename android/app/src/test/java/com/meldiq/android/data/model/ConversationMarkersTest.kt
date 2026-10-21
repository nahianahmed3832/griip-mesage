package com.meldiq.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "New messages" line, mentions of you, and Mark unread (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §5). */
class ConversationMarkersTest {
    private fun line(id: String, out: Boolean = false, counts: Boolean = true) = ConversationMarkers.Line(id, out, counts)

    // Newest first, as the conversation lists them.
    private val history = listOf(line("m6"), line("m5", out = true), line("m4"), line("call", counts = false), line("m3"), line("m2"), line("m1"))

    @Test
    fun `the line goes above the oldest unread message, skipping your own and non-counting ones`() {
        assertEquals("m6", ConversationMarkers.firstUnread(history, 1))
        assertEquals("m4", ConversationMarkers.firstUnread(history, 2))
        assertEquals("m3", ConversationMarkers.firstUnread(history, 3))
        assertNull(ConversationMarkers.firstUnread(history, 0))
        // More unread than loaded: no line rather than a wrong one.
        assertNull(ConversationMarkers.firstUnread(history, 50))
    }

    @Test
    fun `a message mentions you by id or with everyone, never your own`() {
        assertTrue(ConversationMarkers.mentionsYou(false, MentionSet(listOf("me"), false), "me"))
        assertTrue(ConversationMarkers.mentionsYou(false, MentionSet(emptyList(), true), "me"))
        assertFalse(ConversationMarkers.mentionsYou(true, MentionSet(listOf("me"), true), "me"))
        assertFalse(ConversationMarkers.mentionsYou(false, MentionSet(listOf("you"), false), "me"))
        assertFalse(ConversationMarkers.mentionsYou(false, MentionSet(listOf("me"), false), null))
    }

    @Test
    fun `mark unread counts other people's messages from that one to the newest`() {
        assertEquals(3, ConversationMarkers.unreadFrom(history, "m3"))
        assertEquals(1, ConversationMarkers.unreadFrom(history, "m6"))
        assertEquals(1, ConversationMarkers.unreadFrom(history, "m5"))
        assertEquals(0, ConversationMarkers.unreadFrom(history, "missing"))
    }
}
