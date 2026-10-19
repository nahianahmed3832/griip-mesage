package com.griipbuddy.orbit.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.orbit.data.api.NavPrefsDto
import com.griipbuddy.orbit.data.model.NavOp
import com.griipbuddy.orbit.data.model.NavPrefsRules
import com.griipbuddy.orbit.data.repo.NavPreferences
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.data.api.OrgDto
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.UserEntity
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.LocalTabBarPadding
import com.griipbuddy.orbit.ui.Routes
import com.griipbuddy.orbit.ui.components.ArrowKnob
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.IconTile
import com.griipbuddy.orbit.ui.components.ListCard
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.ShimmerList
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.components.UndoBar
import com.griipbuddy.orbit.ui.components.UserAvatar
import com.griipbuddy.orbit.ui.containerViewModel
import com.griipbuddy.orbit.ui.screens.chats.ChatActionsSheet
import com.griipbuddy.orbit.ui.screens.chats.ChatListRules
import com.griipbuddy.orbit.ui.screens.chats.ChatPreview
import com.griipbuddy.orbit.ui.screens.chats.ConversationAvatar
import com.griipbuddy.orbit.ui.screens.chats.channelIcon
import com.griipbuddy.orbit.ui.screens.chats.listTitle
import com.griipbuddy.orbit.ui.screens.chats.rememberChatListActions
import com.griipbuddy.orbit.ui.screens.workspace.WorkspaceSwitcherList
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Motion
import com.griipbuddy.orbit.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Space under the last Home item: the New button floats there, above the tab bar. */
private val FabClearance = 88.dp

/**
 * The Home tab (UI-002): the selected workspace at a glance (Cocoa, like every signed-in screen). A header with the
 * workspace switcher, your avatar (profile and settings), search and options; quick-access cards (Unreads, Drafts,
 * Calls); unread DMs; @mentions; and the workspace's channels grouped by organisation, branch and team, each group
 * collapsible. Everything is this phone's real data; empty sections say so. [onSeeAllUnreadDms] opens the DMs tab
 * filtered to unread.
 */
@Composable
fun WorkspaceHomeScreen(nav: NavHostController, onSeeAllUnreadDms: () -> Unit) = WorkspaceHome(nav, onSeeAllUnreadDms)

