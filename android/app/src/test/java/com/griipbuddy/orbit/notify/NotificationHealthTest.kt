package com.griipbuddy.orbit.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical-device report (2026-10-16): notification problems on both phones, including no sound. */
class NotificationHealthTest {
    private val healthy = listOf(
        NotificationHealth.ChannelState(Channels.MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH, hasSound = true),
        NotificationHealth.ChannelState(Channels.MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH, hasSound = true),
        NotificationHealth.ChannelState(Channels.INCOMING_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH, hasSound = true),
    )
    private fun state(
        permission: Boolean = true, enabled: Boolean = true, channels: List<NotificationHealth.ChannelState> = healthy,
        battery: Boolean? = false, inApp: Boolean = true, background: Boolean = true,
    ) = NotificationHealth.State(34, permission, enabled, channels, battery, inApp, background)

    @Test
    fun aHealthySetupReportsNothing() = assertTrue(NotificationHealth.problems(state()).isEmpty())

    @Test
    fun deniedPermissionIsReportedAloneWithALinkToAppSettings() {
        val p = NotificationHealth.problems(state(permission = false, channels = emptyList(), background = false))
        assertEquals(1, p.size)
        assertEquals(NotificationHealth.Fix.AppNotificationSettings, p.single().fix)
        assertEquals(NotificationHealth.Fix.AppNotificationSettings, NotificationHealth.problems(state(enabled = false)).single().fix)
    }

    @Test
    fun silentAndBlockedChannelsAreToldApart() {
        val channels = listOf(
            healthy[0].copy(hasSound = false), // the phone maker or the person removed the sound
            healthy[1].copy(importance = NotificationManager.IMPORTANCE_NONE),
            healthy[2].copy(importance = NotificationManager.IMPORTANCE_LOW),
        )
        val p = NotificationHealth.problems(state(channels = channels))
        assertEquals(listOf(Channels.MESSAGES, Channels.MENTIONS, Channels.INCOMING_CALL), p.map { it.channelId })
        assertTrue(p[0].title.contains("silent"))
        assertTrue(p[1].title.contains("blocked"))
        assertTrue(p[2].detail.contains("pop-up"))
        assertTrue(p.all { it.fix == NotificationHealth.Fix.ChannelSettings })
    }

    @Test
    fun deliveryProblemsAreSeparateFromDisplayAndSound() {
        val off = NotificationHealth.problems(state(background = false, battery = true)).single()
        assertEquals(NotificationHealth.Fix.InAppSetting, off.fix)
        assertTrue(off.title.contains("Background connection"))
        val battery = NotificationHealth.problems(state(battery = true)).single()
        assertEquals(NotificationHealth.Fix.BatterySettings, battery.fix)
        assertFalse(battery.severe)
        assertFalse(NotificationHealth.problems(state(inApp = false)).single().severe)
    }

    @Test
    fun activeWakeupsReplaceTheBackgroundConnectionChecks() {
        assertTrue(NotificationHealth.problems(state(background = false).copy(pushWakeups = true)).isEmpty())
        assertTrue(NotificationHealth.problems(state(battery = true).copy(pushWakeups = true)).isEmpty())
        // Showing notifications still matters with wake-ups.
        assertEquals(
            NotificationHealth.Fix.AppNotificationSettings,
            NotificationHealth.problems(state(permission = false, background = false).copy(pushWakeups = true)).single().fix,
        )
    }
}
