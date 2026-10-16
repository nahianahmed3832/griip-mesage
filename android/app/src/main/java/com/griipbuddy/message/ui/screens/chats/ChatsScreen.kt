package com.griipbuddy.message.ui.screens.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.call.CallPhase
import com.griipbuddy.message.ui.components.GlassSheet
import com.griipbuddy.message.ui.components.MenuRow
import com.griipbuddy.message.ui.components.ShimmerList
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
import com.griipbuddy.message.data.api.ResolveRes
import com.griipbuddy.message.data.api.UserDto
import com.griipbuddy.message.data.repo.GlobalAddress
import com.griipbuddy.message.crypto.unb64
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.components.ArrowKnob
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.ChipRow
import com.griipbuddy.message.ui.components.CircleIconButton
import com.griipbuddy.message.ui.components.EmptyState
import com.griipbuddy.message.ui.components.GriipTextField
import com.griipbuddy.message.ui.components.GriipTopBar
import com.griipbuddy.message.ui.components.LargeHeader
import com.griipbuddy.message.ui.components.ListCard
import com.griipbuddy.message.ui.components.PillButton
import com.griipbuddy.message.ui.components.PillChip
import com.griipbuddy.message.ui.components.SearchPill
import com.griipbuddy.message.ui.components.SectionHeader
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.components.TagPill
import com.griipbuddy.message.ui.friendly
import com.griipbuddy.message.ui.screens.home.ConnectionBanner
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Space
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
    var refreshing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val permission = com.griipbuddy.message.ui.screens.calls.rememberCallPermission()
    LaunchedEffect(Unit) { runCatching { container.conversations.refresh() }; refreshing = false }

    val filters = listOf("All", "Unread", "Direct", "Groups", "Channels")
    val shown = convs.filter {
        when (filter) {
            1 -> it.unreadCount > 0
            2 -> it.type == "DM"
            3 -> it.type == "GROUP"
            4 -> it.type == "CHANNEL"
            else -> true
        }
    }.filter { query.isBlank() || ConversationRepository.title(it, me).contains(query, ignoreCase = true) }
    val online = users.associate { it.id to it.online }
    val liveCall = if (call.phase == CallPhase.Idle) convs.firstOrNull { it.activeCallId != null } else null

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                LargeHeader("Chats") {
                    CircleIconButton(Icons.Rounded.Search, "Search people, chats and messages", { nav.navigate(Routes.SEARCH) })
                    CircleIconButton(Icons.Outlined.GroupAdd, "New group", { nav.navigate(Routes.NEW_CHAT + "?mode=group") })
                }
            }
            item { ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { container.realtime.reconnectNow() } }
            if (liveCall != null) item {
                val title = ConversationRepository.title(liveCall, me).let { if (liveCall.type == "CHANNEL") "#$it" else it }
                StatusBanner(
                    "${if (liveCall.activeCallVideo) "Video" else "Voice"} call in progress · $title", BannerKind.Success,
                    Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), icon = Icons.Rounded.Call, action = "Join",
                    onAction = { permission(liveCall.activeCallVideo) { scope.launch { container.calls.join(liveCall.id, liveCall.activeCallId!!, liveCall.activeCallVideo) } } },
                )
            }
            item { SearchPill(query, { query = it }, "Filter chats by name", Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
            item {
                ChipRow {
                    filters.forEachIndexed { i, f ->
                        PillChip(f, filter == i, { filter = i }, count = if (i == 1) convs.count { it.unreadCount > 0 } else null)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (refreshing && convs.isEmpty()) item { ShimmerList(6, Modifier.padding(top = 8.dp)) }
            else if (shown.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.ChatBubbleOutline,
                        when {
                            convs.isEmpty() -> "No conversations yet"
                            query.isNotBlank() -> "No chats named \"${query.trim()}\""
                            else -> "Nothing here"
                        },
                        when {
                            convs.isEmpty() -> "Message a colleague, or join your team's channels from the Workspace tab."
                            query.isNotBlank() -> "Search also finds people and message text."
                            else -> "Try another filter."
                        },
                        action = when {
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
                ConversationRow(
                    conv, me, names, peer?.let { online[it.id] },
                    onClick = { nav.navigate(Routes.conversation(conv.id)) },
                    onLongClick = { actionsFor = conv },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.navigate(Routes.NEW_CHAT + "?mode=dm") },
            icon = { Icon(Icons.Outlined.EditNote, null) },
            text = { Text("New chat", style = Griip.type.button) },
            containerColor = c.actionStart, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.gutter),
        )
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
                onClick = { scope.launch { container.conversations.markRead(conv.id) }; container.notifier.clear(conv.id); actionsFor = null }, trailing = null,
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
    val permission = com.griipbuddy.message.ui.screens.calls.rememberCallPermission()

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
                leading = { com.griipbuddy.message.ui.components.IconTile(Icons.Rounded.Groups, c.lavender, c.lavenderSoft, size = 44.dp) },
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
            Text(com.griipbuddy.message.ui.screens.profile.safetyNumber(myKey, theirs), style = Griip.type.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = c.textPrimary)
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
