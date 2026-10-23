package com.meldiq.android.ui.screens.workspace

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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Security
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.OverwriteDto
import com.meldiq.android.data.api.OverwriteReq
import com.meldiq.android.data.api.OverwritesRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.model.OverwriteRules
import com.meldiq.android.data.model.OverwriteState
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Permission overwrites on a category ([category] true) or a channel (docs/COMMUNITY_REBUILD_2026-10-21.md §5): for
 * @everyone, each role and each member, every capability is Allow, Default or Deny. Saved one rule at a time; the
 * server refuses changes to powers the person doesn't hold and re-checks everyone's access afterwards.
 */
@Composable
fun ChannelPermissionsScreen(nav: NavHostController, category: Boolean, id: String, name: String) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = Griip.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf<OverwritesRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var orgId by remember { mutableStateOf<String?>(null) }
    // A channel's category and its rules: what the channel inherits, and what "Sync with category" returns it to.
    var parent by remember { mutableStateOf<Pair<String, OverwritesRes?>?>(null) }
    var confirmSync by remember { mutableStateOf(false) }
    // Basic (a Private switch and who can access) or Advanced (every capability per role and member).
    var basic by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    fun load() = scope.launch {
        runCatching { if (category) container.orgs.categoryOverwrites(id) else container.orgs.channelOverwrites(id) }
            .onSuccess { data = it; error = null }.onFailure { error = it.friendly() }
        if (!category) {
            val conv = container.db.conversations().get(id)
            val catId = conv?.categoryId
            parent = if (catId == null) null else (conv.categoryName ?: "") to runCatching { container.orgs.categoryOverwrites(catId) }.getOrNull()
        }
    }
    LaunchedEffect(id) {
        load()
        orgId = container.orgs.selectedOrg.value
    }
    fun save(subject: String, allow: List<String>, deny: List<String>) {
        busy = true
        scope.launch {
            runCatching { container.orgs.setOverwrite(category, id, OverwriteReq(subject, allow, deny)) }
                .onSuccess {
                    data = it
                    error = null
                    android.widget.Toast.makeText(ctx, if (it.removed == 1) ctx.getString(R.string.b12_perm_removed_one) else if (it.removed > 0) ctx.getString(R.string.perm_removed, it.removed) else ctx.getString(R.string.perm_saved), android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            GriipTopBar(
                stringResource(R.string.perm_title),
                subtitle = if (category) stringResource(R.string.perm_category_sub, name) else stringResource(R.string.perm_channel_sub, name),
                onBack = { nav.popBackStack() },
            )
        }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        if (d == null) {
            item { ShimmerList(3) }
            return@LazyColumn
        }
        item {
            Text(stringResource(R.string.perm_hint), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
        }
        if (!d.canManage) item { StatusBanner(stringResource(R.string.perm_readonly), BannerKind.Info, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        item(key = "mode") {
            Row(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.ux_perm_basic), basic, { basic = true })
                PillChip(stringResource(R.string.ux_perm_advanced), !basic, { basic = false })
            }
        }
        parent?.let { (catName, _) ->
            item(key = "sync") {
                if (OverwriteRules.synced(d.overwrites)) StatusBanner(stringResource(R.string.nv_synced_with, catName), BannerKind.Success, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
                else StatusBanner(
                    stringResource(R.string.nv_not_synced, catName), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp),
                    action = if (d.canManage) stringResource(R.string.nv_sync_now) else null, onAction = if (d.canManage) ({ confirmSync = true }) else null,
                )
            }
        }
        // @everyone is always listed, even without a rule yet, so its defaults can be changed.
        val everyone = d.overwrites.firstOrNull { it.subject == OverwriteRules.EVERYONE }
            ?: OverwriteDto(OverwriteRules.EVERYONE, "everyone", "@everyone")
        if (basic) {
            basicView(d, category, enabled = d.canManage && !busy,
                onPrivate = { on -> val (allow, deny) = OverwriteRules.privacy(everyone, on); save(OverwriteRules.EVERYONE, allow, deny) },
                onRemove = { o -> val (allow, deny) = OverwriteRules.access(o, false); save(o.subject, allow, deny) },
                onAdd = { adding = true },
            )
            return@LazyColumn
        }
        val rules = listOf(everyone) + OverwriteRules.ordered(d.overwrites.filter { it.subject != OverwriteRules.EVERYONE })
        items(rules, key = { it.subject }) { o ->
            OverwriteCard(
                o, d, enabled = d.canManage && !busy, inherited = parent?.second?.overwrites?.firstOrNull { it.subject == o.subject },
                onChange = { cap, state -> val (allow, deny) = OverwriteRules.with(o, cap, state); save(o.subject, allow, deny) },
                onRemove = if (o.subject != OverwriteRules.EVERYONE && (o.allow.isNotEmpty() || o.deny.isNotEmpty())) ({ save(o.subject, emptyList(), emptyList()) }) else null,
            )
        }
        if (d.canManage) item {
            PillButton(stringResource(R.string.perm_add_role), { adding = true }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PersonAdd)
        }
    }

    if (confirmSync && d != null) com.meldiq.android.ui.components.ConfirmDialog(
        stringResource(R.string.nv_sync_title), stringResource(R.string.nv_sync_body, parent?.first.orEmpty()), stringResource(R.string.nv_sync_now),
        onConfirm = {
            confirmSync = false
            busy = true
            scope.launch {
                // One rule at a time, as the server checks each against the writer's own powers; stops at the first refusal.
                runCatching { OverwriteRules.syncTargets(d.overwrites).forEach { subject -> container.orgs.setOverwrite(false, id, OverwriteReq(subject, emptyList(), emptyList())) } }
                    .onSuccess { android.widget.Toast.makeText(ctx, ctx.getString(R.string.nv_synced_toast), android.widget.Toast.LENGTH_SHORT).show() }
                    .onFailure { error = it.friendly() }
                load()
                busy = false
            }
        },
        onDismiss = { confirmSync = false },
    )
    if (adding && d != null) AddSubjectSheet(
        d, orgId,
        taken = if (basic) OverwriteRules.whoCanView(d.overwrites).map { it.subject }.toSet() else d.overwrites.map { it.subject }.toSet(),
        onDismiss = { adding = false },
        onPick = { subject, label, kind ->
            adding = false
            if (basic) {
                // Basic: giving access is saving view on that role or member, keeping anything else it already has.
                val (allow, deny) = OverwriteRules.access(d.overwrites.firstOrNull { it.subject == subject }, true)
                save(subject, allow, deny)
            } else {
                // A new rule starts by allowing nothing and denying nothing: saving it with one choice is what creates it.
                data = d.copy(overwrites = d.overwrites + OverwriteDto(subject, kind, label))
            }
        },
    )
}

@Composable
private fun OverwriteCard(o: OverwriteDto, d: OverwritesRes, enabled: Boolean, inherited: OverwriteDto?, onChange: (String, OverwriteState) -> Unit, onRemove: (() -> Unit)?) {
    val c = Griip.colors
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (o.subject == OverwriteRules.EVERYONE) stringResource(R.string.perm_everyone) else o.label,
                style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f),
            )
            onRemove?.let { CircleIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.perm_remove), it, tint = c.danger) }
        }
        d.capabilities.forEach { cap ->
            Spacer(Modifier.height(10.dp))
            Text(cap.label, style = Griip.type.label, color = c.textSecondary)
            val state = OverwriteRules.state(o, cap.key)
            // Default on the channel: say what the category sets for the same people, if anything.
            val fromCategory = OverwriteRules.state(inherited, cap.key)
            if (state == OverwriteState.INHERIT && fromCategory != OverwriteState.INHERIT) {
                Text(
                    stringResource(R.string.nv_from_category, stringResource(if (fromCategory == OverwriteState.ALLOW) R.string.perm_allow else R.string.perm_deny)),
                    style = Griip.type.caption, color = c.textTertiary,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.perm_allow), state == OverwriteState.ALLOW, { if (enabled) onChange(cap.key, OverwriteState.ALLOW) })
                PillChip(stringResource(R.string.perm_default), state == OverwriteState.INHERIT, { if (enabled) onChange(cap.key, OverwriteState.INHERIT) })
                PillChip(stringResource(R.string.perm_deny), state == OverwriteState.DENY, { if (enabled) onChange(cap.key, OverwriteState.DENY) })
            }
        }
    }
}

