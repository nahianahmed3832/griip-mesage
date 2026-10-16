package com.griipbuddy.orbit.ui.screens.workspace

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.data.api.OrgDetailRes
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.theme.Griip

/**
 * Deleting a workspace (2026-10-17): the confirmation only. The server has no way to delete a workspace from the app yet,
 * so this never calls the server and never says anything was deleted: it explains what deletion would do and that it
 * isn't available. Unit-tested in `DeleteWorkspaceRulesTest`.
 */
object DeleteWorkspaceRules {
    /** Only the workspace's owner sees the option (not admins, and never from the workspace switcher). */
    fun visible(myRole: String?): Boolean = myRole == "OWNER"

    /** The confirmation needs the workspace's exact name (surrounding spaces ignored). */
    fun confirmed(typed: String, orgName: String): Boolean = orgName.isNotBlank() && typed.trim() == orgName.trim()

    const val NOT_AVAILABLE = "Deleting a workspace isn't available yet. Nothing was deleted."
}

@Composable
fun DeleteWorkspaceSheet(d: OrgDetailRes, onDismiss: () -> Unit) {
    val c = Griip.colors
    var typed by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    GlassSheet(onDismiss, title = "Delete workspace") {
        Text(
            "Deleting “${d.org.name}” would remove it for all ${d.stats.members} members, with its channels, teams, branches and " +
                "invitations. It can't be undone.",
            style = Griip.type.body, color = c.textPrimary,
        )
        Spacer(Modifier.height(12.dp))
        StatusBanner(
            "Not available yet: this beta can't delete workspaces, so nothing will be deleted. To remove an empty workspace " +
                "now, ask your server's administrator.",
            BannerKind.Info, icon = Icons.Outlined.Info,
        )
        Spacer(Modifier.height(12.dp))
        GriipTextField(typed, { typed = it.take(80); tried = false }, "Type the workspace name to confirm", Modifier.fillMaxWidth(), placeholder = d.org.name)
        Spacer(Modifier.height(12.dp))
        if (tried) {
            StatusBanner(DeleteWorkspaceRules.NOT_AVAILABLE, BannerKind.Warning)
            Spacer(Modifier.height(8.dp))
        }
        PillButton(
            "Delete workspace", { tried = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger,
            enabled = DeleteWorkspaceRules.confirmed(typed, d.org.name) && !tried,
        )
        Spacer(Modifier.height(8.dp))
        PillButton("Keep workspace", onDismiss, Modifier.fillMaxWidth(), tone = ButtonTone.Glass)
        Spacer(Modifier.height(16.dp))
    }
}
