package com.meldiq.android.ui.screens.workspace.roles

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddModerator
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.BaseRoleDto
import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.RolesRes
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.roleLabel
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RolesViewModel(val c: AppContainer, val orgId: String) : ViewModel() {
    val roles = MutableStateFlow<RolesRes?>(null)
    val error = MutableStateFlow<String?>(null)
    val supported = MutableStateFlow<Boolean?>(null)
    val showArchived = MutableStateFlow(false)

    init {
        refresh()
        viewModelScope.launch { c.messages.orgEvents.collect { if (it == orgId) refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        supported.value = c.features.has(ServerFeatures.CUSTOM_ROLES)
        if (supported.value == false) return@launch
        runCatching { c.roles.roles(orgId, showArchived.value) }.onSuccess { roles.value = it; error.value = null }.onFailure { error.value = it.friendly() }
    }

    fun toggleArchived() {
        showArchived.value = !showArchived.value
        refresh()
    }
}

/**
 * Roles & permissions (docs/WORKSPACE_ROLES.md): the protected built-in roles and the workspace's own roles. A role
 * grants exactly the permissions it lists; the server checks every change, including that nobody gives a role more
 * than they hold themselves.
 */
@Composable
fun RolesScreen(nav: NavHostController, orgId: String) {
    val vm = containerViewModel("roles-$orgId") { RolesViewModel(it, orgId) }
    val c = Griip.colors
    val res by vm.roles.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val showArchived by vm.showArchived.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CustomRoleDto?>(null) }
    var creating by remember { mutableStateOf<RoleDraft?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var viewingBase by remember { mutableStateOf<BaseRoleDto?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.roles_title), onBack = { nav.popBackStack() }) }
        if (supported == false) {
            item { StatusBanner(stringResource(R.string.ws_needs_update), BannerKind.Info, Modifier.padding(horizontal = Space.gutter)) }
            return@LazyColumn
        }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "Retry", onAction = { vm.refresh() }) } }
        val r = res
        if (r == null) {
            item { ShimmerList(5) }
            return@LazyColumn
        }
        if (!r.me.canManage) item { StatusBanner(stringResource(R.string.roles_view_only), BannerKind.Info, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        item { SectionHeader(stringResource(R.string.roles_base_section), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
        items(r.baseRoles, key = { "base-" + it.key }) { b ->
            ListCard(
                roleLabel(b.key), Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = com.meldiq.android.ui.screens.community.peopleCountLabel(b.memberCount) + " · " + r.catalog.count { it.key in b.permissions } + "/" + r.catalog.size,
                leading = { IconTile(Icons.Outlined.Shield, c.primary, c.primarySoft, size = 42.dp) },
                meta = { TagPill(stringResource(R.string.roles_protected), c.textSecondary, c.surfaceVariant) },
                trailing = { ArrowKnob() },
                onClick = { viewingBase = b },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.roles_custom_section), Modifier.weight(1f))
                PillChip(stringResource(R.string.roles_show_archived), showArchived, { vm.toggleArchived() })
            }
        }
        if (r.roles.isEmpty()) item {
            EmptyState(
                Icons.Outlined.AddModerator, stringResource(R.string.roles_none), stringResource(R.string.roles_none_sub),
                action = if (r.me.canManage) stringResource(R.string.roles_new) else null, onAction = { picking = true },
            )
        }
        items(r.roles, key = { it.id }) { role -> RoleRow(role, r) { editing = role } }
        if (r.me.canManage && r.roles.isNotEmpty()) item {
            PillButton(stringResource(R.string.roles_new), { picking = true }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp), tone = ButtonTone.Glass)
        }
    }

    val r = res ?: return
    if (picking) TemplatePicker(r, onDismiss = { picking = false }) { draft -> picking = false; creating = draft }
    creating?.let { draft -> RoleEditorSheet(vm, r, existing = null, draft = draft, onDismiss = { creating = null }) }
    editing?.let { role -> RoleEditorSheet(vm, r, existing = role, draft = RoleDraft.of(role), onDismiss = { editing = null }) }
    viewingBase?.let { b ->
        GlassSheet({ viewingBase = null }, title = roleLabel(b.key)) {
            Text(stringResource(R.string.roles_protected), style = Griip.type.caption, color = c.textSecondary)
            RoleEditorRules.grouped(r.catalog.filter { it.key in b.permissions }).forEach { (cat, perms) ->
                Text(categoryName(cat), style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 10.dp))
                perms.forEach { p -> Text("• ${p.label}", style = Griip.type.bodySmall, color = c.textSecondary) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RoleRow(role: CustomRoleDto, r: RolesRes, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(
        role.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        subtitle = role.description ?: com.meldiq.android.ui.screens.community.peopleCountLabel(role.memberCount),
        leading = { RoleDot(role.color) },
        meta = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(com.meldiq.android.ui.screens.community.peopleCountLabel(role.memberCount), c.textSecondary, c.surfaceVariant)
                if (role.permissions.isNotEmpty()) TagPill("${stringResource(R.string.roles_grants)} ${role.permissions.size}", c.primaryDeep, c.primarySoft)
                if (role.denies.isNotEmpty()) TagPill("${stringResource(R.string.roles_restricts)} ${role.denies.size}", c.warning, c.warningSoft)
                if (role.permissions.any { k -> r.catalog.firstOrNull { it.key == k }?.admin == true }) TagPill(stringResource(R.string.roles_admin_power), c.lavender, c.lavenderSoft)
                if (role.archived) TagPill(stringResource(R.string.roles_archived), c.textTertiary, c.surfaceVariant)
            }
        },
        trailing = { ArrowKnob() },
        onClick = onClick,
    )
}

@Composable
fun RoleDot(hex: String?, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val c = Griip.colors
    val color = RoleEditorRules.parseColor(hex)?.let { Color(it) } ?: c.primary
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size / 3).clip(CircleShape).background(color))
    }
}

