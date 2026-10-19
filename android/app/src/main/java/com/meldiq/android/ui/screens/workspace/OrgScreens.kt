package com.meldiq.android.ui.screens.workspace

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.meldiq.android.R
import com.meldiq.android.data.api.ActivityDto
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.InviteDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.scan.InviteCodes
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MarkTile
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatTile
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.channelIcon
import com.meldiq.android.ui.screens.conversation.AddMembersSheet
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────────── Team ─────────────────────────────

@Composable
fun TeamScreen(nav: NavHostController, orgId: String, teamId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<OrgDetailRes?>(null) }
    var members by remember { mutableStateOf<List<UserDto>?>(null) }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showChannel by remember { mutableStateOf(false) }
    var moveFor by remember { mutableStateOf<UserDto?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { container.orgs.detail(orgId) }.onSuccess { detail = it }.onFailure { error = it.friendly() }
        runCatching { container.orgs.teamMembers(teamId) }.onSuccess { members = it }.onFailure { error = it.friendly() }
        runCatching { container.orgs.channels(orgId) }.onSuccess { channels = it.filter { ch -> ch.scope == "TEAM" && ch.scopeId == teamId } }
    }
    val team = detail?.teams?.firstOrNull { it.id == teamId }
    val me = container.session.session?.userId
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { error = it.friendly() }; reload++ }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar(team?.name ?: "Team", subtitle = team?.let { if (it.crossBranch) "Cross-branch team" else detail?.branches?.firstOrNull { b -> b.id == it.branchId }?.name }, onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "OK", onAction = { error = null }) } }
        if (team != null) item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkTile(team.name, team.name.hashCode(), size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(team.name, style = Griip.type.title, color = c.textPrimary)
                        Text(team.description ?: "No description", style = Griip.type.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Members", "${team.memberCount}", Modifier.weight(1f))
                    StatTile("Channels", "${channels.size}", Modifier.weight(1f))
                    StatTile("Your role", team.myRole?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—", Modifier.weight(1f))
                }
                if (team.canManage) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Add member", { showAdd = true }, Modifier.weight(1f), tone = ButtonTone.Glass, showKnob = false, leadingIcon = Icons.Outlined.PersonAdd, height = 46.dp)
                        PillButton("Channel", { showChannel = true }, Modifier.weight(1f), tone = ButtonTone.Glass, showKnob = false, leadingIcon = Icons.Outlined.PostAdd, height = 46.dp)
                    }
                    if (ScopeAdmin.canEditTeam(team)) {
                        Spacer(Modifier.height(10.dp))
                        PillButton(stringResource(R.string.team_edit), { showEdit = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.Edit, height = 46.dp)
                    }
                    // A department only groups a branch's teams (D-15); cross-branch teams have none.
                    val depts = detail?.departments.orEmpty().filter { it.branchId == team.branchId }
                    if (!team.crossBranch && depts.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Department", style = Griip.type.label, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        ChipRow {
                            PillChip("None", team.departmentId == null, { if (team.departmentId != null) act { container.orgs.setTeamDepartment(teamId, null) } })
                            depts.forEach { dep -> PillChip(dep.name, team.departmentId == dep.id, { if (team.departmentId != dep.id) act { container.orgs.setTeamDepartment(teamId, dep.id) } }) }
                        }
                    }
                }
            }
        }
        item { SectionHeader("Channels", Modifier.padding(top = 10.dp, bottom = 6.dp)) }
        if (channels.isEmpty()) item { Text("No team channels yet.", style = Griip.type.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter)) }
        items(channels, key = { "c" + it.id }) { ch ->
            ListCard(
                "#${ch.name}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp), subtitle = ch.topic,
                leading = { IconTile(channelIcon(ch.kind), c.primary, c.primarySoft, size = 42.dp) },
                trailing = {
                    if (ch.activeCall != null) TagPill("Live", c.success, c.successSoft, icon = Icons.Rounded.Call)
                    else if (ch.joined) ArrowKnob() else PillButton("Join", { scope.launch { runCatching { container.conversations.join(ch.id) }.onSuccess { nav.navigate(Routes.conversation(ch.id)) }.onFailure { error = it.friendly() } } }, tone = ButtonTone.Glass, showKnob = false, height = 36.dp)
                },
                onClick = if (ch.joined) ({ nav.navigate(Routes.conversation(ch.id)) }) else null,
            )
        }
        item { SectionHeader("Members", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        if (members == null) item { ShimmerList(3) }
        items(members.orEmpty(), key = { it.id }) { u ->
            var menu by remember { mutableStateOf(false) }
            ListCard(
                u.displayName + if (u.id == me) " (you)" else "", Modifier.padding(horizontal = 8.dp, vertical = 1.dp), subtitle = "@${u.username}",
                leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoleBadge(u.role?.takeIf { it == "LEAD" })
                        if (team?.canManage == true) {
                            CircleIconButton(Icons.Rounded.MoreVert, "Manage ${u.displayName}", { menu = true }, size = 34.dp)
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (u.role == "LEAD") "Make member" else "Make team lead") }, onClick = { menu = false; act { container.orgs.setTeamMember(teamId, u.id, if (u.role == "LEAD") "MEMBER" else "LEAD") } })
                                DropdownMenuItem(text = { Text("Move to another team") }, onClick = { menu = false; moveFor = u })
                                DropdownMenuItem(text = { Text("Remove from team", color = c.danger) }, onClick = { menu = false; act { container.orgs.setTeamMember(teamId, u.id, null) } })
                            }
                        }
                    }
                },
                onClick = if (u.id != me) ({ nav.navigate(Routes.user(u.id)) }) else null,
            )
        }
    }
    if (showAdd) AddMembersSheet(orgId, members.orEmpty().map { it.id }.toSet(), { showAdd = false }) { ids ->
        showAdd = false
        act { ids.forEach { container.orgs.setTeamMember(teamId, it, "MEMBER") } }
    }
    if (showChannel) detail?.let { d ->
        CreateChannelSheet(d.copy(permissions = d.permissions.copy(canManageOrg = false), branches = emptyList(), teams = d.teams.filter { it.id == teamId }), null, { showChannel = false }) { id ->
            reload++; nav.navigate(Routes.conversation(id))
        }
    }
    if (showEdit) detail?.let { d ->
        team?.let { t -> EditTeamSheet(d, t, onDismiss = { showEdit = false }, onSaved = { reload++ }, onArchived = { nav.popBackStack() }) }
    }
    moveFor?.let { u ->
        GlassSheet({ moveFor = null }, title = "Move ${u.displayName} to…") {
            detail?.teams?.filter { it.id != teamId && it.canManage }?.forEach { t ->
                ListCard(t.name, Modifier.padding(vertical = 4.dp), leading = { MarkTile(t.name, t.name.hashCode(), size = 40.dp) }, trailing = { Text("Move", color = c.primaryDeep, style = Griip.type.label) }, onClick = {
                    moveFor = null
                    act { container.orgs.moveMember(teamId, u.id, t.id) }
                })
            } ?: Text("No other teams you manage.", style = Griip.type.bodySmall, color = c.textSecondary)
        }
    }
}

