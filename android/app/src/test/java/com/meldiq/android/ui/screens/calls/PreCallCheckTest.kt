package com.meldiq.android.ui.screens.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B10: the pre-call microphone meter. */
class PreCallCheckTest {
    @Test
    fun `silence reads zero, a full-scale signal reads one, and the curve lifts quiet speech`() {
        assertEquals(0f, MicMeter.level(ShortArray(100), 100))
        assertEquals(0f, MicMeter.level(ShortArray(0), 0))
        assertEquals(1f, MicMeter.level(ShortArray(100) { Short.MAX_VALUE }, 100), 0.001f)
        val quiet = MicMeter.level(ShortArray(100) { 1000 }, 100)
        assertTrue("quiet speech shows: $quiet", quiet in 0.15f..0.25f)
    }
}

/** Batch B10 fix: declining Bluetooth or the camera doesn't stop a call when the microphone is allowed. */
class CallPermissionRulesTest {
    @Test
    fun `the microphone decides, whether it was asked now or allowed before`() {
        assertTrue(CallPermissionRules.canCall(answered = true, granted = true))
        assertTrue(CallPermissionRules.canCall(answered = null, granted = true))
        org.junit.Assert.assertFalse(CallPermissionRules.canCall(answered = false, granted = false))
        org.junit.Assert.assertFalse(CallPermissionRules.canCall(answered = null, granted = false))
    }
}
