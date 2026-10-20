package com.meldiq.android.ui.screens.account

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.GriipApp
import com.meldiq.android.R
import com.meldiq.android.data.account.AccountRules
import com.meldiq.android.data.api.ApiErrors
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deleting this account (docs/ACCOUNT_DELETION.md). The person types the account's email and the password; the server
 * deletes the account (`DELETE /v2/account`); then this phone erases its copy of the account (other accounts on the
 * phone stay). Unit-tested in `DeleteAccountRulesTest`.
 */
object DeleteAccountRules {
    /** The confirmation needs the account's email (case and surrounding spaces don't matter) and a password. */
    fun confirmed(typedEmail: String, accountEmail: String, password: String): Boolean =
        accountEmail.isNotBlank() && password.isNotEmpty() && AccountRules.normalizeEmail(typedEmail) == AccountRules.normalizeEmail(accountEmail)

    /** null while the server hasn't been asked (the button waits); false: the server is older than this feature. */
    fun available(features: Set<String>?): Boolean? = features?.contains(ServerFeatures.ACCOUNT_DELETE)

    /** Why the account wasn't deleted. Only [Other] and [Offline] can't promise that nothing was deleted. */
    sealed interface Problem {
        data object Mismatch : Problem
        data object WrongPassword : Problem
        data class OwnsWorkspaces(val names: List<String>) : Problem
        data object Unavailable : Problem
        data object Offline : Problem
        data object Other : Problem
    }

    fun problem(e: Throwable): Problem = when {
        e !is ApiException -> Problem.Other
        e.code == "confirm_mismatch" -> Problem.Mismatch
        e.code == "invalid_credentials" -> Problem.WrongPassword
        e.code == "owns_workspaces" -> Problem.OwnsWorkspaces(workspaceNames(e.details))
        e.code == ApiErrors.UNSUPPORTED || e.status == 404 -> Problem.Unavailable
        e.isNetwork -> Problem.Offline
        else -> Problem.Other
    }

    /** The workspaces named in an `owns_workspaces` refusal: `{"workspaces": [{"id", "name", "members"}]}`. */
    fun workspaceNames(details: JsonElement?): List<String> =
        ((details as? JsonObject)?.get("workspaces") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
}

@Composable
private fun problemText(p: DeleteAccountRules.Problem): String = when (p) {
    DeleteAccountRules.Problem.Mismatch -> stringResource(R.string.acct_delete_err_mismatch)
    DeleteAccountRules.Problem.WrongPassword -> stringResource(R.string.acct_delete_err_password)
    is DeleteAccountRules.Problem.OwnsWorkspaces -> stringResource(R.string.acct_delete_err_owner, p.names.joinToString(", ").ifEmpty { "…" })
    DeleteAccountRules.Problem.Unavailable -> stringResource(R.string.acct_delete_err_unavailable)
    DeleteAccountRules.Problem.Offline -> stringResource(R.string.acct_delete_err_offline)
    DeleteAccountRules.Problem.Other -> stringResource(R.string.acct_delete_err_other)
}

@Composable
fun DeleteAccountSheet(accountEmail: String, onDismiss: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<DeleteAccountRules.Problem?>(null) }
    var features by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { features = runCatching { container.features.get() }.getOrNull() ?: emptySet() }
    val available = DeleteAccountRules.available(features)
    val deletedMessage = stringResource(R.string.acct_delete_done)
    GlassSheet({ if (!busy) onDismiss() }, title = stringResource(R.string.acct_delete)) {
        Text(stringResource(R.string.acct_delete_body, accountEmail), style = Griip.type.body, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.acct_delete_stays), style = Griip.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.acct_delete_phone), style = Griip.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        if (available == false) {
            StatusBanner(stringResource(R.string.acct_delete_err_unavailable), BannerKind.Info, icon = Icons.Outlined.Info)
            Spacer(Modifier.height(12.dp))
        }
        GriipTextField(
            typed, { typed = it.take(MAX_TYPED); problem = null }, stringResource(R.string.acct_delete_confirm_email), Modifier.fillMaxWidth(),
            placeholder = accountEmail,
        )
        Spacer(Modifier.height(8.dp))
        PasswordField(password, { password = it; problem = null }, stringResource(R.string.acct_current_password), !busy)
        Spacer(Modifier.height(12.dp))
        problem?.let {
            StatusBanner(problemText(it), BannerKind.Error)
            Spacer(Modifier.height(8.dp))
        }
        PillButton(
            stringResource(R.string.acct_delete_submit),
            {
                busy = true
                problem = null
                val app = context.applicationContext as GriipApp
                // In the account's own scope, not this sheet's: the server also closes this phone's socket and ends its
                // session, which can replace the screen before the answer arrives.
                container.scope.launch {
                    try {
                        container.accounts.deleteAccount(password, typed)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(app, deletedMessage, Toast.LENGTH_LONG).show()
                            // The server has deleted the account: this phone's keys, messages and files for it go too.
                            // Only while it's still the active account (erasing works on the active one).
                            if (app.container === container) app.eraseEverything()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            busy = false
                            problem = DeleteAccountRules.problem(e)
                        }
                    }
                }
            },
            Modifier.fillMaxWidth(), tone = ButtonTone.Danger, loading = busy,
            enabled = available == true && DeleteAccountRules.confirmed(typed, accountEmail, password) && !busy,
        )
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.acct_delete_keep), onDismiss, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, enabled = !busy)
        Spacer(Modifier.height(16.dp))
    }
}

/** Email addresses are at most 254 characters; a little room for stray spaces. */
private const val MAX_TYPED = 260
