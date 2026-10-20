package com.meldiq.android.ui.screens.profile

import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.model.MentionNotify
import com.meldiq.android.data.model.NotifyLevel
import com.meldiq.android.notify.MessageNotifier
import com.meldiq.android.ui.screens.home.ActivityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Round 3: statuses, pausing notifications, Activity filters and the "Nothing" notification level. */
class StatusRulesTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    /** Wednesday 2026-10-21, 15:20 in Dhaka. */
    private val now = ZonedDateTime.of(2026, 10, 21, 15, 20, 0, 0, dhaka).toInstant().toEpochMilli()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, dhaka).toInstant().toEpochMilli()

    @Test
    fun clearAfterChoicesEndAtTheRightTime() {
        assertNull(StatusRules.expiresAt(StatusRules.ClearAfter.NEVER, now, dhaka))
        assertEquals(now + 30 * 60_000, StatusRules.expiresAt(StatusRules.ClearAfter.MINUTES_30, now, dhaka))
        assertEquals(now + 4 * 3_600_000, StatusRules.expiresAt(StatusRules.ClearAfter.HOURS_4, now, dhaka))
        assertEquals(at(2026, 10, 22, 0), StatusRules.expiresAt(StatusRules.ClearAfter.TODAY, now, dhaka))
        // "This week" ends at the start of the next Monday.
        assertEquals(at(2026, 10, 26, 0), StatusRules.expiresAt(StatusRules.ClearAfter.THIS_WEEK, now, dhaka))
    }

    @Test
    fun theReferencePresetsLastAsShown() {
        assertEquals(
            listOf(StatusRules.ClearAfter.HOUR_1, StatusRules.ClearAfter.MINUTES_30, StatusRules.ClearAfter.TODAY, StatusRules.ClearAfter.NEVER, StatusRules.ClearAfter.TODAY),
            StatusRules.PRESETS.map { it.clearAfter },
        )
    }

    @Test
    fun pausingEndsAfterAWhileOrTomorrowMorning() {
        assertEquals(now + 2 * 3_600_000, StatusRules.pauseUntil(StatusRules.Pause.HOURS_2, now, dhaka))
        assertEquals(at(2026, 10, 22, 9), StatusRules.pauseUntil(StatusRules.Pause.TOMORROW_9, now, dhaka))
        assertTrue(StatusRules.paused(now + 1, now))
        assertFalse(StatusRules.paused(now, now))
        assertFalse(StatusRules.paused(null, now))
        assertTrue(MessageNotifier.paused(now + 1, now))
        assertFalse(MessageNotifier.paused(0, now))
    }

    @Test
    fun theStatusLineHidesAnEndedStatus() {
        val u = UserDto("u1", "ada", "Ada", statusText = "In a meeting", statusEmoji = "📅", statusExpiresAt = now + 1000)
        assertEquals("📅 In a meeting", StatusRules.line(u, now))
        assertNull(StatusRules.line(u, now + 1000))
        assertNull(StatusRules.line(UserDto("u2", "bob", "Bob"), now))
        assertEquals("Working remotely", StatusRules.line(UserDto("u3", "cy", "Cy", statusText = "Working remotely"), now))
    }

    @Test
    fun activityChipsShowTheirOwnSection() {
        assertTrue(ActivityRules.shows(ActivityRules.Filter.ALL, ActivityRules.Filter.CALLS))
        assertTrue(ActivityRules.shows(ActivityRules.Filter.MENTIONS, ActivityRules.Filter.MENTIONS))
        assertFalse(ActivityRules.shows(ActivityRules.Filter.MENTIONS, ActivityRules.Filter.THREADS))
        assertTrue(ActivityRules.empty(ActivityRules.Filter.THREADS, mentions = 3, threads = 0, calls = 1))
        assertFalse(ActivityRules.empty(ActivityRules.Filter.ALL, mentions = 0, threads = 0, calls = 1))
    }

    @Test
    fun nothingSilencesEvenAMentionOfYou() {
        assertFalse(MentionNotify.shouldNotify(true, NotifyLevel.NONE, muted = true, mention = true, personal = true))
        assertTrue(MentionNotify.shouldNotify(true, NotifyLevel.MENTIONS, muted = true, mention = true, personal = true))
        assertFalse(MentionNotify.shouldNotify(true, NotifyLevel.MENTIONS, muted = true, mention = true, personal = false))
        assertTrue(MentionNotify.shouldNotify(true, NotifyLevel.ALL, muted = false, mention = false, personal = false))
    }
}
