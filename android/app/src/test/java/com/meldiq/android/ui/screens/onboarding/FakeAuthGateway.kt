package com.meldiq.android.ui.screens.onboarding

import com.meldiq.android.crypto.LocalKeys
import com.meldiq.android.data.api.UsernameAvailableRes
import com.meldiq.android.data.repo.PendingPairing
import kotlinx.coroutines.CompletableDeferred

/**
 * Stands in for the server in onboarding tests. Calls are recorded; answers and failures are set per test, and
 * [gate] holds register/restore open so the loading state can be seen.
 */
class FakeAuthGateway : AuthGateway {
    data class Registration(val serverUrl: String, val username: String, val displayName: String, val avatarColor: Int, val inviteCode: String?)
    data class Restore(val serverUrl: String, val username: String, val recoveryKey: String)

    val takenUsernames = mutableMapOf<String, String?>()
    var availabilityFailure: Exception? = null
    val usernameChecks = mutableListOf<Pair<String, String>>()

    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    val registrations = mutableListOf<Registration>()
    val restores = mutableListOf<Restore>()

    val pairingCodes = ArrayDeque<String>()
    var pairingFailure: Exception? = null
    var pairingsStarted = 0
    /** Answers to successive polls; a thrown exception ends the wait. Empty: still waiting. */
    val pollAnswers = ArrayDeque<Result<Boolean>>()
    var polls = 0

    override suspend fun usernameAvailable(serverUrl: String, username: String): UsernameAvailableRes {
        usernameChecks += serverUrl to username
        availabilityFailure?.let { throw it }
        return if (username in takenUsernames) UsernameAvailableRes(false, username, takenUsernames[username]) else UsernameAvailableRes(true, username)
    }

    override suspend fun register(serverUrl: String, username: String, displayName: String, avatarColor: Int, inviteCode: String?) {
        registrations += Registration(serverUrl, username, displayName, avatarColor, inviteCode)
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun restore(serverUrl: String, username: String, recoveryKey: String) {
        restores += Restore(serverUrl, username, recoveryKey)
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun startPairing(serverUrl: String): PendingPairing {
        pairingsStarted++
        pairingFailure?.let { throw it }
        val code = pairingCodes.removeFirstOrNull() ?: "ABCDE12345"
        return PendingPairing(serverUrl, "pairing-$pairingsStarted", code, 0L, LocalKeys("", "", "", ""), "", "")
    }

    override suspend fun pollPairing(pairing: PendingPairing): Boolean {
        polls++
        return pollAnswers.removeFirstOrNull()?.getOrThrow() ?: false
    }
}