/**
 * Basic view (2026-10-23, the reference's simple permissions): a Private switch, and while private the roles and members
 * who can see it, each removable, with "Add members or roles". It only ever changes [OverwriteRules.VIEW]; everything
 * else stays in Advanced.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.basicView(
    d: OverwritesRes, category: Boolean, enabled: Boolean,
    onPrivate: (Boolean) -> Unit, onRemove: (OverwriteDto) -> Unit, onAdd: () -> Unit,
) {
    val private = OverwriteRules.isPrivate(d.overwrites)
    val access = OverwriteRules.whoCanView(d.overwrites)
    item(key = "private") {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(4.dp)) {
            MenuRow(
                Icons.Outlined.Lock, stringResource(if (category) R.string.ux_private_category else R.string.ux_private_channel),
                subtitle = stringResource(if (private) R.string.ux_private_sub else R.string.ux_public_sub),
                trailing = { com.meldiq.android.ui.components.GriipSwitch(private, onPrivate, enabled = enabled) },
            )
        }
    }
    if (!private) return
    if (access.isEmpty()) item(key = "nobody") {
        StatusBanner(stringResource(R.string.ux_nobody_can_view), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
    }
    item(key = "who") {
        Text(stringResource(R.string.ux_who_can_access), style = Griip.type.section, color = Griip.colors.textSecondary, modifier = Modifier.padding(start = Space.gutter, top = 12.dp, bottom = 4.dp))
    }
    items(access, key = { "a:" + it.subject }) { o ->
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 3.dp), contentPadding = PaddingValues(4.dp)) {
            MenuRow(
                if (o.kind == "role") Icons.Outlined.Security else Icons.Outlined.PersonAdd, o.label,
                subtitle = stringResource(if (o.kind == "role") R.string.ux_role else R.string.ux_member),
                trailing = if (enabled) ({ CircleIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.ux_remove_access), { onRemove(o) }, tint = Griip.colors.danger) }) else null,
            )
        }
    }
    item(key = "always") {
        Text(stringResource(R.string.ux_admins_always), style = Griip.type.caption, color = Griip.colors.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
    }
    if (enabled) item(key = "add") {
        PillButton(stringResource(R.string.ux_add_access), onAdd, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PersonAdd)
    }
}

/** Adds a rule for a role, or a member of the community. */
@Composable
private fun AddSubjectSheet(d: OverwritesRes, orgId: String?, taken: Set<String>, onDismiss: () -> Unit, onPick: (String, String, String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    var members by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    LaunchedEffect(orgId) { if (orgId != null) members = runCatching { container.orgs.members(orgId) }.getOrDefault(emptyList()) }
    GlassSheet(onDismiss, title = stringResource(R.string.perm_add_role)) {
        Column {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                d.roles.filter { it.id !in taken }.forEach { r ->
                    MenuRow(Icons.Outlined.Security, r.name, tint = c.lavender, tintBg = c.lavenderSoft, onClick = { onPick(r.id, r.name, "role") })
                }
            }
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.perm_add_member), style = Griip.type.section, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                    members.filter { (OverwriteRules.USER_PREFIX + it.id) !in taken && it.orgRole != "OWNER" && it.orgRole != "ADMIN" }.take(50).forEach { u ->
                        MenuRow(Icons.Outlined.PersonAdd, u.displayName, subtitle = "@${u.username}", onClick = { onPick(OverwriteRules.USER_PREFIX + u.id, u.displayName, "member") })
                    }
                }
            }
            Spacer(Modifier.width(8.dp).height(8.dp))
        }
    }
}