@Composable
private fun WorkspaceHome(nav: NavHostController, onSeeAllUnreadDms: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val vm = containerViewModel { HomeViewModel(it) }
    val loaded by vm.conversations.collectAsStateWithLifecycle()
    val convs = loaded.orEmpty()
    val users by vm.users.collectAsStateWithLifecycle()
    val callLog by vm.callLog.collectAsStateWithLifecycle()
    val meUser by vm.me.collectAsStateWithLifecycle()
    val orgs by vm.orgs.collectAsStateWithLifecycle()
    val orgError by vm.orgError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val selectedId by vm.selectedOrg.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val conn by container.realtime.state.collectAsStateWithLifecycle()
    val call by container.calls.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val me = container.session.session?.userId
    val myName = meUser?.displayName?.takeIf { it.isNotBlank() } ?: container.session.session?.displayName.orEmpty()
    val actions = rememberChatListActions()
    val scope = rememberCoroutineScope()
    val permission = com.griipbuddy.orbit.ui.screens.calls.rememberCallPermission()
    val context = androidx.compose.ui.platform.LocalContext.current

    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var mentionsOpen by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<HomeSheet?>(null) }
    var actionsFor by remember { mutableStateOf<ConversationEntity?>(null) }
    var movingFor by remember { mutableStateOf<ConversationEntity?>(null) }
    var editingSection by remember { mutableStateOf<CustomSection?>(null) }
    var undo by remember { mutableStateOf<ConversationEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(undo) { if (undo != null) { delay(5_000); undo = null } }

    val org = orgs?.firstOrNull { it.id == selectedId }
    val orgName = org?.name ?: stringResource(R.string.home_no_workspace)
    val unreadDms = remember(convs) { WorkspaceHomeRules.unreadDms(convs) }
    val mentions = remember(convs, org?.id) { WorkspaceHomeRules.mentions(convs, org?.id) }
    // Personal arrangement (starred, sections, collapsed groups): this workspace's and the personal one's, synced.
    val navPrefs by container.navPrefs.prefs.collectAsStateWithLifecycle()
    val starredIds by container.navPrefs.starred.collectAsStateWithLifecycle()
    val orgPrefs = org?.id?.let { navPrefs[it] } ?: NavPrefsDto()
    val personalPrefs = navPrefs[NavPreferences.PERSONAL] ?: NavPrefsDto()
    val collapsed = remember(orgPrefs) { orgPrefs.collapsed.toSet() }
    val starred = remember(convs, org?.id, orgPrefs, personalPrefs, unreadOnly) {
        WorkspaceHomeRules.starred(convs, org?.id, orgPrefs.starred + personalPrefs.starred, unreadOnly)
    }
    val sections = remember(convs, org?.id, orgPrefs, personalPrefs, unreadOnly) {
        WorkspaceHomeRules.customSections(
            convs, org?.id, orgPrefs.sections.map { (org?.id ?: NavPreferences.PERSONAL) to it } + personalPrefs.sections.map { NavPreferences.PERSONAL to it }, unreadOnly,
        )
    }
    val placed = remember(orgPrefs, personalPrefs) { NavPrefsRules.placed(orgPrefs) + NavPrefsRules.placed(personalPrefs) }
    val groups = remember(convs, org?.id, orgName, unreadOnly, placed) { WorkspaceHomeRules.channelGroups(convs, org?.id, orgName, unreadOnly, placed) }
    fun setCollapsed(key: String, value: Boolean) { org?.id?.let { container.navPrefs.change(it, NavOp.Collapse(key, value)) } }
    fun setAllCollapsed(value: Boolean) {
        groups.filter { (it.key in collapsed) != value }.forEach { setCollapsed(it.key, value) }
        sections.filter { it.collapsed != value }.forEach { container.navPrefs.change(it.workspaceId, NavOp.CollapseSection(it.id, value)) }
    }
    val drafts = remember(convs) { WorkspaceHomeRules.drafts(convs) }
    val unreadChats = remember(convs, org?.id) { WorkspaceHomeRules.unreadChats(convs, org?.id) }
    val missed = remember(callLog) { WorkspaceHomeRules.missedCalls(callLog, System.currentTimeMillis(), WorkspaceHomeRules.MISSED_CALLS_CARD_WINDOW_MS).size }
    val online = remember(users) { users.associate { it.id to it.online } }
    val liveCall = if (call.phase == CallPhase.Idle) convs.firstOrNull { it.activeCallId != null } else null

    fun open(conv: ConversationEntity) = nav.navigate(Routes.conversation(conv.id))
    fun archive(conv: ConversationEntity) {
        val archiving = !ChatListRules.isArchived(conv)
        actions.setArchived(conv, archiving)
        undo = if (archiving) conv else null
    }

    val cards = listOf(
        QuickCard(
            Icons.Outlined.MarkChatUnread, stringResource(R.string.home_card_unreads),
            if (unreadChats > 0) stringResource(R.string.home_unread_count, unreadChats) else stringResource(R.string.home_caught_up),
            active = unreadChats > 0, selected = unreadOnly, onClick = { unreadOnly = !unreadOnly },
        ),
        QuickCard(
            Icons.Outlined.EditNote, stringResource(R.string.home_card_drafts),
            if (drafts.isNotEmpty()) stringResource(R.string.home_drafts_count, drafts.size) else stringResource(R.string.home_none),
            active = drafts.isNotEmpty(), onClick = { sheet = HomeSheet.Drafts },
        ),
        QuickCard(
            Icons.Outlined.Call, stringResource(R.string.home_card_calls),
            if (missed > 0) stringResource(R.string.home_missed_today, missed) else stringResource(R.string.home_history),
            active = missed > 0, onClick = { nav.navigate(Routes.CALLS) },
        ),
    )

    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val fabVisible by rememberFabVisible(list)
    val barPadding = LocalTabBarPadding.current

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = FabClearance + barPadding)) {
            item(key = "header") {
                WorkspaceHeader(
                    org, known = orgs != null, onSwitcher = { sheet = HomeSheet.Workspaces }, onProfile = { sheet = HomeSheet.Profile },
                    onSearch = { nav.navigate(Routes.SEARCH) },
                    avatar = { UserAvatar(me, myName.ifBlank { "?" }, meUser?.avatarColor ?: 0, meUser?.photoRev, size = 40.dp) },
                    filter = {
                        HomeFilterButton(
                            unreadOnly, { unreadOnly = it },
                            onExpandAll = { setAllCollapsed(false) },
                            onCollapseAll = { setAllCollapsed(true) },
                        )
                    },
                )
            }
            item(key = "connection") {
                com.griipbuddy.orbit.ui.screens.home.ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { container.realtime.reconnectNow() }
            }
            item(key = "notification-problems") {
                val problems = rememberNotificationProblems(settings)
                NotificationProblems(problems, Modifier.padding(horizontal = Space.gutter), onlySevere = true, max = 1)
            }
            orgError?.let { error ->
                if (orgs.isNullOrEmpty()) item(key = "org-error") {
                    StatusBanner(
                        stringResource(R.string.home_workspace_error, error), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp),
                        action = stringResource(R.string.common_retry), onAction = { vm.refresh() },
                    )
                }
            }
            if (liveCall != null) item(key = "live-call") {
                StatusBanner(
                    "${if (liveCall.activeCallVideo) "Video" else "Voice"} call in progress · ${listTitle(liveCall, me)}", BannerKind.Success,
                    Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), icon = Icons.Rounded.Call, action = "Join",
                    onAction = {
                        liveCall.activeCallId?.let { callId ->
                            permission(liveCall.activeCallVideo) { scope.launch { container.calls.join(liveCall.id, callId, liveCall.activeCallVideo) } }
                        }
                    },
                )
            }
            item(key = "cards") { QuickAccessRow(cards, Modifier.padding(top = 4.dp, bottom = 4.dp)) }

            if (loaded == null || (refreshing && convs.isEmpty())) {
                item(key = "loading") { ShimmerList(5, Modifier.padding(top = 12.dp)) }
                return@LazyColumn
            }

            starredSection(starred, me, onOpen = ::open, onLongPress = { actionsFor = it })
            customSectionsList(
                sections, me,
                onToggle = { s -> container.navPrefs.change(s.workspaceId, NavOp.CollapseSection(s.id, !s.collapsed)) },
                onEdit = { editingSection = it }, onOpen = ::open, onLongPress = { actionsFor = it },
            )
            unreadDmsSection(
                unreadDms, me, online, onSeeAll = onSeeAllUnreadDms, onOpen = ::open, onLongPress = { actionsFor = it },
            )
            mentionsSection(mentions, me, mentionsOpen, onToggle = { mentionsOpen = !mentionsOpen }, onOpen = ::open, onLongPress = { actionsFor = it })
            if (org == null) {
                if (orgs != null) item(key = "join-workspace") {
                    ListCard(
                        stringResource(R.string.home_join_workspace), Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        subtitle = stringResource(R.string.home_join_workspace_sub),
                        leading = { IconTile(Icons.Outlined.Workspaces, c.peach, c.peachSoft, size = 44.dp) },
                        trailing = { ArrowKnob() },
                        onClick = { nav.navigate(Routes.JOIN + "?code=") },
                    )
                }
            } else {
                channelSection(
                    groups, collapsed, unreadOnly, me,
                    onToggle = { key -> setCollapsed(key, key !in collapsed) },
                    onOpen = ::open, onLongPress = { actionsFor = it },
                    onBrowse = { nav.navigate(Routes.WORKSPACE) },
                )
            }
        }
        // Once the list scrolls under the transparent status bar, a scrim keeps the clock and icons readable.
        val scrim by animateFloatAsState(if (scrolled) 0.94f else 0f, Motion.quick(), label = "homeStatusScrim")
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).graphicsLayer { alpha = scrim }.background(c.background))
        ComposeFab(
            stringResource(R.string.new_fab), { sheet = HomeSheet.New },
            Modifier.align(Alignment.BottomEnd).padding(end = Space.gutter, bottom = barPadding + 12.dp),
            visible = fabVisible && undo == null,
        )
        undo?.let { conv ->
            UndoBar(
                stringResource(R.string.chats_archived_toast, listTitle(conv, me)), onUndo = {
                    actions.setArchived(conv, false)
                    undo = null
                },
                Modifier.align(Alignment.BottomCenter).padding(start = Space.gutter, end = Space.gutter, bottom = 16.dp + barPadding),
            )
        }
    }

    when (sheet) {
        HomeSheet.Workspaces -> WorkspacesSheet(
            orgs.orEmpty(), org?.id, onDismiss = { sheet = null },
            onSelect = { id -> vm.switchOrg(id); sheet = null },
            onManage = { sheet = null; nav.navigate(Routes.WORKSPACE) },
            onJoin = { sheet = null; nav.navigate(Routes.JOIN + "?code=") },
            onSettings = if (detail?.org?.id == org?.id && detail != null) ({ sheet = HomeSheet.Settings }) else null,
        )
        HomeSheet.Settings -> detail?.let { d ->
            com.griipbuddy.orbit.ui.screens.workspace.WorkspaceSettingsSheet(
                d, onDismiss = { sheet = null }, onChanged = { vm.reloadDetail(); vm.refresh() },
                onLeft = { name ->
                    sheet = null
                    android.widget.Toast.makeText(context, context.getString(R.string.ws_left, name), android.widget.Toast.LENGTH_SHORT).show()
                    vm.refresh()
                },
            )
        }
        HomeSheet.New -> NewActionsSheet(detail?.takeIf { it.org.id == org?.id }, onDismiss = { sheet = null }) { action ->
            sheet = when (action) {
                NewAction.CHANNEL -> HomeSheet.NewChannel
                else -> null
            }
            when (action) {
                NewAction.MESSAGE -> nav.navigate(Routes.NEW_CHAT + "?mode=dm")
                NewAction.GROUP -> nav.navigate(Routes.NEW_CHAT + "?mode=group")
                NewAction.INVITE -> org?.id?.let { nav.navigate(Routes.invites(it)) }
                NewAction.WORKSPACE -> nav.navigate(Routes.JOIN + "?code=")
                NewAction.CHANNEL -> Unit
            }
        }
        HomeSheet.NewChannel -> detail?.takeIf { it.org.id == org?.id }?.let { d ->
            com.griipbuddy.orbit.ui.screens.workspace.CreateChannelSheet(d, null, { sheet = null }) { id ->
                sheet = null
                nav.navigate(Routes.conversation(id))
            }
        }
        HomeSheet.Profile -> ProfileSheet(
            me, myName, meUser?.username?.takeIf { it.isNotBlank() } ?: container.session.session?.username, meUser,
            onDismiss = { sheet = null },
        ) { route -> sheet = null; nav.navigate(route) }
        HomeSheet.Drafts -> DraftsSheet(drafts, me, onDismiss = { sheet = null }) { conv -> sheet = null; open(conv) }
        null -> Unit
    }
    actionsFor?.let { conv ->
        ChatActionsSheet(
            conv, me, onDismiss = { actionsFor = null },
            onToggleMute = { actions.toggleMute(conv) }, onToggleRead = { actions.toggleRead(conv) },
            onToggleArchive = { archive(conv) }, onInfo = { nav.navigate(Routes.info(conv.id)) },
            starred = conv.id in starredIds, onToggleStar = { actions.toggleStar(conv) },
            onMoveToSection = { movingFor = conv },
        )
    }
    movingFor?.let { conv -> SectionPickerSheet(conv, onDismiss = { movingFor = null }) }
    editingSection?.let { section -> SectionEditSheet(section, onDismiss = { editingSection = null }) }
}