// ───────────────────────────── Members & roles ─────────────────────────────

@Composable
fun MembersScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<UserDto>?>(null) }
    var detail by remember { mutableStateOf<OrgDetailRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf<UserDto?>(null) }
    var roleFilter by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Owners in the whole directory (not the filtered list), so an owner is offered "step down" only when another owner exists.
    var ownerCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(reload) {
        runCatching { container.orgs.detail(orgId) }.onSuccess { detail = it }
        runCatching { container.orgs.members(orgId) }.onSuccess { all -> ownerCount = WorkspaceRoles.ownerCount(all.map { it.orgRole }) }
    }
    LaunchedEffect(q, reload) {
        delay(200)
        runCatching { container.orgs.members(orgId, q) }.onSuccess { list = it }.onFailure { error = it.friendly() }
    }
    val canManage = detail?.permissions?.canManageOrg == true || detail?.branches?.any { it.canManage } == true || detail?.teams?.any { it.canManage } == true
    val shown = list.orEmpty().filter { roleFilter == null || it.orgRole == roleFilter }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Members & roles", subtitle = detail?.org?.name, onBack = { nav.popBackStack() }) }
        item { SearchPill(q, { q = it }, "Search the directory", Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        item { RolesGuideCard(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        item {
            ChipRow {
                PillChip("Everyone", roleFilter == null, { roleFilter = null }, count = list?.size)
                listOf("OWNER", "ADMIN", "MEMBER", "GUEST").forEach { r -> PillChip(r.lowercase().replaceFirstChar { it.uppercase() } + "s", roleFilter == r, { roleFilter = r }) }
            }
        }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "OK", onAction = { error = null }) } }
        if (list == null) item { ShimmerList(5) }
        items(shown, key = { it.id }) { u ->
            val branchNames = u.branches.mapNotNull { b -> detail?.branches?.firstOrNull { it.id == b.id }?.name }
            ListCard(
                u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = "@${u.username}" + (u.title?.let { " · $it" } ?: ""),
                leading = { Avatar(u.displayName, u.avatarColor, size = 46.dp, online = u.online) },
                meta = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoleBadge(u.orgRole)
                        branchNames.take(2).forEach { TagPill(it, c.primaryDeep, c.primarySoft, icon = Icons.Outlined.Apartment) }
                        if (u.teams.isNotEmpty()) TagPill("${u.teams.size} team${if (u.teams.size > 1) "s" else ""}", c.textSecondary, c.surfaceVariant, icon = Icons.Outlined.Groups)
                    }
                },
                trailing = { ArrowKnob() },
                onClick = { if (canManage || u.id == container.session.session?.userId) manage = u else nav.navigate(Routes.user(u.id)) },
            )
        }
    }
    manage?.let { u ->
        detail?.let { d ->
            MemberSheet(d, u, ownerCount, onDismiss = { manage = null }, onOpenProfile = { manage = null; nav.navigate(Routes.user(u.id)) }) { reload++ }
        }
    }
    @Suppress("UNUSED_VARIABLE") val s = scope
}

