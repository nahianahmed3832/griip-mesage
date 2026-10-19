package com.meldiq.android.ui.screens.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Pin
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
import com.meldiq.android.data.account.RecoveryRules
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.screens.onboarding.AuthScaffold
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/** Shows a raw recovery code as groups of four ("ABCD-EFGH-JKLM") while the field itself holds only the characters. */
private val CodeGroups = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(RecoveryRules.display(raw)),
        object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) = RecoveryRules.toDisplayOffset(offset).coerceAtMost(RecoveryRules.display(raw).length)
            override fun transformedToOriginal(offset: Int) = RecoveryRules.toRawOffset(offset, raw.length)
        },
    )
}

/**
 * Lost access to the account's email (docs/ACCOUNT_RECOVERY.md), step one: the address the account uses now, one of its
 * recovery codes and the new address. The note says plainly what this does not restore.
 */
@Composable
internal fun RecoverAccountScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.recover_title), subtitle = stringResource(R.string.recover_subtitle),
        onBack = onBack, backEnabled = !state.submitting,
        bottomBar = { StepButton(stringResource(R.string.recover_submit), stringResource(R.string.acct_working), state, state.canRecover) { focus.clearFocus(); vm.recover() } },
    ) {
        EmailField(state, vm)
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            state.recoveryCode, { v -> vm.edit { it.copy(recoveryCode = RecoveryRules.normalize(v).take(RecoveryRules.CODE_LENGTH)) } },
            stringResource(R.string.recover_code), placeholder = "ABCD-EFGH-JKLM", leadingIcon = Icons.Rounded.Key, shape = Shapes.field,
            enabled = !state.submitting, textStyle = Griip.type.body.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = CodeGroups,
            helper = stringResource(R.string.recover_code_help),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            state.newEmail, { v -> vm.edit { it.copy(newEmail = v.trim().take(AccountRules.EMAIL_MAX)) } }, stringResource(R.string.recover_new_email),
            placeholder = stringResource(R.string.acct_email_hint), leadingIcon = Icons.Rounded.Email, shape = Shapes.field, enabled = !state.submitting,
            error = if (state.newEmail.isNotEmpty() && !state.newEmailValid) stringResource(R.string.recover_new_email_invalid) else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canRecover) vm.recover() }),
        )
        Spacer(Modifier.height(Space.lg))
        StatusBanner(stringResource(R.string.recover_history_note), BannerKind.Info)
        FormProblems(state)
    }
}

/** Step two: the 6-digit code sent to the new address and a new password. Every signed-in app is signed out after. */
@Composable
internal fun RecoverConfirmScreen(vm: AccountFlowViewModel, state: AccountFormState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    AuthScaffold(
        title = stringResource(R.string.recover_confirm_title), subtitle = stringResource(R.string.recover_confirm_subtitle, state.newEmail),
        onBack = onBack, backEnabled = !state.submitting,
        bottomBar = { StepButton(stringResource(R.string.recover_confirm_submit), stringResource(R.string.acct_working), state, state.canRecoverConfirm) { focus.clearFocus(); vm.recoverConfirm() } },
    ) {
        GriipTextField(
            state.code, { v -> vm.edit { it.copy(code = AccountRules.cleanVerifyCode(v)) } }, stringResource(R.string.acct_code),
            placeholder = "123456", leadingIcon = Icons.Rounded.Pin, shape = Shapes.field, enabled = !state.submitting,
            textStyle = Griip.type.title.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        PasswordField(
            state.newPassword, { v -> vm.edit { it.copy(newPassword = v) } }, stringResource(R.string.acct_new_password), !state.submitting,
            issue = state.recoverPasswordIssue, helper = stringResource(R.string.acct_password_rule, AccountRules.PASSWORD_MIN),
            onDone = { if (state.canRecoverConfirm) vm.recoverConfirm() },
        )
        Spacer(Modifier.height(Space.lg))
        StatusBanner(stringResource(R.string.recover_signout_note), BannerKind.Info)
        FormProblems(state)
    }
}