/** Starred conversations (personal, on every device of the person), above everything else; hidden when nothing is starred. */
private fun LazyListScope.starredSection(
    starred: List<ConversationEntity>,
    me: String?,
    onOpen: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
) {
    if (starred.isEmpty()) return
    item(key = "starred-header") { HomeSectionHeader(stringResource(R.string.home_starred)) }
    items(starred, key = { "starred-" + it.id }) { conv ->
        val unread = conv.unreadCount > 0
        HomeListRow(
            listTitle(conv, me).removePrefix("#"),
            leading = { ChannelOrAvatar(conv, me) },
            onClick = { onOpen(conv) }, onLongClick = { onLongPress(conv) },
            subtitle = conv.scopeName?.takeIf { conv.type == "CHANNEL" }, bold = unread && !conv.muted, dim = conv.muted,
            modifier = Modifier.animateItem(),
            trailing = {
                if (conv.mentionCount > 0) HomeCount(conv.mentionCount, accent = true, description = stringResource(R.string.home_mention_count, conv.mentionCount), prefix = "@")
                else HomeCount(conv.unreadCount, accent = false, description = stringResource(R.string.home_unread_messages, conv.unreadCount))
            },
        )
    }
}

/** The bottom sheets Home can show (saved across rotation). */
private enum class HomeSheet { Workspaces, Profile, Drafts, Settings, New, NewChannel }