@Composable
private fun MemberSheet(d: OrgDetailRes, u: UserDto, ownerCount: Int?, onDismiss: () -> Unit, onOpenProfile: () -> Unit, onChanged: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf(u.orgRole) }
    val branchRoles = remember { mutableStateOf(u.branches.associate { it.id to it.role }) }
    val teamRoles = remember { mutableStateOf(u.teams.associate { it.id to it.role }) }
    var confirmRemove by remember { mutableStateOf(false) }
    val me = container.session.session?.userId
    fun act(block: suspend () -> Unit, after: () -> Unit = {}) = scope.launch {
        runCatching { block() }.onSuccess { after(); onChanged() }.onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.displayName, u.avatarColor, size = 52.dp, online = u.online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(u.displayName, style = Griip.type.title, color = c.textPrimary)
                Text("@${u.username}", style = Griip.type.bodySmall, color = c.textSecondary)
            }
            PillButton("Profile", onOpenProfile, tone = ButtonTone.Glass, showKnob = false, height = 38.dp)
        }
        error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
            // Only the roles this person may give (WorkspaceRoles mirrors the server); risky changes ask first.
            item {
                OrgRoleSection(d, u.copy(orgRole = role), ownerCount, onRole = { r -> role = r; onChanged() }, onError = { error = it })
                OrgTitleField(d, u, onSaved = onChanged, onError = { error = it })
            }
            item { SectionHeader("Branches", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
            items(d.branches, key = { "b" + it.id }) { b ->
                val cur = branchRoles.value[b.id]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                    if (b.canManage && role != "GUEST") {
                        PillChip("Off", cur == null, { act({ container.orgs.setBranchMember(b.id, u.id, null) }) { branchRoles.value = branchRoles.value - b.id } })
                        Spacer(Modifier.width(6.dp))
                        PillChip("Member", cur == "MEMBER", { act({ container.orgs.setBranchMember(b.id, u.id, "MEMBER") }) { branchRoles.value = branchRoles.value + (b.id to "MEMBER") } })
                        if (d.permissions.canManageOrg) {
                            Spacer(Modifier.width(6.dp))
                            PillChip("Manager", cur == "MANAGER", { act({ container.orgs.setBranchMember(b.id, u.id, "MANAGER") }) { branchRoles.value = branchRoles.value + (b.id to "MANAGER") } })
                        }
                    } else RoleBadge(cur)
                }
            }
            item { SectionHeader("Teams", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
            items(d.teams, key = { "t" + it.id }) { t ->
                val cur = teamRoles.value[t.id]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = Griip.type.bodyMedium, color = c.textPrimary)
                        Text(if (t.crossBranch) "Cross-branch" else d.branches.firstOrNull { it.id == t.branchId }?.name ?: "", style = Griip.type.caption, color = c.textTertiary)
                    }
                    if (t.canManage && role != "GUEST") {
                        PillChip("Off", cur == null, { act({ container.orgs.setTeamMember(t.id, u.id, null) }) { teamRoles.value = teamRoles.value - t.id } })
                        Spacer(Modifier.width(6.dp))
                        PillChip("Member", cur == "MEMBER", { act({ container.orgs.setTeamMember(t.id, u.id, "MEMBER") }) { teamRoles.value = teamRoles.value + (t.id to "MEMBER") } })
                        Spacer(Modifier.width(6.dp))
                        PillChip("Lead", cur == "LEAD", { act({ container.orgs.setTeamMember(t.id, u.id, "LEAD") }) { teamRoles.value = teamRoles.value + (t.id to "LEAD") } })
                    } else RoleBadge(cur)
                }
            }
            if (WorkspaceRoles.canRemove(d.org.myRole, role, self = u.id == me)) item {
                Spacer(Modifier.height(16.dp))
                PillButton(stringResource(R.string.member_remove), { confirmRemove = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, showKnob = false, leadingIcon = Icons.Outlined.DeleteOutline, height = 48.dp)
            }
        }
    }
    if (confirmRemove) ConfirmDialog(
        "Remove ${u.displayName}?", "They lose access to every branch, team and channel in ${d.org.name} immediately.", "Remove",
        onConfirm = { confirmRemove = false; act({ container.orgs.removeFromOrg(d.org.id, u.id) }) { onDismiss() } }, onDismiss = { confirmRemove = false }, destructive = true,
    )
}

