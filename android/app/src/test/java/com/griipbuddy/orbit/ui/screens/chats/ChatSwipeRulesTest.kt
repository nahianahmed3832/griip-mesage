package com.griipbuddy.orbit.ui.screens.chats

import com.griipbuddy.orbit.ui.screens.chats.ChatSwipe.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSwipeRulesTest {
    private val width = 1080
    private val threshold = ChatSwipe.threshold(width, 264f) // 88 dp at 3x

    @Test
    fun thresholdIsAThirdOfTheRowButNotTooShort() {
        assertEquals(360f, threshold, 0.01f)
        assertEquals(264f, ChatSwipe.threshold(600, 264f), 0.01f)
    }

    @Test
    fun markingReadNeverMovesTheRowOffTheScreen() {
        // Follows the finger up to the threshold, then resists; never past 45% of the row however far the finger goes.
        assertEquals(200f, ChatSwipe.offsetFor(200f, width, threshold), 0.01f)
        assertEquals(threshold, ChatSwipe.offsetFor(threshold, width, threshold), 0.01f)
        val far = ChatSwipe.offsetFor(5000f, width, threshold)
        assertEquals(width * ChatSwipe.READ_MAX_FRACTION, far, 0.01f)
        assertTrue(far < width)
        // Monotonic: more finger never moves the row back.
        var last = 0f
        for (d in 0..3000 step 50) {
            val o = ChatSwipe.offsetFor(d.toFloat(), width, threshold)
            assertTrue(o >= last)
            last = o
        }
    }

    @Test
    fun archivingFollowsTheFingerToTheFullWidth() {
        assertEquals(-300f, ChatSwipe.offsetFor(-300f, width, threshold), 0.01f)
        assertEquals(-width.toFloat(), ChatSwipe.offsetFor(-5000f, width, threshold), 0.01f)
    }

    @Test
    fun releaseDecidesTheAction() {
        assertEquals(Action.ToggleRead, ChatSwipe.resolve(threshold, 0f, threshold))
        assertEquals(Action.ToggleArchive, ChatSwipe.resolve(-threshold, 0f, threshold))
        // A short drag springs back…
        assertEquals(Action.None, ChatSwipe.resolve(100f, 0f, threshold))
        assertEquals(Action.None, ChatSwipe.resolve(-100f, 0f, threshold))
        // …unless it's a quick flick past half the threshold, in the same direction.
        assertEquals(Action.ToggleRead, ChatSwipe.resolve(threshold * 0.6f, 2500f, threshold))
        assertEquals(Action.ToggleArchive, ChatSwipe.resolve(-threshold * 0.6f, -2500f, threshold))
        assertEquals(Action.None, ChatSwipe.resolve(threshold * 0.6f, -2500f, threshold))
        assertEquals(Action.None, ChatSwipe.resolve(threshold * 0.3f, 2500f, threshold))
    }

    @Test
    fun dragsFromTheBackGestureEdgeAreLeftToAndroid() {
        // Gesture navigation: ~ 24 dp edges on both sides.
        assertTrue(ChatSwipe.inSystemEdge(10f, width, 72, 72))
        assertTrue(ChatSwipe.inSystemEdge(1050f, width, 72, 72))
        assertFalse(ChatSwipe.inSystemEdge(200f, width, 72, 72))
        // Button navigation reports no gesture insets: every start counts.
        assertFalse(ChatSwipe.inSystemEdge(0f, width, 0, 0))
    }
}
