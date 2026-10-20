package com.meldiq.android.ui

import com.meldiq.android.ui.screens.workspace.meetings.MeetingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRulesTest {
    @Test
    fun `local date and time become UTC in the chosen zone, DST included`() {
        // 09:00 in Dhaka (UTC+6) is 03:00 UTC.
        assertEquals(1_792_465_200_000L, MeetingRules.startMs("2026-10-20", "09:00", "Asia/Dhaka"))
        // London on the last Sunday of October: 10:00 is BST before the change, GMT after.
        val before = MeetingRules.startMs("2026-10-24", "10:00", "Europe/London")!!
        val after = MeetingRules.startMs("2026-10-26", "10:00", "Europe/London")!!
        assertEquals(2 * 86_400_000L + 3_600_000L, after - before)
        assertNull(MeetingRules.startMs("2026-10-20", "9am", "UTC"))
        assertNull(MeetingRules.startMs("20/10/2026", "09:00", "UTC"))
    }

    @Test
    fun `the next slot is the next half hour`() {
        val now = MeetingRules.startMs("2026-10-20", "14:10", "UTC")!!
        assertEquals("2026-10-20" to "14:30", MeetingRules.nextSlot(now, "UTC"))
        assertEquals("2026-10-21" to "00:00", MeetingRules.nextSlot(MeetingRules.startMs("2026-10-20", "23:45", "UTC")!!, "UTC"))
    }

    @Test
    fun `problems and fields round-trip`() {
        val now = MeetingRules.startMs("2026-10-20", "14:10", "UTC")!!
        assertEquals(listOf("Give the meeting a title", "Pick a voice room", "Pick a time that hasn't passed yet"), MeetingRules.problems("", null, "2026-10-19", "10:00", "UTC", now))
        assertTrue(MeetingRules.problems("Sync", "c1", "2026-10-20", "15:00", "UTC", now).isEmpty())
        val start = MeetingRules.startMs("2026-10-20", "15:00", "UTC")!!
        assertEquals(Triple("2026-10-20", "15:00", 45), MeetingRules.fields(start, start + 45 * 60_000, "UTC"))
    }
}
