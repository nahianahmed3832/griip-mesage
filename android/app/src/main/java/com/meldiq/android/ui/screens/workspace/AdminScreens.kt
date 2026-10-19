package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ActivityDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.UsageRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.model.AttachmentPolicy
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatTile
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/** The full audit log (audit.view): every recorded change, newest first, a page at a time. Never message content. */
@Composable
fun AuditScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ActivityDto>?>(null) }
    var next by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun load(before: Long?) = scope.launch {
        busy = true
        runCatching { container.roles.audit(orgId, before) }
            .onSuccess { r -> entries = (if (before == null) emptyList() else entries.orEmpty()) + r.entries; next = r.nextBefore; error = null }
            .onFailure { error = it.friendly() }
        busy = false
    }
    LaunchedEffect(Unit) { load(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.audit_title), onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter)) } }
        val list = entries
        if (list == null) item { ShimmerList(6) }
        else if (list.isEmpty()) item { EmptyState(Icons.Outlined.History, stringResource(R.string.audit_empty), "") }
        else items(list, key = { it.id }) { e ->
            ListCard(
                "${e.actorName ?: "Meldiq"} · ${AuditText.action(e.action)}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOfNotNull(e.detail?.take(120), TimeFmt.listStamp(e.at)).joinToString(" · "),
            )
        }
        if (next != null) item {
            PillButton(stringResource(R.string.audit_more), { load(next) }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp), tone = ButtonTone.Glass, loading = busy)
        }
    }
}

/** Plain words for an audit action key ("integration.subscription.create" → "integration subscription create"). */
object AuditText {
    fun action(key: String): String = key.replace('.', ' ').replace('_', ' ')
}

/** Usage and storage (workspace.usage.view): real counts from the server, nothing estimated. */
@Composable
fun UsageScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    var u by remember { mutableStateOf<UsageRes?>(null) }
    var plan by remember { mutableStateOf<com.meldiq.android.data.api.PlanRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.roles.usage(orgId) }.onSuccess { u = it }.onFailure { error = it.friendly() }
        if (container.features.has(com.meldiq.android.data.repo.ServerFeatures.INTEGRATIONS) == true) runCatching { container.integrations.plan(orgId) }.onSuccess { plan = it }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.usage_title), onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter)) } }
        val x = u
        if (x == null) {
            item { ShimmerList(4) }
            return@LazyColumn
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                Text(stringResource(R.string.usage_people), style = Griip.type.section, color = c.textPrimary)
                Text("${x.members.total}", style = Griip.type.headline, color = c.textPrimary)
                Text(stringResource(R.string.usage_owners_admins, x.members.owners, x.members.admins), style = Griip.type.bodySmall, color = c.textSecondary)
                Text(stringResource(R.string.usage_members_guests, x.members.members, x.members.guests), style = Griip.type.bodySmall, color = c.textSecondary)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                Text(stringResource(R.string.usage_structure), style = Griip.type.section, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.usage_branches), "${x.structure.branches}", Modifier.weight(1f))
                    StatTile(stringResource(R.string.ws_stat_teams), "${x.structure.teams}", Modifier.weight(1f))
                    StatTile(stringResource(R.string.ws_stat_channels), "${x.structure.channels}", Modifier.weight(1f))
                }
                Text(stringResource(R.string.usage_archived_channels) + ": ${x.structure.archivedChannels} · " + stringResource(R.string.usage_departments) + ": ${x.structure.departments}",
                    style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                Text(stringResource(R.string.usage_files), style = Griip.type.section, color = c.textPrimary)
                Text("${AttachmentPolicy.sizeLabel(x.files.bytes)} · ${x.files.count}", style = Griip.type.title, color = c.textPrimary)
                Text(stringResource(R.string.usage_files_note), style = Griip.type.caption, color = c.textSecondary)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                Text(stringResource(R.string.usage_apps), style = Griip.type.section, color = c.textPrimary)
                Text(stringResource(R.string.usage_apps_line, x.apps.installed, x.apps.connectedAccounts, x.apps.notifications), style = Griip.type.bodySmall, color = c.textSecondary)
                Text(stringResource(R.string.usage_roles) + ": ${x.roles.custom} · " + stringResource(R.string.usage_invites) + ": ${x.invitations.active}", style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // Plans: none exist yet. Server-wide limits, if the operator set any, are shown as they are; no upgrade is offered.
        plan?.let { p ->
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                    Text(stringResource(R.string.usage_limits), style = Griip.type.section, color = c.textPrimary)
                    val limited = p.capabilities.filter { it.limit != null }
                    if (limited.isEmpty()) Text(stringResource(R.string.usage_no_plan), style = Griip.type.bodySmall, color = c.textSecondary)
                    limited.forEach { cap ->
                        Text(stringResource(R.string.usage_limit_line, cap.label, cap.used, cap.limit ?: 0), style = Griip.type.bodySmall, color = if (cap.available) c.textSecondary else c.warning)
                    }
                }
            }
        }
    }
}

/**
 * Handing the workspace to another member (owners only). Explains the consequence, asks for the workspace's exact name,
 * and the server checks everything again. The previous owner becomes an admin.
 */
@Composable
fun TransferOwnershipSheet(detail: OrgDetailRes, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var target by remember { mutableStateOf<UserDto?>(null) }
    var confirmName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val me = container.session.session?.userId
    LaunchedEffect(Unit) {
        runCatching { container.orgs.members(detail.org.id) }.onSuccess { list -> people = list.filter { it.id != me && (it.orgRole == "MEMBER" || it.orgRole == "ADMIN") } }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.owner_transfer)) {
        Text(stringResource(R.string.owner_transfer_sub), style = Griip.type.bodySmall, color = c.textSecondary)
        SectionHeader(stringResource(R.string.owner_transfer_pick), Modifier.padding(vertical = 8.dp))
        Column {
            if (people.isEmpty()) Text(stringResource(R.string.owner_transfer_none), style = Griip.type.bodySmall, color = c.textSecondary)
            people.take(30).forEach { u -> ListCard(u.displayName, subtitle = "@${u.username} · ${roleLabel(u.orgRole)}", selected = target?.id == u.id, onClick = { target = u }) }
        }
        Spacer(Modifier.height(8.dp))
        GriipTextField(confirmName, { confirmName = it }, stringResource(R.string.owner_transfer_confirm), placeholder = detail.org.name)
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.owner_transfer_do), {
            val t = target ?: return@PillButton
            busy = true
            scope.launch {
                runCatching { container.roles.transferOwnership(detail.org.id, t.id, confirmName) }
                    .onSuccess { onDone(t.displayName) }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, enabled = target != null && confirmName.trim() == detail.org.name.trim(), loading = busy, height = 50.dp)
        Spacer(Modifier.height(8.dp))
    }
}
