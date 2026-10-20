package com.meldiq.android.ui.screens.workspace.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.DocumentDto
import com.meldiq.android.data.api.DocumentsRes
import com.meldiq.android.data.api.InboxAccountDto
import com.meldiq.android.data.api.InboxAccountRes
import com.meldiq.android.data.api.InboxAccountsRes
import com.meldiq.android.data.api.InboxAgentDto
import com.meldiq.android.data.api.InboxAgentsRes
import com.meldiq.android.data.api.InboxConnectReq
import com.meldiq.android.data.api.InboxProviderDto
import com.meldiq.android.data.api.InboxProvidersRes
import com.meldiq.android.data.api.InboxQueueDto
import com.meldiq.android.data.api.InboxQueueReq
import com.meldiq.android.data.api.InboxQueueRes
import com.meldiq.android.data.api.InboxQueuesRes
import com.meldiq.android.data.api.InboxReportDto
import com.meldiq.android.data.api.InboxReportRes
import com.meldiq.android.data.api.InboxSettingsDto
import com.meldiq.android.data.api.InboxSettingsRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Inbox setup for inbox admins (round 8, docs/UNIFIED_INBOX.md): which platforms this server can use and what's
 * missing, the connected business accounts and their health, queues, AI and automation, response target, retention,
 * and the last 30 days. Nothing here pretends a platform works before it's set up and verified.
 */
