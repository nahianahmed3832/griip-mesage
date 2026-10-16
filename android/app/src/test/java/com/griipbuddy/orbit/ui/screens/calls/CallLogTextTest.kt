package com.griipbuddy.orbit.ui.screens.calls

import com.griipbuddy.orbit.data.db.CallLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls tab: only what the local call log records (direction, answered, duration). */
class CallLogTextTest {
    private val now = System.currentTimeMillis()
    private fun entry(outgoing: Boolean, answered: Boolean, video: Boolean = false, seconds: Long = 0) = CallLogEntity(
        callId = "k", conversationId = "c", video = video, outgoing = outgoing, startedAt = now,
        answeredAt = if (answered) now + 1_000 else null, endedAt = if (answered) now + 1_000 + seconds * 1000 else now + 30_000,
    )

    @Test
    fun anUnansweredIncomingCallIsMissed() {
        val e = entry(outgoing = false, answered = false)
        assertTrue(CallLogText.missed(e))
        assertTrue(CallLogText.detail(e).startsWith("Missed voice call · "))
    }

    @Test
    fun anUnansweredOutgoingCallIsNotAnsweredNotMissed() {
        val e = entry(outgoing = true, answered = false, video = true)
        assertFalse(CallLogText.missed(e))
        assertTrue(CallLogText.detail(e).startsWith("Not answered video call · "))
    }

    @Test
    fun answeredCallsShowTheirDuration() {
        assertTrue(CallLogText.detail(entry(outgoing = true, answered = true, seconds = 75)).startsWith("Outgoing voice call · 1:15 · "))
        assertTrue(CallLogText.detail(entry(outgoing = false, answered = true, video = true, seconds = 5)).startsWith("Incoming video call · 0:05 · "))
    }

    @Test
    fun aCallWithoutAnEndTimeShowsNoDuration() {
        val e = entry(outgoing = true, answered = true).copy(endedAt = null)
        assertEquals(2, CallLogText.detail(e).split(" · ").size)
    }
}
