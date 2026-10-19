package com.griipbuddy.orbit.ui.screens.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.api.OrgDetailRes
import com.griipbuddy.orbit.data.api.UserDto
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.components.ChipRow
import com.griipbuddy.orbit.ui.components.ConfirmDialog
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.PillChip
import com.griipbuddy.orbit.ui.components.RoleBadge
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.friendly
import com.griipbuddy.orbit.ui.theme.Griip
import kotlinx.coroutines.launch

/** An organization role's name in the interface language. */
@Composable
fun roleLabel(role: String?): String = when (role) {
    WorkspaceRoles.OWNER -> stringResource(R.string.role_owner)
    WorkspaceRoles.ADMIN -> stringResource(R.string.role_admin)
    WorkspaceRoles.GUEST -> stringResource(R.string.role_guest)
    else -> stringResource(R.string.role_member)
}

/** What a role may do, in one sentence (the presets, docs/WORKSPACE_ADMINISTRATION.md). */
@Composable
fun roleSummary(role: String): String = when (role) {
    WorkspaceRoles.OWNER -> stringResource(R.string.role_owner_sum)
    WorkspaceRoles.ADMIN -> stringResource(R.string.role_admin_sum)
    WorkspaceRoles.GUEST -> stringResource(R.string.role_guest_sum)
    else -> stringResource(R.string.role_member_sum)
}

/** "What each role can do": the four role presets, closed until tapped. */
@Composable
fun RolesGuideCard(modifier: Modifier = Modifier) {
    val c = Griip.colors
    var open by rememberSaveable { mutableStateOf(false) }
    GlassCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().pressable({ open = !open }), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AdminPanelSettings, null, tint = c.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.roles_guide_title), style = Griip.type.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.textSecondary)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp)) {
                WorkspaceRoles.ALL.forEach { role ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        RoleBadge(role, Modifier.width(78.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(roleSummary(role), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
                Text(stringResource(R.string.roles_guide_note), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * A member's organization role: the current one, plus only the roles you may give them ([WorkspaceRoles.assignable]).
 * Stepping down, making someone an owner and making someone a guest ask first. [onRole] reports a change the server
 * accepted.
 */
@Composable
fun OrgRoleSection(d: OrgDetailRes, u: UserDto, ownerCount: Int?, onRole: (String) -> Unit, onError: (String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val me = container.session.session?.userId
    val self = u.id == me
    val current = u.orgRole ?: WorkspaceRoles.MEMBER
    val options = WorkspaceRoles.assignable(d.org.myRole, current, self, ownerCount)
    var pending by remember { mutableStateOf<String?>(null) }
    fun apply(role: String) = scope.launch {
        runCatching { container.orgs.setOrgRole(d.org.id, u.id, role) }.onSuccess { onRole(role) }.onFailure { onError(it.friendly()) }
    }
    fun choose(role: String) {
        val risky = self || role == WorkspaceRoles.OWNER || role == WorkspaceRoles.GUEST
        if (risky) pending = role else apply(role)
    }

    SectionHeader(stringResource(R.string.member_role_section), Modifier.padding(top = 14.dp, bottom = 6.dp))
    ChipRow(Modifier.padding(horizontal = 0.dp)) {
        PillChip(roleLabel(current), true, {})
        options.forEach { r -> PillChip(roleLabel(r), false, { choose(r) }) }
    }
    Spacer(Modifier.height(6.dp))
    val note = when {
        options.isNotEmpty() -> roleSummary(current)
        self && current == WorkspaceRoles.OWNER -> stringResource(R.string.member_role_last_owner)
        else -> stringResource(R.string.member_role_none)
    }
    Text(note, style = Griip.type.caption, color = c.textTertiary)

    pending?.let { role ->
        val (title, body) = when {
            self -> stringResource(R.string.member_step_down_title, roleLabel(role)) to stringResource(R.string.member_step_down_body, d.org.name)
            role == WorkspaceRoles.OWNER -> stringResource(R.string.member_owner_title, u.displayName) to stringResource(R.string.member_owner_body)
            else -> stringResource(R.string.member_make_guest_title, u.displayName) to stringResource(R.string.member_guest_body, d.org.name)
        }
        ConfirmDialog(
            title, body, stringResource(R.string.member_confirm),
            onConfirm = { pending = null; apply(role) }, onDismiss = { pending = null }, destructive = self || role == WorkspaceRoles.GUEST,
        )
    }
}

/** A member's job title: editable by themselves, owners, and admins for members and guests. */
@Composable
fun OrgTitleField(d: OrgDetailRes, u: UserDto, onSaved: () -> Unit, onError: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val self = u.id == container.session.session?.userId
    if (!WorkspaceRoles.canChangeTitle(d.org.myRole, u.orgRole, self)) return
    var title by rememberSaveable(u.id) { mutableStateOf(u.title.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    GriipTextField(title, { title = it.take(TITLE_MAX) }, stringResource(R.string.member_title_label))
    Spacer(Modifier.height(8.dp))
    PillButton(stringResource(R.string.member_title_save), {
        busy = true
        scope.launch {
            runCatching { container.orgs.setOrgTitle(d.org.id, u.id, title) }.onSuccess { onSaved() }.onFailure { onError(it.friendly()) }
            busy = false
        }
    }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, enabled = title.trim() != u.title.orEmpty().trim(), loading = busy, height = 44.dp)
}

/** Titles are short labels ("Support lead"); the server accepts up to 500 characters. */
private const val TITLE_MAX = 120
