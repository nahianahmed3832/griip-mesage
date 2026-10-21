package com.meldiq.android.ui.screens.community

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Workspaces
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.CommunityAccess
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.OrgMark
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.DeleteWorkspaceRules
import com.meldiq.android.ui.screens.workspace.DeleteWorkspaceSheet
import com.meldiq.android.ui.screens.workspace.TransferOwnershipSheet
import com.meldiq.android.ui.screens.workspace.WorkspaceList
import com.meldiq.android.ui.screens.workspace.WorkspaceSettingsSheet
import com.meldiq.android.ui.screens.workspace.WorkspaceSetupCard
import com.meldiq.android.ui.screens.workspace.WorkspaceViewModel
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

private enum class SettingsSheet { Overview, Transfer, Delete, Leave }

/**
 * Community settings in groups (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §4): Community, People, Channels,
 * Moderation, More, then the danger zone. Each row opens an existing screen and shows only for people allowed to use
 * it; the server checks again.
 */
@Composable
fun CommunitySettingsScreen(nav: NavHostController) {
    val vm = containerViewModel { WorkspaceViewModel(it) }
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val d = detail

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { GriipTopBar(stringResource(R.string.nv_community_settings), subtitle = d?.org?.name, onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = stringResource(R.string.common_retry), onAction = { vm.refresh() }) } }
        if (d == null) {
            item { if (loading) ShimmerList(4) else EmptyState(Icons.Outlined.Workspaces, stringResource(R.string.cm_none_title), stringResource(R.string.cm_none_body)) }
            return@LazyColumn
        }
        val p = d.permissions
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OrgMark(d.org.id, d.org.name, WorkspaceList.colorSeed(d.org), d.org.photoRev, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.org.name, style = Griip.type.title, color = c.textPrimary)
                        Text(
                            listOf(
                                memberCountLabel(d.stats.members),
                                stringResource(if (d.org.visibility == CommunityAccess.PUBLIC) R.string.cs_public else R.string.cs_private),
                            ).joinToString(" · "),
                            style = Griip.type.bodySmall, color = c.textSecondary,
                        )
                    }
                    RoleBadge(d.org.myRole)
                }
            }
        }
        if (p.canManageOrg) item(key = "setup") { WorkspaceSetupCard(d) }
        group(R.string.nv_group_community) {
            MenuRow(Icons.Outlined.Info, stringResource(R.string.nv_overview), subtitle = stringResource(R.string.nv_overview_sub), onClick = { sheet = SettingsSheet.Overview })
            MenuRow(
                Icons.Outlined.Public, stringResource(R.string.nv_access_rules),
                subtitle = stringResource(if (d.org.joinPolicy == CommunityAccess.OPEN) R.string.dc_open else R.string.dc_invite_only),
                onClick = { sheet = SettingsSheet.Overview },
            )
        }
        group(R.string.nv_group_people) {
            if (p.canViewDirectory) MenuRow(Icons.Outlined.ManageAccounts, stringResource(R.string.cm_stat_members), onClick = { nav.navigate(Routes.members(d.org.id)) })
            if (p.canViewRoles) MenuRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.manage_roles), subtitle = stringResource(R.string.manage_roles_sub), onClick = { nav.navigate(Routes.roles(d.org.id)) })
            if (p.canInvite) MenuRow(Icons.Outlined.GroupAdd, stringResource(R.string.nv_invitations), tint = c.peach, tintBg = c.peachSoft, onClick = { nav.navigate(Routes.invites(d.org.id)) })
        }
        group(R.string.nv_group_channels) {
            MenuRow(Icons.Outlined.Tag, stringResource(R.string.nv_channels_categories), subtitle = stringResource(R.string.nv_channels_categories_sub, d.stats.channels, d.categories.size), onClick = { nav.navigate(Routes.CHANNELS_MANAGE) })
        }
        if (p.canBan || p.canViewAudit || p.canViewDirectory) group(R.string.nv_group_moderation) {
            if (p.canBan) MenuRow(Icons.Outlined.Block, stringResource(R.string.cm_bans), subtitle = stringResource(R.string.cm_bans_sub), tint = c.danger, tintBg = c.dangerSoft, onClick = { nav.navigate(Routes.bans(d.org.id)) })
            if (p.canViewAudit) MenuRow(Icons.Outlined.Policy, stringResource(R.string.manage_audit), subtitle = stringResource(R.string.manage_audit_sub), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { nav.navigate(Routes.audit(d.org.id)) })
            if (p.canViewDirectory) MenuRow(Icons.Outlined.History, stringResource(R.string.activity_workspace_log), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { nav.navigate(Routes.activity(d.org.id)) })
        }
        group(R.string.nv_group_more) {
            MenuRow(Icons.Outlined.Event, stringResource(R.string.cm_events), subtitle = stringResource(R.string.cm_events_sub), tint = c.peach, tintBg = c.peachSoft, onClick = { nav.navigate(Routes.meetings(d.org.id, canSchedule = d.org.myRole != "GUEST")) })
            if (p.canViewUsage) MenuRow(Icons.Outlined.Insights, stringResource(R.string.manage_usage), subtitle = stringResource(R.string.manage_usage_sub), onClick = { nav.navigate(Routes.usage(d.org.id)) })
        }
        group(R.string.nv_group_danger, danger = true) {
            if (p.canTransferOwnership) MenuRow(Icons.Outlined.SwapHoriz, stringResource(R.string.owner_transfer), subtitle = stringResource(R.string.owner_transfer_sub), tint = c.warning, tintBg = c.warningSoft, onClick = { sheet = SettingsSheet.Transfer })
            if (DeleteWorkspaceRules.visible(d.org.myRole)) MenuRow(Icons.Outlined.DeleteForever, stringResource(R.string.nv_delete_community), subtitle = stringResource(R.string.nv_delete_community_sub), tint = c.danger, tintBg = c.dangerSoft, onClick = { sheet = SettingsSheet.Delete })
            if (d.org.myRole != "OWNER") MenuRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.nv_leave_community), tint = c.danger, tintBg = c.dangerSoft, onClick = { sheet = SettingsSheet.Leave })
        }
    }

    if (d != null) when (sheet) {
        SettingsSheet.Overview -> WorkspaceSettingsSheet(d, onDismiss = { sheet = null }, onChanged = { vm.refresh() }, onLeft = { name ->
            sheet = null
            Toast.makeText(ctx, ctx.getString(R.string.ws_left, name), Toast.LENGTH_SHORT).show()
            nav.popBackStack()
        })
        SettingsSheet.Transfer -> TransferOwnershipSheet(d, onDismiss = { sheet = null }, onDone = { name ->
            sheet = null
            Toast.makeText(ctx, ctx.getString(R.string.owner_transfer_done, name), Toast.LENGTH_SHORT).show()
            vm.refresh()
        })
        SettingsSheet.Delete -> DeleteWorkspaceSheet(d, onDismiss = { sheet = null }, onDeleted = { name ->
            sheet = null
            Toast.makeText(ctx, ctx.getString(R.string.nv_community_deleted, name), Toast.LENGTH_SHORT).show()
            nav.popBackStack()
        })
        SettingsSheet.Leave -> ConfirmDialog(
            stringResource(R.string.nv_leave_title, d.org.name), stringResource(R.string.nv_leave_body), stringResource(R.string.nv_leave_community),
            onConfirm = {
                sheet = null
                val me = container.session.session?.userId ?: return@ConfirmDialog
                scope.launch {
                    runCatching { container.orgs.leave(d.org.id, me) }
                        .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.ws_left, d.org.name), Toast.LENGTH_SHORT).show(); nav.popBackStack() }
                        .onFailure { Toast.makeText(ctx, it.friendly(), Toast.LENGTH_SHORT).show() }
                }
            },
            onDismiss = { sheet = null }, destructive = true,
        )
        null -> Unit
    }
}

/** One titled group of rows; left out when it would be empty is the caller's choice (rows inside decide themselves). */
private fun androidx.compose.foundation.lazy.LazyListScope.group(title: Int, danger: Boolean = false, rows: @Composable () -> Unit) {
    item(key = "group-$title") {
        Column {
            Text(
                stringResource(title), style = Griip.type.section, color = if (danger) Griip.colors.danger else Griip.colors.textSecondary,
                modifier = Modifier.padding(start = Space.gutter + 4.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
            )
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(6.dp)) { rows() }
        }
    }
}