/** Filter and options: show only unread channels, and open or close every channel group at once. */
@Composable
private fun HomeFilterButton(unreadOnly: Boolean, onUnreadOnly: (Boolean) -> Unit, onExpandAll: () -> Unit, onCollapseAll: () -> Unit) {
    val c = Griip.colors
    var open by remember { mutableStateOf(false) }
    Box {
        CircleIconButton(Icons.Rounded.Tune, stringResource(R.string.home_filter), { open = true }, background = c.surface, size = 48.dp, badge = unreadOnly)
        DropdownMenu(open, { open = false }, containerColor = c.glassStrong, shape = com.griipbuddy.orbit.ui.theme.Shapes.card) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_unread_only), style = Griip.type.bodyMedium, color = c.textPrimary) },
                leadingIcon = { Icon(Icons.Outlined.MarkChatUnread, null, tint = c.textSecondary) },
                trailingIcon = { if (unreadOnly) Icon(Icons.Rounded.Check, null, tint = c.primary) },
                onClick = { open = false; onUnreadOnly(!unreadOnly) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_expand_all), style = Griip.type.bodyMedium, color = c.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.UnfoldMore, null, tint = c.textSecondary) },
                onClick = { open = false; onExpandAll() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_collapse_all), style = Griip.type.bodyMedium, color = c.textPrimary) },
                leadingIcon = { Icon(Icons.Rounded.UnfoldLess, null, tint = c.textSecondary) },
                onClick = { open = false; onCollapseAll() },
            )
        }
    }
}

