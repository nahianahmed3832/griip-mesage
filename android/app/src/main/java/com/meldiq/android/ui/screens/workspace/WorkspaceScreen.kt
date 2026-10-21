package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.CategoryDto
import com.meldiq.android.data.api.CommunityAccess
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.LayoutReq
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.model.ChannelRef
import com.meldiq.android.data.model.ChannelSection
import com.meldiq.android.data.model.ChannelTree
import com.meldiq.android.data.model.LayoutRules
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.SegmentedPills
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatTile
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
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
    val notice = MutableStateFlow<String?>(null)
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
            // Replaces (never appends to) the list: one entry per community id, in a stable order.
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

    /** Saves a layout change; when moving a channel changed who can see it, says how many memberships changed. */
    fun saveLayout(req: LayoutReq, movedMessage: (Int) -> String) = viewModelScope.launch {
        val orgId = detail.value?.org?.id ?: return@launch
        runCatching { c.orgs.saveLayout(orgId, req) }
            .onSuccess { if (it.removed > 0) notice.value = movedMessage(it.removed); refresh() }
            .onFailure { error.value = it.friendly() }
    }

    fun deleteCategory(cat: CategoryDto) = viewModelScope.launch {
        runCatching { c.orgs.deleteCategory(cat.id) }.onSuccess { refresh() }.onFailure { error.value = it.friendly() }
    }
}

private sealed interface CommunitySheet {
    data object Switcher : CommunitySheet
    data class NewChannel(val categoryId: String?) : CommunitySheet
    data object NewCategory : CommunitySheet
    data class RenameCategory(val category: CategoryDto) : CommunitySheet
    data class CategoryActions(val category: CategoryDto) : CommunitySheet
    data class ChannelActions(val channel: ConversationDto) : CommunitySheet
    data class MoveTo(val channel: ConversationDto) : CommunitySheet
    data object Settings : CommunitySheet
    data object Transfer : CommunitySheet
    data object Delete : CommunitySheet
}

/**
 * The selected community (docs/COMMUNITY_REBUILD_2026-10-21.md): its channels in categories, in order, with joining and
 * (for the people who may) arranging, permissions and new channels or categories; its About; and its administration.
 */