@Composable
fun InboxSetupScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<InboxProviderDto>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<InboxAccountDto>>(emptyList()) }
    var queues by remember { mutableStateOf<List<InboxQueueDto>>(emptyList()) }
    var agents by remember { mutableStateOf<List<InboxAgentDto>>(emptyList()) }
    var settings by remember { mutableStateOf<InboxSettingsDto?>(null) }
    var docs by remember { mutableStateOf<List<DocumentDto>>(emptyList()) }
    var report by remember { mutableStateOf<InboxReportDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // The save result is shown next to the Save button, where the person is looking.
    var saveResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var connecting by remember { mutableStateOf<InboxProviderDto?>(null) }
    var queueSheet by remember { mutableStateOf(false) }
    val savedText = stringResource(R.string.ib_saved)

    LaunchedEffect(reload) {
        runCatching {
            providers = container.api.get<InboxProvidersRes>("/v1/orgs/$orgId/inbox/providers").providers
            accounts = container.api.get<InboxAccountsRes>("/v1/orgs/$orgId/inbox/accounts").accounts
            queues = container.api.get<InboxQueuesRes>("/v1/orgs/$orgId/inbox/queues").queues
            agents = container.api.get<InboxAgentsRes>("/v1/orgs/$orgId/inbox/agents").agents
            settings = settings ?: container.api.get<InboxSettingsRes>("/v1/orgs/$orgId/inbox/settings").settings
            docs = container.api.get<DocumentsRes>("/v1/orgs/$orgId/documents").documents.filter { it.access == "workspace" }
            report = container.api.get<InboxReportRes>("/v1/orgs/$orgId/inbox/report?days=30").report
        }.onSuccess { error = null }.onFailure { error = it.friendly() }
    }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { error = it.friendly() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item { GriipTopBar(stringResource(R.string.ib_setup), onBack = { nav.popBackStack() }) }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        note?.let { n -> item { StatusBanner(n, BannerKind.Success, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }

        item { SectionHeader(stringResource(R.string.ib_setup_providers), Modifier.padding(top = 8.dp, bottom = 2.dp)) }
        items(providers, key = { it.id }) { p ->
            val state = when (p.state) {
                "ready" -> stringResource(R.string.ib_state_ready)
                "needs_configuration" -> stringResource(R.string.ib_state_needs_configuration, p.missing.joinToString(", "))
                else -> stringResource(R.string.ib_state_awaiting_access)
            }
            ListCard(
                p.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOfNotNull(state, p.eligibility, p.webhookUrl?.let { stringResource(R.string.ib_webhook_url, it) }).joinToString("\n"),
                leading = { IconTile(Icons.Outlined.Forum, providerColor(p.id), c.surfaceVariant, size = 40.dp) },
                trailing = if (p.state == "ready") ({ PillButton(stringResource(R.string.ib_connect), { connecting = p }, height = 36.dp, tone = ButtonTone.Glass) }) else null,
            )
        }

        item { SectionHeader(stringResource(R.string.ib_accounts), Modifier.padding(top = 12.dp, bottom = 2.dp)) }
        items(accounts, key = { it.id }) { a ->
            val health = when (a.status) {
                "active" -> stringResource(R.string.ib_acc_active)
                "needs_reauth" -> stringResource(R.string.ib_acc_needs_reauth)
                else -> stringResource(R.string.ib_acc_error, a.lastError ?: "?")
            }
            ListCard(
                a.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOfNotNull(a.providerName, a.number, health).joinToString(" · "),
                leading = { IconTile(Icons.Outlined.Forum, if (a.status == "active") c.success else c.danger, c.surfaceVariant, size = 40.dp) },
                meta = {
                    Column {
                        PillButton(stringResource(R.string.ib_check), { act { container.api.post<InboxAccountRes>("/v1/orgs/$orgId/inbox/accounts/${a.id}/check") } }, height = 32.dp, tone = ButtonTone.Glass)
                        PillButton(stringResource(R.string.ib_disconnect), { act { container.api.delete<OkRes>("/v1/orgs/$orgId/inbox/accounts/${a.id}") } }, Modifier.padding(top = 4.dp), height = 32.dp, tone = ButtonTone.Danger)
                    }
                },
            )
        }

        item { SectionHeader(stringResource(R.string.ib_queues), Modifier.padding(top = 12.dp, bottom = 2.dp), action = stringResource(R.string.ib_new_queue), onAction = { queueSheet = true }) }
        items(queues, key = { it.id }) { q ->
            ListCard(q.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), subtitle = stringResource(R.string.ib_queue_members, q.memberIds.size), leading = {
                IconTile(Icons.Outlined.Groups, c.lavender, c.surfaceVariant, size = 40.dp)
            })
        }

        settings?.let { s ->
            item { SectionHeader(stringResource(R.string.ib_settings), Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item { SettingsForm(s, docs, queues, onChange = { settings = it; saveResult = null }) }
            item {
                PillButton(stringResource(R.string.ib_save), {
                    scope.launch {
                        runCatching { container.api.put<InboxSettingsDto, InboxSettingsRes>("/v1/orgs/$orgId/inbox/settings", s).settings }
                            .onSuccess { settings = it; saveResult = savedText to true }
                            .onFailure { saveResult = it.friendly() to false }
                    }
                }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp))
            }
            saveResult?.let { (text, ok) ->
                item { StatusBanner(text, if (ok) BannerKind.Success else BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
            }
        }

        report?.let { r ->
            item { SectionHeader(stringResource(R.string.ib_report), Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item {
                Text(
                    stringResource(
                        R.string.ib_report_body, r.messages.received, r.messages.sentByPeople, r.messages.sentAutomatically, r.messages.failed,
                        r.conversations.started, r.conversations.resolved, r.escalations, InboxRules.minutesLabel(r.times.medianFirstResponseMs) ?: "–",
                    ),
                    style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp),
                )
            }
        }
    }

    connecting?.let { p -> ConnectSheet(p, onDismiss = { connecting = null }) { req ->
        scope.launch {
            runCatching { container.api.post<InboxConnectReq, InboxAccountRes>("/v1/orgs/$orgId/inbox/accounts", req) }
                .onSuccess { connecting = null; reload++ }.onFailure { error = it.friendly(); connecting = null }
        }
    } }
    if (queueSheet) QueueSheet(agents, onDismiss = { queueSheet = false }) { req ->
        scope.launch {
            runCatching { container.api.post<InboxQueueReq, InboxQueueRes>("/v1/orgs/$orgId/inbox/queues", req) }
                .onSuccess { queueSheet = false; reload++ }.onFailure { error = it.friendly(); queueSheet = false }
        }
    }
}

@Composable
private fun SettingsForm(s: InboxSettingsDto, docs: List<DocumentDto>, queues: List<InboxQueueDto>, onChange: (InboxSettingsDto) -> Unit) {
    val c = Griip.colors
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Text(stringResource(R.string.ib_ai_mode), style = Griip.type.label, color = c.textSecondary)
        ChipRow(Modifier.padding(horizontal = 0.dp)) {
            InboxRules.AI_MODES.forEach { m ->
                val label = stringResource(when (m) { "draft" -> R.string.ib_ai_mode_draft; "auto" -> R.string.ib_ai_mode_auto; else -> R.string.ib_ai_mode_off })
                PillChip(label, s.aiMode == m, { onChange(s.copy(aiMode = m)) })
            }
        }
        Text(stringResource(R.string.ib_ai_mode_note), style = Griip.type.caption, color = c.textTertiary)
        if (s.aiMode == "auto") {
            Text(stringResource(R.string.ib_auto_topics), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
            ChipRow {
                InboxRules.AUTO_INTENTS.forEach { i ->
                    val on = i in s.autoIntents
                    PillChip(intentLabel(i), on, { onChange(s.copy(autoIntents = if (on) s.autoIntents - i else s.autoIntents + i)) })
                }
            }
            Text(stringResource(R.string.ib_knowledge), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
            Text(stringResource(R.string.ib_knowledge_note), style = Griip.type.caption, color = c.textTertiary)
            ChipRow {
                docs.forEach { d ->
                    val on = d.id in s.knowledgeDocumentIds
                    PillChip(d.title, on, { onChange(s.copy(knowledgeDocumentIds = if (on) s.knowledgeDocumentIds - d.id else s.knowledgeDocumentIds + d.id)) })
                }
            }
            ListCard(stringResource(R.string.ib_pause), Modifier.padding(top = 6.dp), trailing = { GriipSwitch(s.autoPaused, { onChange(s.copy(autoPaused = it)) }) })
            GriipTextField(s.autoFallback, { onChange(s.copy(autoFallback = it.take(500))) }, stringResource(R.string.ib_fallback), Modifier.padding(top = 6.dp), singleLine = false)
        }
        if (queues.isNotEmpty()) {
            Text(stringResource(R.string.ib_queue), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
            ChipRow {
                PillChip(stringResource(R.string.ib_no_queue), s.defaultQueueId == null, { onChange(s.copy(defaultQueueId = null)) })
                queues.forEach { q -> PillChip(q.name, s.defaultQueueId == q.id, { onChange(s.copy(defaultQueueId = q.id)) }) }
            }
        }
        val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
        // Kept as typed, so a field can be emptied before typing a new number (empty saves as 0).
        var target by remember { mutableStateOf(s.responseTargetMinutes.toString()) }
        var retention by remember { mutableStateOf(s.retentionDays.toString()) }
        GriipTextField(target, { v ->
            target = v.filter(Char::isDigit).take(5)
            onChange(s.copy(responseTargetMinutes = target.toIntOrNull()?.coerceAtMost(10_080) ?: 0))
        }, stringResource(R.string.ib_response_target), Modifier.padding(top = 8.dp), keyboardOptions = numbers)
        GriipTextField(retention, { v ->
            retention = v.filter(Char::isDigit).take(4)
            onChange(s.copy(retentionDays = retention.toIntOrNull()?.coerceAtMost(3650) ?: 0))
        }, stringResource(R.string.ib_retention), Modifier.padding(top = 8.dp), keyboardOptions = numbers)
    }
}

@Composable
private fun intentLabel(i: String): String = stringResource(
    when (i) {
        "greeting" -> R.string.ib_intent_greeting
        "opening_hours" -> R.string.ib_intent_opening_hours
        "product_question" -> R.string.ib_intent_product_question
        "order_status_question" -> R.string.ib_intent_order_status_question
        "shipping_question" -> R.string.ib_intent_shipping_question
        else -> R.string.ib_intent_general_question
    },
)

/** Connecting with the business's own Meta token (shown masked; never a password). */
@Composable
private fun ConnectSheet(p: InboxProviderDto, onDismiss: () -> Unit, onConnect: (InboxConnectReq) -> Unit) {
    var externalId by remember { mutableStateOf("") }
    var waba by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val digits = KeyboardOptions(keyboardType = KeyboardType.Number)
    GlassSheet(onDismiss, title = stringResource(R.string.ib_connect_title, p.name)) {
        Text(stringResource(R.string.ib_connect_note), style = Griip.type.caption, color = Griip.colors.textSecondary)
        when (p.id) {
            "messenger" -> GriipTextField(externalId, { externalId = it.filter(Char::isDigit).take(30) }, stringResource(R.string.ib_page_id), Modifier.padding(top = 8.dp), keyboardOptions = digits)
            "whatsapp" -> {
                GriipTextField(externalId, { externalId = it.filter(Char::isDigit).take(30) }, stringResource(R.string.ib_phone_id), Modifier.padding(top = 8.dp), keyboardOptions = digits)
                GriipTextField(waba, { waba = it.filter(Char::isDigit).take(30) }, stringResource(R.string.ib_waba_id), Modifier.padding(top = 8.dp), keyboardOptions = digits)
            }
        }
        GriipTextField(token, { token = it.trim().take(4096) }, stringResource(R.string.ib_token), Modifier.padding(top = 8.dp), visualTransformation = PasswordVisualTransformation())
        val ready = token.length >= 20 && (p.id == "instagram" || externalId.isNotEmpty()) && (p.id != "whatsapp" || waba.isNotEmpty())
        PillButton(stringResource(R.string.ib_connect), {
            onConnect(InboxConnectReq(p.id, externalId, token, waba.ifEmpty { null }))
        }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = ready)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun QueueSheet(agents: List<InboxAgentDto>, onDismiss: () -> Unit, onSave: (InboxQueueReq) -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    GlassSheet(onDismiss, title = stringResource(R.string.ib_new_queue)) {
        GriipTextField(name, { name = it.take(60) }, stringResource(R.string.ib_queue_name))
        ChipRow(Modifier.padding(top = 6.dp)) {
            agents.forEach { a -> PillChip(a.name, a.id in picked, { picked = if (a.id in picked) picked - a.id else picked + a.id }) }
        }
        PillButton(stringResource(R.string.ib_save), { onSave(InboxQueueReq(name.trim(), picked.toList())) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = name.isNotBlank())
        Spacer(Modifier.height(16.dp))
    }
}
