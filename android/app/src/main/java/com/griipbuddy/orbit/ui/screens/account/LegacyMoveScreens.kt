package com.griipbuddy.orbit.ui.screens.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.account.AccountRules
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.screens.onboarding.AuthScaffold
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/**
 * Moving a legacy account, step 1: the old username and the recovery key. On a recognised installation the username is
 * this phone's and only shown. The key is checked on the phone and never sent (docs/ORBIT_2_ARCHITECTURE §5).
 */
@Composable
internal fun LegacyCredentialsScreen(
    vm: AccountFlowViewModel,
    state: AccountFormState,
    recognizedUsername: String?,
    onBack: () -> Unit,
    onLater: (() -> Unit)?,
    canGoBack: Boolean = true,
) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.acct_legacy_title), subtitle = stringResource(R.string.acct_legacy_subtitle),
        onBack = onBack, backEnabled = canGoBack && !state.submitting,
        bottomBar = {
            StepButton(stringResource(R.string.acct_legacy_verify), stringResource(R.string.acct_working), state, state.canLegacyVerify) { focus.clearFocus(); vm.legacyVerify() }
            if (onLater != null) com.griipbuddy.orbit.ui.screens.onboarding.AuthSwitchLink(stringResource(R.string.acct_legacy_later_q), stringResource(R.string.acct_legacy_later), onLater)
        },
    ) {
        StatusBanner(stringResource(R.string.acct_legacy_explain), BannerKind.Info, icon = Icons.Rounded.Info)
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            recognizedUsername ?: state.legacyUsername, { v -> vm.edit { it.copy(legacyUsername = v.lowercase().trim().removePrefix("@")) } },
            stringResource(R.string.signup_username), leadingIcon = Icons.Rounded.AlternateEmail, shape = Shapes.field,
            enabled = recognizedUsername == null && !state.submitting,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        RecoveryKeyField(state, vm) { if (state.canLegacyVerify) vm.legacyVerify() }
        FormProblems(state)
    }
}

/** Step 2: the new account's name, email and password. The emailed code comes next. */
@Composable
internal fun LegacyDetailsScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.acct_legacy_details_title), subtitle = stringResource(R.string.acct_legacy_details_subtitle),
        onBack = onBack, backEnabled = !state.submitting,
        bottomBar = { StepButton(stringResource(R.string.acct_legacy_details_submit), stringResource(R.string.acct_working), state, state.canLegacyComplete) { focus.clearFocus(); vm.legacyComplete() } },
    ) {
        GriipTextField(
            state.name, { v -> vm.edit { it.copy(name = AccountRules.cleanName(v)) } }, stringResource(R.string.acct_name),
            leadingIcon = Icons.Rounded.Person, shape = Shapes.field, enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        EmailField(state, vm)
        Spacer(Modifier.height(Space.lg))
        PasswordField(
            state.password, { v -> vm.edit { it.copy(password = v) } }, stringResource(R.string.acct_password), !state.submitting,
            issue = state.passwordIssue, helper = stringResource(R.string.acct_password_rule, AccountRules.PASSWORD_MIN),
            onDone = { if (state.canLegacyComplete) vm.legacyComplete() },
        )
        FormProblems(state)
    }
}
