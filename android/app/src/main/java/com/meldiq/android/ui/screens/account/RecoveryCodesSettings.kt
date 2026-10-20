package com.meldiq.android.ui.screens.account

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.account.RecoveryCodesStatus
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import kotlinx.coroutines.launch

/** What the person is doing with their codes: a password is asked for both making and revoking a set. */
private enum class CodesAction { Create, Revoke }

/**
 * Settings › Security › Recovery codes (docs/ACCOUNT_RECOVERY.md): how many are left, making a new set (shown once,
 * screenshots blocked while shown) and turning them off. Hidden when the server doesn't offer them.
 */
@Composable
fun RecoveryCodesRow() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RecoveryCodesStatus?>(null) }
    var supported by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<CodesAction?>(null) }
    var shown by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reload: () -> Unit = { scope.launch { runCatching { container.recoveryCodes.status() }.onSuccess { status = it; supported = true } } }
    LaunchedEffect(Unit) {
        supported = runCatching { container.features.has("recovery-codes") == true }.getOrDefault(false)
        if (supported) reload()
    }
    if (!supported) return

    val s = status
    MenuRow(
        Icons.Outlined.Key, stringResource(R.string.codes_title),
        subtitle = when {
            s == null -> stringResource(R.string.acct_settings_loading)
            s.total == 0 -> stringResource(R.string.codes_none)
            else -> stringResource(R.string.codes_left, s.remaining, s.total)
        },
        onClick = { error = null; open = true },
    )
    if (open) {
        GlassSheet({ open = false }, title = stringResource(R.string.codes_title)) {
            Text(stringResource(R.string.codes_explainer), style = Griip.type.body, color = Griip.colors.textSecondary)
            Spacer(Modifier.height(12.dp))
            StatusBanner(stringResource(R.string.codes_not_key), BannerKind.Info)
            error?.let {
                Spacer(Modifier.height(8.dp))
                StatusBanner(it, BannerKind.Error)
            }
            Spacer(Modifier.height(16.dp))
            PillButton(
                stringResource(if ((s?.total ?: 0) == 0) R.string.codes_create else R.string.codes_replace),
                { asking = CodesAction.Create }, Modifier.fillMaxWidth(), height = 52.dp,
            )
            if ((s?.remaining ?: 0) > 0) {
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.codes_turn_off), { asking = CodesAction.Revoke }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, height = 52.dp)
            }
        }
    }
    asking?.let { action ->
        PasswordPrompt(
            title = stringResource(if (action == CodesAction.Create) R.string.codes_create else R.string.codes_turn_off),
            onDismiss = { asking = null },
            onConfirm = { password ->
                asking = null
                scope.launch {
                    runCatching {
                        if (action == CodesAction.Create) shown = container.recoveryCodes.generate(password).codes
                        else container.recoveryCodes.revoke(password)
                    }.onSuccess { error = null; open = false; reload() }.onFailure { error = it.friendly() }
                }
            },
        )
    }
    shown?.let { codes -> NewCodesSheet(codes) { shown = null } }
}

/** Asks for the password before a security change; [message] says what it is for. */
@Composable
internal fun PasswordPrompt(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, message: String = stringResource(R.string.codes_password_prompt)) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = Griip.type.bodySmall, color = Griip.colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                PasswordField(password, { password = it }, stringResource(R.string.acct_password), enabled = true, onDone = { if (password.isNotEmpty()) onConfirm(password) })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.codes_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The new set, shown once. The window is marked secure meanwhile (no screenshots or recents preview), and "Copy" marks
 * the clip sensitive so the keyboard and clipboard previews hide it.
 */
@Composable
private fun NewCodesSheet(codes: List<String>, onDone: () -> Unit) {
    val c = Griip.colors
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.codes_title)
    GlassSheet(onDone, title = stringResource(R.string.codes_new_title)) {
        Text(stringResource(R.string.codes_new_body), style = Griip.type.body, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(c.surfaceVariant, Shapes.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            codes.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    pair.forEach { code ->
                        Text(code, style = Griip.type.body.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            stringResource(R.string.codes_copy),
            {
                val clip = ClipData.newPlainText(label, codes.joinToString("\n"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
            },
            Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 52.dp,
        )
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.codes_saved), onDone, Modifier.fillMaxWidth(), height = 52.dp)
    }
}
