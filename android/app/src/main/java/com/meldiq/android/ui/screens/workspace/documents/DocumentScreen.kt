package com.meldiq.android.ui.screens.workspace.documents

import android.content.Intent
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.DocUpdateReq
import com.meldiq.android.data.api.DocumentDto
import com.meldiq.android.data.api.DocumentRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * One document (round 7 slice C4, docs/DOCUMENTS.md): read, edit (with a stale-save check, never a silent overwrite),
 * comments, versions, sharing, export, archive. What you can do follows your role on it; the server checks again.
 */
@Composable
fun DocumentScreen(nav: NavHostController, docId: String, startEditing: Boolean) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<DocumentDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var stale by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(docId, reload) {
        runCatching { container.api.get<DocumentRes>("/v1/documents/$docId").document }
            .onSuccess { d ->
                doc = d
                error = null
                stale = false
                if (!editing) { title = d.title; content = d.content.orEmpty() }
                if (startEditing && reload == 0 && DocRules.atLeast(d.role, "edit") && !d.archived) editing = true
            }
            .onFailure { error = it.friendly() }
    }
    val d = doc
    val canEdit = d != null && DocRules.atLeast(d.role, "edit") && !d.archived
    val isAdmin = d != null && DocRules.atLeast(d.role, "admin")

    fun save() = scope.launch {
        val cur = doc ?: return@launch
        busy = true
        runCatching { container.api.put<DocUpdateReq, DocumentRes>("/v1/documents/${cur.id}", DocUpdateReq(cur.rev, title.trim().ifEmpty { cur.title }, content)).document }
            .onSuccess { doc = it; editing = false; error = null }
            .onFailure { e -> if (e is ApiException && e.code == "stale_revision") stale = true else error = e.friendly() }
        busy = false
    }

    fun act(path: String, then: (DocumentDto?) -> Unit = { doc = it }) = scope.launch {
        sheet = null
        runCatching { container.api.post<DocumentRes>(path).document }.onSuccess(then).onFailure { error = it.friendly() }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            GriipTopBar(if (editing) stringResource(R.string.doc_editing) else d?.title.orEmpty(), onBack = { nav.popBackStack() }, actions = {
                if (d != null && !editing) {
                    CircleIconButton(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.doc_comments), { sheet = "comments" }, tint = c.textSecondary)
                    if (canEdit) CircleIconButton(Icons.Outlined.Edit, stringResource(R.string.doc_edit), { title = d.title; content = d.content.orEmpty(); editing = true }, tint = c.primary)
                    CircleIconButton(Icons.Outlined.MoreVert, stringResource(R.string.doc_more), { sheet = "more" }, tint = c.textSecondary)
                }
            })
        }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        if (stale) item {
            StatusBanner(stringResource(R.string.doc_stale), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp), action = stringResource(R.string.doc_reload), onAction = {
                editing = false; reload++
            })
        }
        if (d == null) {
            if (error == null) item { ShimmerList(5) }
            return@LazyColumn
        }
        item {
            Text(
                listOfNotNull(accessLabel(d), roleLabel(d.role), d.updatedBy?.name?.let { stringResource(R.string.doc_edited_by, it, TimeFmt.listStamp(d.updatedAt)) }, stringResource(R.string.doc_label_short))
                    .joinToString(" · "),
                style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp),
            )
        }
        if (d.archived) item { StatusBanner(stringResource(R.string.doc_archived_note), BannerKind.Info, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        if (editing) {
            item {
                Column(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GriipTextField(title, { title = it.take(DocRules.MAX_TITLE) }, stringResource(R.string.doc_field_title))
                    GriipTextField(content, { content = it.take(DocRules.MAX_CONTENT) }, stringResource(R.string.doc_field_content), singleLine = false, helper = stringResource(R.string.doc_markdown_help))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.doc_cancel), { editing = false; title = d.title; content = d.content.orEmpty() }, Modifier.weight(1f), tone = ButtonTone.Glass, height = 48.dp)
                        PillButton(stringResource(R.string.doc_save), { save() }, Modifier.weight(1f), enabled = title.isNotBlank() && !busy, loading = busy, height = 48.dp)
                    }
                }
            }
        } else {
            item { MarkdownView(d.content.orEmpty(), Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 8.dp)) }
        }
    }

    val ai = com.meldiq.android.ui.screens.workspace.ai.rememberAiStatus(doc?.orgId)
    when (sheet) {
        "ai" -> doc?.let { com.meldiq.android.ui.screens.workspace.ai.AiDocumentSummarySheet(it.orgId, docId, it.title) { sheet = null } }
        "comments" -> d?.let { CommentsSheet(it, onDismiss = { sheet = null }) }
        "versions" -> d?.let { VersionsSheet(it, onRestored = { restored -> doc = restored; sheet = null }, onDismiss = { sheet = null }) }
        "sharing" -> d?.let { SharingSheet(it, onSaved = { saved -> doc = saved; sheet = null }, onDismiss = { sheet = null }) }
        "delete" -> GlassSheet({ sheet = null }, title = stringResource(R.string.doc_delete)) {
            Text(stringResource(R.string.doc_delete_body), style = Griip.type.body, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.doc_delete), {
                scope.launch { runCatching { container.api.delete<OkRes>("/v1/documents/$docId") }.onSuccess { nav.popBackStack() }.onFailure { error = it.friendly(); sheet = null } }
            }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, height = 48.dp)
            Spacer(Modifier.height(16.dp))
        }
        "more" -> if (d != null) GlassSheet({ sheet = null }) {
            if (ai?.available == true) MenuRow(Icons.Outlined.AutoAwesome, stringResource(R.string.ai_doc_summary), onClick = { sheet = "ai" })
            MenuRow(Icons.Outlined.History, stringResource(R.string.doc_versions), onClick = { sheet = "versions" })
            if (isAdmin) MenuRow(Icons.Outlined.PersonAdd, stringResource(R.string.doc_sharing), onClick = { sheet = "sharing" })
            MenuRow(Icons.Outlined.IosShare, stringResource(R.string.doc_export), subtitle = stringResource(R.string.doc_export_sub), onClick = {
                sheet = null
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, d.title).putExtra(Intent.EXTRA_TEXT, "# ${d.title}\n\n${d.content.orEmpty()}")
                runCatching { ctx.startActivity(Intent.createChooser(send, d.title)) }
            })
            if (isAdmin) {
                if (d.archived) MenuRow(Icons.Outlined.Unarchive, stringResource(R.string.doc_restore), onClick = { act("/v1/documents/$docId/restore") })
                else MenuRow(Icons.Outlined.Archive, stringResource(R.string.doc_archive), onClick = { act("/v1/documents/$docId/archive") })
                MenuRow(Icons.Outlined.Delete, stringResource(R.string.doc_delete), tint = c.danger, tintBg = c.dangerSoft, onClick = { sheet = "delete" })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun roleLabel(role: String): String = stringResource(
    when (role) {
        "admin" -> R.string.doc_role_admin
        "edit" -> R.string.doc_role_edit
        "comment" -> R.string.doc_role_comment
        else -> R.string.doc_role_view
    },
)
