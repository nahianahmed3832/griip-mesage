package com.meldiq.android.ui.screens.conversation

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
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.repo.ConversationRepository
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.ConversationAvatar
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A conversation's details. For a channel (batch B14b) that's the tabbed details screen; [settings] opens the channel's
 * settings page instead (its gear): about, access, notifications and the managers' actions.
 */
@Composable
fun ConversationInfoScreen(nav: NavHostController, conversationId: String, settings: Boolean = false) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val me = container.session.session?.userId
    val conv by container.conversations.observe(conversationId).collectAsStateWithLifecycle(null)
    val pinned by container.messages.observePinned(conversationId).collectAsStateWithLifecycle(emptyList())
    var members by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    // Batch B6b: a group's photo (servers with `group-photos`).
    var groupPhotos by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { groupPhotos = container.features.has(com.meldiq.android.data.repo.ServerFeatures.GROUP_PHOTOS) == true }
    val photoCtx = androidx.compose.ui.platform.LocalContext.current
    val pickGroupPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            runCatching { container.conversations.setGroupPhoto(conversationId, com.meldiq.android.media.MediaPrep.profilePhoto(photoCtx, uri)) }
                .onFailure { error = it.friendly() }
            photoBusy = false
        }
    }
    // The server's current view (who may change the channel's access) and whether it supports access modes at all.
    var dto by remember { mutableStateOf<com.meldiq.android.data.api.ConversationDto?>(null) }
    var accessFeature by remember { mutableStateOf<Boolean?>(null) }
    // Channel permissions and forums exist only on servers with communities.
    var communityChannels by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { container.conversations.members(conversationId) }.onSuccess { members = it }.onFailure { error = it.friendly() }
        if (conv?.type == "CHANNEL" || conv == null) {
            accessFeature = container.features.has(com.meldiq.android.data.repo.ServerFeatures.WORKSPACE_ADMIN)
            communityChannels = container.features.has(com.meldiq.android.data.repo.ServerFeatures.COMMUNITY_CHANNELS) == true
            runCatching { container.conversations.fetch(conversationId) }.onSuccess { dto = it }
        }
    }

    val cv = conv ?: return
    val title = ConversationRepository.title(cv, me)
    // Batch B14: shared by both layouts below.
    val settingsCard: @Composable () -> Unit = {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), contentPadding = PaddingValues(6.dp)) {
            // Batch B14: a DM or group mutes from its details' Mute button; channels keep the switch.
            if (cv.type == "CHANNEL") MenuRow(Icons.Outlined.NotificationsOff, stringResource(R.string.b5_mute_notifications), subtitle = stringResource(R.string.b5_mentions_still), trailing = {
                GriipSwitch(cv.muted, { v -> scope.launch { container.navPrefs.setMuted(cv, v) } })
            })
            if (cv.type == "DM") {
                ConversationRepository.peer(cv, me)?.let { p ->
                    MenuRow(Icons.Outlined.Shield, stringResource(R.string.b5_safety_profile), subtitle = stringResource(R.string.b5_verify_key, p.username), onClick = { nav.navigate(Routes.user(p.id)) })
                }
            }
            if (cv.canManage) {
                MenuRow(Icons.Outlined.Edit, stringResource(if (cv.type == "CHANNEL") R.string.b5_edit_name_topic else R.string.b5_rename_group), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { showEdit = true })
                // Batch B14: a group adds people from its details' Add button.
                if (cv.type == "CHANNEL") MenuRow(Icons.Outlined.PersonAdd, stringResource(R.string.b5_add_members), onClick = { showAdd = true })
                if (cv.type == "GROUP" && groupPhotos) {
                    MenuRow(
                        Icons.Outlined.PhotoCamera, stringResource(if (cv.photoRev == null) R.string.b6b_add_photo else R.string.b6b_change_photo),
                        subtitle = if (photoBusy) stringResource(R.string.b6b_photo_uploading) else stringResource(R.string.b6b_photo_sub),
                        tint = c.lavender, tintBg = c.lavenderSoft,
                        onClick = { pickGroupPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    if (cv.photoRev != null) MenuRow(
                        Icons.Outlined.Delete, stringResource(R.string.b6b_remove_photo), tint = c.danger, tintBg = c.dangerSoft, trailing = null,
                        onClick = { scope.launch { runCatching { container.conversations.removeGroupPhoto(conversationId) }.onFailure { error = it.friendly() } } },
                    )
                }
                // Communities: who may see, post and share files here, on top of the category's rules.
                if (cv.type == "CHANNEL" && cv.orgId != null && communityChannels) MenuRow(
                    Icons.Outlined.Security, androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.cm_permissions), tint = c.lavender, tintBg = c.lavenderSoft,
                    onClick = { nav.navigate(Routes.permissions(category = false, id = conversationId, name = cv.name.orEmpty())) },
                )
                // A text channel can become a forum (posts with titles and replies) and back; nothing is lost either way.
                if (cv.type == "CHANNEL" && !cv.voice && communityChannels) MenuRow(
                    Icons.Outlined.Forum,
                    androidx.compose.ui.res.stringResource(if (cv.format == "forum") com.meldiq.android.R.string.fm_make_text else com.meldiq.android.R.string.fm_make_forum),
                    onClick = { scope.launch { runCatching { container.conversations.update(conversationId, format = if (cv.format == "forum") "text" else "forum") }.onFailure { error = it.friendly() }; reload++ } },
                )
                if (cv.type == "CHANNEL") MenuRow(
                    if (cv.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, stringResource(if (cv.archived) R.string.b5_unarchive_channel else R.string.b5_archive_channel),
                    tint = c.warning, tintBg = c.warningSoft,
                    onClick = { scope.launch { runCatching { container.conversations.update(conversationId, archived = !cv.archived) }.onFailure { error = it.friendly() } } },
                )
            }
            // Batch B14b: people leave a community, not one of its channels (master prompt §5.2); groups can still be left.
            if (cv.type == "GROUP" && cv.joined) {
                MenuRow(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.b5_leave), tint = c.danger, tintBg = c.dangerSoft, onClick = { confirmLeave = true }, trailing = null)
            }
        }
    }
    val memberItems: androidx.compose.foundation.lazy.LazyListScope.(List<UserDto>) -> Unit = { list ->
        items(list, key = { it.id }) { u ->
            var menu by remember { mutableStateOf(false) }
            ListCard(
                if (u.id == me) "${u.displayName} (you)" else u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = "@${u.username}",
                leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (u.role == "ADMIN") RoleBadge("ADMIN") else u.orgRole?.takeIf { it != "MEMBER" }?.let { RoleBadge(it) }
                        if (cv.canManage && u.id != me && cv.type != "DM") {
                            CircleIconButton(Icons.Rounded.MoreVert, stringResource(R.string.b5_manage_person, u.displayName), { menu = true }, size = 34.dp)
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(if (u.role == "ADMIN") R.string.b5_make_member else R.string.b5_make_channel_admin)) }, onClick = {
                                    menu = false
                                    scope.launch { runCatching { container.conversations.addMember(conversationId, u.id, if (u.role == "ADMIN") "MEMBER" else "ADMIN") }.onFailure { error = it.friendly() }; reload++ }
                                })
                                DropdownMenuItem(text = { Text(stringResource(if (cv.type == "CHANNEL") R.string.b5_remove_from_channel else R.string.b5_remove_from_group), color = c.danger) }, onClick = {
                                    menu = false
                                    scope.launch { runCatching { container.conversations.removeMember(conversationId, u.id) }.onFailure { error = it.friendly() }; reload++ }
                                })
                            }
                        }
                    }
                },
                onClick = if (u.id != me) ({ nav.navigate(Routes.user(u.id)) }) else null,
            )
        }
    }
    if (cv.type != "CHANNEL" || !settings) {
        val manageAccess = cv.type == "CHANNEL" && cv.canManage && cv.orgId != null && communityChannels
        ConversationDetailsList(
            nav, cv, me, title, members, pinned, error, settingsCard, memberItems,
            onAdd = if (cv.canManage) ({ showAdd = true }) else null,
            onManageAccess = if (manageAccess) ({ nav.navigate(Routes.permissions(category = false, id = conversationId, name = cv.name.orEmpty())) }) else null,
            onOpenSettings = { nav.navigate(Routes.channelSettings(conversationId)) },
        )
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar(stringResource(R.string.b14b_channel_settings), onBack = { nav.popBackStack() }) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ConversationAvatar(cv, me, size = 76.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (cv.type == "CHANNEL") "#$title" else title, style = Griip.type.headline, color = c.textPrimary, textAlign = TextAlign.Center)
                    cv.topic?.let { Text(it, style = Griip.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cv.type == "CHANNEL") {
                            TagPill(cv.kind?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "", c.primaryDeep, c.primarySoft)
                            cv.scopeName?.let { TagPill(it, c.lavender, c.lavenderSoft) }
                        }
                        TagPill("${cv.memberCount} members", c.textSecondary, c.surfaceVariant)
                        if (cv.archived) TagPill(stringResource(R.string.b5_archived), c.warning, c.warningSoft)
                    }
                }
            }
        }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        if (cv.type == "CHANNEL") {
            item(key = "about") { ChannelAboutCard(cv, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
            item(key = "access") {
                ChannelAccessCard(cv, dto, accessFeature, onChanged = { reload++ }, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
            }
        }
        item(key = "settings-header") { SectionHeader(androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.ch_notifications), Modifier.padding(top = 10.dp, bottom = 2.dp)) }
        item { settingsCard() }
        // Batch B14b: pins, files and members are tabs of the channel's details; this page keeps its settings.
    }
    if (showAdd) AddMembersSheet(cv.orgId, members.map { it.id }.toSet(), onDismiss = { showAdd = false }) { ids ->
        scope.launch {
            ids.forEach { id -> runCatching { container.conversations.addMember(conversationId, id) }.onFailure { error = it.friendly() } }
            reload++
            showAdd = false
        }
    }
    if (showEdit) {
        var name by remember { mutableStateOf(cv.name ?: "") }
        var topic by remember { mutableStateOf(cv.topic ?: "") }
        GlassSheet({ showEdit = false }, title = stringResource(R.string.b5_edit)) {
            GriipTextField(name, { name = it.take(60) }, stringResource(R.string.b5_name))
            Spacer(Modifier.height(12.dp))
            if (cv.type == "CHANNEL") GriipTextField(topic, { topic = it.take(300) }, stringResource(R.string.b5_topic), singleLine = false, minLines = 2)
            Spacer(Modifier.height(16.dp))
            PillButton(stringResource(R.string.ja_save), {
                scope.launch {
                    runCatching { container.conversations.update(conversationId, name = name.trim(), topic = if (cv.type == "CHANNEL") topic.trim() else null) }.onFailure { error = it.friendly() }
                    showEdit = false
                }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), height = 50.dp)
        }
    }
    if (confirmLeave) ConfirmDialog(
        stringResource(R.string.b5_leave_q, title), stringResource(R.string.b5_leave_body), stringResource(R.string.b5_leave),
        onConfirm = {
            confirmLeave = false
            scope.launch {
                runCatching { container.conversations.removeMember(conversationId, me!!) }.onSuccess { nav.popBackStack(Routes.CHATS, false) || nav.popBackStack(Routes.HOME, false) || nav.popBackStack() }.onFailure { error = it.friendly() }
            }
        },
        onDismiss = { confirmLeave = false }, destructive = true,
    )
}

