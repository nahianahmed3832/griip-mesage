package com.meldiq.android.ui.screens.workspace.ai

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.AiAnswerDto
import com.meldiq.android.data.api.AiAnswerRes
import com.meldiq.android.data.api.AiAskDto
import com.meldiq.android.data.api.AiAskReq
import com.meldiq.android.data.api.AiAskRes
import com.meldiq.android.data.api.AiCatchUpMessage
import com.meldiq.android.data.api.AiCatchUpReq
import com.meldiq.android.data.api.AiConsentReq
import com.meldiq.android.data.api.AiDraftDto
import com.meldiq.android.data.api.AiDraftReq
import com.meldiq.android.data.api.AiDraftRes
import com.meldiq.android.data.api.AiStatusDto
import com.meldiq.android.data.api.AiStatusRes
import com.meldiq.android.data.api.AiWriteReq
import com.meldiq.android.data.api.WfInput
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The AI sheets (docs/AI.md, round 7 slice C8). Each one shows exactly what will be sent and to which provider, and
 * sends nothing until the person presses "Send to …": that press is their consent for this one request.
 */

/** The workspace's AI status, or null while loading or on a server without AI. */
@Composable
fun rememberAiStatus(orgId: String?): AiStatusDto? {
    val container = LocalContainer.current
    var status by remember(orgId) { mutableStateOf<AiStatusDto?>(null) }
    LaunchedEffect(orgId) {
        val id = orgId ?: return@LaunchedEffect
        if (container.features.has("ai") != true) return@LaunchedEffect
        status = runCatching { container.api.get<AiStatusRes>("/v1/orgs/$id/ai").ai }.getOrNull()
    }
    return status
}

@Composable
fun aiReasonText(reason: String?): String? = when (reason) {
    null -> null
    "not_configured" -> stringResource(R.string.ai_reason_not_configured)
    "guest" -> stringResource(R.string.ai_reason_guest)
    else -> stringResource(R.string.ai_reason_workspace_off)
}

/**
 * The frame every AI sheet shares: why AI isn't available, the input, the disclosure and the Send button, the answer
 * with Copy (and Use, when [onUse] is given). [send] runs the request with consent given.
 */
@Composable
private fun AiFrame(
    title: String,
    status: AiStatusDto?,
    canSend: Boolean,
    onDismiss: () -> Unit,
    send: suspend () -> AiAnswerDto?,
    onUse: ((String) -> Unit)? = null,
    extraNote: String? = null,
    input: @Composable () -> Unit,
    after: @Composable (AiAnswerDto?) -> Unit = {},
) {
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<AiAnswerDto?>(null) }
    var copied by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = title) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            val provider = status?.provider
            aiReasonText(status?.reason)?.let { StatusBanner(it, BannerKind.Info, Modifier.padding(bottom = 8.dp)) }
            input()
            error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(vertical = 6.dp)) }
            val shown = answer
            if (shown == null && status?.available == true && provider != null) {
                extraNote?.let { Text(it, style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp)) }
                Text(stringResource(R.string.ai_consent, provider.name), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
                PillButton(
                    stringResource(R.string.ai_send, provider.name),
                    {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { send() }.onSuccess { answer = it }.onFailure { error = it.friendly() }
                            busy = false
                        }
                    },
                    Modifier.fillMaxWidth(), enabled = canSend, loading = busy,
                )
            }
            if (shown != null) {
                SelectionContainer { Text(shown.text, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.padding(vertical = 8.dp)) }
                Text(stringResource(R.string.ai_result_note, shown.provider.name), style = Griip.type.caption, color = c.textTertiary)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        stringResource(if (copied) R.string.ai_copied else R.string.ai_copy),
                        { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("ai", shown.text).toClipEntry()); copied = true } },
                        Modifier.weight(1f), tone = ButtonTone.Glass, height = 44.dp,
                    )
                    onUse?.let { use -> PillButton(stringResource(R.string.ai_use), { use(shown.text) }, Modifier.weight(1f), height = 44.dp) }
                }
            }
            after(shown)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Writing help in the composer: the current draft, rewritten; "Use this" replaces the draft. */
