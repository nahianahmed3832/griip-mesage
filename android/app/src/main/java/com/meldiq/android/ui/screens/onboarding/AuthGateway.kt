package com.meldiq.android.ui.screens.onboarding

import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.UsernameAvailableRes
import com.meldiq.android.data.repo.PendingPairing
import com.meldiq.android.data.repo.SessionRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * The account calls the sign-up and sign-in screens make. In the app it is [SessionAuthGateway], which hands every
 * call to [SessionRepository] unchanged (keys, signatures and requests stay there); tests put a fake in its place.
 */
interface AuthGateway {
    suspend fun usernameAvailable(serverUrl: String, username: String): UsernameAvailableRes
    suspend fun register(serverUrl: String, username: String, displayName: String, avatarColor: Int, inviteCode: String?)
    suspend fun restore(serverUrl: String, username: String, recoveryKey: String)
    suspend fun startPairing(serverUrl: String): PendingPairing
    /** True once this phone is linked; false while the other phone hasn't approved yet. Throws when the code expired. */
    suspend fun pollPairing(pairing: PendingPairing): Boolean
}

class SessionAuthGateway(private val session: SessionRepository) : AuthGateway {
    override suspend fun usernameAvailable(serverUrl: String, username: String) = session.usernameAvailable(serverUrl, username)
    override suspend fun register(serverUrl: String, username: String, displayName: String, avatarColor: Int, inviteCode: String?) =
        session.register(serverUrl, username, displayName, avatarColor, inviteCode)
    override suspend fun restore(serverUrl: String, username: String, recoveryKey: String) = session.restore(serverUrl, username, recoveryKey)
    override suspend fun startPairing(serverUrl: String) = session.startPairing(serverUrl)
    override suspend fun pollPairing(pairing: PendingPairing) = session.pollPairing(pairing)
}

/** Why a sign-up or sign-in step failed, in a form the screens word in the person's language. */
sealed interface AuthError {
    /** The phone couldn't reach the server. */
    data object Network : AuthError

    /** The server (or this app's own checks) refused, with an explanation already written for people. */
    data class Server(val code: String, val message: String) : AuthError

    /** Anything else. */
    data object Unknown : AuthError
}

/** Sorting failures for the sign-up and sign-in screens (unit-tested in `AuthErrorsTest`). */
object AuthErrors {
    /** Refusals about the invitation code: shown under that field rather than in a banner. */
    private val INVITE_CODES = setOf("invite_required", "invalid_invite")

    fun of(e: Throwable): AuthError = when {
        e !is ApiException -> AuthError.Unknown
        e.isNetwork -> AuthError.Network
        e.message.isBlank() -> AuthError.Unknown
        else -> AuthError.Server(e.code, e.message)
    }

    fun isInviteProblem(error: AuthError?): Boolean = error is AuthError.Server && error.code in INVITE_CODES

    /** The recovery key itself is wrong (it didn't decode): shown under the recovery-key field. */
    fun isRecoveryKeyProblem(error: AuthError?): Boolean = error is AuthError.Server && error.code == "bad_recovery"

    /** Runs [block]; returns null when it succeeded, or the sorted failure. Cancellation is passed on, never swallowed. */
    suspend fun attempt(block: suspend () -> Unit): AuthError? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        of(e)
    }
}
