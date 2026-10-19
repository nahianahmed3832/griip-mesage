package com.meldiq.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meldiq.android.data.repo.PendingPairing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RestoreState(
    val username: String = "",
    val recoveryKey: String = "",
    val submitting: Boolean = false,
    val error: AuthError? = null,
) {
    val canSubmit: Boolean get() = username.length >= MIN_USERNAME && recoveryKey.length >= MIN_RECOVERY_KEY && !submitting

    companion object {
        const val MIN_USERNAME = 3
        /** Shorter than any recovery key (the key itself is checked when it's decoded). */
        const val MIN_RECOVERY_KEY = 20
    }
}

/**
 * Signing in with the recovery key: the same identity on this phone, with a new device key ([AuthGateway.restore]).
 * Unit-tested in `SignInViewModelsTest`.
 */
class RestoreViewModel(private val gateway: AuthGateway) : ViewModel() {
    private val _state = MutableStateFlow(RestoreState())
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    fun onUsernameChange(input: String) = _state.update { it.copy(username = input.lowercase().trim(), error = null) }

    fun onRecoveryKeyChange(input: String) = _state.update { it.copy(recoveryKey = input.uppercase(), error = null) }

    fun submit(serverUrl: String) {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val error = AuthErrors.attempt { gateway.restore(serverUrl, s.username, s.recoveryKey) }
            _state.update { it.copy(submitting = false, error = error) }
        }
    }
}

/** Where linking this phone from another one stands. */
sealed interface LinkState {
    data object Starting : LinkState
    /** Show [code]; the other phone enters it and approves. */
    data class Waiting(val code: String) : LinkState
    data class Failed(val error: AuthError) : LinkState
    data object Linked : LinkState
}

/**
 * Linking this phone from one that is already signed in: asks the server for a link code, then checks every
 * [pollMillis] until the other phone approves ([AuthGateway.pollPairing] signs this phone in) or the code expires.
 * The screen starts it when shown and stops it when left. Unit-tested in `SignInViewModelsTest`.
 */
class LinkDeviceViewModel(
    private val gateway: AuthGateway,
    private val pollMillis: Long = POLL_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow<LinkState>(LinkState.Starting)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private var job: Job? = null
    private var serverUrl: String? = null

    fun start(serverUrl: String) {
        if (job?.isActive == true && serverUrl == this.serverUrl) return
        job?.cancel()
        this.serverUrl = serverUrl
        job = viewModelScope.launch { link(serverUrl) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A fresh code after a failure. */
    fun retry() {
        val url = serverUrl ?: return
        stop()
        start(url)
    }

    private suspend fun link(serverUrl: String) {
        _state.value = LinkState.Starting
        var pairing: PendingPairing? = null
        AuthErrors.attempt { pairing = gateway.startPairing(serverUrl) }?.let { _state.value = LinkState.Failed(it); return }
        val pending = pairing ?: return
        _state.value = LinkState.Waiting(pending.code)
        while (true) {
            delay(pollMillis)
            var linked = false
            AuthErrors.attempt { linked = gateway.pollPairing(pending) }?.let { _state.value = LinkState.Failed(it); return }
            if (linked) {
                _state.value = LinkState.Linked
                return
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 2_000L
    }
}
