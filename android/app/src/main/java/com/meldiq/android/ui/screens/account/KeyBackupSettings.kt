package com.meldiq.android.ui.screens.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * Settings › Key backup (docs/KEY_BACKUP.md): whether a new phone can get this account's keys with the password and an
 * emailed code, turning it on (the password seals the keys on this phone) and off. Hidden when the server doesn't
 * offer it.
 */
@Composable
fun KeyBackupRow() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var supported by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf<Boolean?>(null) }
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, BannerKind>?>(null) }
    val reload: () -> Unit = { scope.launch { runCatching { container.keyBackups.status() }.onSuccess { on = it.exists } } }
    LaunchedEffect(Unit) {
        supported = runCatching { container.features.has("key-backup") == true }.getOrDefault(false)
        if (supported) reload()
    }
    if (!supported) return

    val saved = stringResource(R.string.kb_settings_saved)
    val removed = stringResource(R.string.kb_settings_removed)
    MenuRow(
        if (on == true) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, stringResource(R.string.kb_settings_title),
        subtitle = when (on) {
            null -> stringResource(R.string.acct_settings_loading)
            true -> stringResource(R.string.kb_settings_on)
            false -> stringResource(R.string.kb_settings_off)
        },
        onClick = { message = null; open = true },
    )
    if (open) {
        GlassSheet({ if (!busy) open = false }, title = stringResource(R.string.kb_settings_title)) {
            Text(stringResource(R.string.kb_settings_explain), style = Griip.type.body, color = Griip.colors.textSecondary)
            message?.let { (text, kind) ->
                Spacer(Modifier.height(8.dp))
                StatusBanner(text, kind)
            }
            Spacer(Modifier.height(16.dp))
            if (on == true) {
                PillButton(
                    stringResource(R.string.kb_settings_turn_off),
                    {
                        busy = true
                        scope.launch {
                            runCatching { container.keyBackups.remove() }
                                .onSuccess { on = false; message = removed to BannerKind.Success }
                                .onFailure { message = it.friendly() to BannerKind.Error }
                            busy = false
                        }
                    },
                    Modifier.fillMaxWidth(), tone = ButtonTone.Danger, height = 52.dp, enabled = !busy,
                )
            } else {
                PillButton(stringResource(R.string.kb_settings_turn_on), { asking = true }, Modifier.fillMaxWidth(), height = 52.dp, enabled = !busy, loading = busy)
            }
        }
    }
    if (asking) {
        PasswordPrompt(
            title = stringResource(R.string.kb_settings_turn_on),
            message = stringResource(R.string.kb_settings_password),
            onDismiss = { asking = false },
            onConfirm = { password ->
                asking = false
                busy = true
                scope.launch {
                    runCatching { container.accounts.backUpKeys(password) }
                        .onSuccess { on = true; message = saved to BannerKind.Success }
                        .onFailure { message = it.friendly() to BannerKind.Error }
                    busy = false
                }
            },
        )
    }
}
