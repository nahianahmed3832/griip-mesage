package com.meldiq.android.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.People
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ConnectionsRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.model.ConnectionRules
import com.meldiq.android.data.model.RequestRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.UserAvatar
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.ConversationAvatar
import com.meldiq.android.ui.screens.chats.listTitle
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Batch B6: message requests waiting for you, newest first. Opening one shows it read-only with Accept, Decline and
 * Block; nothing tells the sender you looked.
 */
@Composable
fun RequestsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val me = container.session.session?.userId
    val all by remember { container.conversations.observeAll() }.collectAsStateWithLifecycle(null)
    val requests = remember(all) { all?.let(RequestRules::incoming) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.b6_requests), onBack = { nav.popBackStack() }) }
        item {
            Text(
                stringResource(R.string.b6_requests_intro), style = Griip.type.bodySmall, color = Griip.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp),
            )
        }
        val list = requests
        when {
            list == null -> item { ShimmerList(3) }
            list.isEmpty() -> item { EmptyState(Icons.Outlined.MarkChatUnread, stringResource(R.string.b6_requests_none), "") }
            else -> items(list, key = { it.id }) { conv ->
                ListCard(
                    listTitle(conv, me), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    subtitle = conv.lastMessagePreview?.take(120) ?: stringResource(R.string.b6_request_no_preview),
                    leading = { ConversationAvatar(conv, me, size = 44.dp) },
                    meta = { Text(TimeFmt.listStamp(conv.lastMessageAt), style = Griip.type.caption, color = Griip.colors.textTertiary) },
                    onClick = { nav.navigate(Routes.conversation(conv.id)) },
                )
            }
        }
    }
}

/**
 * Batch B6: your connections. Requests to you (Accept, Decline), ones you sent (Cancel), the people you're connected
 * with (tap for their profile), and Add by username. No suggestions and no mutual lists.
 */
@Composable
fun PeopleScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<ConnectionsRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val sentLabel = stringResource(R.string.b6_request_sent)
    val connectedLabel = stringResource(R.string.b6_now_connected)
    LaunchedEffect(reload) {
        runCatching { container.connections.lists() }.onSuccess { lists = it; error = null }.onFailure { error = it.friendly(); if (lists == null) lists = ConnectionsRes() }
    }
    LaunchedEffect(Unit) { container.messages.connectionsChanged.collect { reload++ } }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { error = it.friendly() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.b6_connections), onBack = { nav.popBackStack() }) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GriipTextField(username, { username = it.take(65) }, stringResource(R.string.b6_add_by_username), Modifier.weight(1f), placeholder = "@username")
                Spacer(Modifier.padding(4.dp))
                PillButton(stringResource(R.string.b6_send_request), {
                    val name = ConnectionRules.username(username) ?: return@PillButton
                    sending = true
                    scope.launch {
                        runCatching { container.connections.connect(username = name) }
                            .onSuccess { state -> notice = if (state == "accepted") connectedLabel else sentLabel; username = ""; error = null; reload++ }
                            .onFailure { error = it.friendly(); notice = null }
                        sending = false
                    }
                }, Modifier.padding(top = 18.dp), enabled = ConnectionRules.username(username) != null, loading = sending, height = 48.dp, showKnob = false)
            }
        }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        notice?.let { item { StatusBanner(it, BannerKind.Success, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        val l = lists
        if (l == null) {
            item { ShimmerList(4) }
            return@LazyColumn
        }
        if (l.incoming.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.b6_incoming), Modifier.padding(start = Space.gutter, top = 12.dp, bottom = 4.dp)) }
            items(l.incoming, key = { "in-" + it.user.id }) { e ->
                PersonRow(e.user, onOpen = { nav.navigate(Routes.user(e.user.id)) }) {
                    PillButton(stringResource(R.string.b6_accept), { act { container.connections.accept(e.user.id) } }, height = 38.dp, showKnob = false)
                    PillButton(stringResource(R.string.b6_decline), { act { container.connections.remove(e.user.id) } }, tone = ButtonTone.Glass, height = 38.dp, showKnob = false)
                }
            }
        }
        if (l.outgoing.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.b6_outgoing), Modifier.padding(start = Space.gutter, top = 12.dp, bottom = 4.dp)) }
            items(l.outgoing, key = { "out-" + it.user.id }) { e ->
                PersonRow(e.user, onOpen = { nav.navigate(Routes.user(e.user.id)) }) {
                    PillButton(stringResource(R.string.b6_cancel_request), { act { container.connections.remove(e.user.id) } }, tone = ButtonTone.Glass, height = 38.dp, showKnob = false)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.b6_connected), Modifier.padding(start = Space.gutter, top = 12.dp, bottom = 4.dp)) }
        if (l.connected.isEmpty()) item {
            EmptyState(Icons.Outlined.People, stringResource(R.string.b6_connected_none), stringResource(R.string.b6_connected_none_body))
        } else items(l.connected, key = { "c-" + it.id }) { u -> PersonRow(u, onOpen = { nav.navigate(Routes.user(u.id)) }) {} }
    }
}

@Composable
private fun PersonRow(u: UserDto, onOpen: () -> Unit, actions: @Composable () -> Unit) {
    ListCard(
        u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), subtitle = "@" + u.username,
        leading = { UserAvatar(u.id, u.displayName, u.avatarColor, u.photoRev, size = 44.dp) },
        trailing = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() } },
        onClick = onOpen,
    )
}

/** The profile's connection button (batch B6): Connect, Request sent (cancel), Accept, or Connected (remove). */
@Composable
fun ConnectionButton(userId: String, modifier: Modifier = Modifier, onError: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<ConnectionsRes?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    // Only a successful load replaces the lists: a reload cancelled by the next one must not blank the button.
    LaunchedEffect(userId, reload) { runCatching { container.connections.lists() }.onSuccess { lists = it } }
    LaunchedEffect(Unit) { container.messages.connectionsChanged.collect { reload++ } }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { onError(it.friendly()) }; reload++ }
    when (ConnectionRules.state(lists, userId)) {
        ConnectionRules.State.NONE -> PillButton(stringResource(R.string.b6_connect), { act { container.connections.connect(userId = userId) } }, modifier, tone = ButtonTone.Glass, height = 48.dp)
        ConnectionRules.State.SENT -> PillButton(stringResource(R.string.b6_request_sent_short), { act { container.connections.remove(userId) } }, modifier, tone = ButtonTone.Glass, height = 48.dp)
        ConnectionRules.State.RECEIVED -> PillButton(stringResource(R.string.b6_accept), { act { container.connections.accept(userId) } }, modifier, height = 48.dp)
        ConnectionRules.State.CONNECTED -> PillButton(stringResource(R.string.b6_connected_button), { confirmRemove = true }, modifier, tone = ButtonTone.Glass, height = 48.dp)
    }
    if (confirmRemove) com.meldiq.android.ui.components.ConfirmDialog(
        stringResource(R.string.b6_remove_q), stringResource(R.string.b6_remove_body), stringResource(R.string.b6_remove),
        onConfirm = { confirmRemove = false; act { container.connections.remove(userId) } },
        onDismiss = { confirmRemove = false }, destructive = true,
    )
}
