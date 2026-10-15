package com.griipbuddy.message.ui.screens.home

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
import com.griipbuddy.message.data.api.UserDto
import com.griipbuddy.message.data.db.MessageEntity
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.ArrowKnob
import com.griipbuddy.message.ui.components.EmptyState
import com.griipbuddy.message.ui.components.GriipTopBar
import com.griipbuddy.message.ui.components.ListCard
import com.griipbuddy.message.ui.components.SearchPill
import com.griipbuddy.message.ui.components.SectionHeader
import com.griipbuddy.message.ui.screens.chats.ConversationAvatar
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Space
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
    val convs by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(q) {
        if (q.isBlank()) { people = emptyList(); messages = emptyList(); return@LaunchedEffect }
        delay(250)
        messages = container.messages.search(q.trim())
        people = runCatching { container.users.search(q.trim()) }.getOrDefault(emptyList())
    }
    val convMatches = if (q.isBlank()) emptyList() else convs.filter { ConversationRepository.title(it, me).contains(q.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar("Search", onBack = { nav.popBackStack() })
        SearchPill(q, { q = it }, "People, chats or message text", Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)) {
            if (q.isNotBlank() && convMatches.isEmpty() && people.isEmpty() && messages.isEmpty()) {
                item { EmptyState(Icons.Outlined.SearchOff, "No results", "Message search covers the history stored on this device.") }
            }
            if (convMatches.isNotEmpty()) item { SectionHeader("Conversations", Modifier.padding(vertical = 8.dp)) }
            items(convMatches.take(8), key = { "c" + it.id }) { conv ->
                ListCard(
                    ConversationRepository.title(conv, me).let { if (conv.type == "CHANNEL") "#$it" else it },
                    Modifier.padding(horizontal = Space.gutter, vertical = 5.dp),
                    subtitle = conv.scopeName ?: conv.topic,
                    leading = { ConversationAvatar(conv, me, size = 44.dp) },
                    trailing = { ArrowKnob() },
                    onClick = { nav.navigate(Routes.conversation(conv.id)) },
                )
            }
            if (people.isNotEmpty()) item { SectionHeader("People", Modifier.padding(vertical = 8.dp)) }
            items(people, key = { "p" + it.id }) { u ->
                ListCard(
                    u.displayName, Modifier.padding(horizontal = Space.gutter, vertical = 5.dp), subtitle = "@${u.username}",
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
                    Modifier.padding(horizontal = Space.gutter, vertical = 5.dp),
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