/** Starting point for a new role: templates first (useful before any configuration), or a blank role. */
@Composable
private fun TemplatePicker(r: RolesRes, onDismiss: () -> Unit, onPick: (RoleDraft) -> Unit) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = stringResource(R.string.roles_new)) {
        Text(stringResource(R.string.roles_from_template), style = Griip.type.label, color = c.textSecondary)
        Column(Modifier.padding(vertical = 6.dp)) {
            r.templates.forEach { t ->
                ListCard(
                    t.name, subtitle = t.description,
                    leading = { RoleDot(t.color, 38.dp) },
                    meta = {
                        ChipRow(Modifier.padding(0.dp)) {
                            t.permissions.take(3).forEach { k -> TagPill(r.catalog.firstOrNull { it.key == k }?.label ?: k, c.textSecondary, c.surfaceVariant) }
                            t.denies.take(3).forEach { k -> TagPill("− " + (r.catalog.firstOrNull { it.key == k }?.label ?: k), c.warning, c.warningSoft) }
                        }
                    },
                    onClick = { onPick(RoleDraft(t.name, t.description, t.color, t.permissions.toSet(), t.denies.toSet(), template = t.key)) },
                )
            }
        }
        PillButton(stringResource(R.string.roles_blank), { onPick(RoleDraft("", "", RoleEditorRules.SWATCHES.first(), emptySet(), emptySet())) }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 46.dp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun categoryName(cat: String): String = when (cat) {
    "workspace" -> stringResource(R.string.roles_cat_workspace)
    "members" -> stringResource(R.string.roles_cat_members)
    "roles" -> stringResource(R.string.roles_cat_roles)
    "channels" -> stringResource(R.string.roles_cat_channels)
    "messaging" -> stringResource(R.string.roles_cat_messaging)
    // Voice channels: joining and speaking (2026-10-21).
    "voice" -> stringResource(R.string.nv_roles_cat_voice)
    "security" -> stringResource(R.string.roles_cat_security)
    else -> cat
}
