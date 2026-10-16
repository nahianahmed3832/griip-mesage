package com.griipbuddy.message.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.3: late joiners ask for a missing call key, paced; the creator answers each call at most every interval. */
class CallKeyRequestsTest {
    private var clock = 0L
    private val r = CallKeyRequests(now = { clock }, graceMs = 1_500, intervalMs = 5_000, maxCalls = 2)

    @Test
    fun aKeyThatIsJustInFlightIsNotRequested() {
        assertFalse(r.shouldRequest("call-1")) // first look: the original key may still be arriving
        clock = 1_000
        assertFalse(r.shouldRequest("call-1"))
        clock = 1_500
        assertTrue(r.shouldRequest("call-1"))
    }

    @Test
    fun requestsAreRepeatedAtMostEveryInterval() {
        r.shouldRequest("call-1")
        clock = 1_500
        var sent = 0
        // waitForKey polls every 250 ms for 10 s
        while (clock <= 10_000) {
            if (r.shouldRequest("call-1")) sent++
            clock += 250
        }
        assertEquals(2, sent) // at 1.5 s and 6.5 s
    }

    @Test
    fun theCreatorAnswersEachCallAtMostEveryInterval() {
        assertTrue(r.shouldAnswer("call-1"))
        assertFalse("a second device asking at once gets the same re-send", r.shouldAnswer("call-1"))
        assertTrue("other calls are paced separately", r.shouldAnswer("call-2"))
        clock = 5_000
        assertTrue(r.shouldAnswer("call-1"))
    }

    @Test
    fun stateStaysBounded() {
        r.shouldRequest("call-1")
        r.shouldRequest("call-2")
        r.shouldRequest("call-3") // evicts call-1's first miss
        clock = 1_500
        assertFalse("call-1 starts its grace period again", r.shouldRequest("call-1"))
    }
}
