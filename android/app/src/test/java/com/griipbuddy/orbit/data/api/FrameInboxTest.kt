package com.griipbuddy.orbit.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** T2.2: a burst of frames reaches the app complete and in order; a stalled consumer means a reconnect, not loss. */
class FrameInboxTest {
    @Test
    fun aBurstOf1000FramesArrivesCompleteAndInOrderWithASlowConsumer() = runBlocking {
        val inbox = FrameInbox<Int>(capacity = 64)
        val received = CopyOnWriteArrayList<Int>()
        val consumer = CoroutineScope(Dispatchers.Default).launch {
            inbox.frames.collect { received += it; if (it % 100 == 0) delay(5) } // slower than the socket
        }
        val reader = thread { for (i in 1..1000) check(inbox.offer(i)) } // the socket's reader thread
        reader.join()
        withTimeout(10_000) { while (received.size < 1000) delay(10) }
        consumer.cancelAndJoin()
        assertEquals((1..1000).toList(), received.toList())
    }

    @Test
    fun aStalledConsumerMakesOfferFailInsteadOfDroppingSilently() {
        val inbox = FrameInbox<Int>(capacity = 2, stallTimeoutMs = 200)
        assertTrue(inbox.offer(1))
        assertTrue(inbox.offer(2))
        val started = System.currentTimeMillis()
        assertFalse("the caller must drop the connection", inbox.offer(3))
        assertTrue(System.currentTimeMillis() - started >= 200)
        // Nothing that was accepted is lost.
        assertEquals(listOf(1, 2), runBlocking { inbox.frames.take(2).toList() })
    }

    @Test
    fun theBufferIsBoundedNotUnbounded() {
        val inbox = FrameInbox<Int>(capacity = 10, stallTimeoutMs = 50)
        var accepted = 0
        for (i in 1..100) if (inbox.offer(i)) accepted++ else break
        assertEquals(10, accepted)
    }
}
