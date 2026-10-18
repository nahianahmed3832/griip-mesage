package com.griipbuddy.orbit.ui.screens.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.account.AccountRepository
import com.griipbuddy.orbit.data.api.UsernameAvailableRes
import com.griipbuddy.orbit.data.repo.PendingPairing
import com.griipbuddy.orbit.data.repo.SessionRepository
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ChoiceCard
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.screens.onboarding.AuthGateway
import com.griipbuddy.orbit.ui.screens.onboarding.AuthScaffold
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/**
 * Signed in on a phone without this account's encryption keys: the three ways to get them. The password can't decrypt
 * messages, which is why this step exists (docs/ORBIT_2_ARCHITECTURE §3).
 */
@Composable
internal fun DeviceSetupScreen(vm: AccountFlowViewModel, state: AccountFormState, onSignOut: () -> Unit) {
    AuthScaffold(title = stringResource(R.string.acct_setup_title), subtitle = stringResource(R.string.acct_setup_subtitle), onBack = onSignOut) {
        ChoiceCard(Icons.Outlined.PhonelinkSetup, stringResource(R.string.acct_setup_link), stringResource(R.string.acct_setup_link_sub), { vm.goTo(AccountStep.Link) })
        Spacer(Modifier.height(Space.md))
        ChoiceCard(Icons.Outlined.Key, stringResource(R.string.acct_setup_key), stringResource(R.string.acct_setup_key_sub), { vm.goTo(AccountStep.RestoreKey) })
        Spacer(Modifier.height(Space.md))
        ChoiceCard(Icons.Outlined.RestartAlt, stringResource(R.string.acct_setup_fresh), stringResource(R.string.acct_setup_fresh_sub), { vm.goTo(AccountStep.StartFresh) })
        FormProblems(state)
    }
}

@Composable
internal fun RecoveryKeyField(state: AccountFormState, vm: AccountFlowViewModel, onDone: () -> Unit) {
    GriipTextField(
        state.recoveryKey, { v -> vm.edit { it.copy(recoveryKey = v.uppercase()) } }, stringResource(R.string.restore_key),
        placeholder = stringResource(R.string.restore_key_hint), leadingIcon = Icons.Rounded.Lock, shape = Shapes.field,
        singleLine = false, minLines = 3, enabled = !state.submitting,
        textStyle = Griip.type.body.copy(fontFamily = FontFamily.Monospace),
        helper = stringResource(R.string.restore_key_help),
        // As on the legacy restore screen: a password keyboard keeps the key out of suggestions and learned words.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
internal fun RestoreKeyScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.acct_restore_title), subtitle = stringResource(R.string.acct_restore_subtitle),
        onBack = onBack, backEnabled = !state.submitting,
        bottomBar = { StepButton(stringResource(R.string.restore_submit), stringResource(R.string.restore_submitting), state, state.canRestore) { focus.clearFocus(); vm.restoreWithKey() } },
    ) {
        RecoveryKeyField(state, vm) { if (state.canRestore) vm.restoreWithKey() }
        FormProblems(state)
    }
}

/** "Start fresh" warns plainly what is lost, and asks for the password again. */
@Composable
internal fun StartFreshScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.acct_fresh_title), subtitle = null, onBack = onBack, backEnabled = !state.submitting,
        bottomBar = { StepButton(stringResource(R.string.acct_fresh_submit), stringResource(R.string.acct_working), state, state.canStartFresh) { focus.clearFocus(); vm.startFresh() } },
    ) {
        StatusBanner(stringResource(R.string.acct_fresh_warning), BannerKind.Warning, icon = Icons.Rounded.Warning)
        Spacer(Modifier.height(Space.lg))
        PasswordField(state.password, { v -> vm.edit { it.copy(password = v) } }, stringResource(R.string.acct_password), !state.submitting, onDone = { if (state.canStartFresh) vm.startFresh() })
        FormProblems(state)
    }
}

/**
 * Lets the existing link-a-phone screen ([com.griipbuddy.orbit.ui.screens.onboarding.LinkDeviceViewModel]) finish an
 * Orbit 2.0 device setup: the other phone seals the identity to this one as before, and this phone then binds its
 * account session with those keys instead of signing in the legacy way.
 */
class AccountLinkGateway(private val session: SessionRepository, private val accounts: AccountRepository) : AuthGateway {
    override suspend fun startPairing(serverUrl: String): PendingPairing = session.startPairing(serverUrl)

    override suspend fun pollPairing(pairing: PendingPairing): Boolean {
        val (keys, _) = session.pollPairingKeys(pairing) ?: return false
        accounts.devices.withLinkedKeys(keys)
        return true
    }

    override suspend fun usernameAvailable(serverUrl: String, username: String): UsernameAvailableRes = unsupported()
    override suspend fun register(serverUrl: String, username: String, displayName: String, avatarColor: Int, inviteCode: String?) = unsupported()
    override suspend fun restore(serverUrl: String, username: String, recoveryKey: String) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not part of linking")
}
