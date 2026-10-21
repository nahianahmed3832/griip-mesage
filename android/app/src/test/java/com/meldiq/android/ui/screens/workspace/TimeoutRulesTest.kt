package com.meldiq.android.ui.screens.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who is offered "Time out", and the durations (the server decides; docs/product/MELDIQ_FEATURE_GAP_MATRIX.md MOD-06). */
class TimeoutRulesTest {
    private val mod = setOf("members.timeout")

    @Test
    fun `owners can time out anyone but owners and themselves`() {
        assertTrue(TimeoutRules.canOffer("OWNER", emptySet(), "ADMIN", self = false))
        assertTrue(TimeoutRules.canOffer("OWNER", emptySet(), "GUEST", self = false))
        assertFalse(TimeoutRules.canOffer("OWNER", emptySet(), "OWNER", self = false))
        assertFalse(TimeoutRules.canOffer("OWNER", emptySet(), "OWNER", self = true))
    }

    @Test
    fun `others need the permission and reach only members and guests`() {
        assertTrue(TimeoutRules.canOffer("MEMBER", mod, "MEMBER", self = false))
        assertTrue(TimeoutRules.canOffer("ADMIN", mod, "GUEST", self = false))
        assertFalse(TimeoutRules.canOffer("ADMIN", mod, "ADMIN", self = false))
        assertFalse(TimeoutRules.canOffer("MEMBER", emptySet(), "MEMBER", self = false))
        assertFalse(TimeoutRules.canOffer("MEMBER", mod, "MEMBER", self = true))
        assertFalse(TimeoutRules.canOffer("MEMBER", mod, null, self = false))
    }

    @Test
    fun `durations run from a minute to a week, within the server's 28 days`() {
        val lengths = TimeoutRules.DURATIONS.map { it.second }
        assertEquals(lengths.sorted(), lengths)
        assertEquals(60_000L, lengths.first())
        assertTrue(lengths.all { it <= TimeoutRules.MAX_MS })
    }

    @Test
    fun `a timeout runs until its end time`() {
        assertTrue(TimeoutRules.running(2_000, now = 1_000))
        assertFalse(TimeoutRules.running(1_000, now = 1_000))
        assertFalse(TimeoutRules.running(null, now = 1_000))
    }
}
