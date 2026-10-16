package com.griipbuddy.orbit.ui.screens.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.ShimmerList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.orbit.data.api.ResolveRes
import com.griipbuddy.orbit.data.api.UserDto
import com.griipbuddy.orbit.data.repo.GlobalAddress
import com.griipbuddy.orbit.crypto.unb64
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.Routes
import com.griipbuddy.orbit.ui.components.ArrowKnob
import com.griipbuddy.orbit.ui.components.Avatar
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ChipRow
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.EmptyState
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.GriipTopBar
import com.griipbuddy.orbit.ui.components.LargeHeader
import com.griipbuddy.orbit.ui.components.ListCard
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.PillChip
import com.griipbuddy.orbit.ui.components.SearchPill
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.components.TagPill
import com.griipbuddy.orbit.ui.friendly
import com.griipbuddy.orbit.ui.screens.home.ConnectionBanner
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val me = container.session.session?.userId
    val convs by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    val users by container.db.users().observeAll().collectAsStateWithLifecycle(emptyList())
    val typing by container.messages.typing.collectAsStateWithLifecycle()
    val conn by container.realtime.state.collectAsStateWithLifecycle()
    val call by container.calls.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ConversationEntity?>(null) }
    // The archived list (local to this phone), and the chat just archived, for Undo.
    var archivedView by rememberSaveable { mutableStateOf(false) }
    var undo by remember { mutableStateOf<ConversationEntity?>(null) }
    androidx.activity.compose.BackHandler(enabled = archivedView) { archivedView = false }
    LaunchedEffect(undo) { if (undo != null) { delay(5_000); undo = null } }
    var refreshing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val permission = com.griipbuddy.orbit.ui.screens.calls.rememberCallPermission()
    LaunchedEffect(Unit) { runCatching { container.conversations.refresh() }; refreshing = false }

    val filters = listOf("All", "Unread", "Direct", "Groups", "Channels")
    val shown = ChatListRules.visible(convs, filter, query, archivedView) { ConversationRepository.title(it, me) }
    val archivedInfo = ChatListRules.archived(convs)
    fun toggleRead(conv: ConversationEntity) {
        scope.launch {
            if (ChatListRules.swipeMarksRead(conv)) {
                container.conversations.markRead(conv.id)
                container.notifier.clear(conv.id)
            } else container.conversations.markUnread(conv.id)
        }
    }
    fun toggleArchive(conv: ConversationEntity) {
        val archive = !ChatListRules.isArchived(conv)
        scope.launch { container.conversations.setArchived(conv.id, archive) }
        undo = if (archive) conv else null
    }
    fun toggleMute(conv: ConversationEntity) {
        scope.launch { container.conversations.setMuted(conv.id, !conv.muted) }
    }
    val online = users.associate { it.id to it.online }
    val liveCall = if (call.phase == CallPhase.Idle) convs.firstOrNull { it.activeCallId != null } else null

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (archivedView) item {
                GriipTopBar(
                    "Archived chats", subtitle = "Only on this phone. They still notify you unless muted.",
                    onBack = { archivedView = false },
                )
            } else item {
                LargeHeader("Chats") {
                    CircleIconButton(Icons.Rounded.Search, "Search people, chats and messages", { nav.navigate(Routes.SEARCH) })
                    CircleIconButton(Icons.Outlined.GroupAdd, "New group", { nav.navigate(Routes.NEW_CHAT + "?mode=group") })
                }
            }
            item { ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { container.realtime.reconnectNow() } }
            item {
                // Blocked or silenced notifications are the first thing to fix: shown here, at most one at a time.
                val settings by container.settings.state.collectAsStateWithLifecycle()
                val problems = com.griipbuddy.orbit.ui.screens.home.rememberNotificationProblems(settings)
                com.griipbuddy.orbit.ui.screens.home.NotificationProblems(problems, Modifier.padding(horizontal = Space.gutter), onlySevere = true, max = 1)
            }
            if (liveCall != null) item {
                val title = ConversationRepository.title(liveCall, me).let { if (liveCall.type == "CHANNEL") "#$it" else it }
                StatusBanner(
                    "${if (liveCall.activeCallVideo) "Video" else "Voice"} call in progress · $title", BannerKind.Success,
                    Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), icon = Icons.Rounded.Call, action = "Join",
                    onAction = { permission(liveCall.activeCallVideo) { scope.launch { container.calls.join(liveCall.id, liveCall.activeCallId!!, liveCall.activeCallVideo) } } },
                )
            }
            item { SearchPill(query, { query = it }, "Filter chats by name", Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
            if (!archivedView) item {
                ChipRow {
                    filters.forEachIndexed { i, f ->
                        PillChip(f, filter == i, { filter = i }, count = if (i == ChatListRules.UNREAD) ChatListRules.unreadCount(convs) else null)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (!archivedView && query.isBlank() && archivedInfo.chats > 0) item(key = "archived") {
                ListCard(
                    "Archived", Modifier.padding(horizontal = 8.dp, vertical = 2.dp).animateItem(),
                    subtitle = "${archivedInfo.chats} chat${if (archivedInfo.chats == 1) "" else "s"}" +
                        if (archivedInfo.unreadChats > 0) " · ${archivedInfo.unreadChats} unread" else "",
                    leading = { com.griipbuddy.orbit.ui.components.IconTile(Icons.Outlined.Archive, c.lavender, c.lavenderSoft, size = 44.dp) },
                    trailing = { ArrowKnob() },
                    onClick = { archivedView = true },
                )
            }
            if (refreshing && convs.isEmpty()) item { ShimmerList(6, Modifier.padding(top = 8.dp)) }
            else if (shown.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.ChatBubbleOutline,
                        when {
                            convs.isEmpty() -> "No conversations yet"
                            query.isNotBlank() -> "No chats named \"${query.trim()}\""
                            archivedView -> "No archived chats"
                            else -> "Nothing here"
                        },
                        when {
                            convs.isEmpty() -> "Message a colleague, or join your team's channels from the Workspace tab."
                            query.isNotBlank() -> "Search also finds people and message text."
                            archivedView -> "Swipe a chat left to archive it. You stay in it and can restore it any time."
                            else -> "Try another filter."
                        },
                        action = when {
                            archivedView && query.isBlank() -> null
                            convs.isEmpty() -> "New message"
                            query.isNotBlank() -> "Search everywhere"
                            else -> null
                        },
                        onAction = { if (convs.isEmpty()) nav.navigate(Routes.NEW_CHAT + "?mode=dm") else nav.navigate(Routes.SEARCH) },
                    )
                }
            }
            items(shown, key = { it.id }) { conv ->
                val peer = ConversationRepository.peer(conv, me)
                val names = typing[conv.id]?.keys?.mapNotNull { id -> users.firstOrNull { it.id == id }?.displayName } ?: emptyList()
                SwipeableChat(
                    archived = ChatListRules.isArchived(conv), unread = conv.unreadCount > 0, muted = conv.muted,
                    onToggleRead = { toggleRead(conv) }, onToggleArchive = { toggleArchive(conv) }, onToggleMute = { toggleMute(conv) },
                    modifier = Modifier.animateItem(),
                ) {
                    ConversationRow(
                        conv, me, names, peer?.let { online[it.id] },
                        onClick = { nav.navigate(Routes.conversation(conv.id)) },
                        onLongClick = { actionsFor = conv },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.navigate(Routes.NEW_CHAT + "?mode=dm") },
            icon = { Icon(Icons.Outlined.EditNote, null) },
            text = { Text("New chat", style = Griip.type.button) },
            containerColor = c.actionStart, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.gutter),
        )
        undo?.let { conv ->
            val title = ConversationRepository.title(conv, me).let { if (conv.type == "CHANNEL") "#$it" else it }
            com.griipbuddy.orbit.ui.components.UndoBar(
                "Archived $title", onUndo = {
                    scope.launch { container.conversations.setArchived(conv.id, false) }
                    undo = null
                },
                Modifier.align(Alignment.BottomCenter).padding(start = Space.gutter, end = Space.gutter, bottom = 84.dp),
            )
        }
    }

    actionsFor?.let { conv ->
        val title = ConversationRepository.title(conv, me).let { if (conv.type == "CHANNEL") "#$it" else it }
        GlassSheet({ actionsFor = null }, title = title) {
            MenuRow(
                if (conv.muted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                if (conv.muted) "Unmute notifications" else "Mute notifications",
                subtitle = if (conv.muted) null else "You'll still get notified when someone @mentions you",
                onClick = { scope.launch { container.conversations.setMuted(conv.id, !conv.muted) }; actionsFor = null }, trailing = null,
            )
            if (conv.unreadCount > 0) MenuRow(
                Icons.Outlined.MarkChatRead, "Mark as read", subtitle = "Only on this device; no read receipt is sent",
                onClick = { toggleRead(conv); actionsFor = null }, trailing = null,
            ) else MenuRow(
                Icons.Outlined.MarkChatUnread, "Mark as unread", subtitle = "Only on this device; nothing is sent",
                onClick = { toggleRead(conv); actionsFor = null }, trailing = null,
            )
            val archived = ChatListRules.isArchived(conv)
            MenuRow(
                if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (archived) "Unarchive" else "Archive",
                subtitle = if (archived) "Back to your chat list" else "Only on this phone; you stay in the chat and can restore it",
                onClick = { toggleArchive(conv); actionsFor = null }, trailing = null,
            )
            MenuRow(
                Icons.Outlined.Info, if (conv.type == "DM") "Contact info" else if (conv.type == "GROUP") "Group info" else "Channel info",
                onClick = { actionsFor = null; nav.navigate(Routes.info(conv.id)) }, trailing = null,
            )
        }
    }
}

/** Pick people → open a DM, create a group (mode=group) or start a call (mode=call). */
@Composable
fun NewChatScreen(nav: NavHostController, mode: String?) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var colleagues by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    val picked = remember { mutableStateListOf<UserDto>() }
    var groupName by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Someone on another server, found by their full address (exact lookup only).
    var remote by remember { mutableStateOf<ResolveRes?>(null) }
    var remoteSheet by remember { mutableStateOf<ResolveRes?>(null) }
    var resolving by remember { mutableStateOf(false) }
    val group = mode == "group"
    val call = mode == "call"
    val me = container.session.session?.userId
    val permission = com.griipbuddy.orbit.ui.screens.calls.rememberCallPermission()

    LaunchedEffect(Unit) {
        val org = container.orgs.selectedOrg.value ?: runCatching { container.orgs.orgs().firstOrNull()?.id }.getOrNull()
        if (org != null) colleagues = runCatching { container.orgs.members(org) }.getOrDefault(emptyList()).filter { it.id != me }
    }
    LaunchedEffect(q) {
        remote = null
        error = null
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        if (GlobalAddress.looksLike(q) && !group) {
            results = emptyList()
            delay(500)
            resolving = true
            remote = runCatching { container.federation.resolve(q) }.onFailure { error = it.friendly() }.getOrNull()
            resolving = false
            return@LaunchedEffect
        }
        delay(250)
        results = runCatching { container.users.search(q) }.onFailure { error = it.friendly() }.getOrDefault(emptyList())
    }
    val list = if (q.length >= 2) results else colleagues

    fun openNow(u: UserDto) {
        scope.launch {
            runCatching { container.conversations.openDm(u.id) }
                .onSuccess { conv ->
                    nav.popBackStack()
                    if (call) container.calls.startCall(conv.id, video = false) else nav.navigate(Routes.conversation(conv.id))
                }
                .onFailure { error = it.friendly() }
        }
    }

    fun open(u: UserDto) {
        if (call) { permission(false) { openNow(u) }; return }
        openNow(u)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar(if (group) "New group" else if (call) "Start a call" else "New message", onBack = { nav.popBackStack() })
        if (group) {
            GriipTextField(groupName, { groupName = it.take(60) }, "Group name", Modifier.padding(horizontal = Space.gutter), placeholder = "Launch crew", leadingIcon = Icons.Rounded.Groups,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Spacer(Modifier.height(12.dp))
        }
        SearchPill(q, { q = it }, if (group) "Search by name or @username" else "Name, @username or @name:server", Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
        if (!group && !call) {
            ListCard(
                "New group", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), subtitle = "Pick people to add",
                leading = { com.griipbuddy.orbit.ui.components.IconTile(Icons.Rounded.Groups, c.lavender, c.lavenderSoft, size = 44.dp) },
                onClick = { nav.navigate(Routes.NEW_CHAT + "?mode=group") { popUpTo(Routes.NEW_CHAT + "?mode={mode}") { inclusive = true } } },
            )
        }
        if (group && picked.isNotEmpty()) {
            ChipRow { picked.forEach { u -> PillChip(u.displayName, true, { picked.remove(u) }) } }
        }
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { SectionHeader(if (q.length >= 2) "Results" else "Colleagues", Modifier.padding(vertical = 6.dp)) }
            if (resolving) item { Text("Looking up that address…", style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
            remote?.let { r ->
                item(key = "remote") {
                    ListCard(
                        r.user.displayName, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        subtitle = (r.user.address ?: "@${r.user.username}") + if (r.server.source == "local") "" else " · on ${r.server.domain}",
                        leading = { Avatar(r.user.displayName, r.user.avatarColor, size = 44.dp) },
                        trailing = { if (r.server.source != "local") TagPill("Other server", c.lavender, c.lavenderSoft) else ArrowKnob() },
                        onClick = { remoteSheet = r },
                    )
                }
            }
            if (list.isEmpty() && remote == null && !resolving) item {
                EmptyState(
                    Icons.Outlined.Search, if (q.length >= 2) "No one found" else "Search for people",
                    if (group) "You can find colleagues in your organization, or anyone on this server by exact @username."
                    else "Find colleagues in your organization, anyone on this server by exact @username, or someone on another Griip server by their full address, like @name:server.example.",
                )
            }
            items(list, key = { it.id }) { u ->
                val sel = picked.any { it.id == u.id }
                ListCard(
                    u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    subtitle = "@${u.username}" + (u.title?.let { " · $it" } ?: ""),
                    leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
                    trailing = {
                        when {
                            group && sel -> TagPill("Added", c.primaryDeep, c.primarySoft, icon = Icons.Rounded.Check)
                            group -> TagPill("Add", c.textSecondary, c.surfaceVariant)
                            call -> Icon(Icons.Rounded.Call, "Call", tint = c.primary)
                            else -> ArrowKnob()
                        }
                    },
                    selected = sel,
                    onClick = { if (group) { if (sel) picked.removeAll { it.id == u.id } else picked.add(u) } else open(u) },
                )
            }
        }
        remoteSheet?.let { r ->
            RemoteContactSheet(r, call, container.session.keys?.identityPublic, onDismiss = { remoteSheet = null }) {
                remoteSheet = null
                open(r.user)
            }
        }
        if (group) {
            PillButton(
                "Create group (${picked.size})",
                {
                    busy = true
                    scope.launch {
                        runCatching { container.conversations.createGroup(groupName.trim(), picked.map { it.id }) }
                            .onSuccess { nav.popBackStack(); nav.navigate(Routes.conversation(it.id)) }
                            .onFailure { error = it.friendly() }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp).padding(bottom = 8.dp),
                enabled = groupName.isNotBlank() && picked.isNotEmpty(), loading = busy,
            )
        }
    }
}

/**
 * Someone found by global address: who they are, which server vouches for them and how that server was verified,
 * and their safety number, before the first message.
 */
@Composable
private fun RemoteContactSheet(r: ResolveRes, call: Boolean, myKey: ByteArray?, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val c = Griip.colors
    val remote = r.server.source != "local"
    GlassSheet(onDismiss, title = r.user.displayName) {
        Text(r.user.address ?: "@${r.user.username}", style = Griip.type.bodyMedium, color = c.textPrimary)
        Spacer(Modifier.height(10.dp))
        if (remote) {
            Text(
                when {
                    r.server.source == "cache" -> "Server ${r.server.domain}: identity checked earlier (the directory couldn't be reached just now)."
                    else -> "Server ${r.server.domain}: identity verified by ${r.server.verifiedBy} directory nodes."
                },
                style = Griip.type.bodySmall, color = c.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text("Server id ${r.server.serverId.take(15)}…", style = Griip.type.caption, color = c.textTertiary)
            Spacer(Modifier.height(10.dp))
        }
        val theirs = r.user.identityKey?.let { runCatching { it.unb64() }.getOrNull() }
        if (theirs != null && myKey != null) {
            Text("Safety number", style = Griip.type.label, color = c.textSecondary)
            Text(com.griipbuddy.orbit.ui.screens.profile.safetyNumber(myKey, theirs), style = Griip.type.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = c.textPrimary)
            Text("Compare it with ${r.user.displayName} in person or on a call you trust.", style = Griip.type.caption, color = c.textTertiary)
            Spacer(Modifier.height(10.dp))
        }
        if (r.server.identityChanged) {
            StatusBanner("This address now points to a different server than before. Check with the person before you trust it.", BannerKind.Warning)
            Spacer(Modifier.height(8.dp))
        }
        if (r.server.state == "unreachable") {
            StatusBanner("${r.server.domain} isn't reachable right now. Messages wait for up to 7 days.", BannerKind.Info)
            Spacer(Modifier.height(8.dp))
        }
        if (call && remote) {
            Text("The call is hosted by your server; ${r.user.displayName} joins through theirs. Media stays end-to-end encrypted.", style = Griip.type.caption, color = c.textTertiary)
            Spacer(Modifier.height(8.dp))
        }
        PillButton(if (call) "Call" else "Message", onMessage, Modifier.fillMaxWidth(), height = 50.dp)
    }
}

@Suppress("unused")
private val unusedIcon = Icons.Rounded.Videocam
@Suppress("unused")
private val unusedW = Modifier.width(1.dp)