private fun LazyListScope.unreadDmsSection(
    unread: List<ConversationEntity>,
    me: String?,
    online: Map<String, Boolean>,
    onSeeAll: () -> Unit,
    onOpen: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
) {
    item(key = "dms-header") {
        HomeSectionHeader(
            stringResource(R.string.home_unread_dms),
            action = if (unread.isNotEmpty()) stringResource(R.string.home_see_all, unread.size) else null, onAction = onSeeAll,
        )
    }
    if (unread.isEmpty()) item(key = "dms-empty") { QuietLine(stringResource(R.string.home_no_unread_dms)) }
    items(unread.take(WorkspaceHomeRules.UNREAD_DMS_SHOWN), key = { "dm-" + it.id }) { conv ->
        val peer = com.griipbuddy.orbit.data.repo.ConversationRepository.peer(conv, me)
        val preview = ChatPreview.of(conv, emptyList())
        HomeListRow(
            listTitle(conv, me),
            leading = { ConversationAvatar(conv, me, size = 36.dp, online = peer?.let { online[it.id] }) },
            onClick = { onOpen(conv) }, onLongClick = { onLongPress(conv) },
            subtitle = listOfNotNull(preview.prefix, preview.text).joinToString(": "), bold = true, dim = conv.muted,
            modifier = Modifier.animateItem(),
            trailing = { HomeCount(conv.unreadCount, accent = !conv.muted, description = stringResource(R.string.home_unread_messages, conv.unreadCount)) },
        )
    }
}

