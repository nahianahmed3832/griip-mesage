package com.griipbuddy.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.ui.components.ChoiceCard
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/** Characters per group when a link code is shown ("ABCDE FGHJK"). */
private const val CODE_GROUP = 5

/**
 * "Log in": Orbit has no passwords, so signing in on this phone means bringing the account's keys here, either from a
 * phone that is already signed in or with the recovery key.
 */
@Composable
internal fun LogInScreen(
    showServer: Boolean,
    onBack: () -> Unit,
    onServer: () -> Unit,
    onLink: () -> Unit,
    onRestore: () -> Unit,
    onSignUp: () -> Unit,
) {
    AuthScaffold(
        title = stringResource(R.string.login_title),
        subtitle = stringResource(R.string.login_subtitle),
        onBack = onBack,
        actions = { if (showServer) CircleIconButton(Icons.Outlined.Dns, stringResource(R.string.auth_server_settings), onServer) },
        bottomBar = { AuthSwitchLink(stringResource(R.string.login_new), stringResource(R.string.welcome_sign_up), onSignUp) },
    ) {
        ChoiceCard(Icons.Outlined.PhonelinkSetup, stringResource(R.string.login_link), stringResource(R.string.login_link_sub), onLink)
        Spacer(Modifier.height(Space.md))
        ChoiceCard(Icons.Outlined.Key, stringResource(R.string.login_restore), stringResource(R.string.login_restore_sub), onRestore)
    }
}

/** Sign in with the recovery key: username and key, as before the redesign. */
@Composable
internal fun RestoreScreen(vm: RestoreViewModel, serverUrl: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyError = state.error?.takeIf { AuthErrors.isRecoveryKeyProblem(it) }
    val submit = {
        focus.clearFocus()
        vm.submit(serverUrl)
    }
    AuthScaffold(
        title = stringResource(R.string.restore_title),
        subtitle = stringResource(R.string.restore_subtitle),
        onBack = onBack,
        backEnabled = !state.submitting,
        bottomBar = {
            PillButton(
                stringResource(if (state.submitting) R.string.restore_submitting else R.string.restore_submit),
                onClick = submit, enabled = state.canSubmit || state.submitting, loading = state.submitting, modifier = Modifier.fillMaxWidth(), height = 56.dp,
            )
        },
    ) {
        GriipTextField(
            state.username, vm::onUsernameChange, stringResource(R.string.signup_username),
            placeholder = stringResource(R.string.signup_username_hint), leadingIcon = Icons.Rounded.AlternateEmail, shape = Shapes.field,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            state.recoveryKey, vm::onRecoveryKeyChange, stringResource(R.string.restore_key),
            placeholder = stringResource(R.string.restore_key_hint), leadingIcon = Icons.Rounded.Lock, shape = Shapes.field,
            singleLine = false, minLines = 3, enabled = !state.submitting,
            textStyle = Griip.type.body.copy(fontFamily = FontFamily.Monospace),
            helper = stringResource(R.string.restore_key_help),
            error = keyError?.text(),
            // A password-type keyboard keeps the key out of the keyboard's suggestions and learned words, and types it
            // without composing (upper-casing a composing word lost characters on the phone). The key stays visible.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) submit() else focus.clearFocus() }),
        )
        state.error?.takeIf { keyError == null }?.let {
            Spacer(Modifier.height(Space.md))
            AuthErrorBanner(it)
        }
    }
}

/** Link this phone from another: shows the code the other phone enters, and waits for its approval. */
@Composable
internal fun LinkDeviceScreen(vm: LinkDeviceViewModel, serverUrl: String, onBack: () -> Unit) {
    val c = Griip.colors
    val state by vm.state.collectAsStateWithLifecycle()
    // Ask for a code while this screen is shown; leaving it stops waiting (the code then simply expires).
    DisposableEffect(serverUrl) {
        vm.start(serverUrl)
        onDispose { vm.stop() }
    }
    AuthScaffold(title = stringResource(R.string.link_title), subtitle = stringResource(R.string.link_subtitle), onBack = onBack) {
        when (val s = state) {
            is LinkState.Failed -> AuthErrorBanner(s.error, onRetry = vm::retry)
            else -> LinkCodeCard(s)
        }
        Spacer(Modifier.height(Space.lg))
        Text(stringResource(R.string.link_footer), style = Griip.type.caption, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** The code (or what is happening instead), announced by TalkBack as it changes. */
@Composable
private fun LinkCodeCard(state: LinkState) {
    val c = Griip.colors
    GlassCard(Modifier.fillMaxWidth(), shape = Shapes.panel) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 132.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                is LinkState.Waiting -> {
                    val label = stringResource(R.string.link_code_label)
                    Text(label, style = Griip.type.label, color = c.textSecondary)
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        state.code.chunked(CODE_GROUP).joinToString(" "),
                        style = Griip.type.display.copy(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 2.sp),
                        color = c.textPrimary, textAlign = TextAlign.Center,
                        // Read letter by letter, not as words.
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label: " + state.code.toList().joinToString(" ") },
                    )
                    Spacer(Modifier.height(Space.md))
                    LinkStatus(stringResource(R.string.link_waiting))
                }
                LinkState.Linked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text(stringResource(R.string.link_linked), style = Griip.type.bodyMedium, color = c.textPrimary)
                }
                LinkState.Starting, is LinkState.Failed -> LinkStatus(stringResource(R.string.link_starting))
            }
        }
    }
}

@Composable
private fun LinkStatus(text: String) {
    val c = Griip.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = c.primary, strokeWidth = 2.dp)
        Spacer(Modifier.width(Space.sm))
        Text(text, style = Griip.type.caption, color = c.textSecondary)
    }
}
