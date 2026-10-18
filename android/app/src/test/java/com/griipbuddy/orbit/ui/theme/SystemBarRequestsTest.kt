package com.griipbuddy.orbit.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dark (Cocoa) screens asking for light system-bar icons (UI-002). */
class SystemBarRequestsTest {
    @Test
    fun darkSurfaceRequestsCountAndNeverGoNegative() {
        val bars = SystemBarRequests()
        bars.acquire()
        bars.acquire() // one tab leaving while the next arrives
        bars.release()
        assertEquals(1, bars.darkSurfaces)
        bars.release()
        bars.release()
        assertEquals(0, bars.darkSurfaces)
    }
}
