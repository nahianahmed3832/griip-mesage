package com.meldiq.android.ui.screens.onboarding

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** Onboarding navigation and how failures are sorted for the screens (UI-001). */
class AuthFlowTest {
    @Test
    fun backWalksTheStepsAndLeavesFromWelcome() {
        assertNull(AuthStep.Welcome.back)
        assertEquals(AuthStep.Welcome, AuthStep.SignUp.back)
        assertEquals(AuthStep.Welcome, AuthStep.LogIn.back)
        assertEquals(AuthStep.LogIn, AuthStep.Restore.back)
        assertEquals(AuthStep.LogIn, AuthStep.Link.back)
        // Each step's back is shallower, so going back always slides the other way.
        AuthStep.entries.forEach { s -> s.back?.let { assertTrue("$s", it.depth < s.depth) } }
    }

    @Test
    fun failuresAreSortedForTheScreens() {
        assertEquals(AuthError.Network, AuthErrors.of(ApiException(0, "network", "Can't reach the server")))
        assertEquals(AuthError.Unknown, AuthErrors.of(IllegalStateException("boom")))
        assertEquals(AuthError.Unknown, AuthErrors.of(ApiException(500, "http_500", "")))
        val limited = AuthErrors.of(ApiException(429, "registration_limited", "Too many new accounts from this network."))
        assertEquals(AuthError.Server("registration_limited", "Too many new accounts from this network."), limited)
        assertFalse(AuthErrors.isInviteProblem(limited))
        assertTrue(AuthErrors.isInviteProblem(AuthErrors.of(ApiException(403, "invalid_invite", "This invite has expired."))))
        assertTrue(AuthErrors.isInviteProblem(AuthErrors.of(ApiException(403, "invite_required", "Registration needs an invitation code"))))
        assertTrue(AuthErrors.isRecoveryKeyProblem(AuthErrors.of(ApiException(400, "bad_recovery", "That recovery key isn't valid."))))
        assertFalse(AuthErrors.isRecoveryKeyProblem(AuthError.Network))
    }

    @Test
    fun attemptReportsFailuresButPassesCancellationOn() = runTest {
        assertNull(AuthErrors.attempt { })
        assertEquals(AuthError.Network, AuthErrors.attempt { throw ApiException(0, "network", "offline") })
        try {
            AuthErrors.attempt { throw CancellationException("left the screen") }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("left the screen", e.message)
        }
    }
}
