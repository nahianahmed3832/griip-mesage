package com.griipbuddy.message.ui.screens.home

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.griipbuddy.message.data.api.ConnState
import com.griipbuddy.message.data.api.OrgDetailRes
import com.griipbuddy.message.data.api.OrgDto
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.di.AppContainer
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.CircleIconButton
import com.griipbuddy.message.ui.components.GlassCard
import com.griipbuddy.message.ui.components.HeroCard
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.RingProgress
import com.griipbuddy.message.ui.components.SearchPill
import com.griipbuddy.message.ui.components.SectionHeader
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.containerViewModel
import com.griipbuddy.message.ui.screens.chats.ConversationRow
import com.griipbuddy.message.ui.switchTab
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(val c: AppContainer) : ViewModel() {
    val conversations = c.conversations.observeAll()
    val users = c.db.users().observeAll()
    val connection = c.realtime.state
    val call = c.calls.state
    val typing = c.messages.typing
    val orgs = MutableStateFlow<List<OrgDto>?>(null)
    val detail = MutableStateFlow<OrgDetailRes?>(null)

    fun refresh() = viewModelScope.launch {
        c.orgs.cachedOrgs()?.let { orgs.value = it }
        runCatching { c.conversations.refresh() }
        runCatching { c.orgs.orgs() }.onSuccess { list ->
            orgs.value = list
            val sel = c.orgs.selectedOrg.value?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
            if (sel != null) {
                if (sel != c.orgs.selectedOrg.value) c.orgs.select(sel)
                c.orgs.cachedDetail(sel)?.let { detail.value = it }
                runCatching { c.orgs.detail(sel) }.onSuccess { detail.value = it }
            }
        }
    }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm = containerViewModel { HomeViewModel(it) }
    val c = Griip.colors
    val session = vm.c.session.session
    val convs by vm.conversations.collectAsStateWithLifecycle(emptyList())
    val users by vm.users.collectAsStateWithLifecycle(emptyList())
    val conn by vm.connection.collectAsStateWithLifecycle()
    val orgs by vm.orgs.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val permission = com.griipbuddy.message.ui.screens.calls.rememberCallPermission()
    LaunchedEffect(Unit) { vm.refresh() }

    val me = users.firstOrNull { it.id == session?.userId }
    val onlineById = users.associate { it.id to it.online }
    val unreadConvs = convs.count { it.unreadCount > 0 }
    val mentions = convs.sumOf { it.mentionCount }
    val readShare = if (convs.isEmpty()) 1f else 1f - unreadConvs.toFloat() / convs.size
    val liveCall = convs.firstOrNull { it.activeCallId != null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${TimeFmt.greeting()},", style = Griip.type.bodySmall, color = c.textSecondary)
                    Text("${(me?.displayName ?: session?.displayName ?: "").substringBefore(' ').ifEmpty { "there" }} 👋", style = Griip.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CircleIconButton(Icons.Rounded.Notifications, if (mentions > 0) "$mentions mentions" else "Notifications", { nav.switchTab(Routes.CHATS) }, badge = mentions > 0)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.pressable({ nav.switchTab(Routes.PROFILE) })) {
                    Avatar(me?.displayName ?: session?.displayName ?: "?", me?.avatarColor ?: 0, size = 44.dp, online = conn == ConnState.Connected)
                }
            }
        }
        item { ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) { vm.c.realtime.reconnectNow() } }
        item {
            Box(Modifier.padding(horizontal = Space.gutter, vertical = 10.dp).pressable({ nav.navigate(Routes.SEARCH) })) {
                SearchPill("", {}, "Search people, chats, messages…", Modifier.fillMaxWidth())
                Box(Modifier.matchParentSize().pressable({ nav.navigate(Routes.SEARCH) }))
            }
        }
        item {
            val org = detail
            HeroCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                Column {
                    when {
                        liveCall != null -> {
                            Text("Call in progress", style = Griip.type.label, color = Color.White.copy(alpha = 0.85f))
                            Text(ConversationRepository.title(liveCall, session?.userId).let { if (liveCall.type == "CHANNEL") "#$it" else it }, style = Griip.type.headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (liveCall.activeCallVideo) "Video call · tap to join" else "Voice call · tap to join", style = Griip.type.bodySmall, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(14.dp))
                            HeroAction(if (liveCall.activeCallVideo) Icons.Rounded.Videocam else Icons.Rounded.Call, "Join call") {
                                permission(liveCall.activeCallVideo) { scope.launch { vm.c.calls.join(liveCall.id, liveCall.activeCallId!!, liveCall.activeCallVideo) } }
                            }
                        }
                        org != null -> {
                            Text("Your workspace", style = Griip.type.label, color = Color.White.copy(alpha = 0.85f))
                            Text(org.org.name, style = Griip.type.headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val branch = org.branches.firstOrNull { it.myRole != null }?.name
                            Text(listOfNotNull(branch, "${org.stats.members} members", "${org.stats.channels} channels").joinToString(" · "), style = Griip.type.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                            Spacer(Modifier.height(14.dp))
                            HeroAction(Icons.Rounded.Inbox, if (unreadConvs > 0) "$unreadConvs unread chats" else "Open workspace") {
                                if (unreadConvs > 0) nav.switchTab(Routes.CHATS) else nav.switchTab(Routes.WORKSPACE)
                            }
                        }
                        else -> {
                            Text("Get set up", style = Griip.type.label, color = Color.White.copy(alpha = 0.85f))
                            Text(if (orgs == null) "Loading workspace…" else "Join your workspace", style = Griip.type.headline, color = Color.White)
                            Text("Use an invite code from your team, or create a new organization.", style = Griip.type.bodySmall, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(14.dp))
                            HeroAction(Icons.Outlined.GroupAdd, "Enter invite") { nav.navigate(Routes.JOIN + "?code=") }
                        }
                    }
                }
            }
        }
        item { SectionHeader("Quick actions", Modifier.padding(top = 18.dp, bottom = 10.dp)) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Outlined.EditNote, "New\nmessage", c.primary, c.primarySoft, Modifier.weight(1f)) { nav.navigate(Routes.NEW_CHAT + "?mode=dm") }
                QuickTile(Icons.Outlined.PersonSearch, "Find\npeople", c.lavender, c.lavenderSoft, Modifier.weight(1f)) { nav.navigate(Routes.SEARCH) }
                QuickTile(Icons.Outlined.Call, "Start\ncall", c.success, c.successSoft, Modifier.weight(1f)) { nav.navigate(Routes.NEW_CHAT + "?mode=call") }
                if (detail?.permissions?.canInvite == true) {
                    QuickTile(Icons.Outlined.GroupAdd, "Invite\npeople", c.peach, c.peachSoft, Modifier.weight(1f)) { detail?.org?.id?.let { nav.navigate(Routes.invites(it)) } }
                } else {
                    QuickTile(Icons.Outlined.Campaign, "Join\nworkspace", c.peach, c.peachSoft, Modifier.weight(1f)) { nav.navigate(Routes.JOIN + "?code=") }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 16.dp), contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Inbox health", style = Griip.type.section, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(if (conn == ConnState.Connected) "Live" else "Offline", style = Griip.type.caption, color = if (conn == ConnState.Connected) c.success else c.textTertiary)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingProgress(readShare, label = "${(readShare * 100).toInt()}%")
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                convs.isEmpty() -> "Nothing here yet"
                                unreadConvs == 0 -> "All caught up ✨"
                                mentions > 0 -> "$mentions mention${if (mentions > 1) "s" else ""} waiting"
                                else -> "$unreadConvs chat${if (unreadConvs > 1) "s" else ""} to catch up on"
                            },
                            style = Griip.type.titleSmall, color = c.textPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text("Messages are end-to-end encrypted and stored only on your devices.", style = Griip.type.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }
        item { SectionHeader("Recent conversations", Modifier.padding(bottom = 10.dp), action = "See all", onAction = { nav.switchTab(Routes.CHATS) }) }
        if (convs.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                    Text("No conversations yet", style = Griip.type.titleSmall, color = c.textPrimary)
                    Text("Start a direct message or join your team's channels from the Workspace tab.", style = Griip.type.bodySmall, color = c.textSecondary)
                }
            }
        }
        items(convs.take(5), key = { it.id }) { conv ->
            val peer = ConversationRepository.peer(conv, session?.userId)
            val typingNames = typing[conv.id]?.keys?.mapNotNull { id -> users.firstOrNull { it.id == id }?.displayName } ?: emptyList()
            ConversationRow(conv, session?.userId, typingNames, peer?.let { onlineById[it.id] }, { nav.navigate(Routes.conversation(conv.id)) }, Modifier.padding(horizontal = Space.gutter, vertical = 5.dp))
        }
    }
}

@Composable
private fun HeroAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(42.dp).clip(Shapes.pill).background(Color.White.copy(alpha = 0.22f)).pressable(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
        Text(label, style = Griip.type.button, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, tint: Color, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Griip.colors
    GlassCard(modifier, shape = Shapes.tile, onClick = onClick, contentPadding = PaddingValues(vertical = 14.dp, horizontal = 6.dp), elevation = 6.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon, tint, bg, size = 42.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = Griip.type.caption, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
fun ConnectionBanner(state: ConnState, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    when (state) {
        ConnState.Offline -> StatusBanner("You're offline — messages will send when you reconnect.", BannerKind.Warning, modifier, icon = Icons.Outlined.CloudOff)
        ConnState.Connecting -> StatusBanner("Connecting to the relay…", BannerKind.Info, modifier, icon = Icons.Outlined.Sync, action = "Retry", onAction = onRetry)
        else -> {}
    }
}