private fun LazyListScope.mentionsSection(
    mentions: List<ConversationEntity>,
    me: String?,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
) {
    item(key = "mentions-header") { HomeSectionHeader(stringResource(R.string.home_mentions), expanded = open, onToggle = onToggle) }
    if (!open) return
    if (mentions.isEmpty()) item(key = "mentions-empty") { QuietLine(stringResource(R.string.home_no_mentions)) }
    items(mentions, key = { "mention-" + it.id }) { conv ->
        HomeListRow(
            listTitle(conv, me),
            leading = { ChannelOrAvatar(conv, me) },
            onClick = { onOpen(conv) }, onLongClick = { onLongPress(conv) },
            subtitle = conv.scopeName?.takeIf { conv.type == "CHANNEL" }, bold = true,
            modifier = Modifier.animateItem(),
            trailing = { HomeCount(conv.mentionCount, accent = true, description = stringResource(R.string.home_mention_count, conv.mentionCount), prefix = "@") },
        )
    }
}

private fun LazyListScope.channelSection(
    groups: List<ChannelGroup>,
    collapsed: Set<String>,
    unreadOnly: Boolean,
    me: String?,
    onToggle: (String) -> Unit,
    onOpen: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
    onBrowse: () -> Unit,
) {
    if (groups.isEmpty()) {
        item(key = "channels-empty") {
            QuietLine(stringResource(if (unreadOnly) R.string.home_no_unread_channels else R.string.home_no_channels), Modifier.padding(top = 12.dp))
        }
    }
    groups.forEach { group ->
        val expanded = group.key !in collapsed
        item(key = "group-" + group.key) { ChannelGroupHeader(group, expanded, { onToggle(group.key) }, Modifier.animateItem()) }
        if (expanded) items(group.channels, key = { "channel-" + it.id }) { conv ->
            val unread = conv.unreadCount > 0
            HomeListRow(
                listTitle(conv, me).removePrefix("#"),
                leading = { ChannelOrAvatar(conv, me) },
                onClick = { onOpen(conv) }, onLongClick = { onLongPress(conv) },
                bold = unread && !conv.muted, dim = conv.muted, indent = 8.dp,
                modifier = Modifier.animateItem(),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (conv.activeCallId != null) Icon(Icons.Rounded.Call, "Live call", tint = Griip.colors.success, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                        if (conv.mentionCount > 0) HomeCount(conv.mentionCount, accent = true, description = stringResource(R.string.home_mention_count, conv.mentionCount), prefix = "@")
                        else HomeCount(conv.unreadCount, accent = false, description = stringResource(R.string.home_unread_messages, conv.unreadCount))
                    }
                },
            )
        }
    }
    item(key = "browse") {
        HomeListRow(
            stringResource(R.string.home_browse),
            leading = { Icon(Icons.Outlined.Workspaces, null, tint = Griip.colors.textSecondary, modifier = Modifier.size(20.dp)) },
            onClick = onBrowse, modifier = Modifier.padding(top = 4.dp),
            trailing = { ArrowKnob() },
        )
    }
}

/** A channel's kind as an icon (#, lock for private, megaphone for announcements); people and groups as avatars. */
@Composable
internal fun ChannelOrAvatar(conv: ConversationEntity, me: String?) {
    val c = Griip.colors
    if (conv.type != "CHANNEL") {
        ConversationAvatar(conv, me, size = 32.dp)
        return
    }
    val description = when (conv.kind) {
        "PRIVATE" -> "Private channel"
        "ANNOUNCEMENT" -> "Announcement channel"
        else -> null
    }
    Icon(
        channelIcon(conv.kind), description, tint = if (conv.unreadCount > 0 && !conv.muted) c.textPrimary else c.textSecondary,
        modifier = Modifier.size(20.dp),
    )
}

