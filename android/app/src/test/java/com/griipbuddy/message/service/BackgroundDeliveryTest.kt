package com.griipbuddy.message.service

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
}
