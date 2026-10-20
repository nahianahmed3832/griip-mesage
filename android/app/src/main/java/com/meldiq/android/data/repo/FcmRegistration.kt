package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.FcmTokenReq
import com.meldiq.android.data.api.FcmTokenRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Where Firebase wake-ups stand on this phone (docs/PUSH_FCM.md). */
sealed interface FcmState {
    /** Not started yet (signed out, or the app just started). */
    data object Off : FcmState

    /** The server doesn't send Firebase wake-ups ("push-fcm" isn't in its server-info). */
    data object ServerUnsupported : FcmState

    /** This build has no Firebase configuration (no google-services.json), or Google Play services can't give a token. */
    data object Unavailable : FcmState

    /** The server couldn't be told yet: sent again when the app next connects. */
    data object Pending : FcmState

    /** The server has this phone's current token. */
    data object Active : FcmState
}

/** Firebase on this phone: whether it is set up, and the current registration token. */
interface FcmTokenSource {
    fun available(): Boolean

    /** The current token, or null when Firebase can't give one now. */
    suspend fun token(): String?
}

/** The app's server side of Firebase wake-ups. */
interface FcmServer {
    /** true / false once known; null while the server can't be asked. */
    suspend fun supported(): Boolean?

    /** Stores [token] as this device's Firebase address. Throws [ApiException]. */
    suspend fun register(token: String)
}

/**
 * Firebase wake-up registration for the signed-in device. Pure logic over [FcmTokenSource] and [FcmServer]
 * (`FcmRegistrarTest`). A wake-up only ever means "fetch your queue", exactly like UnifiedPush: the server sends no
 * content. The token goes to the server at each start (the server may have dropped one that stopped working) and
 * whenever Firebase rotates it.
 */
class FcmRegistrar(private val tokens: FcmTokenSource, private val server: FcmServer) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<FcmState>(FcmState.Off)
    val state: StateFlow<FcmState> = _state.asStateFlow()
    private var pendingToken: String? = null

    /** Signed in (app start, account switch): find out whether wake-ups apply and send the token. */
    suspend fun start() = lock.withLock {
        when {
            server.supported() == false -> _state.value = FcmState.ServerUnsupported
            !tokens.available() -> _state.value = FcmState.Unavailable
            else -> {
                val token = tokens.token()
                _state.value = if (token == null) FcmState.Unavailable else send(token)
            }
        }
    }

    /** Firebase issued a new token (it rotates them). Ignored unless wake-ups apply to this server. */
    suspend fun onNewToken(token: String) = lock.withLock {
        if (_state.value == FcmState.Active || _state.value == FcmState.Pending) _state.value = send(token)
    }

    /** Sends a token the server couldn't be told about before. Called whenever the app (re)connects. */
    suspend fun retryPending() = lock.withLock {
        val token = pendingToken ?: return@withLock
        if (_state.value == FcmState.Pending) _state.value = send(token)
    }

    /** Signed out: forget what was sent (the server ends the device, and its token with it). */
    fun reset() {
        pendingToken = null
        _state.value = FcmState.Off
    }

    private suspend fun send(token: String): FcmState {
        val outcome = try {
            server.register(token)
            FcmState.Active
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                e.isNetwork -> FcmState.Pending
                // A server answer (refused, or FCM turned off there) won't change by retrying the same token.
                e.status == 404 -> FcmState.ServerUnsupported
                else -> FcmState.Unavailable
            }
        } catch (e: Exception) {
            FcmState.Pending
        }
        pendingToken = if (outcome == FcmState.Pending) token else null
        return outcome
    }
}

class ApiFcmServer(private val api: ApiClient, private val features: ServerFeatures) : FcmServer {
    override suspend fun supported(): Boolean? = features.has(ServerFeatures.PUSH_FCM)

    override suspend fun register(token: String) {
        api.put<FcmTokenReq, FcmTokenRes>(PATH, FcmTokenReq(token))
    }

    companion object {
        const val PATH = "/v1/devices/current/fcm"
    }
}
