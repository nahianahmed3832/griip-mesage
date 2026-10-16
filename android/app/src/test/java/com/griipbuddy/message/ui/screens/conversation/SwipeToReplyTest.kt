package com.griipbuddy.message.ui.screens.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeToReplyTest {
    private val t = 100f
    private val max = 150f

    @Test
    fun followsTheFingerUpToTheThresholdThenResists() {
        assertEquals(0f, SwipeReply.offsetFor(-40f, t, max)) // the other way does nothing
        assertEquals(60f, SwipeReply.offsetFor(60f, t, max))
        assertEquals(100f, SwipeReply.offsetFor(100f, t, max))
        assertEquals(135f, SwipeReply.offsetFor(200f, t, max), 0.01f) // 100 + 100 × 0.35
        assertEquals(150f, SwipeReply.offsetFor(10_000f, t, max)) // capped
    }

    @Test
    fun repliesOnlyPastTheThreshold() {
        assertFalse(SwipeReply.triggers(99f, t)) // a short or cancelled swipe sets nothing
        assertTrue(SwipeReply.triggers(100f, t))
        assertEquals(0.5f, SwipeReply.progress(50f, t))
        assertEquals(1f, SwipeReply.progress(140f, t))
    }
}
