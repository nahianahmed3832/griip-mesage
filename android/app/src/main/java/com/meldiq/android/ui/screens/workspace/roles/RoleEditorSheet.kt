package com.meldiq.android.ui.screens.workspace.roles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.CreateRoleReq
import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.PatchRoleReq
import com.meldiq.android.data.api.PermissionDto
import com.meldiq.android.data.api.RoleMemberDto
import com.meldiq.android.data.api.RolesRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.roleLabel
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** What the editor is changing: a new role (from a template or blank) or an existing one. */
data class RoleDraft(
    val name: String,
    val description: String?,
    val color: String?,
    val grants: Set<String>,
    val denies: Set<String>,
    val template: String? = null,
) {
    companion object {
        fun of(r: CustomRoleDto) = RoleDraft(r.name, r.description, r.color, r.permissions.toSet(), r.denies.toSet(), r.template)
    }
}

/**
 * Creating or editing a role, and who holds it. Each permission's switch is locked (with the reason) when the server
 * would refuse it: powers the editor doesn't hold, admin powers without `roles.delegate`, owner-only powers.
 */
@Composable
fun RoleEditorSheet(vm: RolesViewModel, r: RolesRes, existing: CustomRoleDto?, draft: RoleDraft, onDismiss: () -> Unit) {
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(existing) }
    var name by remember { mutableStateOf(draft.name) }
    var description by remember { mutableStateOf(draft.description.orEmpty()) }
    var color by remember { mutableStateOf(draft.color) }
    var grants by remember { mutableStateOf(draft.grants) }
    var denies by remember { mutableStateOf(draft.denies) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var holders by remember { mutableStateOf<List<RoleMemberDto>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val mine = r.me.permissions.toSet()
    val editable = role?.canEdit ?: r.me.canManage
    val savedText = stringResource(R.string.roles_saved)
    val staleText = stringResource(R.string.roles_stale)
    val loadHolders = { scope.launch { role?.let { runCatching { vm.c.roles.members(it.id) }.onSuccess { list -> holders = list } } } }
    LaunchedEffect(role?.id) { loadHolders() }

    val canSave = editable && RoleEditorRules.canSave(name, grants, denies, r.catalog, mine, role)
    val changed = role == null || name.trim() != role!!.name || description.trim() != role!!.description.orEmpty() || color != role!!.color ||
        grants != role!!.permissions.toSet() || denies != role!!.denies.toSet()

    fun save() {
        busy = true
        error = null
        notice = null
        scope.launch {
            val current = role
            runCatching {
                if (current == null) {
                    vm.c.roles.create(vm.orgId, CreateRoleReq(name = name.trim(), description = description.trim().ifEmpty { null }, color = color, permissions = grants.toList().sorted(), denies = denies.toList().sorted(), template = draft.template))
                } else {
                    vm.c.roles.update(current.id, PatchRoleReq(
                        baseRev = current.rev, name = name.trim(),
                        description = description.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull,
                        color = color?.let { JsonPrimitive(it) } ?: JsonNull,
                        permissions = grants.toList().sorted(), denies = denies.toList().sorted(),
                    ))
                }
            }.onSuccess { saved -> role = saved; notice = savedText; vm.refresh() }
                .onFailure { e ->
                    if (e is ApiException && e.code == "stale") {
                        // Someone else saved first: show theirs, never silently overwrite it.
                        vm.refresh()
                        notice = staleText
                    }
                    error = e.friendly()
                }
            busy = false
        }
    }

    fun setArchived(archived: Boolean) = scope.launch {
        val cur = role ?: return@launch
        runCatching { vm.c.roles.update(cur.id, PatchRoleReq(baseRev = cur.rev, archived = archived)) }
            .onSuccess { role = it; vm.refresh() }.onFailure { error = it.friendly() }
    }

    GlassSheet(onDismiss, title = role?.name ?: stringResource(R.string.roles_new)) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            // Honest progress: named, what it allows, who holds it.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(if (name.isNotBlank()) "✓ ${stringResource(R.string.roles_name)}" else stringResource(R.string.roles_name), if (name.isNotBlank()) c.success else c.textSecondary, if (name.isNotBlank()) c.successSoft else c.surfaceVariant)
                TagPill("${stringResource(R.string.roles_grants)} ${grants.size} · ${stringResource(R.string.roles_restricts)} ${denies.size}", c.textSecondary, c.surfaceVariant)
                if (role != null) TagPill(stringResource(R.string.roles_people, holders.size), c.textSecondary, c.surfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            GriipTextField(name, { name = it.take(r.limits.nameMax) }, stringResource(R.string.roles_name), helper = stringResource(R.string.roles_name_hint), enabled = editable)
            Spacer(Modifier.height(8.dp))
            GriipTextField(description, { description = it.take(r.limits.descriptionMax) }, stringResource(R.string.roles_description), singleLine = false, minLines = 2, enabled = editable)
            Text(stringResource(R.string.roles_color), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleEditorRules.SWATCHES.forEach { hex ->
                    val col = Color(RoleEditorRules.parseColor(hex)!!)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(col)
                            .then(if (color == hex) Modifier.border(3.dp, c.textPrimary, CircleShape) else Modifier)
                            .pressable(if (editable) ({ color = hex }) else null),
                    )
                }
            }
            error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 10.dp)) }
            notice?.let { StatusBanner(it, BannerKind.Success, Modifier.padding(top = 10.dp)) }

            SectionHeader(stringResource(R.string.roles_grants), Modifier.padding(top = 14.dp, bottom = 4.dp))
            RoleEditorRules.grouped(r.catalog).forEach { (cat, perms) ->
                Text(categoryName(cat), style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp))
                perms.forEach { p ->
                    val lock = RoleEditorRules.grantLock(p, mine, r.me.canDelegate)
                    PermissionSwitch(p, p.key in grants, editable && lock == RoleEditorRules.Lock.None, lock) { on ->
                        grants = if (on) grants + p.key else grants - p.key
                        if (on) denies = denies - p.key
                    }
                }
            }
            SectionHeader(stringResource(R.string.roles_restricts), Modifier.padding(top = 14.dp, bottom = 2.dp))
            Text(stringResource(R.string.roles_restricts_sub), style = Griip.type.caption, color = c.textSecondary)
            r.catalog.filter { it.restrictable }.forEach { p ->
                val lock = RoleEditorRules.denyLock(p, mine)
                PermissionSwitch(p, p.key in denies, editable && lock == RoleEditorRules.Lock.None, lock) { on ->
                    denies = if (on) denies + p.key else denies - p.key
                    if (on) grants = grants - p.key
                }
            }
            Spacer(Modifier.height(12.dp))
            if (editable) PillButton(
                stringResource(if (role == null) R.string.roles_create else R.string.roles_save), { save() },
                Modifier.fillMaxWidth(), enabled = canSave && changed, loading = busy, height = 50.dp,
            )
            val cur = role
            if (cur != null) {
                SectionHeader(stringResource(R.string.roles_holders), Modifier.padding(top = 16.dp, bottom = 4.dp))
                if (holders.isEmpty()) Text(stringResource(R.string.roles_nobody), style = Griip.type.bodySmall, color = c.textSecondary)
                holders.forEach { m ->
                    ListCard(
                        m.displayName.ifBlank { m.username }, subtitle = "@${m.username} · ${roleLabel(m.orgRole)}",
                        trailing = if (cur.canAssign) ({
                            PillButton(stringResource(R.string.roles_unassign), {
                                scope.launch { runCatching { vm.c.roles.unassign(cur.id, m.id) }.onSuccess { loadHolders(); vm.refresh() }.onFailure { error = it.friendly() } }
                            }, tone = ButtonTone.Glass, height = 36.dp)
                        }) else null,
                    )
                }
                if (cur.canAssign && !cur.archived) PillButton(stringResource(R.string.roles_assign), { adding = true }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp)
                if (cur.canEdit) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.roles_clone), {
                            scope.launch {
                                runCatching { vm.c.roles.create(vm.orgId, CreateRoleReq(cloneOf = cur.id)) }.onSuccess { role = it; name = it.name; vm.refresh() }.onFailure { error = it.friendly() }
                            }
                        }, Modifier.weight(1f), tone = ButtonTone.Glass, height = 42.dp)
                        if (cur.archived) PillButton(stringResource(R.string.roles_restore), { setArchived(false) }, Modifier.weight(1f), tone = ButtonTone.Glass, height = 42.dp)
                        else PillButton(stringResource(R.string.roles_archive), { confirm = "archive" }, Modifier.weight(1f), tone = ButtonTone.Danger, height = 42.dp)
                    }
                    if (cur.archived) PillButton(stringResource(R.string.roles_delete), { confirm = "delete" }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Danger, height = 42.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    when (confirm) {
        "archive" -> ConfirmDialog(stringResource(R.string.roles_archive) + " · " + (role?.name ?: ""), stringResource(R.string.roles_archive_body), stringResource(R.string.roles_archive), onConfirm = {
            confirm = null
            setArchived(true)
        }, onDismiss = { confirm = null }, destructive = true)
        "delete" -> ConfirmDialog(stringResource(R.string.roles_delete_confirm_title, role?.name ?: ""), stringResource(R.string.roles_delete_confirm_body), stringResource(R.string.roles_delete), onConfirm = {
            confirm = null
            val cur = role ?: return@ConfirmDialog
            scope.launch { runCatching { vm.c.roles.delete(cur.id) }.onSuccess { vm.refresh(); onDismiss() }.onFailure { error = it.friendly() } }
        }, onDismiss = { confirm = null }, destructive = true)
    }
    if (adding) role?.let { cur ->
        MemberPicker(vm, exclude = holders.map { it.id }.toSet(), onDismiss = { adding = false }) { u ->
            adding = false
            scope.launch { runCatching { vm.c.roles.assign(cur.id, u.id) }.onSuccess { loadHolders(); vm.refresh() }.onFailure { error = it.friendly() } }
        }
    }
}

