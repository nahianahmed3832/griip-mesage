package com.meldiq.android.ui.screens.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.meldiq.android.R
import com.meldiq.android.data.account.AccountRules
import com.meldiq.android.data.account.AccountRepository
import com.meldiq.android.data.api.UsernameAvailableRes
import com.meldiq.android.data.repo.PendingPairing
import com.meldiq.android.data.repo.SessionRepository
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChoiceCard
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.screens.onboarding.AuthGateway
import com.meldiq.android.ui.screens.onboarding.AuthScaffold
import com.meldiq.android.ui.screens.onboarding.AuthSwitchLink
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/**
 * Signed in on a phone without this account's encryption keys: the ways to get them (docs/ORBIT_2_ARCHITECTURE §3).
 * With a key backup for the current password, an emailed code comes first (docs/KEY_BACKUP.md); another phone, the
 * recovery key and starting fresh are for when the email can't work.
 */
@Composable
internal fun DeviceSetupScreen(vm: AccountFlowViewModel, state: AccountFormState, onSignOut: () -> Unit) {
    AuthScaffold(title = stringResource(R.string.acct_setup_title), subtitle = stringResource(R.string.acct_setup_subtitle), onBack = onSignOut) {
        when (state.keyBackup) {
            true -> {
                ChoiceCard(Icons.Outlined.MarkEmailRead, stringResource(R.string.kb_setup_email), stringResource(R.string.kb_setup_email_sub), { if (!state.submitting) vm.startEmailUnlock() })
                Spacer(Modifier.height(Space.lg))
                SectionHeader(stringResource(R.string.kb_setup_other_ways))
            }
            false -> {
                StatusBanner(stringResource(R.string.kb_setup_no_backup), BannerKind.Info, icon = Icons.Rounded.Info)
                Spacer(Modifier.height(Space.md))
            }
            null -> Unit
        }
        ChoiceCard(Icons.Outlined.PhonelinkSetup, stringResource(R.string.acct_setup_link), stringResource(R.string.acct_setup_link_sub), { vm.goTo(AccountStep.Link) })
        Spacer(Modifier.height(Space.md))
        ChoiceCard(Icons.Outlined.Key, stringResource(R.string.acct_setup_key), stringResource(R.string.acct_setup_key_sub), { vm.goTo(AccountStep.RestoreKey) })
        Spacer(Modifier.height(Space.md))
        ChoiceCard(Icons.Outlined.RestartAlt, stringResource(R.string.acct_setup_fresh), stringResource(R.string.acct_setup_fresh_sub), { vm.goTo(AccountStep.StartFresh) })
        FormProblems(state)
    }
}

/** The code emailed for unlocking the key backup, and the password again if the app forgot it since sign-in. */
@Composable
internal fun EmailUnlockScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.kb_unlock_title), subtitle = stringResource(R.string.kb_unlock_subtitle, state.unlockSentTo.orEmpty()),
        onBack = onBack, backEnabled = !state.submitting,
        bottomBar = {
            StepButton(stringResource(R.string.kb_unlock_submit), stringResource(R.string.acct_working), state, state.canUnlock) { focus.clearFocus(); vm.unlockWithEmail() }
            AuthSwitchLink(stringResource(R.string.acct_no_code), stringResource(R.string.acct_resend), vm::startEmailUnlock)
        },
    ) {
        GriipTextField(
            state.code, { v -> vm.edit { it.copy(code = AccountRules.cleanVerifyCode(v)) } }, stringResource(R.string.acct_code),
            placeholder = "123456", leadingIcon = Icons.Rounded.Pin, shape = Shapes.field, enabled = !state.submitting,
            textStyle = Griip.type.title.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (state.unlockNeedsPassword) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canUnlock) vm.unlockWithEmail() }),
        )
        if (state.unlockNeedsPassword) {
            Spacer(Modifier.height(Space.lg))
            PasswordField(
                state.password, { v -> vm.edit { it.copy(password = v) } }, stringResource(R.string.acct_password), !state.submitting,
                helper = stringResource(R.string.kb_unlock_password_help), onDone = { if (state.canUnlock) vm.unlockWithEmail() },
            )
        }
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
 * Lets the existing link-a-phone screen ([com.meldiq.android.ui.screens.onboarding.LinkDeviceViewModel]) finish an
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
