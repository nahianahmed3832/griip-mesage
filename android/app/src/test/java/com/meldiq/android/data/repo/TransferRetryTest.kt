package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRetryTest {
    @Test
    fun classifiesWhatIsWorthRetrying() {
        assertTrue(TransferRetry.isTransient(ApiException(0, "network", "offline")))
        assertTrue(TransferRetry.isTransient(ApiException(429, "too_many_uploads", "busy")))
        assertTrue(TransferRetry.isTransient(ApiException(503, "http_503", "unavailable")))
        assertFalse(TransferRetry.isTransient(ApiException(413, "too_large", "too large")))
        assertFalse(TransferRetry.isTransient(ApiException(429, "quota_exceeded", "daily limit")))
        assertFalse(TransferRetry.isTransient(ApiException(507, "storage_full", "full")))
        assertFalse(TransferRetry.isTransient(ApiException(404, "not_found", "gone")))
    }

    @Test
    fun retriesTransientFailuresThenSucceeds() = runTest {
        var calls = 0
        val r = TransferRetry.run(delays = listOf(10, 20, 30)) {
            if (++calls < 3) throw ApiException(0, "network", "offline")
            "uploaded"
        }
        assertEquals("uploaded", r)
        assertEquals(3, calls)
    }

    @Test
    fun givesUpAfterTheLastRetryAndNeverRetriesAPermanentRefusal() = runTest {
        var calls = 0
        assertThrows(ApiException::class.java) {
            kotlinx.coroutines.runBlocking { TransferRetry.run(delays = listOf(1, 1)) { calls++; throw ApiException(503, "http_503", "down") } }
        }
        assertEquals(3, calls)
        calls = 0
        val e = assertThrows(ApiException::class.java) {
            kotlinx.coroutines.runBlocking { TransferRetry.run(delays = listOf(1, 1)) { calls++; throw ApiException(429, "quota_exceeded", "daily limit") } }
        }
        assertEquals("quota_exceeded", e.code)
        assertEquals(1, calls)
    }
}
