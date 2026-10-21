package com.meldiq.android.ui.screens.workspace

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * Deleting a workspace (2026-10-17, docs/WORKSPACES.md › Deleting a workspace). Owners only, after typing its exact name;
 * the server checks both again and deletes the organization in one step. Unit-tested in `DeleteWorkspaceRulesTest`.
 */
object DeleteWorkspaceRules {
    /** Only the workspace's owner sees the option (not admins, and never from the workspace switcher). */
    fun visible(myRole: String?): Boolean = myRole == "OWNER"

    /** The confirmation needs the workspace's exact name (surrounding spaces ignored). */
    fun confirmed(typed: String, orgName: String): Boolean = orgName.isNotBlank() && typed.trim() == orgName.trim()

    /** null while the server hasn't been asked (the button waits); false: the server is older than this feature. */
    fun available(features: Set<String>?): Boolean? = features?.contains(ServerFeatures.ORG_DELETE)

    const val NOT_AVAILABLE = "Your server can't delete workspaces yet (it needs an update). Nothing was deleted."

    /** What to tell the owner when the server refused or couldn't be reached. Nothing was deleted in any of these cases. */
    fun failure(e: Throwable): String = when {
        e is ApiException && e.code == "confirm_mismatch" -> "The name doesn't match the workspace's name. Nothing was deleted."
        e is ApiException && e.status == 403 -> "Only an owner can delete this workspace. Nothing was deleted."
        e is ApiException && e.status == 404 && e.code != "unsupported" -> "This workspace no longer exists."
        e is ApiException && e.code == "unsupported" -> NOT_AVAILABLE
        e is ApiException && e.isNetwork -> "Couldn't reach the server. Nothing was deleted; try again when you're online."
        e is ApiException -> "${e.message} Nothing was deleted."
        else -> "Something went wrong. Nothing was deleted."
    }
}

@Composable
fun DeleteWorkspaceSheet(d: OrgDetailRes, onDismiss: () -> Unit, onDeleted: (String) -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var features by remember { mutableStateOf<Set<String>?>(null) }
    // The same id for every attempt from this sheet: a retry after a lost answer gets the same result.
    val requestId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    LaunchedEffect(Unit) { features = container.features.get() }
    val available = DeleteWorkspaceRules.available(features)
    GlassSheet({ if (!busy) onDismiss() }, title = "Delete workspace") {
        Text(
            "Deleting “${d.org.name}” removes it for ${if (d.stats.members == 1) "its only member" else "all ${d.stats.members} members"}: " +
                "its channels and their files on the server, its categories, roles and invitations. It can't be undone.",
            style = Griip.type.body, color = c.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "People's accounts, their direct messages and groups, and other workspaces aren't affected. Messages already on " +
                "members' phones stay there, shown as a channel they've left.",
            style = Griip.type.bodySmall, color = c.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        if (available == false) {
            StatusBanner(DeleteWorkspaceRules.NOT_AVAILABLE, BannerKind.Info, icon = Icons.Outlined.Info)
            Spacer(Modifier.height(12.dp))
        }
        GriipTextField(typed, { typed = it.take(80); problem = null }, "Type the workspace name to confirm", Modifier.fillMaxWidth(), placeholder = d.org.name)
        Spacer(Modifier.height(12.dp))
        problem?.let {
            StatusBanner(it, BannerKind.Error)
            Spacer(Modifier.height(8.dp))
        }
        PillButton(
            "Delete workspace",
            {
                busy = true
                problem = null
                scope.launch {
                    runCatching { container.orgs.delete(d.org.id, typed, requestId) }
                        .onSuccess { busy = false; onDeleted(d.org.name) }
                        .onFailure { e -> busy = false; problem = DeleteWorkspaceRules.failure(e) }
                }
            },
            Modifier.fillMaxWidth(), tone = ButtonTone.Danger, loading = busy,
            enabled = available == true && DeleteWorkspaceRules.confirmed(typed, d.org.name) && !busy,
        )
        Spacer(Modifier.height(8.dp))
        PillButton("Keep workspace", onDismiss, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, enabled = !busy)
        Spacer(Modifier.height(16.dp))
    }
}
