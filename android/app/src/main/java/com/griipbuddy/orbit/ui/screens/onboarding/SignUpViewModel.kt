package com.griipbuddy.orbit.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.griipbuddy.orbit.data.model.ProfileRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Input rules of the sign-up form (unit-tested in `SignUpViewModelTest`). */
object SignUpRules {
    const val MAX_NAME = 60
    const val MIN_USERNAME = 3
    const val MAX_USERNAME = 24
    const val MAX_INVITE = 20
    const val AVATAR_COLOURS = 8
    /** Typing pause before the username is checked with the server. */
    const val CHECK_DELAY_MS = 400L

    fun cleanName(input: String) = input.take(MAX_NAME)

    fun cleanUsername(input: String) = input.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(MAX_USERNAME)

    fun cleanInvite(input: String) = input.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(MAX_INVITE)

    /** "Nusrat Jahan" → "nusrat.jahan": the username offered while the person hasn't typed their own. */
    fun suggestUsername(name: String) = cleanUsername(name.lowercase().trim().replace(Regex("\\s+"), "."))
}

/** The server's answer about the username being typed. */
sealed interface UsernameCheck {
    /** Too short or badly formed (the form says why), or not asked yet. */
    data object Idle : UsernameCheck
    data object Checking : UsernameCheck
    data class Available(val username: String) : UsernameCheck
    data class Taken(val reason: String?) : UsernameCheck
    /** The server couldn't be asked; sign-up stays possible and the server decides. */
    data object Unreachable : UsernameCheck
}

data class SignUpState(
    val serverUrl: String,
    val displayName: String = "",
    val username: String = "",
    val inviteCode: String = "",
    val colorIndex: Int = 0,
    val usernameCheck: UsernameCheck = UsernameCheck.Idle,
    val submitting: Boolean = false,
    val error: AuthError? = null,
) {
    /** A format problem with a typed username; none while the field is empty. */
    val usernameIssue: ProfileRules.UsernameIssue? get() = if (username.isEmpty()) null else ProfileRules.usernameIssue(username)

    val canSubmit: Boolean
        get() = displayName.isNotBlank() && username.length >= SignUpRules.MIN_USERNAME && usernameIssue == null &&
            usernameCheck !is UsernameCheck.Taken && !submitting
}

/**
 * The sign-up form: a display name, a username checked with the server while typing, an optional invitation code
 * and an avatar colour. [submit] registers through [AuthGateway.register] (keys are made on the phone there); on
 * success the session becomes signed in and the app leaves onboarding by itself.
 */
class SignUpViewModel(
    private val gateway: AuthGateway,
    serverUrl: String,
    initialColor: Int = Random.nextInt(SignUpRules.AVATAR_COLOURS),
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState(serverUrl = serverUrl, colorIndex = initialColor))
    val state: StateFlow<SignUpState> = _state.asStateFlow()
    private var check: Job? = null

    fun onDisplayNameChange(input: String) {
        val before = _state.value
        val name = SignUpRules.cleanName(input)
        // The username follows the name until the person types their own.
        val follows = before.username.isEmpty() || before.username == SignUpRules.suggestUsername(before.displayName)
        val username = if (follows) SignUpRules.suggestUsername(name) else before.username
        _state.update { it.copy(displayName = name, username = username, error = null) }
        if (username != before.username) checkUsername()
    }

    fun onUsernameChange(input: String) {
        val username = SignUpRules.cleanUsername(input)
        if (username == _state.value.username) return
        _state.update { it.copy(username = username, error = null) }
        checkUsername()
    }

    fun onInviteChange(input: String) = _state.update { it.copy(inviteCode = SignUpRules.cleanInvite(input), error = null) }

    fun onColorChange(index: Int) = _state.update { it.copy(colorIndex = index.mod(SignUpRules.AVATAR_COLOURS)) }

    /** The server choice changed (Server sheet): the username is checked again against the new server. */
    fun onServerChange(url: String) {
        if (url == _state.value.serverUrl) return
        _state.update { it.copy(serverUrl = url) }
        checkUsername()
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val error = AuthErrors.attempt { gateway.register(s.serverUrl, s.username, s.displayName, s.colorIndex, s.inviteCode) }
            _state.update { it.copy(submitting = false, error = error) }
        }
    }

    private fun checkUsername() {
        check?.cancel()
        val asked = _state.value
        if (asked.username.length < SignUpRules.MIN_USERNAME || asked.usernameIssue != null) {
            _state.update { it.copy(usernameCheck = UsernameCheck.Idle) }
            return
        }
        _state.update { it.copy(usernameCheck = UsernameCheck.Checking) }
        check = viewModelScope.launch {
            delay(SignUpRules.CHECK_DELAY_MS)
            val result = try {
                val res = gateway.usernameAvailable(asked.serverUrl, asked.username)
                if (res.available) UsernameCheck.Available(res.username ?: asked.username) else UsernameCheck.Taken(res.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsernameCheck.Unreachable
            }
            _state.update { if (it.username == asked.username && it.serverUrl == asked.serverUrl) it.copy(usernameCheck = result) else it }
        }
    }
}
