package com.meldiq.android.ui.screens.account

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.meldiq.android.R
import com.meldiq.android.data.account.PasskeyDto
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.ValueChevron
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * The platform's passkey prompts (Credential Manager, 2026-10-23, docs/PASSKEYS.md). Both take the server's WebAuthn
 * options as JSON and return the platform's JSON answer for the server to verify. [context] is any context of the
 * activity (a sheet's or dialog's wraps it); the prompt needs the activity itself. Cancelling throws [Cancelled]; the
 * platform's other refusals become an [ApiException] with a readable message, as on Meldiq Web (auth/passkeys.ts).
 */
object PasskeyPrompt {
    class Cancelled : Exception("cancelled")

    suspend fun create(context: Context, optionsJson: String): String {
        val activity = context.activity()
        return try {
            val r = CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(optionsJson))
            (r as CreatePublicKeyCredentialResponse).registrationResponseJson
        } catch (e: CreateCredentialCancellationException) {
            throw Cancelled()
        } catch (e: CreatePublicKeyCredentialDomException) {
            if (e.domError is InvalidStateError) throw ApiException(409, "passkey_exists", activity.getString(R.string.ux_passkey_exists))
            if (e.domError is NotAllowedError) throw Cancelled()
            throw platform(activity, e.errorMessage, creating = true)
        } catch (e: CreateCredentialNoCreateOptionException) {
            throw ApiException(400, "passkey_platform", activity.getString(R.string.ux_passkey_no_manager))
        } catch (e: CreateCredentialException) {
            throw platform(activity, e.errorMessage, creating = true)
        }
    }

    suspend fun get(context: Context, optionsJson: String): String {
        val activity = context.activity()
        return try {
            val r = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))))
            (r.credential as? PublicKeyCredential)?.authenticationResponseJson ?: throw ApiException(400, "passkey_platform", "That wasn't a passkey. Try again.")
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: NoCredentialException) {
            throw ApiException(400, "no_passkey", "There's no passkey for this server on this phone. Sign in with your password, then add one in Account.")
        } catch (e: GetPublicKeyCredentialDomException) {
            if (e.domError is NotAllowedError) throw Cancelled()
            throw platform(activity, e.errorMessage, creating = false)
        } catch (e: GetCredentialException) {
            throw platform(activity, e.errorMessage, creating = false)
        }
    }

    private fun platform(context: Context, detail: CharSequence?, creating: Boolean): ApiException {
        android.util.Log.w("Passkeys", "platform refused (${if (creating) "create" else "get"}): $detail")
        val text = if (creating) "This phone couldn't make a passkey. Check that a screen lock and Google Password Manager are set up, then try again."
        else "This phone couldn't use a passkey. Try again, or sign in with your password."
        return ApiException(400, "passkey_platform", text)
    }

    private tailrec fun Context.activity(): Context = when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> if (baseContext === this) this else baseContext.activity()
        else -> this
    }
}

/** Whether the server the sign-in goes to offers passkeys (server-info `passkeys`, API 0.30.0); false until it says. */
@Composable
internal fun passkeysOffered(): Boolean {
    val container = LocalContainer.current
    val url = container.settings.current.serverUrl
    val offered by androidx.compose.runtime.produceState(false, url) {
        value = runCatching { container.session.serverInfo(url) }.getOrNull()?.passkeys != null
    }
    return offered
}

/** The Account page's passkey row, with its "Forget about your password" card while there are none. */
@Composable
internal fun PasskeysSection() {
    val container = LocalContainer.current
    var state by remember { mutableStateOf<com.meldiq.android.data.account.PasskeyListRes?>(null) }
    var open by remember { mutableStateOf(false) }
    val reload: suspend () -> Unit = { state = runCatching { container.accounts.passkeys() }.getOrNull() }
    LaunchedEffect(Unit) { reload() }
    val s = state ?: return
    if (!s.available) return
    if (s.passkeys.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
            Text(stringResource(R.string.ux_passkey_promo_title), style = Griip.type.titleSmall, color = Griip.colors.textPrimary)
            Text(stringResource(R.string.ux_passkey_promo_body), style = Griip.type.bodySmall, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            PillButton(stringResource(R.string.ux_get_started), { open = true }, height = 40.dp, showKnob = false)
        }
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp), contentPadding = PaddingValues(6.dp)) {
        MenuRow(
            Icons.Outlined.Key, stringResource(R.string.ux_passkeys), subtitle = stringResource(R.string.ux_passkeys_sub),
            onClick = { open = true }, trailing = { ValueChevron(stringResource(R.string.ux_passkeys_added, s.passkeys.size)) },
        )
    }
    if (open) PasskeysSheet(s.passkeys, onChanged = reload, onDismiss = { open = false })
}

@Composable
private fun PasskeysSheet(passkeys: List<PasskeyDto>, onChanged: suspend () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, BannerKind>?>(null) }
    val c = Griip.colors
    val added = stringResource(R.string.ux_passkey_added)
    GlassSheet(onDismiss, title = stringResource(R.string.ux_passkeys)) {
        Text(stringResource(R.string.ux_passkeys_explain), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        Text(stringResource(R.string.ux_passkeys_where), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(bottom = 10.dp))
        message?.let { (t, k) -> StatusBanner(t, k, Modifier.padding(bottom = 8.dp)) }
        passkeys.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = Griip.type.bodyMedium, color = c.textPrimary)
                    val used = p.lastUsedAt?.let { stringResource(R.string.ux_passkey_used, TimeFmt.relative(it)) } ?: stringResource(R.string.ux_passkey_never_used)
                    Text(
                        stringResource(R.string.ux_passkey_created, TimeFmt.dayHeader(p.createdAt)) + " · " + used + if (p.synced) " · " + stringResource(R.string.ux_passkey_synced) else "",
                        style = Griip.type.caption, color = c.textSecondary,
                    )
                }
                if (removing == p.id) {
                    // Asks once, as on Meldiq Web: a removed passkey can't sign in again until it's added again.
                    PillButton(stringResource(R.string.ux_passkey_keep), { removing = null }, height = 36.dp, showKnob = false, tone = ButtonTone.Glass, enabled = !busy)
                    Spacer(Modifier.width(6.dp))
                    PillButton(stringResource(R.string.ux_passkey_remove_confirm), {
                        busy = true
                        scope.launch {
                            runCatching { container.accounts.removePasskey(p.id) }.onSuccess { onChanged() }.onFailure { message = it.friendly() to BannerKind.Error }
                            busy = false
                            removing = null
                        }
                    }, height = 36.dp, showKnob = false, tone = ButtonTone.Danger, enabled = !busy)
                } else {
                    CircleIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.ux_passkey_remove), { removing = p.id }, tint = c.danger)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PillButton(
            stringResource(if (busy) R.string.acct_working else R.string.ux_passkey_add),
            {
                busy = true
                message = null
                scope.launch {
                    try {
                        container.accounts.addPasskey(android.os.Build.MODEL ?: "Android") { json -> PasskeyPrompt.create(context, json) }
                        message = added to BannerKind.Success
                        onChanged()
                    } catch (_: PasskeyPrompt.Cancelled) {
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        message = e.friendly() to BannerKind.Error
                    }
                    busy = false
                }
            },
            Modifier.fillMaxWidth(), enabled = !busy, leadingIcon = Icons.Outlined.Key, tone = ButtonTone.Primary,
        )
        Spacer(Modifier.width(8.dp).height(16.dp))
    }
}