@Composable
private fun PermissionSwitch(p: PermissionDto, checked: Boolean, enabled: Boolean, lock: RoleEditorRules.Lock, onChange: (Boolean) -> Unit) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = Griip.type.bodyMedium, color = if (enabled || checked) c.textPrimary else c.textSecondary)
                if (p.admin) TagPill(stringResource(R.string.roles_admin_power), c.lavender, c.lavenderSoft)
            }
            Text(p.description, style = Griip.type.caption, color = c.textSecondary)
            val why = when (lock) {
                RoleEditorRules.Lock.NotYours -> stringResource(R.string.roles_beyond)
                RoleEditorRules.Lock.NeedsDelegate -> stringResource(R.string.roles_needs_delegate)
                RoleEditorRules.Lock.OwnerOnly -> stringResource(R.string.roles_owner_only)
                else -> null
            }
            if (why != null && !checked) Text(why, style = Griip.type.caption, color = c.textTertiary)
        }
        GriipSwitch(checked, onChange, enabled = enabled)
    }
}

/** Choosing who gets a role: the workspace's members, searched on the server. */
@Composable
private fun MemberPicker(vm: RolesViewModel, exclude: Set<String>, onDismiss: () -> Unit, onPick: (UserDto) -> Unit) {
    val c = Griip.colors
    var q by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q) { runCatching { vm.c.orgs.members(vm.orgId, q) }.onSuccess { people = it.filter { u -> u.id !in exclude } }.onFailure { error = it.friendly() } }
    GlassSheet(onDismiss, title = stringResource(R.string.roles_assign_pick)) {
        SearchPill(q, { q = it }, stringResource(R.string.roles_assign_pick))
        error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(top = 8.dp)) }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            people.forEach { u ->
                ListCard(u.displayName, subtitle = "@${u.username} · ${roleLabel(u.orgRole)}", onClick = { onPick(u) })
            }
            if (people.isEmpty()) Text("—", style = Griip.type.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
    }
}
