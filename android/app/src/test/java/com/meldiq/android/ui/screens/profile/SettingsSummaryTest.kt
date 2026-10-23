package com.meldiq.android.ui.screens.profile

import com.meldiq.android.ui.screens.profile.SettingsSummary.Notifications
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryTest {
    @Test
    fun `notifications summary says what actually happens, Android's block first`() {
        assertEquals(Notifications.BLOCKED, SettingsSummary.notifications(enabledInApp = true, allowedBySystem = false, pausedUntil = null, now = 0))
        assertEquals(Notifications.OFF, SettingsSummary.notifications(enabledInApp = false, allowedBySystem = true, pausedUntil = null, now = 0))
        assertEquals(Notifications.PAUSED, SettingsSummary.notifications(enabledInApp = true, allowedBySystem = true, pausedUntil = 100, now = 50))
        assertEquals(Notifications.ON, SettingsSummary.notifications(enabledInApp = true, allowedBySystem = true, pausedUntil = 100, now = 100))
        assertEquals(Notifications.ON, SettingsSummary.notifications(enabledInApp = true, allowedBySystem = true, pausedUntil = null, now = 0))
    }
}