/** Directory picker used to add people to channels/groups. */
@Composable
fun AddMembersSheet(orgId: String?, existing: Set<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    val picked = remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(q) {
        delay(200)
        list = runCatching { if (orgId != null) container.orgs.members(orgId, q) else if (q.length >= 2) container.users.search(q) else emptyList() }.getOrDefault(emptyList())
            .filter { it.id !in existing }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.b5_add_members)) {
        SearchPill(q, { q = it }, stringResource(R.string.b5_search_people))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            items(list, key = { it.id }) { u ->
                val sel = u.id in picked.value
                ListCard(
                    u.displayName, Modifier.padding(vertical = 4.dp), subtitle = "@${u.username}" + (u.orgRole?.let { " · ${it.lowercase()}" } ?: ""),
                    leading = { Avatar(u.displayName, u.avatarColor, size = 40.dp) },
                    trailing = { if (sel) TagPill(stringResource(R.string.b5_selected), c.primaryDeep, c.primarySoft) },
                    selected = sel,
                    onClick = { picked.value = if (sel) picked.value - u.id else picked.value + u.id },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.b5_add_n, picked.value.size), { onAdd(picked.value.toList()) }, Modifier.fillMaxWidth(), enabled = picked.value.isNotEmpty(), height = 50.dp)
        Spacer(Modifier.width(1.dp))
    }
}
