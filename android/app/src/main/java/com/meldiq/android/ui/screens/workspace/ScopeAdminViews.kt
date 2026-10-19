package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ArchivedRes
import com.meldiq.android.data.api.BranchDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.TeamDto
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/** The server's limits (`zName`, `zText`). */
private const val NAME_MAX = 80
private const val TEXT_MAX = 500

/**
 * Edit a team (docs/WORKSPACE_ADMINISTRATION.md): its leads and managers rename and describe it; org admins and the
 * branch's managers can also archive it (read-only until restored). [onArchived] runs after a successful archive.
 */
@Composable
fun EditTeamSheet(detail: OrgDetailRes, team: TeamDto, onDismiss: () -> Unit, onSaved: () -> Unit, onArchived: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by rememberSaveable(team.id) { mutableStateOf(team.name) }
    var description by rememberSaveable(team.id) { mutableStateOf(team.description.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    val archivedMessage = stringResource(R.string.scope_archived, team.name)
    GlassSheet(onDismiss, title = stringResource(R.string.team_edit)) {
        error?.let { StatusBanner(it, BannerKind.Error); Spacer(Modifier.height(8.dp)) }
        GriipTextField(name, { name = it.take(NAME_MAX) }, stringResource(R.string.team_name))
        Spacer(Modifier.height(10.dp))
        GriipTextField(description, { description = it.take(TEXT_MAX) }, stringResource(R.string.scope_description), singleLine = false, minLines = 2)
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.ws_save), {
            busy = true
            scope.launch {
                runCatching { container.orgs.editTeam(team.id, name, description) }.onSuccess { onSaved(); onDismiss() }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && (name.trim() != team.name || description.trim() != team.description.orEmpty()), loading = busy, height = 50.dp)
        if (ScopeAdmin.canArchiveTeam(detail, team)) {
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                MenuRow(
                    Icons.Outlined.Archive, stringResource(R.string.team_archive), subtitle = stringResource(R.string.team_archive_sub),
                    tint = c.warning, tintBg = c.warningSoft, onClick = { confirmArchive = true }, trailing = null,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirmArchive) ConfirmDialog(
        stringResource(R.string.team_archive_title, team.name), stringResource(R.string.team_archive_body), stringResource(R.string.team_archive),
        onConfirm = {
            confirmArchive = false
            scope.launch {
                runCatching { container.orgs.setTeamArchived(team.id, true) }
                    .onSuccess {
                        android.widget.Toast.makeText(context, archivedMessage, android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                        onArchived()
                    }
                    .onFailure { error = it.friendly() }
            }
        },
        onDismiss = { confirmArchive = false }, destructive = true,
    )
}

/** Edit a branch: its managers and org admins rename it and set its description and location; org admins archive it. */
@Composable
fun EditBranchSheet(detail: OrgDetailRes, branch: BranchDto, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by rememberSaveable(branch.id) { mutableStateOf(branch.name) }
    var description by rememberSaveable(branch.id) { mutableStateOf(branch.description.orEmpty()) }
    var location by rememberSaveable(branch.id) { mutableStateOf(branch.location.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    val archivedMessage = stringResource(R.string.scope_archived, branch.name)
    val changed = name.trim() != branch.name || description.trim() != branch.description.orEmpty() || location.trim() != branch.location.orEmpty()
    GlassSheet(onDismiss, title = stringResource(R.string.branch_edit)) {
        error?.let { StatusBanner(it, BannerKind.Error); Spacer(Modifier.height(8.dp)) }
        GriipTextField(name, { name = it.take(NAME_MAX) }, stringResource(R.string.branch_name))
        Spacer(Modifier.height(10.dp))
        GriipTextField(location, { location = it.take(TEXT_MAX) }, stringResource(R.string.branch_location))
        Spacer(Modifier.height(10.dp))
        GriipTextField(description, { description = it.take(TEXT_MAX) }, stringResource(R.string.scope_description), singleLine = false, minLines = 2)
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.ws_save), {
            busy = true
            scope.launch {
                runCatching { container.orgs.editBranch(branch.id, name, description, location) }.onSuccess { onChanged(); onDismiss() }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && changed, loading = busy, height = 50.dp)
        if (ScopeAdmin.canArchiveBranch(detail)) {
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                MenuRow(
                    Icons.Outlined.Archive, stringResource(R.string.branch_archive), subtitle = stringResource(R.string.team_archive_sub),
                    tint = c.warning, tintBg = c.warningSoft, onClick = { confirmArchive = true }, trailing = null,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirmArchive) ConfirmDialog(
        stringResource(R.string.team_archive_title, branch.name), stringResource(R.string.branch_archive_body), stringResource(R.string.branch_archive),
        onConfirm = {
            confirmArchive = false
            scope.launch {
                runCatching { container.orgs.setBranchArchived(branch.id, true) }
                    .onSuccess {
                        android.widget.Toast.makeText(context, archivedMessage, android.widget.Toast.LENGTH_SHORT).show()
                        onChanged()
                        onDismiss()
                    }
                    .onFailure { error = it.friendly() }
            }
        },
        onDismiss = { confirmArchive = false }, destructive = true,
    )
}

/**
 * Archived branches and teams you can restore, at the end of the Workspace screen's Manage tab. Shown only to people
 * the server says may restore something, on servers with `workspace-admin`; nothing is shown when the list is empty.
 */
@Composable
fun ArchivedScopesSection(detail: OrgDetailRes, reloadKey: Int, onRestored: () -> Unit) {
    if (!detail.permissions.canRestore) return
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var archived by remember(detail.org.id) { mutableStateOf<ArchivedRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    LaunchedEffect(detail.org.id, reloadKey, local) {
        if (container.features.has(ServerFeatures.WORKSPACE_ADMIN) != true) return@LaunchedEffect
        runCatching { container.orgs.archived(detail.org.id) }.onSuccess { archived = it; error = null }.onFailure { error = it.friendly() }
    }
    val a = archived ?: return
    if (a.branches.isEmpty() && a.teams.isEmpty() && error == null) return
    val restoredFormat = stringResource(R.string.scope_restored, "%s")
    fun restore(name: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }
            .onSuccess {
                android.widget.Toast.makeText(context, restoredFormat.replace("%s", name), android.widget.Toast.LENGTH_SHORT).show()
                local++
                onRestored()
            }
            .onFailure { error = it.friendly() }
    }
    Column {
        Text(stringResource(R.string.archived_title), style = Griip.type.section, color = c.textPrimary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 10.dp))
        Text(stringResource(R.string.archived_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter))
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        a.branches.forEach { b ->
            ListCard(
                b.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOfNotNull(stringResource(R.string.archived_kind_branch), b.location).joinToString(" · "),
                leading = { IconTile(Icons.Outlined.Apartment, c.textSecondary, c.surfaceVariant, size = 42.dp) },
                trailing = {
                    PillButton(stringResource(R.string.archived_restore), { restore(b.name) { container.orgs.setBranchArchived(b.id, false) } }, tone = ButtonTone.Glass, height = 36.dp)
                },
            )
        }
        a.teams.forEach { t ->
            ListCard(
                t.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = if (t.branchArchived) stringResource(R.string.archived_branch_first) else stringResource(R.string.archived_kind_team),
                leading = { IconTile(Icons.Outlined.Groups, c.textSecondary, c.surfaceVariant, size = 42.dp) },
                trailing = {
                    PillButton(
                        stringResource(R.string.archived_restore), { restore(t.name) { container.orgs.setTeamArchived(t.id, false) } },
                        tone = ButtonTone.Glass, height = 36.dp, enabled = !t.branchArchived,
                    )
                },
            )
        }
    }
}
