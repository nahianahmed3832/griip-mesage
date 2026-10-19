package com.griipbuddy.orbit.ui.screens.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.api.OrgDetailRes
import com.griipbuddy.orbit.data.repo.ServerFeatures
import com.griipbuddy.orbit.media.MediaPrep
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.TimeFmt
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.ConfirmDialog
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.OrgMark
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.RoleBadge
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.components.StatTile
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.friendly
import com.griipbuddy.orbit.ui.theme.Griip
import kotlinx.coroutines.launch

/** The server's limits on a workspace's name and description (`zName`, `zText`). */
private const val NAME_MAX = 80
private const val DESCRIPTION_MAX = 500

/**
 * Workspace settings (docs/WORKSPACE_ADMINISTRATION.md): the workspace's details and your role, editing its name and
 * description (owners and admins), and leaving it (everyone but the last owner). The server checks each action again.
 * [onChanged] reloads the caller's data; [onLeft] gets the workspace's name once you've left.
 */
@Composable
fun WorkspaceSettingsSheet(detail: OrgDetailRes, onDismiss: () -> Unit, onChanged: () -> Unit, onLeft: (String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val org = detail.org
    val canEdit = detail.permissions.canManageOrg
    var name by rememberSaveable(org.id) { mutableStateOf(org.name) }
    var description by rememberSaveable(org.id) { mutableStateOf(org.description.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var photoRev by remember(org.id) { mutableStateOf(org.photoRev) }
    var photoBusy by remember { mutableStateOf(false) }
    var photosSupported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { photosSupported = container.features.has(ServerFeatures.ORG_PHOTOS) == true }
    val ctx = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            error = null
            runCatching { container.orgs.setOrgPhoto(org.id, MediaPrep.profilePhoto(ctx, uri)) }
                .onSuccess { photoRev = it; onChanged() }.onFailure { error = it.friendly() }
            photoBusy = false
        }
    }
    val changed = name.trim() != org.name || description.trim() != org.description.orEmpty().trim()

    GlassSheet(onDismiss, title = stringResource(R.string.ws_settings_title)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrgMark(org.id, org.name, WorkspaceList.colorSeed(org), photoRev, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(org.name, style = Griip.type.title, color = c.textPrimary, maxLines = 2)
                    Text(
                        org.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ws_no_description),
                        style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 3,
                    )
                }
                RoleBadge(org.myRole)
            }
            Spacer(Modifier.height(12.dp))
            Row {
                StatTile(stringResource(R.string.ws_stat_members), "${detail.stats.members}", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatTile(stringResource(R.string.ws_stat_teams), "${detail.stats.teams}", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatTile(stringResource(R.string.ws_stat_channels), "${detail.stats.channels}", Modifier.weight(1f))
            }
            if (canEdit && photosSupported) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        stringResource(if (photoRev == null) R.string.ws_photo_add else R.string.ws_photo_change),
                        { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        tone = ButtonTone.Glass, height = 40.dp, leadingIcon = Icons.Outlined.PhotoCamera, loading = photoBusy,
                    )
                    if (photoRev != null) PillButton(
                        stringResource(R.string.ws_photo_remove), {
                            scope.launch {
                                photoBusy = true
                                error = null
                                runCatching { container.orgs.removeOrgPhoto(org.id) }.onSuccess { photoRev = null; onChanged() }.onFailure { error = it.friendly() }
                                photoBusy = false
                            }
                        },
                        tone = ButtonTone.Glass, height = 40.dp, leadingIcon = Icons.Outlined.Delete,
                    )
                }
            }
            if (org.createdAt > 0) {
                Text(
                    stringResource(R.string.ws_created, TimeFmt.listStamp(org.createdAt)), style = Griip.type.caption, color = c.textTertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            if (saved && !changed) { Spacer(Modifier.height(8.dp)); StatusBanner(stringResource(R.string.ws_saved), BannerKind.Success) }

            SectionHeader(stringResource(R.string.ws_edit), Modifier.padding(top = 16.dp, bottom = 6.dp))
            if (canEdit) {
                GriipTextField(name, { name = it.take(NAME_MAX); saved = false }, stringResource(R.string.ws_name))
                Spacer(Modifier.height(10.dp))
                GriipTextField(
                    description, { description = it.take(DESCRIPTION_MAX); saved = false }, stringResource(R.string.ws_description),
                    singleLine = false, minLines = 2,
                )
                Spacer(Modifier.height(12.dp))
                PillButton(stringResource(R.string.ws_save), {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { container.orgs.updateOrg(org.id, name, description) }
                            .onSuccess { saved = true; onChanged() }
                            .onFailure { error = it.friendly() }
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), enabled = changed && name.isNotBlank(), loading = busy, height = 50.dp)
            } else {
                Text(stringResource(R.string.ws_admin_only), style = Griip.type.bodySmall, color = c.textSecondary)
            }

            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                if (WorkspaceRoles.canLeave(detail)) {
                    MenuRow(
                        Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.ws_leave), subtitle = stringResource(R.string.ws_leave_sub),
                        tint = c.danger, tintBg = c.dangerSoft, onClick = { confirmLeave = true }, trailing = null,
                    )
                } else {
                    MenuRow(
                        Icons.Outlined.Info, stringResource(R.string.ws_leave), subtitle = stringResource(R.string.ws_leave_owner),
                        tint = c.textSecondary, tintBg = c.surfaceVariant, trailing = null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            stringResource(R.string.ws_leave_confirm_title, org.name), stringResource(R.string.ws_leave_confirm_body), stringResource(R.string.ws_leave_confirm),
            onConfirm = {
                confirmLeave = false
                val me = container.session.session?.userId
                if (me == null) return@ConfirmDialog
                scope.launch {
                    runCatching { container.orgs.leave(org.id, me) }
                        .onSuccess { runCatching { container.navPrefs.forget(org.id) }; onLeft(org.name) }
                        .onFailure { error = it.friendly() }
                }
            },
            onDismiss = { confirmLeave = false }, destructive = true,
        )
    }
}
