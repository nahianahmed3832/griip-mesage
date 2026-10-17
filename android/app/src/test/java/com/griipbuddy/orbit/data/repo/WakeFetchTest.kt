package com.griipbuddy.orbit.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a push wake-up does (docs/BACKGROUND_DELIVERY.md), with the relay connection, backlog and calls faked as flows:
 * [Rig.holds] above zero is "the app keeps its relay connection open"; [Rig.drained] is the backlog having drained.
 */
class WakeFetchTest {
    private class Rig(scope: CoroutineScope, limits: WakeFetch.Limits = WakeFetch.Limits()) {
        val holds = MutableStateFlow(0)
        val drained = MutableStateFlow(false)
        val ringChecks = MutableStateFlow(0)
        val ringing = MutableStateFlow(false)
        val fetch = WakeFetch(scope, holds, drained, ringChecks, ringing, limits)

        /** The connection opened, the backlog arrived and ring recovery looked for calls. */
        fun fetched() {
            drained.value = true
            ringChecks.value++
        }
    }

    private fun TestScope.history(r: Rig): List<Int> {
        val seen = ArrayList<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.holds.toList(seen) }
        return seen
    }

    @Test
    fun holdsTheConnectionUntilTheBacklogDrainedAndRingRecoveryRanThenLetsGo() = runTest {
        val r = Rig(backgroundScope)
        r.fetch.request()
        runCurrent()
        assertEquals(1, r.holds.value)
        assertTrue(r.fetch.busy.value)

        r.drained.value = true
        runCurrent()
        assertEquals("waits for ring recovery after a fresh drain", 1, r.holds.value)
        r.ringChecks.value++
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
        assertEquals(WakeFetch.Outcome.Fetched, r.fetch.lastOutcome.value)
    }

    @Test
    fun keepsHoldingWhileACallRingsUntilItStops() = runTest {
        val r = Rig(backgroundScope)
        r.fetch.request()
        r.ringing.value = true // the wake-up was a ring: recovery found the call
        r.fetched()
        runCurrent()
        assertEquals(1, r.holds.value)
        advanceTimeBy(30_000)
        assertEquals("still ringing", 1, r.holds.value)
        r.ringing.value = false // answered, declined or missed
        runCurrent()
        assertEquals(0, r.holds.value)
    }

    @Test
    fun aRingThatNeverEndsIsLetGoAtTheLimit() = runTest {
        val r = Rig(backgroundScope, WakeFetch.Limits(ringMs = 5_000))
        r.ringing.value = true
        r.fetch.request()
        r.fetched()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(0, r.holds.value)
    }

    @Test
    fun givesUpWhenTheBacklogDoesntDrainInTime() = runTest {
        val r = Rig(backgroundScope, WakeFetch.Limits(drainMs = 1_000))
        r.fetch.request()
        advanceTimeBy(999)
        assertEquals(1, r.holds.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
        assertEquals(WakeFetch.Outcome.TimedOut, r.fetch.lastOutcome.value)
    }

    @Test
    fun alreadyConnectedAndDrainedItIsDoneAtOnce() = runTest {
        val r = Rig(backgroundScope)
        r.drained.value = true // "Stay connected" kept the connection: rings would have arrived live
        r.fetch.request()
        runCurrent()
        assertEquals(0, r.holds.value)
        assertEquals(WakeFetch.Outcome.Fetched, r.fetch.lastOutcome.value)
    }

    @Test
    fun aWakeupDuringAFetchAddsARoundInsteadOfReconnecting() = runTest {
        val r = Rig(backgroundScope)
        val seen = history(r)
        r.fetch.request()
        r.fetch.request() // a second wake-up while the first fetch runs
        r.fetch.request()
        runCurrent()
        assertEquals(1, r.holds.value)
        r.fetched()
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
        assertEquals("the connection is held once, without a gap between rounds", listOf(0, 1, 0), seen)
    }

    @Test
    fun aWakeupDuringAFetchIsNotLostWhenTheConnectionDropsMeanwhile() = runTest {
        val r = Rig(backgroundScope)
        r.fetch.request()
        r.ringing.value = true
        r.fetched() // first round: drained, now waiting while a call rings
        runCurrent()
        r.fetch.request() // a message arrives meanwhile
        r.drained.value = false // and the connection drops before it was fetched
        r.ringing.value = false
        runCurrent()
        assertEquals("the added round waits for the backlog again", 1, r.holds.value)
        r.fetched()
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
    }

    @Test
    fun aWakeupAfterTheFetchFinishedStartsANewOne() = runTest {
        val r = Rig(backgroundScope)
        r.fetch.request()
        r.fetched()
        runCurrent()
        assertEquals(0, r.holds.value)

        r.drained.value = false // the connection closed again
        r.fetch.request()
        runCurrent()
        assertEquals(1, r.holds.value)
        r.fetched()
        runCurrent()
        assertEquals(0, r.holds.value)
    }

    @Test
    fun closingTheProfileMidFetchReleasesTheHold() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val r = Rig(scope)
        r.fetch.request()
        runCurrent()
        assertEquals(1, r.holds.value)
        scope.cancel()
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
    }

    @Test
    fun aWakeupForAClosedProfileDoesntStayBusy() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        val r = Rig(scope)
        r.fetch.request()
        runCurrent()
        assertEquals(0, r.holds.value)
        assertFalse(r.fetch.busy.value)
        assertNull(r.fetch.lastOutcome.value)
    }
}
