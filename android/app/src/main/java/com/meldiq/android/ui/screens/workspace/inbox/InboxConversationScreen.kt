package com.meldiq.android.ui.screens.workspace.inbox

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.AiStatusDto
import com.meldiq.android.data.api.InboxAgentDto
import com.meldiq.android.data.api.InboxAgentsRes
import com.meldiq.android.data.api.InboxConversationRes
import com.meldiq.android.data.api.InboxDetailRes
import com.meldiq.android.data.api.InboxDraftRes
import com.meldiq.android.data.api.InboxMessageDto
import com.meldiq.android.data.api.InboxMessageRes
import com.meldiq.android.data.api.InboxNoteReq
import com.meldiq.android.data.api.InboxQueueDto
import com.meldiq.android.data.api.InboxQueuesRes
import com.meldiq.android.data.api.InboxSettingsRes
import com.meldiq.android.data.api.InboxTemplateDto
import com.meldiq.android.data.api.InboxTemplateReplyReq
import com.meldiq.android.data.api.InboxTemplatesRes
import com.meldiq.android.data.api.InboxTextReplyReq
import com.meldiq.android.data.api.InboxUpdateReq
import com.meldiq.android.data.api.AiConsentReq
import com.meldiq.android.data.api.AiAnswerRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.ai.rememberAiStatus
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File

/**
 * One customer conversation (round 8, docs/UNIFIED_INBOX.md). The composer only offers what the platform allows now:
 * a free reply inside its window, an approved template on WhatsApp after it, nothing when it's closed. Notes stay
 * inside the team. AI drafts are labelled until the person sends them.
 */
