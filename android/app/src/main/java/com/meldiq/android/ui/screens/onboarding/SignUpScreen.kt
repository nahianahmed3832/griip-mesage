package com.meldiq.android.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meldiq.android.R
import com.meldiq.android.data.model.ProfileRules.UsernameIssue
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.theme.AvatarGradients
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/**
 * Sign-up: the same fields, checks and registration call as before the 2026-10-18 redesign (display name, username
 * checked with the server, optional invitation code, avatar colour), restyled. Back is held while keys are being made.
 */
@Composable
internal fun SignUpScreen(
    vm: SignUpViewModel,
    serverUrl: String,
    showServer: Boolean,
    onBack: () -> Unit,
    onServer: () -> Unit,
    onLogIn: () -> Unit,
) {
    val c = Griip.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(serverUrl) { vm.onServerChange(serverUrl) }
    val inviteError = state.error?.takeIf { AuthErrors.isInviteProblem(it) }
    val submit = {
        focus.clearFocus()
        vm.submit()
    }

    AuthScaffold(
        title = stringResource(R.string.signup_title),
        subtitle = stringResource(R.string.signup_subtitle),
        onBack = onBack,
        backEnabled = !state.submitting,
        actions = { if (showServer) CircleIconButton(Icons.Outlined.Dns, stringResource(R.string.auth_server_settings), onServer) },
        bottomBar = {
            PillButton(
                stringResource(if (state.submitting) R.string.signup_submitting else R.string.signup_submit),
                onClick = submit,
                enabled = state.canSubmit || state.submitting, // not dimmed while loading; loading stops taps
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
            )
            // Hidden while typing, so the form keeps the room above the keyboard.
            if (!WindowInsets.isImeVisible) AuthSwitchLink(stringResource(R.string.signup_have_account), stringResource(R.string.welcome_log_in), onLogIn)
        },
    ) {
        ProfilePreview(state.displayName, state.username, state.colorIndex, enabled = !state.submitting, onColor = vm::onColorChange)
        Spacer(Modifier.height(Space.xl))
        GriipTextField(
            state.displayName, vm::onDisplayNameChange, stringResource(R.string.signup_name),
            placeholder = stringResource(R.string.signup_name_hint), leadingIcon = Icons.Rounded.Person, shape = Shapes.field,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            state.username, vm::onUsernameChange, stringResource(R.string.signup_username),
            placeholder = stringResource(R.string.signup_username_hint), leadingIcon = Icons.Rounded.AlternateEmail, shape = Shapes.field,
            enabled = !state.submitting,
            helper = usernameHelper(state.usernameCheck),
            error = state.usernameIssue?.let { usernameIssueText(it) }
                ?: (state.usernameCheck as? UsernameCheck.Taken)?.let { it.reason ?: stringResource(R.string.signup_username_taken) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            trailing = {
                if (state.usernameCheck is UsernameCheck.Available) Icon(Icons.Rounded.Check, null, tint = c.success, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(Space.lg))
        GriipTextField(
            state.inviteCode, vm::onInviteChange, stringResource(R.string.signup_invite),
            placeholder = stringResource(R.string.signup_invite_hint), leadingIcon = Icons.Outlined.Key, shape = Shapes.field,
            enabled = !state.submitting,
            helper = stringResource(R.string.signup_invite_help),
            error = inviteError?.text(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) submit() else focus.clearFocus() }),
        )
        Spacer(Modifier.height(Space.xl))
        GlassCard(Modifier.fillMaxWidth(), shape = Shapes.panel) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(Icons.Outlined.Shield, c.primary, c.primarySoft, size = 40.dp)
                Spacer(Modifier.width(Space.md))
                Column {
                    Text(stringResource(R.string.signup_keys_title), style = Griip.type.titleSmall, color = c.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.signup_keys_body), style = Griip.type.bodySmall, color = c.textSecondary)
                }
            }
        }
        if (showServer) {
            Spacer(Modifier.height(Space.md))
            Text(stringResource(R.string.auth_server_line, serverUrl), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp))
        }
        if (serverUrl.startsWith("http://")) {
            Spacer(Modifier.height(Space.sm))
            StatusBanner(stringResource(R.string.auth_cleartext_warning), BannerKind.Warning, icon = Icons.Rounded.Warning)
        }
        state.error?.takeIf { inviteError == null }?.let {
            Spacer(Modifier.height(Space.md))
            AuthErrorBanner(it)
        }
    }
}

@Composable
private fun usernameHelper(check: UsernameCheck): String = when (check) {
    UsernameCheck.Checking -> stringResource(R.string.signup_username_checking)
    is UsernameCheck.Available -> stringResource(R.string.signup_username_available, check.username)
    UsernameCheck.Unreachable -> stringResource(R.string.signup_username_unchecked)
    UsernameCheck.Idle, is UsernameCheck.Taken -> stringResource(R.string.signup_username_help)
}

@Composable
private fun usernameIssueText(issue: UsernameIssue): String = stringResource(
    when (issue) {
        UsernameIssue.TOO_SHORT -> R.string.signup_username_too_short
        UsernameIssue.TOO_LONG -> R.string.signup_username_too_long
        UsernameIssue.CHARACTERS -> R.string.signup_username_characters
        UsernameIssue.DOUBLE_DOT -> R.string.signup_username_double_dot
    },
)

/** Avatar colours per row: two even rows of four on a phone. */
private const val SWATCHES_PER_ROW = 4

/** How the account will look to others, and the avatar colour picker (radio group, 48 dp targets). */
@Composable
private fun ProfilePreview(name: String, username: String, colorIndex: Int, enabled: Boolean, onColor: (Int) -> Unit) {
    val c = Griip.colors
    val shownName = name.ifBlank { stringResource(R.string.signup_name_preview) }
    val preview = stringResource(R.string.signup_preview, shownName, username)
    GlassCard(Modifier.fillMaxWidth(), shape = Shapes.panel) {
        Row(Modifier.clearAndSetSemantics { contentDescription = preview }, verticalAlignment = Alignment.CenterVertically) {
            if (name.isBlank()) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(AvatarGradients[colorIndex].first), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            } else {
                Avatar(name, colorIndex, size = 64.dp)
            }
            Spacer(Modifier.width(Space.lg))
            Column(Modifier.weight(1f)) {
                Text(shownName, style = Griip.type.title, color = if (name.isBlank()) c.textTertiary else c.textPrimary, maxLines = 1)
                Text("@" + username.ifBlank { stringResource(R.string.signup_username_hint) }, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 1)
            }
        }
        Spacer(Modifier.height(Space.md))
        Text(stringResource(R.string.signup_colour), style = Griip.type.label, color = c.textSecondary)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Start, maxItemsInEachRow = SWATCHES_PER_ROW) {
            AvatarGradients.take(SignUpRules.AVATAR_COLOURS).forEachIndexed { i, (tone, _) ->
                val label = stringResource(R.string.signup_colour_option, i + 1)
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .selectable(selected = i == colorIndex, enabled = enabled, role = Role.RadioButton, onClick = { onColor(i) })
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(tone)
                            .then(if (i == colorIndex) Modifier.border(2.dp, c.primary, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (i == colorIndex) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}
