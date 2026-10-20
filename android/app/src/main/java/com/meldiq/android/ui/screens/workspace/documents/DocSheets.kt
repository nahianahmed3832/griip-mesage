package com.meldiq.android.ui.screens.workspace.documents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.DocCommentDto
import com.meldiq.android.data.api.DocCommentReq
import com.meldiq.android.data.api.DocCommentsRes
import com.meldiq.android.data.api.DocMemberInput
import com.meldiq.android.data.api.DocResolveReq
import com.meldiq.android.data.api.DocSharingReq
import com.meldiq.android.data.api.DocVersionDto
import com.meldiq.android.data.api.DocVersionRes
import com.meldiq.android.data.api.DocVersionsRes
import com.meldiq.android.data.api.DocumentDto
import com.meldiq.android.data.api.DocumentRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

@Composable
internal fun CommentsSheet(doc: DocumentDto, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<DocCommentDto>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { runCatching { container.api.get<DocCommentsRes>("/v1/documents/${doc.id}/comments").comments }.onSuccess { comments = it }.onFailure { error = it.friendly() } }
    fun call(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { error = it.friendly() } }
    val canComment = DocRules.atLeast(doc.role, "comment") && !doc.archived

    GlassSheet(onDismiss, title = stringResource(R.string.doc_comments)) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (comments.isEmpty()) Text(stringResource(R.string.doc_no_comments), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
            comments.forEach { cm ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${cm.author?.name.orEmpty()} · ${TimeFmt.listStamp(cm.createdAt)}", style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, modifier = Modifier.weight(1f))
                        if (canComment) CircleIconButton(Icons.Outlined.CheckCircle, stringResource(if (cm.resolved) R.string.doc_reopen else R.string.doc_resolve), {
                            call { container.api.post<DocResolveReq, OkRes>("/v1/document-comments/${cm.id}/resolve", DocResolveReq(!cm.resolved)) }
                        }, size = 36.dp, tint = if (cm.resolved) c.success else c.textTertiary)
                        if (cm.canDelete) CircleIconButton(Icons.Outlined.Delete, stringResource(R.string.doc_delete_comment), { call { container.api.delete<OkRes>("/v1/document-comments/${cm.id}") } }, size = 36.dp, tint = c.textTertiary)
                    }
                    cm.quote?.let { Text("“$it”", style = Griip.type.caption, color = c.textTertiary) }
                    Text(cm.body, style = Griip.type.body, color = if (cm.resolved) c.textSecondary else c.textPrimary)
                }
            }
        }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger) }
        if (canComment) {
            GriipTextField(draft, { draft = it.take(2000) }, stringResource(R.string.doc_add_comment), singleLine = false)
            Spacer(Modifier.height(6.dp))
            PillButton(stringResource(R.string.doc_post_comment), {
                val body = draft.trim()
                call { container.api.post<DocCommentReq, kotlinx.serialization.json.JsonObject>("/v1/documents/${doc.id}/comments", DocCommentReq(body)); draft = "" }
            }, Modifier.fillMaxWidth(), enabled = draft.isNotBlank(), height = 44.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun VersionsSheet(doc: DocumentDto, onRestored: (DocumentDto) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<DocVersionDto>>(emptyList()) }
    var open by remember { mutableStateOf<DocVersionDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.id) { runCatching { container.api.get<DocVersionsRes>("/v1/documents/${doc.id}/versions").versions }.onSuccess { versions = it }.onFailure { error = it.friendly() } }
    GlassSheet(onDismiss, title = open?.let { stringResource(R.string.doc_version_n, it.rev) } ?: stringResource(R.string.doc_versions)) {
        val v = open
        if (v == null) {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                versions.forEach { ver ->
                    PillButton(
                        "${stringResource(R.string.doc_version_n, ver.rev)} · ${ver.author?.name.orEmpty()} · ${TimeFmt.listStamp(ver.createdAt)}",
                        { scope.launch { runCatching { container.api.get<DocVersionRes>("/v1/documents/${doc.id}/versions/${ver.rev}").version }.onSuccess { open = it }.onFailure { error = it.friendly() } } },
                        Modifier.fillMaxWidth().padding(vertical = 3.dp), tone = ButtonTone.Glass, height = 40.dp,
                    )
                }
            }
        } else {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(v.title, style = Griip.type.titleSmall, color = c.textPrimary)
                MarkdownView(v.content.orEmpty(), Modifier.padding(vertical = 6.dp))
            }
            if (DocRules.atLeast(doc.role, "edit") && !doc.archived && v.rev != doc.rev) PillButton(stringResource(R.string.doc_restore_version), {
                scope.launch { runCatching { container.api.post<DocumentRes>("/v1/documents/${doc.id}/versions/${v.rev}/restore").document }.onSuccess(onRestored).onFailure { error = it.friendly() } }
            }, Modifier.fillMaxWidth(), height = 44.dp)
            PillButton(stringResource(R.string.doc_back_to_versions), { open = null }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 40.dp)
        }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun SharingSheet(doc: DocumentDto, onSaved: (DocumentDto) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(doc.access) }
    var defaultRole by remember { mutableStateOf(doc.defaultRole) }
    var channelId by remember { mutableStateOf(doc.channel?.id) }
    var members by remember { mutableStateOf(doc.members.orEmpty().map { DocMemberInput(it.userId, it.role) }) }
    val names = remember { mutableStateOf(doc.members.orEmpty().associate { it.userId to (it.name ?: it.username ?: it.userId) }) }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.orgId) { channels = runCatching { container.orgs.channels(doc.orgId).filter { it.joined && !it.archived } }.getOrDefault(emptyList()) }
    LaunchedEffect(query) {
        found = if (query.trim().length < 2) emptyList() else runCatching { container.orgs.members(doc.orgId, query.trim()) }.getOrDefault(emptyList())
    }
    GlassSheet(onDismiss, title = stringResource(R.string.doc_sharing)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.doc_who_can_open), style = Griip.type.label, color = c.textSecondary)
            ChipRow { DocRules.ACCESS.forEach { a -> PillChip(accessName(a), access == a, { access = a }) } }
            if (access == "channel") ChipRow { channels.forEach { ch -> PillChip("#${ch.name.orEmpty()}", channelId == ch.id, { channelId = ch.id }) } }
            if (access != "restricted") {
                Text(stringResource(R.string.doc_everyone_can), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                ChipRow { DocRules.DEFAULT_ROLES.forEach { r -> PillChip(roleLabel(r), defaultRole == r, { defaultRole = r }) } }
            }
            Text(stringResource(R.string.doc_people), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names.value[m.userId] ?: m.userId, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.Close, stringResource(R.string.doc_remove_person), { members = members.filterNot { it.userId == m.userId } }, size = 36.dp, tint = c.textSecondary)
                }
                ChipRow { DocRules.MEMBER_ROLES.forEach { r -> PillChip(roleLabel(r), m.role == r, { members = members.map { if (it.userId == m.userId) it.copy(role = r) else it } }) } }
            }
            SearchPill(query, { query = it }, stringResource(R.string.doc_add_person))
            found.filter { u -> members.none { it.userId == u.id } }.take(6).forEach { u ->
                PillButton("${u.displayName} · @${u.username}", {
                    members = members + DocMemberInput(u.id, "comment"); names.value = names.value + (u.id to u.displayName); query = ""
                }, Modifier.fillMaxWidth().padding(vertical = 2.dp), tone = ButtonTone.Glass, height = 40.dp)
            }
        }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger) }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.doc_save), {
            scope.launch {
                runCatching { container.api.put<DocSharingReq, DocumentRes>("/v1/documents/${doc.id}/sharing", DocSharingReq(access, defaultRole, channelId.takeIf { access == "channel" }, members)).document }
                    .onSuccess(onSaved).onFailure { error = it.friendly() }
            }
        }, Modifier.fillMaxWidth(), enabled = access != "channel" || channelId != null, height = 48.dp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun accessName(a: String): String = stringResource(
    when (a) {
        "workspace" -> R.string.doc_access_workspace
        "channel" -> R.string.doc_access_channel
        else -> R.string.doc_access_restricted
    },
)
