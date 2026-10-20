package com.meldiq.android.ui.screens.workspace.documents

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
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Description
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
import com.meldiq.android.data.api.DocCreateReq
import com.meldiq.android.data.api.DocTemplateDto
import com.meldiq.android.data.api.DocTemplatesRes
import com.meldiq.android.data.api.DocumentDto
import com.meldiq.android.data.api.DocumentRes
import com.meldiq.android.data.api.DocumentsRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * A workspace's documents (round 7 slice C4, docs/DOCUMENTS.md): the ones you may read, newest change first, and the
 * archived ones on their own tab. New documents start from a template, shared with the workspace for comments; the
 * person changes that in Sharing.
 */
@Composable
fun DocumentsScreen(nav: NavHostController, orgId: String, canCreate: Boolean) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf(false) }
    var docs by remember { mutableStateOf<List<DocumentDto>?>(null) }
    var templates by remember { mutableStateOf<List<DocTemplateDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(archived, reload) {
        docs = null
        runCatching { container.api.get<DocumentsRes>("/v1/orgs/$orgId/documents?archived=$archived").documents }
            .onSuccess { docs = it; error = null }.onFailure { error = it.friendly() }
    }
    LaunchedEffect(Unit) { templates = runCatching { container.api.get<DocTemplatesRes>("/v1/documents/templates").templates }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { nav.currentBackStackEntryFlow.drop(1).collect { if (it.destination.route == "documents/{orgId}?create={create}") reload++ } }

    fun create(t: DocTemplateDto) = scope.launch {
        picking = false
        runCatching { container.api.post<DocCreateReq, DocumentRes>("/v1/orgs/$orgId/documents", DocCreateReq(t.title, t.content, "workspace", "comment")).document }
            .onSuccess { nav.navigate(Routes.document(it.id, edit = true)) }
            .onFailure { error = it.friendly() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            item { GriipTopBar(stringResource(R.string.doc_title), onBack = { nav.popBackStack() }) }
            item { Text(stringResource(R.string.doc_label_server), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp)) }
            item {
                ChipRow {
                    PillChip(stringResource(R.string.doc_tab_active), !archived, { archived = false })
                    PillChip(stringResource(R.string.doc_tab_archived), archived, { archived = true })
                }
            }
            error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
            val list = docs
            if (list == null && error == null) item { ShimmerList(4) }
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.Article, stringResource(R.string.doc_empty), stringResource(R.string.doc_empty_body)) }
            items(list.orEmpty(), key = { it.id }) { d ->
                ListCard(
                    d.title, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = listOfNotNull(accessLabel(d), d.updatedBy?.name, TimeFmt.listStamp(d.updatedAt)).joinToString(" · "),
                    leading = { IconTile(Icons.Outlined.Description, c.primary, c.primarySoft, size = 44.dp) },
                    onClick = { nav.navigate(Routes.document(d.id)) },
                )
            }
        }
        if (canCreate && !archived) ExtendedFloatingActionButton(
            onClick = { picking = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.doc_new)) },
            containerColor = c.actionStart, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
    if (picking) {
        GlassSheet({ picking = false }, title = stringResource(R.string.doc_new)) {
            templates.forEach { t -> MenuRow(Icons.Outlined.Description, t.title, onClick = { create(t) }) }
            Spacer(Modifier.height(16.dp).fillMaxWidth())
        }
    }
}

@Composable
internal fun accessLabel(d: DocumentDto): String = when (d.access) {
    "workspace" -> stringResource(R.string.doc_access_workspace)
    "channel" -> d.channel?.name?.let { "#$it" } ?: stringResource(R.string.doc_access_channel)
    else -> stringResource(R.string.doc_access_restricted)
}