@Composable
fun AiWriteSheet(orgId: String, draft: String, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val status = rememberAiStatus(orgId)
    var mode by remember { mutableStateOf(AiRules.WRITE_MODES.first()) }
    val labels = mapOf(
        "improve" to stringResource(R.string.ai_mode_improve),
        "shorter" to stringResource(R.string.ai_mode_shorter),
        "friendlier" to stringResource(R.string.ai_mode_friendlier),
        "formal" to stringResource(R.string.ai_mode_formal),
        "fix_grammar" to stringResource(R.string.ai_mode_fix_grammar),
    )
    AiFrame(
        stringResource(R.string.ai_write_title), status, canSend = draft.isNotBlank(), onDismiss = onDismiss, onUse = onUse,
        send = { container.api.post<AiWriteReq, AiAnswerRes>("/v1/orgs/$orgId/ai/write", AiWriteReq(true, mode, draft.trim())).result },
        input = {
            if (draft.isBlank()) {
                Text(stringResource(R.string.ai_write_empty), style = Griip.type.body, color = Griip.colors.textSecondary)
            } else {
                Text(draft, style = Griip.type.body, color = Griip.colors.textSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
                ChipRow(Modifier.padding(top = 8.dp)) { AiRules.WRITE_MODES.forEach { m -> PillChip(labels[m] ?: m, mode == m, { mode = m }) } }
            }
        },
    )
}

/** A channel catch-up: [messages] were picked (and decrypted) on this phone by [AiRules.catchUp]. */
@Composable
fun AiCatchUpSheet(orgId: String, channelId: String, messages: List<AiCatchUpMessage>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val status = rememberAiStatus(orgId)
    AiFrame(
        stringResource(R.string.ai_catchup_title), status, canSend = messages.isNotEmpty(), onDismiss = onDismiss,
        send = { container.api.post<AiCatchUpReq, AiAnswerRes>("/v1/orgs/$orgId/ai/catch-up", AiCatchUpReq(true, channelId, messages)).result },
        input = {
            Text(
                if (messages.isEmpty()) stringResource(R.string.ai_catchup_none) else stringResource(R.string.ai_catchup_scope, messages.size),
                style = Griip.type.body, color = Griip.colors.textSecondary,
            )
        },
    )
}

/** A document summary (documents are server-readable, docs/DOCUMENTS.md). */
@Composable
fun AiDocumentSummarySheet(orgId: String, documentId: String, title: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val status = rememberAiStatus(orgId)
    AiFrame(
        stringResource(R.string.ai_doc_summary), status, canSend = true, onDismiss = onDismiss,
        extraNote = status?.provider?.let { stringResource(R.string.ai_doc_note, it.name) },
        send = { container.api.post<AiConsentReq, AiAnswerRes>("/v1/documents/$documentId/ai/summary", AiConsentReq(true)).result },
        input = { Text(title, style = Griip.type.titleSmall, color = Griip.colors.textPrimary) },
    )
}

/** A question answered from the workspace documents the person can read, with the documents it used. */
@Composable
fun AiAskSheet(orgId: String, onOpenDocument: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val status = rememberAiStatus(orgId)
    var question by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<AiAskDto?>(null) }
    AiFrame(
        stringResource(R.string.ai_ask_title), status, canSend = question.trim().length >= 3, onDismiss = onDismiss,
        extraNote = stringResource(R.string.ai_ask_note),
        send = {
            val r = container.api.post<AiAskReq, AiAskRes>("/v1/orgs/$orgId/ai/ask", AiAskReq(true, question.trim())).result
            last = r
            val text = r.text
            val provider = r.provider
            if (text != null && provider != null) AiAnswerDto(text, provider) else null
        },
        input = { GriipTextField(question, { question = it.take(500) }, stringResource(R.string.ai_ask_hint), singleLine = false, minLines = 2) },
        after = { shown ->
            val r = last
            if (r != null && !r.found) Text(stringResource(R.string.ai_ask_none), style = Griip.type.body, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
            if (shown != null && r != null && r.sources.isNotEmpty()) {
                Text(stringResource(R.string.ai_ask_sources), style = Griip.type.label, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                r.sources.forEach { s -> MenuRow(Icons.Outlined.Description, "[${s.n}] ${s.title}", onClick = { onOpenDocument(s.id) }) }
            }
        },
    )
}

/** A workflow drafted from a description; "Open in the editor" hands it over, switched off and unsaved. */
@Composable
fun AiWorkflowDraftSheet(orgId: String, onOpen: (WfInput) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val status = rememberAiStatus(orgId)
    var description by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AiDraftDto?>(null) }
    val zone = remember { ZoneId.systemDefault().id }
    AiFrame(
        stringResource(R.string.ai_wf_draft), status, canSend = description.trim().length >= 5, onDismiss = onDismiss,
        extraNote = stringResource(R.string.ai_wf_off_note),
        send = {
            val r = container.api.post<AiDraftReq, AiDraftRes>("/v1/orgs/$orgId/ai/workflow-draft", AiDraftReq(true, description.trim(), zone)).result
            result = r
            AiAnswerDto(r.draft?.name ?: r.problems.joinToString(". "), r.provider)
        },
        input = {
            GriipTextField(
                description, { description = it.take(1000) }, stringResource(R.string.ai_wf_describe),
                placeholder = stringResource(R.string.ai_wf_example), singleLine = false, minLines = 3,
            )
        },
        after = { shown ->
            val r = result
            val draft = r?.draft
            if (shown != null && r != null) {
                if (r.problems.isNotEmpty() && draft != null) {
                    StatusBanner(stringResource(R.string.ai_wf_problems, r.problems.joinToString(". ")), BannerKind.Warning, Modifier.padding(top = 8.dp))
                }
                if (draft != null) PillButton(stringResource(R.string.ai_wf_open), { onOpen(draft) }, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
    )
}
