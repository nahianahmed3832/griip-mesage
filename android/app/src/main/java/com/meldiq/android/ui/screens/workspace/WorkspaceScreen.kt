package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBusiness
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.LargeHeader
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MarkTile
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.SegmentedPills
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatTile
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.channelIcon
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class WorkspaceViewModel(val c: AppContainer) : ViewModel() {
    val orgs = MutableStateFlow<List<OrgDto>?>(null)
    val detail = MutableStateFlow<OrgDetailRes?>(null)
    val channels = MutableStateFlow<List<ConversationDto>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val selectedOrg = c.orgs.selectedOrg

    init {
        refresh()
        viewModelScope.launch { c.messages.orgEvents.collect { if (it == selectedOrg.value) refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        loading.value = true
        if (orgs.value == null) c.orgs.cachedOrgs()?.let { orgs.value = WorkspaceList.normalize(it) }
        runCatching { c.orgs.orgs() }.onSuccess { fetched ->
            // Replaces (never appends to) the list: one entry per organisation id, in a stable order.
            val list = WorkspaceList.normalize(fetched)
            orgs.value = list
            val sel = selectedOrg.value?.takeIf { s -> list.any { it.id == s } } ?: list.firstOrNull()?.id
            if (sel != selectedOrg.value) c.orgs.select(sel)
            if (sel != null) load(sel) else detail.value = null
        }.onFailure {
            error.value = it.friendly()
            selectedOrg.value?.let { id -> c.orgs.cachedDetail(id)?.let { d -> detail.value = d } }
        }
        loading.value = false
    }

    private suspend fun load(orgId: String) {
        if (detail.value?.org?.id != orgId) c.orgs.cachedDetail(orgId)?.let { detail.value = it }
        runCatching { c.orgs.detail(orgId) }.onSuccess { detail.value = it; error.value = null }.onFailure { error.value = it.friendly() }
        runCatching { c.orgs.channels(orgId) }.onSuccess { channels.value = it }
    }

    fun switchOrg(id: String) = viewModelScope.launch {
        c.orgs.select(id)
        detail.value = null
        load(id)
    }

    fun join(ch: ConversationDto, then: (String) -> Unit) = viewModelScope.launch {
        runCatching { c.conversations.join(ch.id) }.onSuccess { then(ch.id); refresh() }.onFailure { error.value = it.friendly() }
    }
}

@Composable
fun WorkspaceScreen(nav: NavHostController) {
    val vm = containerViewModel { WorkspaceViewModel(it) }
    val c = Griip.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val orgs by vm.orgs.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selectedOrg by vm.selectedOrg.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var branchFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showSwitcher by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var archivedReload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }

    val d = detail
    // AI shows once it can be used here, or for admins who can switch it on (round 7, docs/AI.md).
    val aiStatus = com.meldiq.android.ui.screens.workspace.ai.rememberAiStatus(d?.org?.id)
    val aiOffered = aiStatus?.available == true || (aiStatus?.canManage == true && aiStatus.providers.isNotEmpty())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            com.meldiq.android.ui.components.GriipTopBar("Workspace", subtitle = d?.org?.name, onBack = { nav.popBackStack() }) {
                if ((orgs?.size ?: 0) > 0) CircleIconButton(Icons.Outlined.SwapHoriz, "Switch organization", { showSwitcher = true })
            }
        }
        // More than one workspace: switch with one tap, like a server rail.
        if ((orgs?.size ?: 0) > 1) item(key = "rail") {
            WorkspaceRail(orgs.orEmpty(), d?.org?.id ?: selectedOrg, { vm.switchOrg(it) }, { nav.navigate(Routes.JOIN + "?code=") }, Modifier.padding(bottom = 6.dp))
        }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "Retry", onAction = { vm.refresh() }) } }
        if (d == null) {
            item {
                if (loading && orgs == null) ShimmerList(4)
                else EmptyState(
                    Icons.Outlined.Workspaces, "No workspace yet", "Join your organization with an invite code from your manager, or create a new one.",
                    action = "Join or create", onAction = { nav.navigate(Routes.JOIN + "?code=") },
                )
            }
            return@LazyColumn
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.meldiq.android.ui.components.OrgMark(d.org.id, d.org.name, WorkspaceList.colorSeed(d.org), d.org.photoRev, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.org.name, style = Griip.type.title, color = c.textPrimary)
                        Text(d.org.description ?: "@${d.org.slug}", style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 2)
                    }
                    RoleBadge(d.org.myRole)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Members", "${d.stats.members}", Modifier.weight(1f))
                    StatTile("Teams", "${d.stats.teams}", Modifier.weight(1f))
                    StatTile("Channels", "${d.stats.channels}", Modifier.weight(1f))
                }
            }
        }
        if (d.branches.isNotEmpty()) {
            item {
                ChipRow {
                    PillChip("All branches", branchFilter == null, { branchFilter = null }, icon = Icons.Rounded.Public)
                    d.branches.forEach { b -> PillChip(b.name, branchFilter == b.id, { branchFilter = b.id }, icon = if (b.canAccess) null else Icons.Rounded.Lock) }
                }
            }
        }
        item { SegmentedPills(listOf("Teams", "Channels", "Manage"), tab, { tab = it }, Modifier.padding(horizontal = Space.gutter, vertical = 10.dp)) }

        when (tab) {
            0 -> {
                val selectedBranch = d.branches.firstOrNull { it.id == branchFilter }
                if (selectedBranch != null && !selectedBranch.canAccess) {
                    item { StatusBanner("You're not a member of ${selectedBranch.name}. Ask an admin or that branch's manager for access.", BannerKind.Info, Modifier.padding(horizontal = Space.gutter), icon = Icons.Rounded.Lock) }
                }
                val teams = d.teams.filter { branchFilter == null || it.branchId == branchFilter || it.crossBranch }
                if (teams.isEmpty()) item { EmptyState(Icons.Outlined.Groups, "No teams here", "Teams you belong to, or can manage, appear here.") }
                items(teams, key = { it.id }) { t ->
                    val branch = d.branches.firstOrNull { it.id == t.branchId }?.name
                    val dept = d.departments.firstOrNull { it.id == t.departmentId }?.name
                    ListCard(
                        t.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        subtitle = t.description ?: listOfNotNull(branch, dept).joinToString(" · "),
                        leading = { MarkTile(t.name, t.name.hashCode(), size = 50.dp) },
                        meta = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill(if (t.memberCount == 1) "1 member" else "${t.memberCount} members", c.textSecondary, c.surfaceVariant)
                                if (t.crossBranch) TagPill("Cross-branch", c.lavender, c.lavenderSoft) else branch?.let { TagPill(it, c.primaryDeep, c.primarySoft) }
                                t.myRole?.let { RoleBadge(it) }
                            }
                        },
                        trailing = { ArrowKnob() },
                        onClick = { nav.navigate(Routes.team(d.org.id, t.id)) },
                    )
                }
            }
            1 -> {
                val teamBranch = d.teams.associate { it.id to it.branchId }
                val shown = channels.filter { ch ->
                    branchFilter == null || ch.scope == "ORG" || (ch.scope == "BRANCH" && ch.scopeId == branchFilter) || (ch.scope == "TEAM" && teamBranch[ch.scopeId] == branchFilter)
                }
                if (shown.isEmpty()) item { EmptyState(Icons.Outlined.Tag, "No channels", "Channels you can see in this branch appear here.") }
                items(shown, key = { it.id }) { ch ->
                    val (fg, bg) = when (ch.kind) {
                        "ANNOUNCEMENT" -> c.peach to c.peachSoft
                        "PRIVATE" -> c.lavender to c.lavenderSoft
                        else -> c.primary to c.primarySoft
                    }
                    ListCard(
                        "#${ch.name}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        subtitle = ch.topic ?: ch.scopeName,
                        leading = { IconTile(channelIcon(ch.kind), fg, bg, size = 46.dp) },
                        meta = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagPill(ch.scopeName ?: ch.scope?.lowercase() ?: "", c.textSecondary, c.surfaceVariant)
                                TagPill("${ch.memberCount}", c.textSecondary, c.surfaceVariant, icon = Icons.Outlined.Groups)
                                if (ch.activeCall != null) TagPill("Live call", c.success, c.successSoft)
                            }
                        },
                        trailing = {
                            if (ch.joined) ArrowKnob()
                            else PillButton("Join", { vm.join(ch) { id -> nav.navigate(Routes.conversation(id)) } }, tone = ButtonTone.Glass, showKnob = false, height = 38.dp)
                        },
                        // A channel you haven't joined joins on a tap of the row too, not only its Join button (round 7).
                        onClick = if (ch.joined) ({ nav.navigate(Routes.conversation(ch.id)) }) else ({ vm.join(ch) { id -> nav.navigate(Routes.conversation(id)) } }),
                    )
                }
                if (d.permissions.canCreateChannels) item {
                    PillButton("New channel", { sheet = "channel" }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PostAdd)
                }
            }
            else -> {
                // Honest setup progress for the people who run the workspace (hidden once every step is done).
                if (d.permissions.canManageOrg) item(key = "setup") { WorkspaceSetupCard(d) }
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(6.dp)) {
                        MenuRow(
                            Icons.Outlined.Settings, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ws_settings_open),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ws_your_role, roleLabel(d.org.myRole)),
                            onClick = { sheet = "settings" },
                        )
                        if (d.permissions.canViewDirectory) MenuRow(Icons.Outlined.ManageAccounts, "Members & roles", subtitle = "Directory, branches, teams, roles", onClick = { nav.navigate(Routes.members(d.org.id)) })
                        if (d.permissions.canInvite) MenuRow(Icons.Outlined.GroupAdd, "Invitations", subtitle = "Create invite codes & QR, revoke", tint = c.peach, tintBg = c.peachSoft, onClick = { nav.navigate(Routes.invites(d.org.id)) })
                        if (d.permissions.canViewDirectory) MenuRow(Icons.Outlined.History, "Branch & team activity", tint = c.lavender, tintBg = c.lavenderSoft, onClick = { nav.navigate(Routes.activity(d.org.id)) })
                        // Custom roles, apps, audit and usage (2026-10-19): shown only to people the server says may use them.
                        if (d.permissions.canViewRoles) MenuRow(
                            Icons.Outlined.AdminPanelSettings, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_roles),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_roles_sub), onClick = { nav.navigate(Routes.roles(d.org.id)) },
                        )
                        if (d.permissions.canViewApps) MenuRow(
                            Icons.Outlined.Apps, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_apps),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_apps_sub), tint = c.peach, tintBg = c.peachSoft,
                            onClick = { nav.navigate(Routes.apps(d.org.id)) },
                        )
                        // Meetings and groups (round 7, docs/WORKSPACE_C5.md).
                        MenuRow(
                            Icons.Outlined.Event, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.mtg_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.mtg_menu_sub), tint = c.peach, tintBg = c.peachSoft,
                            onClick = { nav.navigate(Routes.meetings(d.org.id, canSchedule = d.org.myRole != "GUEST")) },
                        )
                        if (d.org.myRole != "GUEST") MenuRow(
                            Icons.Outlined.Groups, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.grp_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.grp_menu_sub), tint = c.lavender, tintBg = c.lavenderSoft,
                            onClick = { nav.navigate(Routes.groups(d.org.id, canManage = d.permissions.canManageGroups)) },
                        )
                        if (d.permissions.canManageOrg && d.permissions.canCreateChannels) MenuRow(
                            Icons.Outlined.AutoAwesome, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.tpl_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.tpl_menu_sub),
                            onClick = { sheet = "templates" },
                        )
                        // Documents (round 7): everyone sees the ones shared with them; guests can't create.
                        MenuRow(
                            Icons.Outlined.Description, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.doc_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.doc_menu_sub), tint = c.primary, tintBg = c.primarySoft,
                            onClick = { nav.navigate(Routes.documents(d.org.id, canCreate = d.org.myRole != "GUEST")) },
                        )
                        // The customer inbox (round 8, docs/UNIFIED_INBOX.md): for people with inbox access.
                        if ("inbox.view" in d.permissions.effective) MenuRow(
                            Icons.Outlined.Forum, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ib_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ib_menu_sub), tint = c.success, tintBg = c.successSoft,
                            onClick = { nav.navigate(Routes.inbox(d.org.id, canManage = "inbox.manage" in d.permissions.effective)) },
                        )
                        // AI (round 7, docs/AI.md): off until the server has a provider and an admin turns it on.
                        if (d.org.myRole != "GUEST" && aiOffered) MenuRow(
                            Icons.Outlined.AutoAwesome, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ai_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ai_menu_sub), tint = c.lavender, tintBg = c.lavenderSoft,
                            onClick = { nav.navigate(Routes.ai(d.org.id)) },
                        )
                        if (d.permissions.canManageWorkflows) MenuRow(
                            Icons.Outlined.Bolt, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.wf_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.wf_menu_sub), tint = c.success, tintBg = c.successSoft,
                            onClick = { nav.navigate(Routes.workflows(d.org.id)) },
                        )
                        if (d.permissions.canManageDevelopers) MenuRow(
                            Icons.Outlined.Code, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.dev_title),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.dev_menu_sub),
                            onClick = { nav.navigate(Routes.developer(d.org.id)) },
                        )
                        if (d.permissions.canViewAudit) MenuRow(
                            Icons.Outlined.Policy, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_audit),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_audit_sub), tint = c.lavender, tintBg = c.lavenderSoft,
                            onClick = { nav.navigate(Routes.audit(d.org.id)) },
                        )
                        if (d.permissions.canViewUsage) MenuRow(
                            Icons.Outlined.Insights, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_usage),
                            subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.manage_usage_sub), onClick = { nav.navigate(Routes.usage(d.org.id)) },
                        )
                        if (d.permissions.canManageOrg) {
                            MenuRow(Icons.Outlined.AddBusiness, "New branch", onClick = { sheet = "branch" })
                        }
                        if (d.permissions.canManageOrg || d.branches.any { it.canManage }) {
                            MenuRow(Icons.Outlined.Apartment, "New department", onClick = { sheet = "department" })
                            MenuRow(Icons.Outlined.Groups, "New team", onClick = { sheet = "team" })
                        }
                        if (d.permissions.canCreateChannels) MenuRow(Icons.Outlined.PostAdd, "New channel", onClick = { sheet = "channel" })
                        MenuRow(Icons.Outlined.Workspaces, "Join or create another workspace", tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { nav.navigate(Routes.JOIN + "?code=") })
                    }
                }
                item {
                    Text("Branches", style = Griip.type.section, color = c.textPrimary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 10.dp))
                }
                items(d.branches, key = { "b" + it.id }) { b ->
                    ListCard(
                        b.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        subtitle = listOfNotNull(b.location, if (b.memberCount == 1) "1 member" else "${b.memberCount} members", d.departments.count { it.branchId == b.id }.let { if (it == 1) "1 department" else "$it departments" }).joinToString(" · "),
                        leading = { MarkTile(b.name, b.name.hashCode() + 3, size = 46.dp) },
                        trailing = { if (b.myRole != null) RoleBadge(b.myRole) else if (!b.canAccess) Icon(Icons.Rounded.Lock, "No access", tint = c.textTertiary) },
                        // Managers and org admins edit the branch; org admins can also archive it (EditBranchSheet).
                        onClick = if (b.canManage) ({ sheet = "branch:" + b.id }) else null,
                    )
                }
                val depts = d.departments.filter { dep -> d.branches.any { it.id == dep.branchId } } // archived branches aren't listed
                if (depts.isNotEmpty()) item {
                    Text("Departments", style = Griip.type.section, color = c.textPrimary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 10.dp))
                }
                items(depts, key = { "d" + it.id }) { dep ->
                    val branch = d.branches.first { it.id == dep.branchId }
                    ListCard(
                        dep.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        subtitle = listOfNotNull(branch.name, "${d.teams.count { it.departmentId == dep.id }} teams", dep.description).joinToString(" · "),
                        leading = { IconTile(Icons.Outlined.Apartment, c.lavender, c.lavenderSoft, size = 42.dp) },
                        trailing = { if (branch.canManage) ArrowKnob() },
                        onClick = if (branch.canManage) ({ sheet = "dept:" + dep.id }) else null,
                    )
                }
                item(key = "archived") { ArchivedScopesSection(d, archivedReload) { vm.refresh() } }
                // Owner only, at the very end of Manage: far from switching workspaces, so a tap there can't reach it.
                if (DeleteWorkspaceRules.visible(d.org.myRole)) {
                    item {
                        Text("Danger zone", style = Griip.type.section, color = c.danger, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 10.dp))
                    }
                    item {
                        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(6.dp)) {
                            if (d.permissions.canTransferOwnership) MenuRow(
                                Icons.Outlined.SwapHoriz, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.owner_transfer),
                                subtitle = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.owner_transfer_sub),
                                tint = c.warning, tintBg = c.warningSoft, onClick = { sheet = "transfer" },
                            )
                            MenuRow(
                                Icons.Outlined.DeleteForever, "Delete workspace", subtitle = "For every member. Can't be undone.",
                                tint = c.danger, tintBg = c.dangerSoft, onClick = { sheet = "delete" },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSwitcher) GlassSheet({ showSwitcher = false }, title = "Workspaces") {
        WorkspaceSwitcherList(orgs.orEmpty(), d?.org?.id) { id -> vm.switchOrg(id); showSwitcher = false }
        Spacer(Modifier.height(10.dp))
        PillButton("Join or create", { showSwitcher = false; nav.navigate(Routes.JOIN + "?code=") }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 50.dp)
    }
    d?.let { det ->
        when (sheet) {
            "branch" -> CreateBranchSheet(det, { sheet = null }) { vm.refresh() }
            "department" -> CreateDepartmentSheet(det, { sheet = null }) { vm.refresh() }
            "team" -> CreateTeamSheet(det, branchFilter, { sheet = null }) { vm.refresh() }
            "channel" -> CreateChannelSheet(det, branchFilter, { sheet = null }) { id -> vm.refresh(); nav.navigate(Routes.conversation(id)) }
            "templates" -> TemplatesSheet(det.org.id, onApplied = { sheet = null; vm.refresh() }, onDismiss = { sheet = null })
            "settings" -> WorkspaceSettingsSheet(det, onDismiss = { sheet = null }, onChanged = { vm.refresh() }, onLeft = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, ctx.getString(com.meldiq.android.R.string.ws_left, name), android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            "transfer" -> if (det.permissions.canTransferOwnership) TransferOwnershipSheet(det, onDismiss = { sheet = null }, onDone = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, ctx.getString(com.meldiq.android.R.string.owner_transfer_done, name), android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            "delete" -> if (DeleteWorkspaceRules.visible(det.org.myRole)) DeleteWorkspaceSheet(det, onDismiss = { sheet = null }, onDeleted = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, "Deleted “$name”", android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            else -> if (sheet?.startsWith("branch:") == true) {
                det.branches.firstOrNull { it.id == sheet?.removePrefix("branch:") }?.let { b ->
                    EditBranchSheet(det, b, onDismiss = { sheet = null }) { vm.refresh(); archivedReload++ }
                }
            } else sheet?.removePrefix("dept:")?.takeIf { sheet!!.startsWith("dept:") }?.let { id ->
                det.departments.firstOrNull { it.id == id }?.let { dep -> EditDepartmentSheet(det, dep, { sheet = null }) { vm.refresh() } }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = rememberCoroutineScope()
}
