package com.meldiq.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Batch B12: relative times are bucketed before they're put into words (the words come from string resources). */
class TimeFmtTest {
    @Test
    fun `seconds become whole minutes, hours or days`() {
        assertEquals(TimeFmt.Ago.NOW to 0L, TimeFmt.ago(59))
        assertEquals(TimeFmt.Ago.MINUTES to 1L, TimeFmt.ago(60))
        assertEquals(TimeFmt.Ago.MINUTES to 59L, TimeFmt.ago(3599))
        assertEquals(TimeFmt.Ago.HOURS to 1L, TimeFmt.ago(3600))
        assertEquals(TimeFmt.Ago.HOURS to 23L, TimeFmt.ago(86399))
        assertEquals(TimeFmt.Ago.DAYS to 2L, TimeFmt.ago(2 * 86400 + 5))
    }

    @Test
    fun `without the app's resources the English words are used`() {
        assertEquals("a while ago", TimeFmt.relative(null))
        assertEquals("just now", TimeFmt.relative(System.currentTimeMillis() - 5_000))
        assertEquals("3 min ago", TimeFmt.relative(System.currentTimeMillis() - 3 * 60_000 - 1_000))
    }
}