@Composable
fun InboxConversationScreen(nav: NavHostController, conversationId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<InboxDetailRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var noteMode by remember { mutableStateOf(false) }
    var fromDraft by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var aiMode by remember { mutableStateOf("off") }
    var summary by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(conversationId, reload) {
        runCatching { container.api.get<InboxDetailRes>("/v1/inbox/conversations/$conversationId") }
            .onSuccess { d ->
                detail = d
                error = null
                if (d.conversation.unread > 0) runCatching { container.api.post<OkRes>("/v1/inbox/conversations/$conversationId/read") }
            }
            .onFailure { error = it.friendly() }
    }
    LaunchedEffect(conversationId) { container.messages.inboxEvents.filter { it.conversationId == conversationId || it.conversationId == null }.collect { reload++ } }
    val orgId = detail?.conversation?.orgId
    val ai = rememberAiStatus(orgId)
    LaunchedEffect(orgId) {
        val id = orgId ?: return@LaunchedEffect
        runCatching { container.api.get<InboxSettingsRes>("/v1/orgs/$id/inbox/settings").settings.aiMode }.onSuccess { aiMode = it }
    }
    LaunchedEffect(detail?.messages?.size) { detail?.messages?.size?.takeIf { it > 0 }?.let { listState.scrollToItem(it - 1) } }

    fun send() = scope.launch {
        val d = detail ?: return@launch
        val body = text.trim()
        if (body.isEmpty()) return@launch
        busy = true
        error = null
        runCatching {
            if (noteMode) container.api.post<InboxNoteReq, InboxMessageRes>("/v1/inbox/conversations/${d.conversation.id}/notes", InboxNoteReq(body))
            else container.api.post<InboxTextReplyReq, InboxMessageRes>("/v1/inbox/conversations/${d.conversation.id}/replies", InboxTextReplyReq(text = body, fromAiDraft = fromDraft))
        }.onSuccess { text = ""; fromDraft = false; reload++ }.onFailure { error = it.friendly() }
        busy = false
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val d = detail
        val conv = d?.conversation
        GriipTopBar(
            conv?.let { InboxRules.customerLabel(it, stringResource(R.string.ib_customer)) } ?: stringResource(R.string.ib_title),
            subtitle = conv?.let { "${InboxRules.providerBadge(it.provider)} · ${it.account.name} · ${statusLabel(it.status)}" },
            onBack = { nav.popBackStack() },
            actions = {
                if (ai?.available == true && aiMode != "off") CircleIconButton(Icons.Outlined.AutoAwesome, stringResource(R.string.ib_ai_summary), { sheet = "summary" }, tint = c.primary)
                CircleIconButton(Icons.Outlined.MoreVert, stringResource(R.string.ib_actions), { sheet = "actions" }, tint = c.textSecondary)
            },
        )
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        if (conv?.handling == "escalated") StatusBanner(stringResource(R.string.ib_escalated_because, conv.escalationReason ?: "?"), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
        if (d == null) {
            if (error == null) ShimmerList(5)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item { Text(stringResource(R.string.ib_not_e2ee), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
            items(d.messages, key = { it.id }) { m -> MessageItem(m) }
        }
        Composer(
            d, text, { text = it; if (it.isEmpty()) fromDraft = false }, noteMode, { noteMode = it }, fromDraft, busy,
            onSend = { send() },
            onTemplate = { sheet = "template" },
            onDraft = if (ai?.available == true && aiMode != "off") ({ sheet = "draft" }) else null,
        )
    }

    val d = detail
    when (sheet) {
        "actions" -> d?.let { ActionsSheet(it, onDone = { sheet = null; reload++ }, onError = { e -> error = e; sheet = null }, onErased = { sheet = null; nav.popBackStack() }) }
        "template" -> d?.let { TemplateSheet(it, onSent = { sheet = null; reload++ }, onDismiss = { sheet = null }) }
        "draft" -> d?.let { dd ->
            AiInboxSheet(ai, stringResource(R.string.ib_ai_draft), onDismiss = { sheet = null }) {
                val r = container.api.post<AiConsentReq, InboxDraftRes>("/v1/inbox/conversations/${dd.conversation.id}/ai/draft", AiConsentReq(true)).result
                text = r.text
                fromDraft = true
                noteMode = false
                sheet = null
                null
            }
        }
        "summary" -> d?.let { dd ->
            AiInboxSheet(ai, stringResource(R.string.ib_ai_summary), result = summary, onDismiss = { sheet = null; summary = null }) {
                container.api.post<AiConsentReq, AiAnswerRes>("/v1/inbox/conversations/${dd.conversation.id}/ai/summary", AiConsentReq(true)).result.text.also { summary = it }
            }
        }
    }
}

@Composable
internal fun statusLabel(s: String): String = stringResource(
    when (s) {
        "waiting_customer" -> R.string.ib_status_waiting_customer
        "resolved" -> R.string.ib_status_resolved
        else -> R.string.ib_status_waiting_agent
    },
)

@Composable
private fun deliveryLabel(m: InboxMessageDto): String = when (m.status) {
    "failed" -> stringResource(R.string.ib_failed, m.errorKind ?: "?")
    "queued" -> stringResource(R.string.ib_state_queued)
    "delivered" -> stringResource(R.string.ib_state_delivered)
    "read" -> stringResource(R.string.ib_state_read)
    else -> stringResource(R.string.ib_state_sent)
}

@Composable
private fun MessageItem(m: InboxMessageDto) {
    val c = Griip.colors
    val ctx = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val note = m.direction == "note"
    val out = m.direction == "out"
    val bg = when {
        note -> c.warning.copy(alpha = 0.14f)
        out -> c.primarySoft
        else -> c.surfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 3.dp), horizontalArrangement = if (out) Arrangement.End else if (note) Arrangement.Center else Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (note) Text(stringResource(R.string.ib_note), style = Griip.type.label, color = c.warning)
            if (m.origin == "ai_auto") Text(stringResource(R.string.ib_from_ai), style = Griip.type.caption, color = c.lavender)
            if (m.origin == "ai_draft") Text(stringResource(R.string.ib_from_ai_draft), style = Griip.type.caption, color = c.lavender)
            m.text?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = Griip.type.body, color = c.textPrimary) } }
            m.attachments.forEach { a ->
                Text(
                    stringResource(R.string.ib_attachment, a.type) + " · " + stringResource(R.string.ib_open_file),
                    style = Griip.type.bodySmall, color = c.primary,
                    modifier = Modifier.padding(top = 4.dp).clickable {
                        scope.launch {
                            runCatching {
                                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                                val f = File(dir, "inbox-${m.id}-${a.n}")
                                if (!f.exists()) container.api.downloadTo("/v1/inbox/messages/${m.id}/attachments/${a.n}", f) {}
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        }
                    },
                )
            }
            Text(
                TimeFmt.listStamp(m.at) + if (out) " · ${deliveryLabel(m)}" else "",
                style = Griip.type.caption, color = if (m.status == "failed") c.danger else c.textTertiary, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Composer(
    d: InboxDetailRes,
    text: String,
    onText: (String) -> Unit,
    noteMode: Boolean,
    onNoteMode: (Boolean) -> Unit,
    fromDraft: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onTemplate: () -> Unit,
    onDraft: (() -> Unit)?,
) {
    val c = Griip.colors
    val state = InboxRules.composer(d)
    val replyOk = state is InboxRules.Composer.Text
    val reply = !noteMode
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = Space.gutter, vertical = 8.dp)) {
        ChipRow(Modifier.padding(bottom = 4.dp)) {
            PillChip(stringResource(R.string.ib_mode_reply), reply, { onNoteMode(false) })
            PillChip(stringResource(R.string.ib_mode_note), noteMode, { onNoteMode(true) })
            if (onDraft != null && replyOk) PillChip(stringResource(R.string.ib_ai_draft), false, onDraft)
            if (d.reply.templates && d.reply.canReply) PillChip(stringResource(R.string.ib_template), false, onTemplate)
        }
        if (reply) {
            val hint = when (state) {
                is InboxRules.Composer.Text -> if (d.reply.rule.mode == "human_agent_tag") stringResource(R.string.ib_window_tag)
                    else InboxRules.hoursLeft(d.reply.windowEndsAt, System.currentTimeMillis())?.let { stringResource(R.string.ib_window_left, it) }
                is InboxRules.Composer.TemplateOnly -> stringResource(R.string.ib_closed_template_required)
                is InboxRules.Composer.Closed -> closedText(state.reason)
                is InboxRules.Composer.NoPermission -> stringResource(R.string.ib_no_reply_permission)
            }
            hint?.let { Text(it, style = Griip.type.caption, color = if (replyOk) c.textTertiary else c.warning, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        if (fromDraft && reply) Text(stringResource(R.string.ib_ai_draft_label), style = Griip.type.caption, color = c.lavender, modifier = Modifier.padding(bottom = 4.dp))
        if (noteMode || replyOk) {
            val fits = noteMode || InboxRules.fits(d.conversation.provider, text, d.reply.textLimit)
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    GriipTextField(
                        text, onText, stringResource(if (noteMode) R.string.ib_note_hint else R.string.ib_reply_hint),
                        singleLine = false, minLines = 1,
                        error = if (fits) null else "${d.reply.textLimit}",
                    )
                }
                Spacer(Modifier.padding(4.dp))
                PillButton(
                    stringResource(if (noteMode) R.string.ib_add_note else R.string.ib_send), onSend,
                    enabled = text.isNotBlank() && fits && !busy, loading = busy, height = 48.dp,
                    tone = if (noteMode) ButtonTone.Glass else ButtonTone.Primary,
                )
            }
        }
    }
}

@Composable
private fun closedText(reason: String): String = stringResource(
    when (reason) {
        "window_closed" -> R.string.ib_closed_window_closed
        "template_required" -> R.string.ib_closed_template_required
        "no_customer_message" -> R.string.ib_closed_no_customer_message
        else -> R.string.ib_closed_unsupported
    },
)

/** Ownership, queue, priority, tags, status, handing over from AI, and (inbox admins) erasing the customer's data. */
@Composable
private fun ActionsSheet(d: InboxDetailRes, onDone: () -> Unit, onError: (String) -> Unit, onErased: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val conv = d.conversation
    var agents by remember { mutableStateOf<List<InboxAgentDto>>(emptyList()) }
    var queues by remember { mutableStateOf<List<InboxQueueDto>>(emptyList()) }
    var canManage by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(conv.tags.joinToString(", ")) }
    var confirmErase by remember { mutableStateOf(false) }
    val me = container.session.session?.userId
    LaunchedEffect(conv.orgId) {
        runCatching {
            agents = container.api.get<InboxAgentsRes>("/v1/orgs/${conv.orgId}/inbox/agents").agents
            queues = container.api.get<InboxQueuesRes>("/v1/orgs/${conv.orgId}/inbox/queues").queues
            canManage = container.api.get<InboxSettingsRes>("/v1/orgs/${conv.orgId}/inbox/settings").canManage
        }
    }
    fun patch(req: InboxUpdateReq) = scope.launch {
        runCatching { container.api.patch<InboxUpdateReq, InboxConversationRes>("/v1/inbox/conversations/${conv.id}", req) }
            .onSuccess { onDone() }.onFailure { onError(it.friendly()) }
    }
    fun post(path: String) = scope.launch {
        runCatching { container.api.post<InboxConversationRes>(path) }.onSuccess { onDone() }.onFailure { onError(it.friendly()) }
    }
    GlassSheet({ onDone() }, title = stringResource(R.string.ib_actions)) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            if (conv.assigneeId != me) MenuRow(Icons.Outlined.AutoAwesome, stringResource(R.string.ib_claim), onClick = { post("/v1/inbox/conversations/${conv.id}/claim") })
            if (conv.handling == "ai_auto" || conv.handling == "escalated") MenuRow(Icons.Outlined.AutoAwesome, stringResource(R.string.ib_handoff), onClick = { post("/v1/inbox/conversations/${conv.id}/handoff") })
            Text(stringResource(R.string.ib_assign), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            ChipRow {
                PillChip(stringResource(R.string.ib_unassign), conv.assigneeId == null, { patch(InboxUpdateReq(rev = conv.rev, assigneeId = "")) })
                agents.forEach { a -> PillChip(a.name, conv.assigneeId == a.id, { patch(InboxUpdateReq(rev = conv.rev, assigneeId = a.id)) }) }
            }
            if (queues.isNotEmpty()) {
                Text(stringResource(R.string.ib_queue), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                ChipRow {
                    PillChip(stringResource(R.string.ib_no_queue), conv.queueId == null, { patch(InboxUpdateReq(rev = conv.rev, queueId = "")) })
                    queues.forEach { q -> PillChip(q.name, conv.queueId == q.id, { patch(InboxUpdateReq(rev = conv.rev, queueId = q.id)) }) }
                }
            }
            Text(stringResource(R.string.ib_priority), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            ChipRow { InboxRules.PRIORITIES.forEach { p -> PillChip(priorityLabel(p), conv.priority == p, { patch(InboxUpdateReq(rev = conv.rev, priority = p)) }) } }
            GriipTextField(tags, { tags = it.take(330) }, stringResource(R.string.ib_tags), Modifier.padding(top = 8.dp))
            PillButton(stringResource(R.string.ib_save_tags), { patch(InboxUpdateReq(rev = conv.rev, tags = InboxRules.parseTags(tags))) }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
            Spacer(Modifier.height(8.dp))
            if (conv.status == "resolved") PillButton(stringResource(R.string.ib_reopen), { patch(InboxUpdateReq(rev = conv.rev, status = "waiting_agent")) }, Modifier.fillMaxWidth(), height = 48.dp)
            else PillButton(stringResource(R.string.ib_resolve), { patch(InboxUpdateReq(rev = conv.rev, status = "resolved")) }, Modifier.fillMaxWidth(), height = 48.dp)
            if (canManage) {
                Spacer(Modifier.height(8.dp))
                if (!confirmErase) PillButton(stringResource(R.string.ib_erase), { confirmErase = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, height = 44.dp)
                else {
                    Text(stringResource(R.string.ib_erase_body), style = Griip.type.bodySmall, color = c.textSecondary)
                    PillButton(stringResource(R.string.ib_erase), {
                        scope.launch { runCatching { container.api.delete<OkRes>("/v1/inbox/conversations/${conv.id}") }.onSuccess { onErased() }.onFailure { onError(it.friendly()) } }
                    }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Danger, height = 44.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** WhatsApp after 24 hours: only the business account's approved templates. */
@Composable
private fun TemplateSheet(d: InboxDetailRes, onSent: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<InboxTemplateDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(d.conversation.account.id) {
        runCatching { container.api.get<InboxTemplatesRes>("/v1/orgs/${d.conversation.orgId}/inbox/accounts/${d.conversation.account.id}/templates").templates }
            .onSuccess { templates = it }.onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.ib_template)) {
        error?.let { StatusBanner(it, BannerKind.Error) }
        val list = templates
        if (list != null && list.isEmpty()) Text(stringResource(R.string.ib_templates_none), style = Griip.type.body, color = Griip.colors.textSecondary)
        list.orEmpty().forEach { t ->
            MenuRow(Icons.Outlined.AutoAwesome, t.name, subtitle = listOfNotNull(t.language, t.category).joinToString(" · "), onClick = {
                scope.launch {
                    runCatching { container.api.post<InboxTemplateReplyReq, InboxMessageRes>("/v1/inbox/conversations/${d.conversation.id}/replies", InboxTemplateReplyReq(name = t.name, language = t.language)) }
                        .onSuccess { onSent() }.onFailure { error = it.friendly() }
                }
            })
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** AI in a conversation: says who receives the text (never the notes), and sends only on the person's press. */
@Composable
private fun AiInboxSheet(ai: AiStatusDto?, title: String, result: String? = null, onDismiss: () -> Unit, run: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val provider = ai?.provider
    GlassSheet(onDismiss, title = title) {
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(bottom = 6.dp)) }
        if (result != null) {
            SelectionContainer { Text(result, style = Griip.type.body, color = Griip.colors.textPrimary) }
            provider?.let { Text(stringResource(R.string.ai_result_note, it.name), style = Griip.type.caption, color = Griip.colors.textTertiary, modifier = Modifier.padding(top = 6.dp)) }
        } else if (provider != null) {
            Text(stringResource(R.string.ib_ai_consent, provider.name), style = Griip.type.caption, color = Griip.colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            PillButton(stringResource(R.string.ai_send, provider.name), {
                scope.launch {
                    busy = true
                    runCatching { run() }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), loading = busy)
        }
        Spacer(Modifier.height(16.dp))
    }
}
