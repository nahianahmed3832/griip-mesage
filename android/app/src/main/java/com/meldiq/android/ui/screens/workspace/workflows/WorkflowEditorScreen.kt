package com.meldiq.android.ui.screens.workspace.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.WfAction
import com.meldiq.android.data.api.WfCatalogRes
import com.meldiq.android.data.api.WfInput
import com.meldiq.android.data.api.WfRunDto
import com.meldiq.android.data.api.WfRunsRes
import com.meldiq.android.data.api.WfTestDto
import com.meldiq.android.data.api.WfTestRes
import com.meldiq.android.data.api.WorkspaceWorkflowRes
import com.meldiq.android.data.api.WorkspaceWorkflowsRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Create or change one workflow (round 7 slice C3, docs/WORKFLOWS.md): trigger, conditions, actions; Test shows what it
 * would do for a sample event without doing it; Save makes you its owner (it runs with your rights). [workflowId] is a
 * workflow's id, "new", or "tpl:<template id>".
 */
@Composable
fun WorkflowEditorScreen(nav: NavHostController, orgId: String, workflowId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toString() }
    val zone = remember { ZoneId.systemDefault().id }
    var catalog by remember { mutableStateOf<WfCatalogRes?>(null) }
    var draft by remember { mutableStateOf<WfInput?>(null) }
    var existingId by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var runs by remember { mutableStateOf<List<WfRunDto>>(emptyList()) }
    var test by remember { mutableStateOf<WfTestDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(workflowId) {
        runCatching {
            val cat = container.api.get<WfCatalogRes>("/v1/orgs/$orgId/workflows/catalog")
            catalog = cat
            channels = container.orgs.channels(orgId).filter { !it.archived }
            draft = when {
                workflowId == "new" -> WorkflowRules.blank()
                workflowId == com.meldiq.android.ui.screens.workspace.ai.AiRules.DraftInbox.EDITOR_ID -> com.meldiq.android.ui.screens.workspace.ai.AiRules.DraftInbox.take() ?: WorkflowRules.blank()
                workflowId.startsWith("tpl:") -> cat.templates.firstOrNull { it.id == workflowId.removePrefix("tpl:") }?.let { WorkflowRules.fromTemplate(it, null, today, zone) } ?: WorkflowRules.blank()
                else -> container.api.get<WorkspaceWorkflowsRes>("/v1/orgs/$orgId/workflows").workflows.firstOrNull { it.id == workflowId }?.let { w ->
                    existingId = w.id
                    runs = container.api.get<WfRunsRes>("/v1/orgs/$orgId/workflows/${w.id}/runs").runs
                    WfInput(w.name, w.trigger, w.conditions, w.actions, w.enabled, w.rev)
                }
            }
        }.onFailure { error = it.friendly() }
    }
    val d = draft
    val cat = catalog?.catalog
    val channelName = { id: String? -> channels.firstOrNull { it.id == id }?.name?.let { "#$it" } }
    val problems = d?.let { WorkflowRules.problems(it) }.orEmpty()

    fun update(f: (WfInput) -> WfInput) { draft = draft?.let(f); test = null }
    fun setAction(i: Int, a: WfAction) = update { it.copy(actions = it.actions.mapIndexed { j, x -> if (j == i) a else x }) }

    fun save() = scope.launch {
        val input = draft ?: return@launch
        busy = true
        error = null
        val id = existingId
        runCatching {
            if (id == null) container.api.post<WfInput, WorkspaceWorkflowRes>("/v1/orgs/$orgId/workflows", input.copy(rev = null))
            else container.api.put<WfInput, WorkspaceWorkflowRes>("/v1/orgs/$orgId/workflows/$id", input)
        }.onSuccess { nav.popBackStack() }.onFailure { error = it.friendly() }
        busy = false
    }

    fun runTest() = scope.launch {
        val input = draft ?: return@launch
        busy = true
        error = null
        runCatching { container.api.post<WfInput, WfTestRes>("/v1/orgs/$orgId/workflows/test", input.copy(rev = null)).test }
            .onSuccess { test = it }.onFailure { error = it.friendly() }
        busy = false
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            GriipTopBar(stringResource(if (existingId == null) R.string.wf_new else R.string.wf_edit), onBack = { nav.popBackStack() }, actions = {
                if (existingId != null) CircleIconButton(Icons.Outlined.Delete, stringResource(R.string.wf_delete), { confirmDelete = true }, tint = c.danger)
            })
        }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        if (d == null || cat == null) {
            if (error == null) item { ShimmerList(4) }
            return@LazyColumn
        }
        item {
            Section {
                GriipTextField(d.name, { v -> update { it.copy(name = v.take(80)) } }, stringResource(R.string.wf_name))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wf_enabled), style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    GriipSwitch(d.enabled, { on -> update { it.copy(enabled = on) } })
                }
            }
        }
        item { SectionHeader(stringResource(R.string.wf_when), Modifier.padding(top = 10.dp, bottom = 4.dp)) }
        item {
            Section {
                ChipRow { cat.triggers.forEach { t -> PillChip(t.label, d.trigger.type == t.type, { update { WorkflowRules.withTrigger(it, cat, t.type, today, zone) } }) } }
                cat.triggers.firstOrNull { it.type == d.trigger.type }?.let { Text(it.description, style = Griip.type.caption, color = c.textSecondary) }
                d.trigger.schedule?.let { s ->
                    ChipRow { WorkflowRules.RECURRENCES.forEach { r -> PillChip(r, s.recurrence == r, { update { it.copy(trigger = it.trigger.copy(schedule = s.copy(recurrence = r))) } }) } }
                    GriipTextField(s.localTime, { v -> update { it.copy(trigger = it.trigger.copy(schedule = s.copy(localTime = v.take(5)))) } }, stringResource(R.string.wf_time), helper = s.timeZone)
                }
            }
        }
        val fields = WorkflowRules.fieldsFor(cat, d.trigger.type)
        if (fields.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.wf_only_if), Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            item {
                Section {
                    d.conditions.forEachIndexed { i, cond ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(WorkflowRules.fieldLabel(cond.field), style = Griip.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, modifier = Modifier.weight(1f))
                            CircleIconButton(Icons.Rounded.Close, stringResource(R.string.wf_remove), { update { it.copy(conditions = it.conditions.filterIndexed { j, _ -> j != i }) } }, size = 36.dp, tint = c.textSecondary)
                        }
                        ChipRow {
                            WorkflowRules.OPS.forEach { op ->
                                PillChip(WorkflowRules.opLabel(op), cond.op == op, { update { it.copy(conditions = it.conditions.mapIndexed { j, x -> if (j == i) x.copy(op = op) else x }) } })
                            }
                        }
                        GriipTextField(cond.value, { v -> update { it.copy(conditions = it.conditions.mapIndexed { j, x -> if (j == i) x.copy(value = v.take(60)) else x }) } }, stringResource(R.string.wf_value))
                    }
                    if (d.conditions.size < WorkflowRules.MAX_CONDITIONS) ChipRow {
                        fields.forEach { f -> PillChip("+ ${WorkflowRules.fieldLabel(f)}", false, { update { it.copy(conditions = it.conditions + WorkflowRules.condition(f)) } }) }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.wf_then), Modifier.padding(top = 10.dp, bottom = 4.dp)) }
        d.actions.forEachIndexed { i, a ->
            item {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.actions.firstOrNull { it.type == a.type }?.label ?: a.type, style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, modifier = Modifier.weight(1f))
                        if (d.actions.size > 1) CircleIconButton(Icons.Rounded.Close, stringResource(R.string.wf_remove), { update { it.copy(actions = it.actions.filterIndexed { j, _ -> j != i }) } }, size = 36.dp, tint = c.textSecondary)
                    }
                    cat.actions.firstOrNull { it.type == a.type }?.let { Text(it.description, style = Griip.type.caption, color = c.textSecondary) }
                    when (a.type) {
                        "post_channel" -> {
                            PillButton(channelName(a.channelId) ?: stringResource(R.string.wf_pick_channel), { pickFor = i }, tone = ButtonTone.Glass, height = 40.dp)
                            GriipTextField(a.text.orEmpty(), { v -> setAction(i, a.copy(text = v.take(WorkflowRules.MAX_TEXT))) }, stringResource(R.string.wf_message), singleLine = false)
                        }
                        "add_to_channels" -> PillButton(
                            a.channelIds.orEmpty().mapNotNull { channelName(it) }.joinToString(", ").ifEmpty { stringResource(R.string.wf_pick_channels) },
                            { pickFor = i }, tone = ButtonTone.Glass, height = 40.dp,
                        )
                        "email_admins" -> {
                            GriipTextField(a.subject.orEmpty(), { v -> setAction(i, a.copy(subject = v.take(120))) }, stringResource(R.string.wf_subject))
                            GriipTextField(a.text.orEmpty(), { v -> setAction(i, a.copy(text = v.take(WorkflowRules.MAX_TEXT))) }, stringResource(R.string.wf_email_text), singleLine = false)
                        }
                    }
                }
            }
        }
        if (d.actions.size < WorkflowRules.MAX_ACTIONS) item {
            ChipRow { WorkflowRules.actionsFor(d.trigger.type).forEach { t -> PillChip("+ ${cat.actions.firstOrNull { it.type == t }?.label ?: t}", false, { update { it.copy(actions = it.actions + WorkflowRules.newAction(t)) } }) } }
        }
        item {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.firstOrNull()?.let { Text(it, style = Griip.type.caption, color = c.warning) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.wf_test), { runTest() }, Modifier.weight(1f), tone = ButtonTone.Glass, enabled = problems.isEmpty() && !busy, height = 48.dp)
                    PillButton(stringResource(R.string.wf_save), { save() }, Modifier.weight(1f), enabled = problems.isEmpty() && !busy, loading = busy, height = 48.dp)
                }
                Text(stringResource(R.string.wf_owner_note), style = Griip.type.caption, color = c.textSecondary)
            }
        }
        test?.let { t ->
            item { SectionHeader(stringResource(R.string.wf_test_result), Modifier.padding(top = 6.dp, bottom = 4.dp)) }
            item {
                Section {
                    if (!t.conditionsHoldForSample) Text(stringResource(R.string.wf_test_conditions), style = Griip.type.caption, color = c.textSecondary)
                    t.nextRunAt?.let { Text(stringResource(R.string.wf_next_run, TimeFmt.listStamp(it)), style = Griip.type.caption, color = c.textSecondary) }
                    t.outcomes.forEach { o -> Text("• ${o.detail}", style = Griip.type.bodySmall, color = if (o.status == "failed") c.danger else c.textPrimary) }
                }
            }
        }
        if (runs.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.wf_runs), Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            item {
                Section {
                    runs.take(20).forEach { r ->
                        val what = r.event?.member?.name ?: r.event?.channel?.name?.let { "#$it" } ?: ""
                        Text("${TimeFmt.listStamp(r.createdAt)} · ${r.state}${r.errorKind?.let { " ($it)" } ?: ""} ${what}".trim(), style = Griip.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = if (r.state == "failed") c.danger else c.textPrimary)
                        r.outcomes.forEach { o -> Text("   ${o.detail}", style = Griip.type.caption, color = c.textSecondary) }
                    }
                }
            }
        }
    }

    pickFor?.let { i ->
        val a = d?.actions?.getOrNull(i)
        if (a != null) ChannelPicker(
            channels, multi = a.type == "add_to_channels",
            selected = if (a.type == "add_to_channels") a.channelIds.orEmpty().toSet() else setOfNotNull(a.channelId),
            onDone = { picked ->
                setAction(i, if (a.type == "add_to_channels") a.copy(channelIds = picked.toList()) else a.copy(channelId = picked.firstOrNull()))
                pickFor = null
            },
            onDismiss = { pickFor = null },
        )
    }
    if (confirmDelete) {
        GlassSheet({ confirmDelete = false }, title = stringResource(R.string.wf_delete)) {
            Text(stringResource(R.string.wf_delete_body), style = Griip.type.body, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.wf_delete), {
                scope.launch {
                    runCatching { container.api.delete<kotlinx.serialization.json.JsonObject>("/v1/orgs/$orgId/workflows/$existingId") }.onSuccess { nav.popBackStack() }.onFailure { error = it.friendly(); confirmDelete = false }
                }
            }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, height = 48.dp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ChannelPicker(channels: List<ConversationDto>, multi: Boolean, selected: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(selected) }
    GlassSheet(onDismiss, title = stringResource(if (multi) R.string.wf_pick_channels else R.string.wf_pick_channel)) {
        channels.take(100).forEach { ch ->
            val on = ch.id in picked
            // The whole row toggles, not only the box.
            Row(
                Modifier.fillMaxWidth().toggleable(on, role = androidx.compose.ui.semantics.Role.Checkbox) { now ->
                    picked = if (multi) (if (now) picked + ch.id else picked - ch.id) else setOf(ch.id)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(on, null)
                Text("#${ch.name.orEmpty()}" + (ch.kind?.takeIf { it != "PUBLIC" }?.let { " · ${it.lowercase()}" } ?: ""), style = Griip.type.body, color = Griip.colors.textPrimary)
            }
        }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.wf_done), { onDone(picked) }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty(), height = 48.dp)
        Spacer(Modifier.height(16.dp))
    }
}