@Composable
fun WorkspaceScreen(nav: NavHostController) {
    val vm = containerViewModel { WorkspaceViewModel(it) }
    val c = Griip.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val orgs by vm.orgs.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selectedOrg by vm.selectedOrg.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var sheet by remember { mutableStateOf<CommunitySheet?>(null) }
    // Discover exists only on servers with communities (an older one answers 404).
    var discovery by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.refresh()
        discovery = vm.c.features.has(com.meldiq.android.data.repo.ServerFeatures.COMMUNITY_DISCOVERY) == true
    }
    LaunchedEffect(notice) { notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); vm.notice.value = null } }
    val movedMessage = { n: Int -> ctx.getString(R.string.cm_moved, n) }

    val d = detail
    val canArrange = d?.permissions?.canManageChannels == true
    val canCreateCategory = d != null && ("channels.create" in d.permissions.effective || d.permissions.canManageOrg)
    val sections: List<ChannelSection<ConversationDto>> = remember(channels, d?.categories, canArrange) {
        ChannelTree.sections(channels.filter { !it.archived }, d?.categories.orEmpty(), keepEmpty = canArrange, ref = { ChannelRef(it.id, it.name.orEmpty(), it.categoryId, it.position) }, categoryName = { it.categoryName })
    }
    val categoryOrder = d?.categories.orEmpty().sortedBy { it.position }.map { it.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            com.meldiq.android.ui.components.GriipTopBar(stringResource(R.string.cm_title), subtitle = d?.org?.name, onBack = { nav.popBackStack() }) {
                if (discovery) CircleIconButton(Icons.Outlined.Explore, stringResource(R.string.cm_discover), { nav.navigate(Routes.DISCOVER) })
                if ((orgs?.size ?: 0) > 0) CircleIconButton(Icons.Outlined.SwapHoriz, "Switch community", { sheet = CommunitySheet.Switcher })
            }
        }
        // More than one community: switch with one tap, like a server rail.
        if ((orgs?.size ?: 0) > 1) item(key = "rail") {
            WorkspaceRail(orgs.orEmpty(), d?.org?.id ?: selectedOrg, { vm.switchOrg(it) }, { nav.navigate(Routes.JOIN + "?code=") }, Modifier.padding(bottom = 6.dp))
        }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "Retry", onAction = { vm.refresh() }) } }
        if (d == null) {
            item {
                if (loading && orgs == null) ShimmerList(4)
                else Column {
                    EmptyState(
                        Icons.Outlined.Workspaces, stringResource(R.string.cm_none_title), stringResource(R.string.cm_none_body),
                        action = if (discovery) stringResource(R.string.cm_discover) else null, onAction = if (discovery) ({ nav.navigate(Routes.DISCOVER) }) else null,
                    )
                    PillButton(stringResource(R.string.cm_join_or_create), { nav.navigate(Routes.JOIN + "?code=") }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), tone = ButtonTone.Glass, height = 50.dp)
                }
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
                    StatTile(stringResource(R.string.cm_stat_members), "${d.stats.members}", Modifier.weight(1f))
                    StatTile(stringResource(R.string.cm_stat_channels), "${d.stats.channels}", Modifier.weight(1f))
                    StatTile(stringResource(R.string.cm_stat_categories), "${d.categories.size}", Modifier.weight(1f))
                }
            }
        }
        item {
            SegmentedPills(
                listOf(stringResource(R.string.cm_channels), stringResource(R.string.cm_about), stringResource(R.string.cm_manage)), tab, { tab = it },
                Modifier.padding(horizontal = Space.gutter, vertical = 10.dp),
            )
        }

        when (tab) {
            0 -> {
                if (sections.isEmpty()) item { EmptyState(Icons.Outlined.Tag, stringResource(R.string.cm_empty_category), stringResource(R.string.cm_none_body)) }
                sections.forEach { section ->
                    val category = d.categories.firstOrNull { it.id == section.categoryId }
                    item(key = "header-" + section.key) {
                        CategoryHeader(
                            section.title ?: stringResource(R.string.cm_channels), section.categoryId != null,
                            onMore = category?.takeIf { it.canManage }?.let { { sheet = CommunitySheet.CategoryActions(it) } },
                        )
                    }
                    if (section.channels.isEmpty()) item(key = "empty-" + section.key) {
                        Text(stringResource(R.string.cm_empty_category), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter + 8.dp, vertical = 4.dp))
                    }
                    items(section.channels, key = { "ch-" + it.id }) { ch ->
                        ChannelRow(
                            ch,
                            onOpen = { if (ch.joined) nav.navigate(Routes.conversation(ch.id)) else vm.join(ch) { id -> nav.navigate(Routes.conversation(id)) } },
                            onJoin = { vm.join(ch) { id -> nav.navigate(Routes.conversation(id)) } },
                            onMore = if (ch.canManage || canArrange) ({ sheet = CommunitySheet.ChannelActions(ch) }) else null,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (d.permissions.canCreateChannels) PillButton(stringResource(R.string.cm_new_channel), { sheet = CommunitySheet.NewChannel(null) }, Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PostAdd)
                        if (canCreateCategory) PillButton(stringResource(R.string.cm_new_category), { sheet = CommunitySheet.NewCategory }, Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.CreateNewFolder)
                    }
                }
            }
            1 -> item { CommunityAbout(d, onInvite = if (d.permissions.canInvite) ({ nav.navigate(Routes.invites(d.org.id)) }) else null) }
            else -> {
                // Honest setup progress for the people who run the community (hidden once every step is done).
                if (d.permissions.canManageOrg) item(key = "setup") { WorkspaceSetupCard(d) }
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(6.dp)) {
                        MenuRow(
                            Icons.Outlined.Settings, stringResource(R.string.ws_settings_open),
                            subtitle = stringResource(R.string.ws_your_role, roleLabel(d.org.myRole)),
                            onClick = { sheet = CommunitySheet.Settings },
                        )
                        if (d.permissions.canViewDirectory) MenuRow(Icons.Outlined.ManageAccounts, stringResource(R.string.cm_stat_members), subtitle = stringResource(R.string.manage_roles_sub), onClick = { nav.navigate(Routes.members(d.org.id)) })
                        if (d.permissions.canInvite) MenuRow(Icons.Outlined.GroupAdd, "Invitations", subtitle = "Create invite codes & QR, revoke", tint = c.peach, tintBg = c.peachSoft, onClick = { nav.navigate(Routes.invites(d.org.id)) })
                        if (d.permissions.canViewRoles) MenuRow(
                            Icons.Outlined.AdminPanelSettings, stringResource(R.string.manage_roles),
                            subtitle = stringResource(R.string.manage_roles_sub), onClick = { nav.navigate(Routes.roles(d.org.id)) },
                        )
                        if (d.permissions.canBan) MenuRow(Icons.Outlined.Block, stringResource(R.string.cm_bans), subtitle = stringResource(R.string.cm_bans_sub), tint = c.danger, tintBg = c.dangerSoft, onClick = { nav.navigate(Routes.bans(d.org.id)) })
                        MenuRow(
                            Icons.Outlined.Event, stringResource(R.string.cm_events), subtitle = stringResource(R.string.cm_events_sub), tint = c.peach, tintBg = c.peachSoft,
                            onClick = { nav.navigate(Routes.meetings(d.org.id, canSchedule = d.org.myRole != "GUEST")) },
                        )
                        if (d.permissions.canViewDirectory) MenuRow(Icons.Outlined.History, stringResource(R.string.activity_workspace_log), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { nav.navigate(Routes.activity(d.org.id)) })
                        if (d.permissions.canViewAudit) MenuRow(
                            Icons.Outlined.Policy, stringResource(R.string.manage_audit),
                            subtitle = stringResource(R.string.manage_audit_sub), tint = c.lavender, tintBg = c.lavenderSoft,
                            onClick = { nav.navigate(Routes.audit(d.org.id)) },
                        )
                        if (d.permissions.canViewUsage) MenuRow(
                            Icons.Outlined.Insights, stringResource(R.string.manage_usage),
                            subtitle = stringResource(R.string.manage_usage_sub), onClick = { nav.navigate(Routes.usage(d.org.id)) },
                        )
                        if (discovery) MenuRow(Icons.Outlined.Explore, stringResource(R.string.cm_discover), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { nav.navigate(Routes.DISCOVER) })
                        MenuRow(Icons.Outlined.Workspaces, stringResource(R.string.cm_join_or_create), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { nav.navigate(Routes.JOIN + "?code=") })
                    }
                }
                // Owner only, at the very end of Manage: far from switching communities, so a tap there can't reach it.
                if (DeleteWorkspaceRules.visible(d.org.myRole)) {
                    item { Text("Danger zone", style = Griip.type.section, color = c.danger, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 10.dp)) }
                    item {
                        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(6.dp)) {
                            if (d.permissions.canTransferOwnership) MenuRow(
                                Icons.Outlined.SwapHoriz, stringResource(R.string.owner_transfer), subtitle = stringResource(R.string.owner_transfer_sub),
                                tint = c.warning, tintBg = c.warningSoft, onClick = { sheet = CommunitySheet.Transfer },
                            )
                            MenuRow(
                                Icons.Outlined.DeleteForever, "Delete community", subtitle = "For every member. Can't be undone.",
                                tint = c.danger, tintBg = c.dangerSoft, onClick = { sheet = CommunitySheet.Delete },
                            )
                        }
                    }
                }
            }
        }
    }

    val det = d
    when (val s = sheet) {
        CommunitySheet.Switcher -> GlassSheet({ sheet = null }, title = stringResource(R.string.cm_title)) {
            WorkspaceSwitcherList(orgs.orEmpty(), det?.org?.id) { id -> vm.switchOrg(id); sheet = null }
            Spacer(Modifier.height(10.dp))
            PillButton(stringResource(R.string.cm_join_or_create), { sheet = null; nav.navigate(Routes.JOIN + "?code=") }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 50.dp)
        }
        null -> Unit
        else -> if (det != null) when (s) {
            is CommunitySheet.NewChannel -> CreateChannelSheet(det, s.categoryId, { sheet = null }) { id -> vm.refresh(); nav.navigate(Routes.conversation(id)) }
            CommunitySheet.NewCategory -> CategoryNameSheet(det.org.id, null, { sheet = null }) { vm.refresh() }
            is CommunitySheet.RenameCategory -> CategoryNameSheet(det.org.id, s.category, { sheet = null }) { vm.refresh() }
            is CommunitySheet.CategoryActions -> {
                val i = categoryOrder.indexOf(s.category.id)
                CategoryActionsSheet(
                    s.category, canMoveUp = i > 0, canMoveDown = i in 0 until categoryOrder.lastIndex, canReorder = canArrange,
                    onDismiss = { if (sheet == s) sheet = null },
                    onNewChannel = if (s.category.canCreateChannels) ({ sheet = CommunitySheet.NewChannel(s.category.id) }) else null,
                    onRename = { sheet = CommunitySheet.RenameCategory(s.category) },
                    onPermissions = { nav.navigate(Routes.permissions(category = true, id = s.category.id, name = s.category.name)) },
                    onMove = { delta -> vm.saveLayout(LayoutReq(categories = LayoutRules.move(categoryOrder, s.category.id, delta)), movedMessage) },
                    onDelete = if (canArrange) ({ vm.deleteCategory(s.category) }) else null,
                )
            }
            is CommunitySheet.ChannelActions -> {
                val siblings = sections.firstOrNull { sec -> sec.channels.any { it.id == s.channel.id } }?.channels.orEmpty().map { it.id }
                val i = siblings.indexOf(s.channel.id)
                ChannelArrangeSheet(
                    s.channel, canMoveUp = i > 0, canMoveDown = i in 0 until siblings.lastIndex, canArrange = canArrange,
                    onDismiss = { if (sheet == s) sheet = null },
                    onMove = { delta -> vm.saveLayout(LayoutReq(channels = LayoutRules.positions(s.channel.categoryId, LayoutRules.move(siblings, s.channel.id, delta))), movedMessage) },
                    onMoveTo = { sheet = CommunitySheet.MoveTo(s.channel) },
                    onPermissions = { nav.navigate(Routes.permissions(category = false, id = s.channel.id, name = s.channel.name.orEmpty())) },
                )
            }
            is CommunitySheet.MoveTo -> MoveToCategorySheet(s.channel, det.categories, { if (sheet == s) sheet = null }) { target ->
                val targetIds = sections.firstOrNull { it.categoryId == target }?.channels.orEmpty().map { it.id }
                vm.saveLayout(LayoutReq(channels = LayoutRules.moveTo(s.channel.id, target, targetIds)), movedMessage)
            }
            CommunitySheet.Settings -> WorkspaceSettingsSheet(det, onDismiss = { sheet = null }, onChanged = { vm.refresh() }, onLeft = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.ws_left, name), android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            CommunitySheet.Transfer -> if (det.permissions.canTransferOwnership) TransferOwnershipSheet(det, onDismiss = { sheet = null }, onDone = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.owner_transfer_done, name), android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            CommunitySheet.Delete -> if (DeleteWorkspaceRules.visible(det.org.myRole)) DeleteWorkspaceSheet(det, onDismiss = { sheet = null }, onDeleted = { name ->
                sheet = null
                android.widget.Toast.makeText(ctx, "Deleted “$name”", android.widget.Toast.LENGTH_SHORT).show()
                vm.refresh()
            })
            else -> Unit
        }
    }
}

