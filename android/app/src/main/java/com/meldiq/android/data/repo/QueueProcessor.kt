package com.meldiq.android.data.repo

import com.meldiq.android.crypto.EnvelopeException
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.QueueItem
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

/** The sender's identity key changed and hasn't been accepted: hold the item instead of discarding it (T1.2). */
class SenderIdentityChanged : Exception()

/**
 * Decides, for each delivered queue item, whether it may be acknowledged to the relay, which then
 * deletes its only copy (T1.4). An item is acknowledged only when:
 * - its effect is committed locally ([apply] returned);
 * - it's kept locally for later ([quarantine], sender identity changed);
 * - it's a permanent crypto or format failure, recorded as such ([undecryptable]); or
 * - it failed unexpectedly [maxAttempts] times and was set aside locally, kept and visible ([setAside]),
 *   so one poisoned item can't loop or hold others back.
 *
 * Unreachable keys or a network error leave it unacknowledged for redelivery without counting against
 * it. Cancellation always propagates. Nothing is acknowledged before its local state is written.
 */
class QueueProcessor(
    private val apply: suspend (QueueItem) -> Unit,
    private val undecryptable: suspend (QueueItem, String) -> Unit,
    private val quarantine: suspend (QueueItem) -> Unit,
    private val setAside: suspend (QueueItem, Throwable) -> Unit,
    private val maxAttempts: Int = 5,
) {
    private val failures = HashMap<Long, Int>()

    /** Processes [items] in order and returns the ids that are safe to acknowledge. */
    suspend fun process(items: List<QueueItem>): List<Long> {
        val acks = ArrayList<Long>()
        for (item in items) if (processOne(item)) acks += item.id
        return acks
    }

    private suspend fun processOne(item: QueueItem): Boolean {
        try {
            apply(item)
            failures.remove(item.id)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SenderIdentityChanged) {
            quarantine(item)
        } catch (e: EnvelopeException) {
            undecryptable(item, e.message ?: "undecryptable")
        } catch (e: SerializationException) {
            undecryptable(item, "malformed payload") // decrypted fine but isn't a valid payload: permanent
        } catch (e: ApiException) {
            return false // keys or server unreachable: redelivered later, not counted as a failure
        } catch (e: Exception) {
            val n = (failures[item.id] ?: 0) + 1
            if (n < maxAttempts) {
                failures[item.id] = n
                return false
            }
            setAside(item, e)
        }
        failures.remove(item.id)
        return true
    }
}
