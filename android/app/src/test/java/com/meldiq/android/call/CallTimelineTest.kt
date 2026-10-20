package com.meldiq.android.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallTimelineTest {
    @Test
    fun `stages are timed from the start, once each`() {
        var now = 1_000L
        val t = CallTimeline { now }
        assertNull(t.mark("call created")) // nothing before begin
        t.begin("caller")
        now = 1_180
        assertEquals(180L, t.mark("call created"))
        now = 1_900
        assertEquals(900L, t.mark("media joined"))
        now = 2_500
        assertNull(t.mark("media joined")) // the first time counts
        assertEquals("call timing (caller): call created 180 ms › media joined 900 ms", t.summary())
    }

    @Test
    fun `a new call starts a new timeline`() {
        var now = 0L
        val t = CallTimeline { now }
        now = 10
        t.begin("callee")
        now = 50
        t.mark("accepted")
        now = 100
        t.begin("caller")
        assertNull(t.elapsed("accepted"))
        now = 130
        assertEquals(30L, t.mark("call created"))
    }
}
