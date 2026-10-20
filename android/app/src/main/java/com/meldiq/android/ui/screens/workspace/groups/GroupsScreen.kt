package com.meldiq.android.ui.screens.workspace.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.GroupDto
import com.meldiq.android.data.api.UserGroupReq
import com.meldiq.android.data.api.GroupRes
import com.meldiq.android.data.api.GroupsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * A workspace's user groups (round 7 slice C5, docs/WORKSPACE_C5.md): named sets of members such as Marketing, to add to
 * channels or meetings in one go. Members see them; people who can manage groups change them. A group grants nothing.
 */
@Composable
fun GroupsScreen(nav: NavHostController, orgId: String, canManage: Boolean) {
    val container = LocalContainer.current
    val c = Griip.colors
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<GroupDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runCatching { container.api.get<GroupsRes>("/v1/orgs/$orgId/groups").groups }.onSuccess { groups = it; error = null }.onFailure { error = it.friendly() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            item { GriipTopBar(stringResource(R.string.grp_title), onBack = { nav.popBackStack() }) }
            item { Text(stringResource(R.string.grp_intro), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp)) }
            error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
            val list = groups
            if (list == null && error == null) item { ShimmerList(3) }
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.Groups, stringResource(R.string.grp_empty), stringResource(R.string.grp_empty_body)) }
            items(list.orEmpty(), key = { it.id }) { g ->
                ListCard(
                    g.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = "@${g.handle} · " + stringResource(R.string.grp_members_n, g.members.size) + (g.members.take(4).joinToString(", ", prefix = " · ") { it.name.orEmpty() }.takeIf { g.members.isNotEmpty() } ?: ""),
                    leading = { IconTile(Icons.Outlined.Groups, c.primary, c.primarySoft, size = 44.dp) },
                    onClick = if (canManage) ({ editing = g }) else null,
                )
            }
        }
        if (canManage) ExtendedFloatingActionButton(
            onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.grp_new)) },
            containerColor = c.actionStart, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
    if (creating || editing != null) GroupEditor(orgId, editing, onDone = { creating = false; editing = null; reload++ }, onDismiss = { creating = false; editing = null })
}

@Composable
private fun GroupEditor(orgId: String, group: GroupDto?, onDone: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var members by remember { mutableStateOf(group?.members.orEmpty().associate { it.id to (it.name ?: it.username.orEmpty()) }) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) { found = if (query.trim().length < 2) emptyList() else runCatching { container.orgs.members(orgId, query.trim()) }.getOrDefault(emptyList()) }
    fun call(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { onDone() }.onFailure { error = it.friendly() } }

    GlassSheet(onDismiss, title = stringResource(if (group == null) R.string.grp_new else R.string.grp_edit)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            GriipTextField(name, { name = it.take(60) }, stringResource(R.string.grp_name))
            Text(stringResource(R.string.grp_members_n, members.size), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
            members.forEach { (id, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.Close, stringResource(R.string.grp_remove_member), { members = members - id }, size = 36.dp, tint = c.textSecondary)
                }
            }
            SearchPill(query, { query = it }, stringResource(R.string.grp_add_member))
            found.filter { it.id !in members }.take(6).forEach { u ->
                PillButton("${u.displayName} · @${u.username}", { members = members + (u.id to u.displayName); query = "" }, Modifier.fillMaxWidth().padding(vertical = 2.dp), tone = ButtonTone.Glass, height = 40.dp)
            }
        }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger) }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.grp_save), {
            val req = UserGroupReq(name.trim(), group?.description, members.keys.toList())
            call {
                if (group == null) container.api.post<UserGroupReq, GroupRes>("/v1/orgs/$orgId/groups", req)
                else container.api.put<UserGroupReq, GroupRes>("/v1/orgs/$orgId/groups/${group.id}", req)
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), height = 48.dp)
        if (group != null) PillButton(stringResource(R.string.grp_delete), { call { container.api.delete<OkRes>("/v1/orgs/$orgId/groups/${group.id}") } }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Danger, height = 44.dp)
        Spacer(Modifier.height(16.dp))
    }
}

/** In a channel's info, for its managers: add a group's current members (round 7). */
@Composable
fun AddGroupSheet(orgId: String, conversationId: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<GroupDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val addedText = stringResource(R.string.grp_added)
    LaunchedEffect(orgId) { runCatching { container.api.get<GroupsRes>("/v1/orgs/$orgId/groups").groups }.onSuccess { groups = it }.onFailure { error = it.friendly() } }
    GlassSheet(onDismiss, title = stringResource(R.string.grp_add_to_channel)) {
        if (groups.isEmpty() && error == null) Text(stringResource(R.string.grp_empty), style = Griip.type.bodySmall, color = Griip.colors.textSecondary)
        groups.forEach { g ->
            PillButton("${g.name} · ${g.members.size}", {
                scope.launch {
                    runCatching { container.api.post<com.meldiq.android.data.api.AddGroupReq, com.meldiq.android.data.api.AddGroupRes>("/v1/conversations/$conversationId/add-group", com.meldiq.android.data.api.AddGroupReq(g.id)) }
                        .onSuccess { onDone(addedText.format(it.added)) }.onFailure { error = it.friendly() }
                }
            }, Modifier.fillMaxWidth().padding(vertical = 3.dp), tone = ButtonTone.Glass, height = 44.dp)
        }
        error?.let { Text(it, style = Griip.type.caption, color = Griip.colors.danger) }
        Spacer(Modifier.height(16.dp))
    }
}
