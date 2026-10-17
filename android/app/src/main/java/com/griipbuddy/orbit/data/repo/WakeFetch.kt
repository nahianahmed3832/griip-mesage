package com.griipbuddy.orbit.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the app does when a push wake-up arrives (docs/BACKGROUND_DELIVERY.md). The wake-up carries nothing, so the app
 * fetches its queue the usual way: [holds] above zero keeps the relay connection open (AppContainer), the existing
 * queue path verifies, decrypts, stores, acknowledges and notifies, and the connection is let go once
 * - the backlog has drained (or [Limits.drainMs] passed);
 * - after a fresh drain, ring recovery has looked for calls that rang meanwhile (a ring wakes the app too);
 * - no call is ringing any more (answered, declined or missed), so the incoming-call screen isn't cut off.
 *
 * Wake-ups that arrive during a fetch don't start another: they add one more round to it, so the connection isn't
 * dropped and reopened in between. Pure logic over flows (`WakeFetchTest`).
 */
class WakeFetch(
    private val scope: CoroutineScope,
    private val holds: MutableStateFlow<Int>,
    private val drained: StateFlow<Boolean>,
    private val ringChecks: StateFlow<Int>,
    private val ringing: Flow<Boolean>,
    private val limits: Limits = Limits(),
) {
    data class Limits(val drainMs: Long = 45_000, val ringCheckMs: Long = 10_000, val ringMs: Long = 90_000)

    enum class Outcome { Fetched, TimedOut }

    private val lock = Any()
    private var running = false
    private var again = false
    private val _busy = MutableStateFlow(false)

    /** True from a wake-up until its fetch, with any rounds added meanwhile, is done. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _last = MutableStateFlow<Outcome?>(null)
    val lastOutcome: StateFlow<Outcome?> = _last.asStateFlow()

    /** A wake-up arrived: fetch now, or once more after the fetch in progress. */
    fun request() {
        synchronized(lock) {
            if (running) {
                again = true
                return
            }
            running = true
            _busy.value = true
        }
        // Undispatched: the loop's cleanup runs even if the scope is already cancelled (a closed profile), so busy never sticks.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { loop() }
    }

    private suspend fun loop() {
        holds.update { it + 1 }
        var finished = false
        try {
            while (true) {
                _last.value = fetchOnce()
                val more = synchronized(lock) {
                    // Decided under the lock that request() takes: a wake-up either adds this round or starts a new loop.
                    if (again) {
                        again = false
                    } else {
                        running = false
                        _busy.value = false
                        finished = true
                    }
                    !finished
                }
                if (!more) return
            }
        } finally {
            if (!finished) synchronized(lock) {
                running = false
                again = false
                _busy.value = false
            }
            holds.update { it - 1 }
        }
    }

    private suspend fun fetchOnce(): Outcome {
        val wasDrained = drained.value
        val checks = ringChecks.value
        if (withTimeoutOrNull(limits.drainMs) { drained.first { it } } == null) return Outcome.TimedOut
        // Already connected and drained: rings arrive live. Otherwise ring recovery runs right after this drain.
        if (!wasDrained) withTimeoutOrNull(limits.ringCheckMs) { ringChecks.first { it > checks } }
        withTimeoutOrNull(limits.ringMs) { ringing.first { !it } }
        return Outcome.Fetched
    }
}
