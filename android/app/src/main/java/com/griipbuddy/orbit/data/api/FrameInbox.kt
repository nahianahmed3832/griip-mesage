package com.griipbuddy.orbit.data.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ordered hand-off of socket frames to their single consumer (T2.2). It replaces a
 * `tryEmit` into a 256-frame buffer that silently dropped frames during a burst.
 * - The buffer is bounded at [capacity]. When the consumer is that far behind, [offer] makes the
 *   socket's reader thread wait. That is TCP backpressure toward the relay, with no extra memory and no dropped frame.
 * - If the consumer stays stuck for [stallTimeoutMs], [offer] returns false and the caller drops the
 *   connection. Unacknowledged queue items are redelivered after reconnecting, so a stall costs a
 *   reconnect, never a message.
 */
class FrameInbox<T>(capacity: Int = 1024, private val stallTimeoutMs: Long = 20_000) {
    private val channel = Channel<T>(capacity)

    /** Frames in arrival order. Collect it from exactly one place. */
    val frames: Flow<T> = channel.receiveAsFlow()

    /** Called from the socket's reader thread. Returns false if the consumer is stalled. */
    fun offer(frame: T): Boolean {
        if (channel.trySend(frame).isSuccess) return true
        return runBlocking { withTimeoutOrNull(stallTimeoutMs) { channel.send(frame) } } != null
    }
}
