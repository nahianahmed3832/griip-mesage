package com.meldiq.android.data.model

import com.meldiq.android.data.api.CallAttendanceDto
import com.meldiq.android.data.api.CallDto
import com.meldiq.android.data.api.GriipJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call history lines (2026-10-17, docs/CALL_HISTORY.md). */
class CallHistoryTest {
    private fun call(
        by: String = "ann", type: String = "DM", state: String = "ENDED", answered: Long? = null, ended: Long? = 200_000,
        reason: String? = "hangup", attended: List<CallAttendanceDto>? = null, video: Boolean = false,
    ) = CallDto(
        id = "call_1", conversationId = "c1", conversationType = type, createdBy = by, video = video, state = state, createdAt = 100_000,
        answeredAt = answered, endedAt = if (state == "ENDED") ended else null, endReason = if (state == "ENDED") reason else null, attended = attended,
    )
    private fun at(user: String, sec: Long) = CallAttendanceDto(user, 100_000, 100_000 + sec * 1000, sec)

    @Test
    fun oneToOneOutcomesForTheCallerAndTheCallee() {
        val done = call(answered = 110_000, ended = 235_000)
        assertEquals(CallInfo("call_1", false, CallHistory.COMPLETED, 125, "ann"), CallHistory.summarize(done, "ann"))
        assertEquals(125L, CallHistory.summarize(done, "bob").durationSec)
        // Not answered: what the caller and the callee each see.
        assertEquals(CallHistory.CANCELLED, CallHistory.summarize(call(reason = "hangup"), "ann").outcome)
        assertEquals(CallHistory.MISSED, CallHistory.summarize(call(reason = "hangup"), "bob").outcome)
        assertEquals(CallHistory.NO_ANSWER, CallHistory.summarize(call(reason = "missed"), "ann").outcome)
        assertEquals(CallHistory.MISSED, CallHistory.summarize(call(reason = "missed"), "bob").outcome)
        assertEquals(CallHistory.DECLINED, CallHistory.summarize(call(reason = "declined"), "ann").outcome)
        assertEquals(CallHistory.DECLINED, CallHistory.summarize(call(reason = "declined"), "bob").outcome)
        assertEquals(CallHistory.BUSY, CallHistory.summarize(call(reason = "busy"), "ann").outcome)
        assertEquals(CallHistory.MISSED, CallHistory.summarize(call(reason = "busy"), "bob").outcome)
        assertEquals(CallHistory.FAILED, CallHistory.summarize(call(reason = "error"), "ann").outcome)
        assertEquals(CallHistory.ONGOING, CallHistory.summarize(call(state = "ACTIVE", answered = 110_000), "bob").outcome)
    }

    @Test
    fun aDurationIsNeverInventedOrNegative() {
        assertNull(CallHistory.summarize(call(reason = "declined"), "ann").durationSec) // not answered
        assertNull(CallHistory.summarize(call(answered = 110_000, ended = null), "ann").durationSec) // no end recorded
        assertNull(CallHistory.summarize(call(state = "ACTIVE", answered = 110_000), "ann").durationSec)
        assertEquals(0L, CallHistory.summarize(call(answered = 300_000, ended = 200_000), "ann").durationSec) // clock skew
    }

    @Test
    fun groupCallsUseWhoConnected() {
        val attended = listOf(at("ann", 600), at("bob", 90))
        val g = call(type = "GROUP", answered = 105_000, reason = "empty", attended = attended)
        val bob = CallHistory.summarize(g, "bob")
        assertEquals(CallHistory.COMPLETED, bob.outcome)
        assertEquals(90L, bob.durationSec) // my own time, not the call's
        assertEquals(2, bob.joined)
        assertTrue(bob.group)
        assertEquals(CallHistory.MISSED, CallHistory.summarize(g, "carol").outcome)
        assertEquals(CallHistory.DECLINED, CallHistory.summarize(g, "carol", declinedHere = true).outcome)
        // Nobody else came.
        val alone = call(type = "GROUP", reason = "empty", attended = listOf(at("ann", 30)))
        assertEquals(CallHistory.NO_ANSWER, CallHistory.summarize(alone, "ann").outcome)
        assertEquals(CallHistory.MISSED, CallHistory.summarize(alone, "bob").outcome)
        // An older server doesn't say who connected: "ended", never "missed".
        val old = CallHistory.summarize(call(type = "GROUP", answered = 105_000, attended = null), "bob")
        assertEquals(CallHistory.ENDED, old.outcome)
        assertNull(old.durationSec)
    }

    @Test
    fun linesSayWhatHappened() {
        val l = { info: CallInfo, out: Boolean -> CallHistory.line(info, out, "Ann") }
        assertEquals(CallHistory.Line("Outgoing voice call", "2 min 5 s", false), l(CallInfo("x", false, CallHistory.COMPLETED, 125), true))
        assertEquals("Missed video call", l(CallInfo("x", true, CallHistory.MISSED), false).title)
        assertTrue(l(CallInfo("x", true, CallHistory.MISSED), false).missed)
        assertFalse(l(CallInfo("x", true, CallHistory.DECLINED), false).missed)
        assertEquals("You declined a voice call", l(CallInfo("x", false, CallHistory.DECLINED), false).title)
        assertEquals("Voice call declined", l(CallInfo("x", false, CallHistory.DECLINED), true).title)
        val group = l(CallInfo("x", true, CallHistory.COMPLETED, 3_725, "ann", joined = 3, group = true), false)
        assertEquals(CallHistory.Line("Group video call", "1 h 2 min · 3 people joined · Started by Ann", false), group)
        assertEquals("Missed group voice call", l(CallInfo("x", false, CallHistory.MISSED, group = true), false).title)
        assertEquals("Incoming voice call", l(CallInfo("x", false), false).title) // an entry from before call history
        assertEquals("📞 Missed voice call", CallHistory.preview(CallInfo("x", false, CallHistory.MISSED), false, "Voice call"))
        assertEquals("📞 Voice call", CallHistory.preview(null, false, "Voice call"))
        assertEquals("45 s", CallHistory.duration(45))
        assertEquals("3 min", CallHistory.duration(180))
        assertEquals("2 h", CallHistory.duration(7_200))
    }

    @Test
    fun storedEntriesStayReadable() {
        // Entries written before call history (two fields) still decode; new ones round-trip.
        val old = GriipJson.decodeFromString(CallInfo.serializer(), """{"callId":"c","video":true}""")
        assertEquals(CallInfo("c", true), old)
        val now = CallInfo("c", false, CallHistory.COMPLETED, 12, "ann", 2, true)
        assertEquals(now, GriipJson.decodeFromString(CallInfo.serializer(), GriipJson.encodeToString(CallInfo.serializer(), now)))
        // A server's ended call without attendance (older) and with it.
        val dto = GriipJson.decodeFromString(CallDto.serializer(), """{"id":"a","conversationId":"c","createdBy":"u","video":false,"state":"ENDED","createdAt":1}""")
        assertNull(dto.attended)
    }
}
