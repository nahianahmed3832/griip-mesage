package com.griipbuddy.message.ui.screens.chats

import androidx.compose.animation.animateContentSize
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
import com.griipbuddy.message.data.api.UserDto
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
    val me = container.session.session?.userId
    val convs by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    val users by container.db.users().observeAll().collectAsStateWithLifecycle(emptyList())
    val typing by container.messages.typing.collectAsStateWithLifecycle()
    val conn by container.realtime.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { container.conversations.refresh() } }

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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            LargeHeader("Chats", subtitle = "Messages & channels") {
                CircleIconButton(Icons.Outlined.GroupAdd, "New group", { nav.navigate(Routes.NEW_CHAT + "?mode=group") })
                CircleIconButton(Icons.Outlined.EditNote, "New message", { nav.navigate(Routes.NEW_CHAT + "?mode=dm") })
            }
        }
        item { ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { container.realtime.reconnectNow() } }
        item { SearchPill(query, { query = it }, "Search conversations", Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
        item {
            ChipRow {
                filters.forEachIndexed { i, f ->
                    PillChip(f, filter == i, { filter = i }, count = if (i == 1) convs.count { it.unreadCount > 0 } else null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.ChatBubbleOutline,
                    if (convs.isEmpty()) "No conversations yet" else "Nothing here",
                    if (convs.isEmpty()) "Message a colleague or join your team's channels from the Workspace tab." else "Try another filter.",
                    action = if (convs.isEmpty()) "New message" else null,
                    onAction = { nav.navigate(Routes.NEW_CHAT + "?mode=dm") },
                )
            }
        }
        items(shown, key = { it.id }) { conv ->
            val peer = ConversationRepository.peer(conv, me)
            val names = typing[conv.id]?.keys?.mapNotNull { id -> users.firstOrNull { it.id == id }?.displayName } ?: emptyList()
            ConversationRow(conv, me, names, peer?.let { online[it.id] }, { nav.navigate(Routes.conversation(conv.id)) }, Modifier.padding(horizontal = Space.gutter, vertical = 5.dp).animateContentSize())
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
    val group = mode == "group"
    val call = mode == "call"
    val me = container.session.session?.userId
    val permission = com.griipbuddy.message.ui.screens.calls.rememberCallPermission()

    LaunchedEffect(Unit) {
        val org = container.orgs.selectedOrg.value ?: runCatching { container.orgs.orgs().firstOrNull()?.id }.getOrNull()
        if (org != null) colleagues = runCatching { container.orgs.members(org) }.getOrDefault(emptyList()).filter { it.id != me }
    }
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
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
        SearchPill(q, { q = it }, "Search by name or @username", Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
        if (group && picked.isNotEmpty()) {
            ChipRow { picked.forEach { u -> PillChip(u.displayName, true, { picked.remove(u) }) } }
        }
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { SectionHeader(if (q.length >= 2) "Results" else "Colleagues", Modifier.padding(vertical = 6.dp)) }
            if (list.isEmpty()) item {
                EmptyState(Icons.Outlined.Search, if (q.length >= 2) "No one found" else "Search for people", "You can find colleagues in your organization, or anyone by exact @username.")
            }
            items(list, key = { it.id }) { u ->
                val sel = picked.any { it.id == u.id }
                ListCard(
                    u.displayName, Modifier.padding(horizontal = Space.gutter, vertical = 5.dp),
                    subtitle = "@${u.username}" + (u.title?.let { " · $it" } ?: ""),
                    leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
                    trailing = {
                        when {
                            group && sel -> TagPill("Added", c.primaryDeep, c.primarySoft, icon = Icons.Rounded.Check)
                            group -> TagPill("Add", c.textSecondary, c.hairline)
                            call -> Icon(Icons.Rounded.Call, "Call", tint = c.primary)
                            else -> ArrowKnob()
                        }
                    },
                    selected = sel,
                    onClick = { if (group) { if (sel) picked.removeAll { it.id == u.id } else picked.add(u) } else open(u) },
                )
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

@Suppress("unused")
private val unusedIcon = Icons.Rounded.Videocam
@Suppress("unused")
private val unusedW = Modifier.width(1.dp)
