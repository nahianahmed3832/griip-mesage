package com.meldiq.android.ui.screens.workspace.workflows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.rounded.Add
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
import com.meldiq.android.data.api.WfCatalogRes
import com.meldiq.android.data.api.WfInput
import com.meldiq.android.data.api.WorkspaceWorkflowDto
import com.meldiq.android.data.api.WorkspaceWorkflowRes
import com.meldiq.android.data.api.WorkspaceWorkflowsRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * A workspace's workflows (round 7 slice C3, docs/WORKFLOWS.md), for people who may manage them: each one's trigger,
 * whether it's on, how its last run went, and a warning when its owner can no longer run it. New ones start from a
 * template or from scratch.
 */
@Composable
fun WorkflowsScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var flows by remember { mutableStateOf<List<WorkspaceWorkflowDto>?>(null) }
    var catalog by remember { mutableStateOf<WfCatalogRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    val ai = com.meldiq.android.ui.screens.workspace.ai.rememberAiStatus(orgId)
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runCatching {
            catalog = catalog ?: container.api.get<WfCatalogRes>("/v1/orgs/$orgId/workflows/catalog")
            flows = container.api.get<WorkspaceWorkflowsRes>("/v1/orgs/$orgId/workflows").workflows
        }.onFailure { error = it.friendly() }
    }
    val triggerLabel = { type: String -> catalog?.catalog?.triggers?.firstOrNull { it.type == type }?.label ?: type }

    fun toggle(w: WorkspaceWorkflowDto, on: Boolean) = scope.launch {
        val input = WfInput(w.name, w.trigger, w.conditions, w.actions, on, w.rev)
        runCatching { container.api.put<WfInput, WorkspaceWorkflowRes>("/v1/orgs/$orgId/workflows/${w.id}", input).workflow }
            .onSuccess { saved -> flows = flows?.map { if (it.id == saved.id) saved else it } }
            .onFailure { error = it.friendly() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            item { GriipTopBar(stringResource(R.string.wf_title), onBack = { nav.popBackStack() }) }
            item {
                Text(stringResource(R.string.wf_intro), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 4.dp))
            }
            error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
            val list = flows
            if (list == null) {
                if (error == null) item { ShimmerList(3) }
            } else if (list.isEmpty()) {
                item { EmptyState(Icons.Outlined.Bolt, stringResource(R.string.wf_empty), stringResource(R.string.wf_empty_body)) }
            }
            items(list.orEmpty(), key = { it.id }) { w ->
                val input = WfInput(w.name, w.trigger, w.conditions, w.actions, w.enabled, w.rev)
                val status = when {
                    !w.owner.allowed -> stringResource(R.string.wf_owner_lost, w.owner.name ?: "?")
                    w.lastRun?.state == "failed" -> stringResource(R.string.wf_last_failed)
                    w.lastRun != null -> stringResource(R.string.wf_last_run, w.lastRun.state)
                    else -> stringResource(R.string.wf_never_ran)
                }
                ListCard(
                    w.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = "${triggerLabel(w.trigger.type)} · ${WorkflowRules.summary(input)}\n$status",
                    leading = { IconTile(Icons.Outlined.Bolt, if (w.enabled) c.primary else c.textTertiary, if (w.enabled) c.primarySoft else c.surfaceVariant, size = 44.dp) },
                    trailing = { GriipSwitch(w.enabled, { on -> toggle(w, on) }) },
                    onClick = { nav.navigate(Routes.workflowEdit(orgId, w.id)) },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picking = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.wf_new)) },
            containerColor = c.actionStart, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
    if (picking) {
        GlassSheet({ picking = false }, title = stringResource(R.string.wf_new)) {
            catalog?.templates.orEmpty().forEach { t ->
                MenuRow(Icons.Outlined.AutoAwesome, t.name, subtitle = t.description, onClick = { picking = false; nav.navigate(Routes.workflowEdit(orgId, "tpl:${t.id}")) })
            }
            MenuRow(Icons.Rounded.Add, stringResource(R.string.wf_from_scratch), onClick = { picking = false; nav.navigate(Routes.workflowEdit(orgId, "new")) })
            if (ai?.available == true) MenuRow(Icons.Outlined.AutoAwesome, stringResource(R.string.ai_wf_draft), subtitle = stringResource(R.string.ai_wf_off_note), onClick = { picking = false; drafting = true })
            Spacer(Modifier.height(16.dp).fillMaxWidth())
        }
    }
    if (drafting) com.meldiq.android.ui.screens.workspace.ai.AiWorkflowDraftSheet(orgId, onOpen = { draft ->
        drafting = false
        com.meldiq.android.ui.screens.workspace.ai.AiRules.DraftInbox.put(draft)
        nav.navigate(Routes.workflowEdit(orgId, com.meldiq.android.ui.screens.workspace.ai.AiRules.DraftInbox.EDITOR_ID))
    }, onDismiss = { drafting = false })
    // Back from the editor: the list is fetched again.
    LaunchedEffect(Unit) { nav.currentBackStackEntryFlow.drop(1).collect { if (it.destination.route == "workflows/{orgId}") reload++ } }
}
