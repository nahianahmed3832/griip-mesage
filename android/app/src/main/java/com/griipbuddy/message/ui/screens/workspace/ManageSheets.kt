package com.griipbuddy.message.ui.screens.workspace

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.data.api.CreateChannelReq
import com.griipbuddy.message.data.api.DepartmentDto
import com.griipbuddy.message.data.api.OrgDetailRes
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.ButtonTone
import com.griipbuddy.message.ui.components.ConfirmDialog
import com.griipbuddy.message.ui.components.ChipRow
import com.griipbuddy.message.ui.components.GlassSheet
import com.griipbuddy.message.ui.components.GriipTextField
import com.griipbuddy.message.ui.components.PillButton
import com.griipbuddy.message.ui.components.PillChip
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.friendly
import com.griipbuddy.message.ui.theme.Griip
import kotlinx.coroutines.launch

@Composable
private fun Label(text: String) {
    Text(text, style = Griip.type.label, color = Griip.colors.textSecondary)
    Spacer(Modifier.height(6.dp))
}

@Composable
fun CreateBranchSheet(d: OrgDetailRes, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = "New branch in ${d.org.name}") {
        GriipTextField(name, { name = it.take(80) }, "Branch name", placeholder = "Bangladesh", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        GriipTextField(location, { location = it.take(80) }, "Location (optional)", placeholder = "Dhaka")
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton("Create branch", {
            busy = true
            scope.launch {
                runCatching { container.orgs.createBranch(d.org.id, name.trim(), location.trim().ifEmpty { null }) }
                    .onSuccess { onDone(); onDismiss() }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = busy, height = 50.dp)
    }
}

@Composable
fun CreateDepartmentSheet(d: OrgDetailRes, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val branches = d.branches.filter { it.canManage }
    var branch by remember { mutableStateOf(branches.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss, title = "New department") {
        Label("Branch")
        ChipRow { branches.forEach { b -> PillChip(b.name, branch == b.id, { branch = b.id }) } }
        Spacer(Modifier.height(12.dp))
        GriipTextField(name, { name = it.take(80) }, "Department name", placeholder = "Operations", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton("Create department", {
            scope.launch {
                runCatching { container.orgs.createDepartment(branch!!, name.trim()) }.onSuccess { onDone(); onDismiss() }.onFailure { error = it.friendly() }
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && branch != null, height = 50.dp)
    }
}

/** Rename a department, change its description, or delete it (Phase 16). Branch managers only; the server enforces it. */
@Composable
fun EditDepartmentSheet(d: OrgDetailRes, dept: DepartmentDto, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(dept.name) }
    var desc by remember { mutableStateOf(dept.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val teams = d.teams.count { it.departmentId == dept.id }
    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            runCatching { block() }.onSuccess { onDone(); onDismiss() }.onFailure { error = it.friendly() }
            busy = false
        }
    }
    GlassSheet(onDismiss, title = "Edit department") {
        GriipTextField(name, { name = it.take(80) }, "Department name", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        GriipTextField(desc, { desc = it.take(300) }, "Description (optional)", singleLine = false, minLines = 2)
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton("Save", { run { container.orgs.updateDepartment(dept.id, name.trim(), desc.trim().ifEmpty { null }) } },
            Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !busy, loading = busy, height = 50.dp)
        Spacer(Modifier.height(10.dp))
        PillButton("Delete department", { confirmDelete = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, enabled = !busy, height = 50.dp)
    }
    if (confirmDelete) ConfirmDialog(
        "Delete ${dept.name}?",
        if (teams == 0) "It has no teams. Nobody loses access." else "Its $teams team${if (teams == 1) "" else "s"} stay in the branch, without a department. Nobody loses access.",
        "Delete",
        onConfirm = { confirmDelete = false; run { container.orgs.deleteDepartment(dept.id) } },
        onDismiss = { confirmDelete = false },
        destructive = true,
    )
}

@Composable
fun CreateTeamSheet(d: OrgDetailRes, preselectBranch: String?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val branches = d.branches.filter { it.canManage }
    var branch by remember { mutableStateOf(preselectBranch?.takeIf { id -> branches.any { it.id == id } } ?: if (d.permissions.canManageOrg) null else branches.firstOrNull()?.id) }
    var dept by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss, title = "New team") {
        Label("Where")
        ChipRow {
            if (d.permissions.canManageOrg) PillChip("Cross-branch", branch == null, { branch = null; dept = null })
            branches.forEach { b -> PillChip(b.name, branch == b.id, { branch = b.id; dept = null }) }
        }
        val depts = d.departments.filter { it.branchId == branch }
        if (depts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Label("Department (optional)")
            ChipRow {
                PillChip("None", dept == null, { dept = null })
                depts.forEach { dp -> PillChip(dp.name, dept == dp.id, { dept = dp.id }) }
            }
        }
        Spacer(Modifier.height(12.dp))
        GriipTextField(name, { name = it.take(80) }, "Team name", placeholder = "Customer Support", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        GriipTextField(desc, { desc = it.take(300) }, "Description (optional)", singleLine = false, minLines = 2)
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton("Create team", {
            scope.launch {
                runCatching { container.orgs.createTeam(d.org.id, name.trim(), desc.trim().ifEmpty { null }, branch, dept) }
                    .onSuccess { onDone(); onDismiss() }.onFailure { error = it.friendly() }
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), height = 50.dp)
    }
}

@Composable
fun CreateChannelSheet(d: OrgDetailRes, preselectBranch: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val branches = d.branches.filter { it.canManage }
    val teams = d.teams.filter { it.canManage }
    var scopeKind by remember { mutableStateOf(if (d.permissions.canManageOrg) "ORG" else if (branches.isNotEmpty()) "BRANCH" else "TEAM") }
    var scopeId by remember { mutableStateOf(preselectBranch?.takeIf { id -> branches.any { it.id == id } } ?: branches.firstOrNull()?.id) }
    var kind by remember { mutableStateOf("PUBLIC") }
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = "New channel") {
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            Label("Visible to")
            ChipRow {
                if (d.permissions.canManageOrg) PillChip("Whole organization", scopeKind == "ORG", { scopeKind = "ORG"; scopeId = null })
                if (branches.isNotEmpty()) PillChip("A branch", scopeKind == "BRANCH", { scopeKind = "BRANCH"; scopeId = branches.first().id })
                if (teams.isNotEmpty()) PillChip("A team", scopeKind == "TEAM", { scopeKind = "TEAM"; scopeId = teams.first().id })
            }
            if (scopeKind == "BRANCH") {
                Spacer(Modifier.height(8.dp))
                ChipRow { branches.forEach { b -> PillChip(b.name, scopeId == b.id, { scopeId = b.id }) } }
            }
            if (scopeKind == "TEAM") {
                Spacer(Modifier.height(8.dp))
                ChipRow { teams.forEach { t -> PillChip(t.name, scopeId == t.id, { scopeId = t.id }) } }
            }
            Spacer(Modifier.height(12.dp))
            Label("Type")
            ChipRow {
                PillChip("Public", kind == "PUBLIC", { kind = "PUBLIC" }, icon = Icons.Outlined.Tag)
                PillChip("Private", kind == "PRIVATE", { kind = "PRIVATE" }, icon = Icons.Outlined.Lock)
                PillChip("Announcements", kind == "ANNOUNCEMENT", { kind = "ANNOUNCEMENT" }, icon = Icons.Outlined.Campaign)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (kind) {
                    "PRIVATE" -> "Only people you add can see and read it — even admins aren't added automatically."
                    "ANNOUNCEMENT" -> "Everyone in scope is added automatically; only managers can post."
                    else -> "Everyone in scope can find and join it."
                },
                style = Griip.type.caption, color = Griip.colors.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            GriipTextField(name, { name = it.lowercase().replace(' ', '-').take(60) }, "Channel name", placeholder = "support-desk", prefix = "#")
            Spacer(Modifier.height(12.dp))
            GriipTextField(topic, { topic = it.take(300) }, "Topic (optional)")
            error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(16.dp))
            PillButton("Create channel", {
                busy = true
                scope.launch {
                    runCatching { container.orgs.createChannel(d.org.id, CreateChannelReq(name.trim(), topic.trim().ifEmpty { null }, scopeKind, scopeId, kind)) }
                        .onSuccess { onDismiss(); onDone(it.id) }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && (scopeKind == "ORG" || scopeId != null), loading = busy, height = 50.dp)
        }
    }
}
