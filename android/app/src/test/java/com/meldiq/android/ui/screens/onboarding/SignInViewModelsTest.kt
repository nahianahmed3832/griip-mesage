package com.meldiq.android.ui.screens.onboarding

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Signing in on this phone (UI-001): with the recovery key, or by linking from another phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = "https://msg.example.test"
    private val gateway = FakeAuthGateway()
    private val key = "ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restoreNeedsAUsernameAndAWholeKey() = runTest(dispatcher) {
        val vm = RestoreViewModel(gateway)
        vm.onUsernameChange(" Nusrat ")
        vm.onRecoveryKeyChange("abcd-efgh")
        assertEquals("nusrat", vm.state.value.username)
        assertEquals("ABCD-EFGH", vm.state.value.recoveryKey)
        assertFalse(vm.state.value.canSubmit)
        vm.submit(server)
        advanceUntilIdle()
        assertTrue(gateway.restores.isEmpty())
        vm.onRecoveryKeyChange(key)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun restoreShowsLoadingThenSignsInWithTheKey() = runTest(dispatcher) {
        val vm = RestoreViewModel(gateway).apply {
            onUsernameChange("nusrat")
            onRecoveryKeyChange(key)
        }
        gateway.gate = CompletableDeferred()
        vm.submit(server)
        advanceUntilIdle()
        assertTrue(vm.state.value.submitting)
        assertFalse(vm.state.value.canSubmit)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.error)
        assertEquals(listOf(FakeAuthGateway.Restore(server, "nusrat", key)), gateway.restores)
    }

    @Test
    fun aWrongRecoveryKeyIsShownOnTheKeyField() = runTest(dispatcher) {
        gateway.failure = ApiException(400, "bad_recovery", "That recovery key isn't valid. Check for typos.")
        val vm = RestoreViewModel(gateway).apply {
            onUsernameChange("nusrat")
            onRecoveryKeyChange(key)
        }
        vm.submit(server)
        advanceUntilIdle()
        assertTrue(AuthErrors.isRecoveryKeyProblem(vm.state.value.error))
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun linkingShowsTheCodeThenFinishesWhenTheOtherPhoneApproves() = runTest(dispatcher) {
        gateway.pollAnswers += listOf(Result.success(false), Result.success(false), Result.success(true))
        val vm = LinkDeviceViewModel(gateway)
        vm.start(server)
        assertEquals(LinkState.Starting, vm.state.value)
        runCurrent()
        assertEquals(LinkState.Waiting("ABCDE12345"), vm.state.value)
        advanceTimeBy(LinkDeviceViewModel.POLL_MILLIS * 2 + 1)
        assertEquals(2, gateway.polls)
        assertEquals(LinkState.Waiting("ABCDE12345"), vm.state.value)
        advanceUntilIdle()
        assertEquals(3, gateway.polls)
        assertEquals(LinkState.Linked, vm.state.value)
    }

    @Test
    fun anExpiredCodeFailsAndRetryAsksForANewOne() = runTest(dispatcher) {
        gateway.pairingCodes += listOf("FIRST00000", "SECOND0000")
        gateway.pollAnswers += Result.failure(ApiException(410, "expired", "The link code expired. Start again."))
        val vm = LinkDeviceViewModel(gateway)
        vm.start(server)
        advanceUntilIdle()
        assertEquals(LinkState.Failed(AuthError.Server("expired", "The link code expired. Start again.")), vm.state.value)
        vm.retry()
        runCurrent()
        assertEquals(LinkState.Waiting("SECOND0000"), vm.state.value)
        assertEquals(2, gateway.pairingsStarted)
        vm.stop()
    }

    @Test
    fun noConnectionWhileAskingForACodeOffersRetry() = runTest(dispatcher) {
        gateway.pairingFailure = ApiException(0, "network", "Can't reach the server")
        val vm = LinkDeviceViewModel(gateway)
        vm.start(server)
        advanceUntilIdle()
        assertEquals(LinkState.Failed(AuthError.Network), vm.state.value)
        assertEquals(0, gateway.polls)
    }

    @Test
    fun leavingTheScreenStopsWaiting() = runTest(dispatcher) {
        val vm = LinkDeviceViewModel(gateway)
        vm.start(server)
        runCurrent()
        vm.stop()
        advanceTimeBy(LinkDeviceViewModel.POLL_MILLIS * 10)
        assertEquals(0, gateway.polls)
        vm.start(server) // coming back asks for a fresh code
        runCurrent()
        assertEquals(2, gateway.pairingsStarted)
        vm.stop()
    }

    @Test
    fun startingTwiceKeepsTheSameCode() = runTest(dispatcher) {
        val vm = LinkDeviceViewModel(gateway)
        vm.start(server)
        runCurrent()
        vm.start(server)
        runCurrent()
        assertEquals(1, gateway.pairingsStarted)
        vm.stop()
    }
}