// ───────────────────────────── Invitations ─────────────────────────────

@Composable
fun InvitesScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<OrgDetailRes?>(null) }
    var invites by remember { mutableStateOf<List<InviteDto>?>(null) }
    var created by remember { mutableStateOf<InviteDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf("MEMBER") }
    var branch by remember { mutableStateOf<String?>(null) }
    var team by remember { mutableStateOf<String?>(null) }
    var uses by remember { mutableIntStateOf(10) }
    var hours by remember { mutableIntStateOf(72) }
    var busy by remember { mutableStateOf(false) }
    // null: every invite; true: only usable ones; false: revoked, expired and used up.
    var stateFilter by remember { mutableStateOf<Boolean?>(null) }
    var revokeFor by remember { mutableStateOf<InviteDto?>(null) }
    LaunchedEffect(reload) {
        runCatching { container.orgs.detail(orgId) }.onSuccess { d ->
            detail = d
            if (!d.permissions.canManageOrg && branch == null && team == null) {
                branch = d.branches.firstOrNull { it.canManage }?.id
                if (branch == null) team = d.teams.firstOrNull { it.canManage }?.id
            }
        }
        runCatching { container.orgs.invites(orgId) }.onSuccess { invites = it }.onFailure { error = it.friendly() }
    }
    val d = detail
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Invitations", subtitle = d?.org?.name, onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "OK", onAction = { error = null }) } }
        if (d != null) item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                Text("New invite", style = Griip.type.title, color = c.textPrimary)
                Spacer(Modifier.height(10.dp))
                Text("Role", style = Griip.type.label, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Member", role == "MEMBER", { role = "MEMBER" })
                    PillChip("Guest", role == "GUEST", { role = "GUEST" })
                    if (d.permissions.isOwner) PillChip("Admin", role == "ADMIN", { role = "ADMIN" })
                }
                Spacer(Modifier.height(10.dp))
                Text("Adds them to", style = Griip.type.label, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                ChipRow(Modifier.padding(horizontal = 0.dp)) {
                    if (d.permissions.canManageOrg) PillChip("Org only", branch == null && team == null, { branch = null; team = null })
                    d.branches.filter { it.canManage }.forEach { b -> PillChip(b.name, branch == b.id && team == null, { branch = b.id; team = null }) }
                    d.teams.filter { it.canManage }.forEach { t -> PillChip(t.name, team == t.id, { team = t.id; branch = t.branchId }) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Uses", style = Griip.type.label, color = c.textSecondary, modifier = Modifier.width(56.dp))
                    listOf(1, 10, 50).forEach { n -> PillChip("$n", uses == n, { uses = n }) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Expires", style = Griip.type.label, color = c.textSecondary, modifier = Modifier.width(56.dp))
                    listOf(24 to "1 day", 72 to "3 days", 168 to "7 days").forEach { (h, l) -> PillChip(l, hours == h, { hours = h }) }
                }
                Spacer(Modifier.height(14.dp))
                PillButton("Create invite", {
                    busy = true
                    scope.launch {
                        runCatching { container.orgs.createInvite(orgId, role, branch, team, uses, hours) }.onSuccess { created = it; reload++ }.onFailure { error = it.friendly() }
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), loading = busy, height = 50.dp, enabled = d.permissions.canManageOrg || branch != null || team != null)
            }
        }
        item { SectionHeader("Recent invites", Modifier.padding(top = 10.dp, bottom = 6.dp)) }
        item {
            ChipRow {
                PillChip(stringResource(R.string.inv_filter_all), stateFilter == null, { stateFilter = null }, count = invites?.size)
                PillChip(stringResource(R.string.inv_filter_active), stateFilter == true, { stateFilter = true })
                PillChip(stringResource(R.string.inv_filter_ended), stateFilter == false, { stateFilter = false })
            }
        }
        val now = System.currentTimeMillis()
        val shownInvites = InviteStates.filter(invites.orEmpty(), stateFilter, now)
        if (invites?.isEmpty() == true) item { EmptyState(Icons.Outlined.GroupAdd, "No invites yet", "Codes you create appear here so you can revoke them.") }
        else if (invites != null && shownInvites.isEmpty()) item {
            Text(
                stringResource(if (stateFilter == true) R.string.inv_none_active else R.string.inv_none_ended), style = Griip.type.bodySmall, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = 8.dp),
            )
        }
        items(shownInvites, key = { it.id }) { inv ->
            val scopeName = listOfNotNull(d?.branches?.firstOrNull { it.id == inv.branchId }?.name, d?.teams?.firstOrNull { it.id == inv.teamId }?.name).joinToString(" · ").ifEmpty { "Organization" }
            val state = InviteStates.of(inv, now)
            val usable = state == InviteState.ACTIVE
            ListCard(
                "…${inv.hint} · ${roleLabel(inv.role)}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOfNotNull(
                    scopeName,
                    stringResource(R.string.inv_uses, inv.uses, inv.maxUses),
                    if (usable) stringResource(R.string.inv_expires, TimeFmt.listStamp(inv.expiresAt)) else null,
                    inv.createdByName?.let { stringResource(R.string.inv_created_by, it) },
                ).joinToString(" · "),
                leading = { IconTile(Icons.AutoMirrored.Outlined.Login, if (usable) c.primary else c.textTertiary, if (usable) c.primarySoft else c.surfaceVariant, size = 42.dp) },
                meta = { InviteStatePill(state) },
                trailing = {
                    if (usable) PillButton(stringResource(R.string.inv_revoke), { revokeFor = inv }, tone = ButtonTone.Glass, showKnob = false, height = 36.dp)
                },
            )
        }
    }
    revokeFor?.let { inv ->
        ConfirmDialog(
            stringResource(R.string.inv_revoke_title), stringResource(R.string.inv_revoke_body, inv.hint ?: ""), stringResource(R.string.inv_revoke),
            onConfirm = {
                revokeFor = null
                scope.launch { runCatching { container.orgs.revokeInvite(inv.id) }.onFailure { error = it.friendly() }; reload++ }
            },
            onDismiss = { revokeFor = null }, destructive = true,
        )
    }
    created?.let { inv ->
        GlassSheet({ created = null }, title = "Invite ready") {
            val code = inv.code ?: ""
            val qr = remember(code) { qrBitmap(InviteCodes.link(code)) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(Shapes.tile).background(Color.White).padding(12.dp)) { Image(qr.asImageBitmap(), "Invite QR code", Modifier.size(200.dp)) }
                Spacer(Modifier.height(14.dp))
                Text(code, style = Griip.type.display.copy(fontFamily = FontFamily.Monospace, fontSize = 26.sp), color = c.primaryDeep, textAlign = TextAlign.Center)
                Text("Shown once — the server stores only a hash. ${inv.maxUses} use(s), ${inv.role.lowercase()}.", style = Griip.type.caption, color = c.textTertiary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PillButton("Share code", {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join ${d?.org?.name ?: "our workspace"} on Meldiq with invite code $code (${InviteCodes.link(code)})")
                    ctx.startActivity(Intent.createChooser(send, "Share invite"))
                }, Modifier.fillMaxWidth(), leadingIcon = Icons.Outlined.Share, height = 50.dp)
            }
        }
    }
}

