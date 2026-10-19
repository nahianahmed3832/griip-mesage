package com.meldiq.android.data.repo

import com.meldiq.android.crypto.EnvelopeException
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.QueueItem
import com.meldiq.android.data.api.QueuePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.SQLException

/** T1.4: a message is acknowledged (and deleted by the relay) only when it's safe locally. */
class QueueProcessorTest {
    private fun item(id: Long) = QueueItem(id, "envelope", "c1", "m$id", "u_sender", "d_sender", 1, 2, QueuePayload(cls = "message"))

    private val applied = ArrayList<Long>()
    private val undecryptable = ArrayList<Pair<Long, String>>()
    private val quarantined = ArrayList<Long>()
    private val setAside = ArrayList<Pair<Long, String>>()

    /** `failWith[id]` decides what applying item `id` throws; absent means it applies fine. */
    private fun processor(failWith: Map<Long, () -> Throwable>, maxAttempts: Int = 3) = QueueProcessor(
        apply = { failWith[it.id]?.let { f -> throw f() }; applied += it.id },
        undecryptable = { it, reason -> undecryptable += it.id to reason },
        quarantine = { quarantined += it.id },
        setAside = { it, e -> setAside += it.id to e.javaClass.simpleName },
        maxAttempts = maxAttempts,
    )

    @Test
    fun appliedItemsAreAcknowledgedInOrder() = runTest {
        assertEquals(listOf(1L, 2L, 3L), processor(emptyMap()).process(listOf(item(1), item(2), item(3))))
        assertEquals(listOf(1L, 2L, 3L), applied)
    }

    @Test
    fun aDatabaseErrorLeavesTheItemForRedeliveryWhileOthersProceed() = runTest {
        val p = processor(mapOf(2L to { SQLException("database is locked") }))
        assertEquals(listOf(1L, 3L), p.process(listOf(item(1), item(2), item(3))))
        assertTrue(setAside.isEmpty() && undecryptable.isEmpty())
    }

    @Test
    fun cancellationPropagatesAndAcknowledgesNothingFurther() = runTest {
        val p = processor(mapOf(2L to { CancellationException("screen closed") }))
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { p.process(listOf(item(1), item(2), item(3))) } }
        assertEquals("item 3 was never touched", listOf(1L), applied)
    }

    @Test
    fun cryptoAndFormatFailuresAreRecordedThenAcknowledged() = runTest {
        val p = processor(mapOf(1L to { EnvelopeException("signature verification failed") }, 2L to { SerializationException("bad payload") }))
        assertEquals(listOf(1L, 2L), p.process(listOf(item(1), item(2))))
        assertEquals(listOf(1L to "signature verification failed", 2L to "malformed payload"), undecryptable)
    }

    @Test
    fun unreachableKeysAreRetriedWithoutCountingAsFailures() = runTest {
        val p = processor(mapOf(1L to { ApiException(0, "network", "offline") }), maxAttempts = 2)
        repeat(5) { assertEquals(emptyList<Long>(), p.process(listOf(item(1)))) }
        assertTrue("network trouble never sets an item aside", setAside.isEmpty())
    }

    @Test
    fun anIdentityChangeIsQuarantinedThenAcknowledged() = runTest {
        assertEquals(listOf(1L), processor(mapOf(1L to { SenderIdentityChanged() })).process(listOf(item(1))))
        assertEquals(listOf(1L), quarantined)
    }

    @Test
    fun aPoisonedItemIsSetAsideAfterBoundedRetriesAndNeverBlocksOthers() = runTest {
        val p = processor(mapOf(5L to { IllegalStateException("bug in apply") }), maxAttempts = 3)
        assertEquals(listOf(4L), p.process(listOf(item(4), item(5))))
        assertEquals(listOf(6L), p.process(listOf(item(5), item(6))))
        assertEquals(listOf(5L, 7L), p.process(listOf(item(5), item(7)))) // third failure: kept locally, then acknowledged
        assertEquals(listOf(5L to "IllegalStateException"), setAside)
        assertEquals(listOf(4L, 6L, 7L), applied)
    }

    @Test
    fun aSuccessResetsTheFailureCount() = runTest {
        var fail = true
        val p = QueueProcessor(
            apply = { if (fail) throw IllegalStateException("flaky") },
            undecryptable = { _, _ -> }, quarantine = {}, setAside = { it, _ -> setAside += it.id to "x" }, maxAttempts = 2,
        )
        assertEquals(emptyList<Long>(), p.process(listOf(item(1))))
        fail = false
        assertEquals(listOf(1L), p.process(listOf(item(1))))
        fail = true
        assertEquals("the earlier failure no longer counts", emptyList<Long>(), p.process(listOf(item(1))))
        assertTrue(setAside.isEmpty())
    }
}