@Composable
private fun CategoryHeader(title: String, isCategory: Boolean, onMore: (() -> Unit)?) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(start = Space.gutter + 4.dp, end = Space.gutter - 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (isCategory) Icons.Outlined.FolderOpen else Icons.Outlined.Tag, null, tint = c.peach, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(title.uppercase(), style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, modifier = Modifier.weight(1f))
        if (onMore != null) CircleIconButton(Icons.Outlined.MoreHoriz, title, onMore)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(ch: ConversationDto, onOpen: () -> Unit, onJoin: () -> Unit, onMore: (() -> Unit)?) {
    val c = Griip.colors
    val (fg, bg) = when {
        ch.voice -> c.success to c.successSoft
        ch.format == "forum" -> c.lavender to c.lavenderSoft
        ch.kind == "ANNOUNCEMENT" -> c.peach to c.peachSoft
        ch.kind == "PRIVATE" -> c.lavender to c.lavenderSoft
        else -> c.primary to c.primarySoft
    }
    ListCard(
        if (ch.voice) ch.name.orEmpty() else "#${ch.name}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp).combinedClickable(onClick = onOpen, onLongClick = onMore),
        subtitle = ch.topic,
        leading = { IconTile(channelRowIcon(ch), fg, bg, size = 42.dp) },
        meta = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("${ch.memberCount}", c.textSecondary, c.surfaceVariant, icon = Icons.Outlined.Groups)
                if (ch.activeCall != null) TagPill("Live", c.success, c.successSoft)
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.joined) ArrowKnob()
                else PillButton(stringResource(R.string.cm_join), onJoin, tone = ButtonTone.Glass, showKnob = false, height = 38.dp)
                if (onMore != null) CircleIconButton(Icons.Outlined.MoreHoriz, ch.name.orEmpty(), onMore)
            }
        },
    )
}

/** About: the community's description, rules and how people find and join it. */
@Composable
private fun CommunityAbout(d: OrgDetailRes, onInvite: (() -> Unit)?) {
    val c = Griip.colors
    Column(Modifier.padding(horizontal = Space.gutter)) {
        GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(d.org.name, style = Griip.type.title, color = c.textPrimary)
            d.org.description?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Griip.type.body, color = c.textSecondary) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(stringResource(if (d.org.visibility == CommunityAccess.PUBLIC) R.string.cs_public else R.string.cs_private), c.primaryDeep, c.primarySoft)
                TagPill(stringResource(if (d.org.joinPolicy == CommunityAccess.OPEN) R.string.dc_open else R.string.dc_invite_only), c.textSecondary, c.surfaceVariant)
            }
        }
        d.org.rules?.takeIf { it.isNotBlank() }?.let { rules ->
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(stringResource(R.string.cm_rules), style = Griip.type.section, color = c.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(rules, style = Griip.type.body, color = c.textSecondary)
            }
        }
        onInvite?.let { PillButton("Invite people", it, Modifier.fillMaxWidth().padding(vertical = 10.dp), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.GroupAdd) }
    }
}