/** An invitation's state as a small coloured label. */
@Composable
private fun InviteStatePill(state: InviteState) {
    val c = Griip.colors
    val (label, fg, bg) = when (state) {
        InviteState.ACTIVE -> Triple(stringResource(R.string.inv_status_active), c.success, c.successSoft)
        InviteState.USED_UP -> Triple(stringResource(R.string.inv_status_used_up), c.textSecondary, c.surfaceVariant)
        InviteState.EXPIRED -> Triple(stringResource(R.string.inv_status_expired), c.warning, c.warningSoft)
        InviteState.REVOKED -> Triple(stringResource(R.string.inv_status_revoked), c.danger, c.dangerSoft)
    }
    TagPill(label, fg, bg)
}

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF13221F.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

// ───────────────────────────── Activity ─────────────────────────────

@Composable
fun ActivityScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    var list by remember { mutableStateOf<List<ActivityDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { container.orgs.activity(orgId) }.onSuccess { list = it }.onFailure { error = it.friendly() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Activity", onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter)) } }
        if (list == null && error == null) item { ShimmerList(6) }
        if (list?.isEmpty() == true) item { EmptyState(Icons.Outlined.History, "Quiet so far", "Branch, team and channel changes will show up here.") }
        items(list.orEmpty(), key = { it.id }) { a ->
            val (icon, label) = describe(a)
            ListCard(
                label, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = "${a.actorName ?: "Someone"} · ${TimeFmt.relative(a.at)}",
                leading = { IconTile(icon, c.primary, c.primarySoft, size = 40.dp) },
            )
        }
    }
}

