package com.griipbuddy.orbit.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.StatusBanner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.orbit.data.api.UserDto
import com.griipbuddy.orbit.data.db.MessageEntity
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.Routes
import com.griipbuddy.orbit.ui.TimeFmt
import com.griipbuddy.orbit.ui.components.Avatar
import com.griipbuddy.orbit.ui.components.ArrowKnob
import com.griipbuddy.orbit.ui.components.EmptyState
import com.griipbuddy.orbit.ui.components.GriipTopBar
import com.griipbuddy.orbit.ui.components.ListCard
import com.griipbuddy.orbit.ui.components.SearchPill
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.screens.chats.ConversationAvatar
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Global search: conversations (local), people (directory), message text (local history only). */
@Composable
fun SearchScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val me = container.session.session?.userId
    var q by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var peopleFailed by remember { mutableStateOf(false) }
    val convs by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    // Workspaces you belong to (the cached list) and channels you could join in the selected one (the server's list,
    // which includes only channels you're allowed to see: private ones you're not in never appear).
    val selectedOrg by container.orgs.selectedOrg.collectAsStateWithLifecycle()
    var orgs by remember { mutableStateOf<List<com.griipbuddy.orbit.data.api.OrgDto>>(emptyList()) }
    var channels by remember { mutableStateOf<List<com.griipbuddy.orbit.data.api.ConversationDto>>(emptyList()) }
    LaunchedEffect(Unit) { orgs = container.orgs.cachedOrgs().orEmpty() }
    LaunchedEffect(q) {
        if (q.isBlank()) { people = emptyList(); messages = emptyList(); channels = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(250)
        channels = selectedOrg?.let { id -> runCatching { container.orgs.channels(id, q.trim()) }.getOrDefault(emptyList()).filter { !it.joined && !it.archived } }.orEmpty()
        messages = container.messages.search(q.trim())
        val found = runCatching { container.users.search(q.trim()) }
        peopleFailed = found.isFailure
        people = found.getOrDefault(emptyList())
        searching = false
    }
    val convMatches = if (q.isBlank()) emptyList() else convs.filter { ConversationRepository.title(it, me).contains(q.trim(), ignoreCase = true) }
    val orgMatches = if (q.isBlank()) emptyList() else orgs.filter { it.name.contains(q.trim(), ignoreCase = true) }
    val selectedName = orgs.firstOrNull { it.id == selectedOrg }?.name.orEmpty()

    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar("Search", onBack = { nav.popBackStack() })
        SearchPill(q, { q = it }, "People, chats or message text", Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
        Text(
            "People in your organizations, or anyone by exact @username. Message text is searched in this phone's history only.",
            style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp),
        )
        if (searching) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), color = c.primary, trackColor = c.surfaceVariant)
        if (peopleFailed && !searching) StatusBanner("Couldn't search people right now. Chats and messages on this phone are still searched.", BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)) {
            if (q.isNotBlank() && !searching && convMatches.isEmpty() && people.isEmpty() && messages.isEmpty() && channels.isEmpty() && orgMatches.isEmpty()) {
                item { EmptyState(Icons.Outlined.SearchOff, "No results", "Message search covers the history stored on this device.") }
            }
            if (convMatches.isNotEmpty()) item { SectionHeader("Conversations", Modifier.padding(vertical = 8.dp)) }
            items(convMatches.take(8), key = { "c" + it.id }) { conv ->
                ListCard(
                    ConversationRepository.title(conv, me).let { if (conv.type == "CHANNEL") "#$it" else it },
                    Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = conv.scopeName ?: conv.topic,
                    leading = { ConversationAvatar(conv, me, size = 44.dp) },
                    trailing = { ArrowKnob() },
                    onClick = { nav.navigate(Routes.conversation(conv.id)) },
                )
            }
            if (channels.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_channels, selectedName), Modifier.padding(vertical = 8.dp)) }
            items(channels.take(8), key = { "ch" + it.id }) { ch ->
                ListCard(
                    "#${ch.name}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = listOfNotNull(ch.topic, ch.scopeName).joinToString(" · ").ifBlank { null },
                    leading = {
                        com.griipbuddy.orbit.ui.components.IconTile(com.griipbuddy.orbit.ui.screens.chats.channelIcon(ch.kind), c.textSecondary, c.surfaceVariant, size = 44.dp)
                    },
                    trailing = {
                        com.griipbuddy.orbit.ui.components.PillButton(stringResource(R.string.search_join), {
                            scope.launch { runCatching { container.conversations.join(ch.id) }.onSuccess { nav.navigate(Routes.conversation(ch.id)) } }
                        }, tone = com.griipbuddy.orbit.ui.components.ButtonTone.Glass, height = 36.dp)
                    },
                )
            }
            if (orgMatches.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_workspaces), Modifier.padding(vertical = 8.dp)) }
            items(orgMatches, key = { "o" + it.id }) { o ->
                val current = o.id == selectedOrg
                ListCard(
                    o.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = o.description ?: "@${o.slug}",
                    leading = { com.griipbuddy.orbit.ui.components.OrgMark(o.id, o.name, com.griipbuddy.orbit.ui.screens.workspace.WorkspaceList.colorSeed(o), o.photoRev, size = 44.dp) },
                    trailing = {
                        if (current) com.griipbuddy.orbit.ui.components.TagPill(stringResource(R.string.search_current), c.primaryDeep, c.primarySoft)
                        else Text(stringResource(R.string.search_switch), style = Griip.type.label, color = c.primaryDeep)
                    },
                    onClick = if (current) null else ({ scope.launch { container.orgs.select(o.id); nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false } } } }),
                )
            }
            if (people.isNotEmpty()) item { SectionHeader("People", Modifier.padding(vertical = 8.dp)) }
            items(people, key = { "p" + it.id }) { u ->
                ListCard(
                    u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), subtitle = "@${u.username}",
                    leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
                    trailing = { ArrowKnob(contentDescription = "Message ${u.displayName}") },
                    onClick = { scope.launch { runCatching { container.conversations.openDm(u.id) }.onSuccess { nav.navigate(Routes.conversation(it.id)) } } },
                )
            }
            if (messages.isNotEmpty()) item { SectionHeader("Messages", Modifier.padding(vertical = 8.dp)) }
            items(messages, key = { "m" + it.id }) { m ->
                val conv = convs.firstOrNull { it.id == m.conversationId }
                ListCard(
                    conv?.let { ConversationRepository.title(it, me) } ?: "Conversation",
                    Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = m.text,
                    leading = conv?.let { cv -> { ConversationAvatar(cv, me, size = 44.dp) } },
                    trailing = { Text(TimeFmt.listStamp(m.sortTs), style = Griip.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { nav.navigate(Routes.conversation(m.conversationId)) },
                )
            }
            item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        }
    }
}
