package com.meldiq.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDeliveryTest {
    @Test
    fun runsOnlyForASignedInUserWhoLeftItOn() {
        assertTrue(BackgroundDelivery.shouldRun(signedIn = true, enabled = true))
        assertFalse(BackgroundDelivery.shouldRun(signedIn = true, enabled = false))
        assertFalse("never after sign-out, e.g. a reboot after erasing", BackgroundDelivery.shouldRun(signedIn = false, enabled = true))
    }

    @Test
    fun pushEventsGoToTheActiveSignedInProfileOnly() {
        assertEquals(PushEvents.Route.Handle, PushEvents.route("p1", activeProfile = "p1", signedIn = true))
        assertEquals("another profile's registration is ended", PushEvents.Route.EndStale, PushEvents.route("p2", activeProfile = "p1", signedIn = true))
        assertEquals(PushEvents.Route.EndStale, PushEvents.route("p2", activeProfile = "p1", signedIn = false))
        assertEquals("signed out: nothing to fetch with", PushEvents.Route.Ignore, PushEvents.route("p1", activeProfile = "p1", signedIn = false))
        assertEquals(PushEvents.Route.Ignore, PushEvents.route("p1", activeProfile = null, signedIn = false))
    }
}
