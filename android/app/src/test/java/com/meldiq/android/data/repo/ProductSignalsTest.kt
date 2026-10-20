package com.meldiq.android.data.repo

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.ui.screens.home.SearchRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meldiq 2.0: the analytics buffer, the closed analytics values and the workspace kind (docs/product/EXPERIMENTS.md). */
class ProductSignalsTest {
    private fun e(n: Int) = AnalyticsEventDto("app.opened", value = n.toLong())

    @Test
    fun theQueueKeepsTheNewestEventsWithinItsCapacityAndHandsThemOutInBatches() {
        val q = AnalyticsQueue(capacity = 3)
        (1..5).forEach { q.add(e(it)) }
        assertEquals(3, q.size)
        assertEquals(listOf(3L, 4L), q.take(2).map { it.value })
        assertEquals(listOf(5L), q.take().map { it.value })
        assertEquals(0, q.take().size)
    }

    @Test
    fun aFailedBatchGoesBackInFrontWithoutOverflowing() {
        val q = AnalyticsQueue(capacity = 3)
        (1..3).forEach { q.add(e(it)) }
        val batch = q.take(2)
        q.add(e(4))
        q.putBack(batch)
        // Full again: the oldest event (1) is the one that doesn't fit back.
        assertEquals(listOf(2L, 3L, 4L), q.take().map { it.value })
        assertEquals(0, q.size)
    }

    @Test
    fun searchesAreCountedByScopeAndBucketOnly() {
        assertEquals(listOf("0", "1", "2-5", "2-5", "6+"), listOf(0, 1, 2, 5, 40).map(SearchRules::resultBucket))
        val allowed = setOf("all", "messages", "people", "channels", "files", "apps")
        assertTrue(SearchRules.Filter.entries.all { SearchRules.analyticsScope(it) in allowed })
    }

    @Test
    fun aWorkspaceIsATeamUnlessTheServerSaysCommunity() {
        val old = GriipJson.decodeFromString(OrgDto.serializer(), """{"id":"o1","name":"Acme"}""")
        assertEquals("team", old.kind)
        assertFalse(old.isCommunity)
        assertTrue(GriipJson.decodeFromString(OrgDto.serializer(), """{"id":"o2","name":"Makers","kind":"community"}""").isCommunity)
    }
}