private fun describe(a: ActivityDto): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> {
    val detail = a.detail?.substringAfter(' ') ?: ""
    return when (a.action) {
        "branch.create" -> Icons.Outlined.Apartment to "Created branch ${a.detail ?: ""}"
        "department.create" -> Icons.Outlined.Apartment to "Created department ${a.detail ?: ""}"
        "team.create" -> Icons.Outlined.Groups to "Created team ${a.detail ?: ""}"
        "channel.create" -> Icons.Outlined.PostAdd to "Created channel #$detail"
        "invite.accept" -> Icons.AutoMirrored.Outlined.Login to "Joined the organization"
        "invite.create" -> Icons.Outlined.GroupAdd to "Created a ${a.detail?.lowercase()} invite"
        "invite.revoke" -> Icons.Outlined.DeleteOutline to "Revoked an invite"
        "member.role" -> Icons.Outlined.SwapHoriz to "Changed a role (${a.detail})"
        "member.remove" -> Icons.Outlined.DeleteOutline to "Removed a member"
        "member.leave" -> Icons.AutoMirrored.Outlined.Login to "Left the organization"
        "call.start" -> Icons.Rounded.Call to "Started a ${a.detail} call"
        "channel.archive" -> Icons.Outlined.Campaign to "Archived a channel"
        "channel.restore" -> Icons.Outlined.Campaign to "Restored a channel"
        "channel.update" -> Icons.Outlined.Campaign to "Edited a channel"
        "channel.access" -> Icons.Outlined.Lock to "Changed who can see a channel (${a.detail?.lowercase()?.replace("→", " → ") ?: ""})"
        "branch.archive" -> Icons.Outlined.Archive to "Archived branch ${a.detail ?: ""}"
        "branch.restore" -> Icons.Outlined.Unarchive to "Restored branch ${a.detail ?: ""}"
        "branch.update" -> Icons.Outlined.Apartment to "Edited branch ${a.detail ?: ""}"
        "team.archive" -> Icons.Outlined.Archive to "Archived team ${a.detail ?: ""}"
        "team.restore" -> Icons.Outlined.Unarchive to "Restored team ${a.detail ?: ""}"
        "team.update" -> Icons.Outlined.Groups to "Edited team ${a.detail ?: ""}"
        "org.update" -> Icons.Outlined.Edit to "Edited the workspace's details"
        "org.photo" -> Icons.Outlined.Image to if (a.detail == "removed") "Removed the workspace photo" else "Changed the workspace photo"
        else -> Icons.Outlined.History to a.action.replace('.', ' ')
    }
}
