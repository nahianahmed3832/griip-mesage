package com.meldiq.android.ui.screens.onboarding

import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.model.ProfileRules.UsernameIssue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sign-up form (UI-001): validation, the username check, loading, errors and the registration call. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = "https://msg.example.test"
    private val gateway = FakeAuthGateway()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.filledIn(): SignUpViewModel = SignUpViewModel(gateway, server, initialColor = 3).apply {
        onDisplayNameChange("Nusrat Jahan")
        onInviteChange("abcd-efgh")
        advanceUntilIdle()
    }

    @Test
    fun theUsernameFollowsTheNameUntilThePersonTypesTheirOwn() = runTest(dispatcher) {
        val vm = SignUpViewModel(gateway, server)
        vm.onDisplayNameChange("Nusrat Jahan")
        assertEquals("nusrat.jahan", vm.state.value.username)
        vm.onUsernameChange("nj_dhaka")
        vm.onDisplayNameChange("Nusrat J")
        assertEquals("nj_dhaka", vm.state.value.username)
        assertEquals("Nusrat J", vm.state.value.displayName)
    }

    @Test
    fun inputsAreCleanedTheWayTheServerExpects() = runTest(dispatcher) {
        val vm = SignUpViewModel(gateway, server)
        vm.onUsernameChange("Nu\$rat_01!")
        vm.onInviteChange("abcd efgh-jk")
        vm.onDisplayNameChange("x".repeat(80))
        assertEquals("nurat_01", vm.state.value.username)
        assertEquals("ABCDEFGH-JK", vm.state.value.inviteCode)
        assertEquals(SignUpRules.MAX_NAME, vm.state.value.displayName.length)
        vm.onUsernameChange("a".repeat(40))
        assertEquals(SignUpRules.MAX_USERNAME, vm.state.value.username.length)
        vm.onColorChange(11)
        assertEquals(3, vm.state.value.colorIndex)
    }

    @Test
    fun badlyFormedUsernamesAreExplainedAndNeverSentToTheServer() = runTest(dispatcher) {
        val vm = SignUpViewModel(gateway, server)
        vm.onDisplayNameChange("Nusrat")
        vm.onUsernameChange("ab")
        assertEquals(UsernameIssue.TOO_SHORT, vm.state.value.usernameIssue)
        vm.onUsernameChange("_nusrat")
        assertEquals(UsernameIssue.CHARACTERS, vm.state.value.usernameIssue)
        vm.onUsernameChange("nus..rat")
        assertEquals(UsernameIssue.DOUBLE_DOT, vm.state.value.usernameIssue)
        advanceUntilIdle()
        assertEquals(UsernameCheck.Idle, vm.state.value.usernameCheck)
        assertTrue(gateway.usernameChecks.isEmpty())
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun theUsernameIsCheckedAfterAPauseAndOnlyTheLatestCounts() = runTest(dispatcher) {
        val vm = SignUpViewModel(gateway, server)
        vm.onUsernameChange("nusrat")
        assertEquals(UsernameCheck.Checking, vm.state.value.usernameCheck)
        advanceTimeBy(SignUpRules.CHECK_DELAY_MS - 1)
        vm.onUsernameChange("nusrat2")
        advanceUntilIdle()
        assertEquals(listOf(server to "nusrat2"), gateway.usernameChecks)
        assertEquals(UsernameCheck.Available("nusrat2"), vm.state.value.usernameCheck)
    }

    @Test
    fun aTakenUsernameBlocksSignUp() = runTest(dispatcher) {
        gateway.takenUsernames["nusrat.jahan"] = "That username is taken"
        val vm = filledIn()
        assertEquals(UsernameCheck.Taken("That username is taken"), vm.state.value.usernameCheck)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertTrue(gateway.registrations.isEmpty())
    }

    @Test
    fun anUnreachableServerDoesNotBlockSignUp() = runTest(dispatcher) {
        gateway.availabilityFailure = ApiException(0, "network", "Can't reach the server")
        val vm = filledIn()
        assertEquals(UsernameCheck.Unreachable, vm.state.value.usernameCheck)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun anotherServerChecksTheUsernameAgain() = runTest(dispatcher) {
        val vm = filledIn()
        vm.onServerChange("https://other.example.test")
        advanceUntilIdle()
        assertEquals(listOf(server to "nusrat.jahan", "https://other.example.test" to "nusrat.jahan"), gateway.usernameChecks)
        vm.submit()
        advanceUntilIdle()
        assertEquals("https://other.example.test", gateway.registrations.single().serverUrl)
    }

    @Test
    fun submittingShowsLoadingThenRegistersWithWhatWasTyped() = runTest(dispatcher) {
        val vm = filledIn()
        gateway.gate = CompletableDeferred()
        vm.submit()
        assertTrue(vm.state.value.submitting)
        assertFalse(vm.state.value.canSubmit)
        vm.submit() // a second tap while keys are being made does nothing
        advanceUntilIdle()
        assertTrue(vm.state.value.submitting)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.error)
        assertEquals(listOf(FakeAuthGateway.Registration(server, "nusrat.jahan", "Nusrat Jahan", 3, "ABCD-EFGH")), gateway.registrations)
    }

    @Test
    fun aRefusedInvitationIsShownOnTheInvitationField() = runTest(dispatcher) {
        gateway.failure = ApiException(403, "invite_required", "Registration needs an invitation code")
        val vm = filledIn()
        vm.submit()
        advanceUntilIdle()
        val error = vm.state.value.error
        assertEquals(AuthError.Server("invite_required", "Registration needs an invitation code"), error)
        assertTrue(AuthErrors.isInviteProblem(error))
        assertFalse(vm.state.value.submitting)
        assertTrue(vm.state.value.canSubmit) // can try again
        vm.onInviteChange("WXYZ")
        assertNull(vm.state.value.error) // editing clears the old failure
    }

    @Test
    fun aLostConnectionIsReportedAsSuch() = runTest(dispatcher) {
        gateway.failure = ApiException(0, "network", "Can't reach the server")
        val vm = filledIn()
        vm.submit()
        advanceUntilIdle()
        assertEquals(AuthError.Network, vm.state.value.error)
    }

    @Test
    fun anIncompleteFormIsNeverSent() = runTest(dispatcher) {
        val vm = SignUpViewModel(gateway, server)
        vm.onUsernameChange("nusrat")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSubmit) // no display name
        vm.submit()
        advanceUntilIdle()
        assertTrue(gateway.registrations.isEmpty())
    }
}