/** The workspace switcher: the workspaces you belong to (the server's list), then managing this one or joining another. */
@Composable
private fun WorkspacesSheet(
    orgs: List<OrgDto>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onJoin: () -> Unit,
    onSettings: (() -> Unit)?,
) {
    GlassSheet(onDismiss, title = stringResource(R.string.home_workspaces)) {
        WorkspaceSwitcherList(orgs, selectedId, onSelect)
        Spacer(Modifier.height(10.dp))
        if (onSettings != null) {
            PillButton(stringResource(R.string.ws_settings_open), onSettings, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 50.dp)
            Spacer(Modifier.height(8.dp))
        }
        if (selectedId != null) {
            PillButton(stringResource(R.string.home_manage_workspace), onManage, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 50.dp)
            Spacer(Modifier.height(8.dp))
        }
        PillButton(stringResource(R.string.home_join_workspace), onJoin, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 50.dp)
    }
}

/** You: your name and handle, then the existing profile, settings and account screens. */
@Composable
private fun ProfileSheet(me: String?, myName: String, handle: String?, user: UserEntity?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val c = Griip.colors
    GlassSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(me, myName.ifBlank { "?" }, user?.avatarColor ?: 0, user?.photoRev, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(myName, style = Griip.type.title, color = c.textPrimary, maxLines = 1)
                handle?.takeIf { it.isNotBlank() }?.let { Text("@$it", style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 1) }
            }
        }
        MenuRow(Icons.Outlined.AccountCircle, stringResource(R.string.settings_edit_profile), onClick = { onOpen(Routes.EDIT_PROFILE) })
        MenuRow(Icons.Outlined.Settings, stringResource(R.string.settings_title), onClick = { onOpen(Routes.PROFILE) })
        MenuRow(Icons.Outlined.Notifications, stringResource(R.string.settings_notifications), tint = c.peach, tintBg = c.peachSoft, onClick = { onOpen(Routes.NOTIFICATION_SETTINGS) })
        MenuRow(Icons.Outlined.Palette, stringResource(R.string.chat_appearance), onClick = { onOpen(Routes.CHAT_SETTINGS) })
        MenuRow(Icons.Outlined.Lock, stringResource(R.string.settings_privacy), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { onOpen(Routes.PRIVACY) })
        MenuRow(Icons.Outlined.Language, stringResource(R.string.settings_language), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onOpen(Routes.LANGUAGE) })
        MenuRow(Icons.Outlined.People, stringResource(R.string.contacts_title), onClick = { onOpen(Routes.CONTACTS) })
        MenuRow(Icons.Outlined.Call, stringResource(R.string.home_call_history), onClick = { onOpen(Routes.CALLS) })
        MenuRow(
            Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.home_accounts), tint = c.textSecondary, tintBg = c.surfaceVariant,
            onClick = { onOpen(Routes.ACCOUNTS) },
        )
    }
}

/** Unsent drafts saved on this phone; opening one restores it in the composer. */
@Composable
private fun DraftsSheet(drafts: List<ConversationEntity>, me: String?, onDismiss: () -> Unit, onOpen: (ConversationEntity) -> Unit) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = stringResource(R.string.home_card_drafts)) {
        if (drafts.isEmpty()) Text(stringResource(R.string.home_none), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        drafts.forEach { conv ->
            androidx.compose.runtime.key(conv.id) {
                ListCard(
                    listTitle(conv, me), Modifier.padding(vertical = 2.dp),
                    subtitle = conv.draft?.trim()?.replace('\n', ' '),
                    leading = { ConversationAvatar(conv, me, size = 40.dp) },
                    trailing = { ArrowKnob() },
                    onClick = { onOpen(conv) },
                )
            }
        }
    }
}
