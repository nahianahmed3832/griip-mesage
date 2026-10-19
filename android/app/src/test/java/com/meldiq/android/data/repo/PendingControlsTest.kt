package com.meldiq.android.data.repo

import com.meldiq.android.data.db.PendingControlEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1: controls that arrive before their target are kept (bounded, expiring) and replayed in order. */
class PendingControlsTest {
    private class MemoryStore : PendingControlStore {
        val rows = LinkedHashMap<String, PendingControlEntity>()
        override suspend fun insertIfAbsent(e: PendingControlEntity) = rows.putIfAbsent(e.id, e) == null
        override suspend fun forTarget(conversationId: String, targetId: String) = rows.values.filter { it.conversationId == conversationId && it.targetId == targetId }
        override suspend fun delete(ids: List<String>) = ids.forEach { rows.remove(it) }
        override suspend fun countIn(conversationId: String) = rows.values.count { it.conversationId == conversationId }
        override suspend fun deleteOldestIn(conversationId: String, keep: Int) {
            val keepIds = rows.values.filter { it.conversationId == conversationId }.sortedWith(compareByDescending<PendingControlEntity> { it.serverTs }.thenByDescending { it.id }).take(keep).map { it.id }.toSet()
            rows.values.removeIf { it.conversationId == conversationId && it.id !in keepIds }
        }
        override suspend fun deleteExpired(now: Long) {
            rows.values.removeIf { it.expiresAt <= now }
        }
    }

    private var clock = 1_000L
    private val store = MemoryStore()
    private val pending = PendingControls(store, now = { clock }, maxPerConversation = 3, ttlMs = 10_000)

    @Test
    fun replaysInTheirOriginalOrderOnceTheTargetArrives() = runTest {
        pending.hold("ctl-react-remove", "c1", "m1", "u2", "{remove}", serverTs = 30)
        pending.hold("ctl-edit", "c1", "m1", "u1", "{edit}", serverTs = 10)
        pending.hold("ctl-react-add", "c1", "m1", "u2", "{add}", serverTs = 20)
        assertEquals(listOf("ctl-edit", "ctl-react-add", "ctl-react-remove"), pending.takeFor("c1", "m1").map { it.id })
        assertTrue("taken controls are gone", pending.takeFor("c1", "m1").isEmpty())
    }

    @Test
    fun aRedeliveredControlIsHeldOnce() = runTest {
        assertTrue(pending.hold("ctl-1", "c1", "m1", "u1", "{}", 10))
        assertFalse(pending.hold("ctl-1", "c1", "m1", "u1", "{}", 10))
        assertEquals(1, pending.takeFor("c1", "m1").size)
    }

    @Test
    fun onlyTheSameConversationsControlsAreReplayed() = runTest {
        pending.hold("ctl-other", "c2", "m1", "u9", "{}", 10) // same message id claimed from another conversation
        assertTrue(pending.takeFor("c1", "m1").isEmpty())
        assertEquals(listOf("ctl-other"), pending.takeFor("c2", "m1").map { it.id })
    }

    @Test
    fun theOldestAreDroppedBeyondThePerConversationBound() = runTest {
        for (i in 1..5) pending.hold("ctl-$i", "c1", "m$i", "u1", "{}", serverTs = i.toLong())
        assertEquals(setOf("ctl-3", "ctl-4", "ctl-5"), store.rows.keys)
        pending.hold("ctl-x", "c2", "m1", "u1", "{}", 1) // other conversations have their own bound
        assertEquals(4, store.rows.size)
    }

    @Test
    fun expiredControlsAreNotReplayedAndArePurged() = runTest {
        pending.hold("ctl-old", "c1", "m1", "u1", "{}", 10)
        clock += 10_001
        pending.hold("ctl-new", "c1", "m2", "u1", "{}", 20)
        assertTrue(pending.takeFor("c1", "m1").isEmpty())
        pending.purgeExpired()
        assertEquals(setOf("ctl-new"), store.rows.keys)
    }
}
