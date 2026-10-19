package com.meldiq.android.ui.screens.workspace.apps

import android.content.ClipData
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.HookCreatedRes
import com.meldiq.android.data.api.HookDto
import com.meldiq.android.data.api.WorkflowDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/** Every "when this happens, post there" in the workspace (docs/INTEGRATIONS.md › Workflows), with its real state. */
@Composable
fun WorkflowsPanel(orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    var list by remember { mutableStateOf<List<WorkflowDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orgId) { runCatching { container.integrations.workflows(orgId) }.onSuccess { list = it }.onFailure { error = it.friendly() } }
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Text(stringResource(R.string.workflows_sub), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
        error?.let { StatusBanner(it, BannerKind.Warning) }
        val l = list ?: return
        if (l.isEmpty()) EmptyState(Icons.Outlined.AccountTree, stringResource(R.string.workflows_empty), "")
        l.forEach { w ->
            ListCard(
                w.trigger,
                subtitle = listOf(
                    w.appName,
                    stringResource(R.string.workflow_post_to, w.channelName ?: "?"),
                    w.ownerName?.let { stringResource(R.string.workflow_owner, it) },
                    w.lastRunAt?.let { stringResource(R.string.workflow_last_run, TimeFmt.listStamp(it)) } ?: stringResource(R.string.workflow_never),
                ).filterNotNull().joinToString(" · "),
                leading = { AppTile(if (w.kind == "incoming_webhook") "webhook" else "apps", if (w.active) "installed" else "error", size = 38.dp) },
                meta = if (!w.active) ({ TagPill(stringResource(R.string.app_paused, w.lastError ?: ""), c.warning, c.warningSoft) }) else null,
            )
        }
    }
}

/**
 * Incoming webhooks of the workspace (integrations.configure): which tool posts where, when it last did, and making,
 * re-keying or deleting one. A new secret is shown exactly once.
 */
@Composable
fun HooksCard(orgId: String, canConfigure: Boolean) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var hooks by remember { mutableStateOf<List<HookDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<HookCreatedRes?>(null) }
    var deleting by remember { mutableStateOf<HookDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reload: () -> Unit = { scope.launch { runCatching { container.integrations.hooks(orgId) }.onSuccess { hooks = it }.onFailure { error = it.friendly() } } }
    LaunchedEffect(orgId) { reload() }
    SectionHeader(stringResource(R.string.hooks_title), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp))
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text(stringResource(R.string.app_notifications_sub), style = Griip.type.caption, color = c.textSecondary)
        error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(top = 6.dp)) }
        if (hooks.isEmpty()) Text(stringResource(R.string.hooks_none), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
        hooks.forEach { h ->
            ListCard(
                h.name,
                subtitle = listOfNotNull("#${h.channelName ?: "?"}", h.lastDeliveryAt?.let { stringResource(R.string.workflow_last_run, TimeFmt.listStamp(it)) } ?: stringResource(R.string.workflow_never)).joinToString(" · "),
                meta = if (!h.active) ({ TagPill(stringResource(R.string.app_paused, h.lastError ?: ""), c.warning, c.warningSoft) }) else null,
                trailing = if (canConfigure) ({
                    Row {
                        PillButton(stringResource(R.string.hooks_rotate), {
                            scope.launch { runCatching { container.integrations.rotateHook(orgId, h.id) }.onSuccess { created = it; reload() }.onFailure { error = it.friendly() } }
                        }, tone = ButtonTone.Glass, height = 36.dp)
                    }
                }) else null,
                onClick = if (canConfigure) ({ deleting = h }) else null,
            )
        }
        if (canConfigure) PillButton(stringResource(R.string.hooks_new), { creating = true }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp)
        Text(stringResource(R.string.hooks_how), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }
    if (creating) CreateHookSheet(orgId, onDismiss = { creating = false }) { res -> creating = false; created = res; reload() }
    created?.let { SecretSheet(it) { created = null } }
    deleting?.let { h ->
        ConfirmDialog(stringResource(R.string.hooks_delete_title, h.name), stringResource(R.string.hooks_delete_body), stringResource(R.string.hooks_delete), onConfirm = {
            deleting = null
            scope.launch { runCatching { container.integrations.deleteHook(orgId, h.id) }.onSuccess { reload() }.onFailure { error = it.friendly() } }
        }, onDismiss = { deleting = null }, destructive = true)
    }
}

@Composable
private fun CreateHookSheet(orgId: String, onDismiss: () -> Unit, onCreated: (HookCreatedRes) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var channel by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(orgId) { runCatching { container.orgs.channels(orgId) }.onSuccess { list -> channels = list.filter { it.joined && !it.archived } } }
    GlassSheet(onDismiss, title = stringResource(R.string.hooks_new)) {
        GriipTextField(name, { name = it.take(80) }, stringResource(R.string.hooks_name))
        SectionHeader(stringResource(R.string.app_pick_channel), Modifier.padding(vertical = 6.dp))
        ChipRow { channels.forEach { ch -> PillChip("#${ch.name}", channel == ch.id, { channel = ch.id }) } }
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.hooks_create), {
            val ch = channel ?: return@PillButton
            busy = true
            scope.launch {
                runCatching { container.integrations.createHook(orgId, ch, name) }.onSuccess(onCreated).onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && channel != null, loading = busy, height = 50.dp)
        Spacer(Modifier.height(8.dp))
    }
}

/** The URL and secret, once: copied with a tap, never shown again (a new secret replaces a lost one). */
@Composable
private fun SecretSheet(res: HookCreatedRes, onDismiss: () -> Unit) {
    val c = Griip.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss, title = stringResource(R.string.hooks_secret_title)) {
        StatusBanner(stringResource(R.string.hooks_secret_body), BannerKind.Warning, icon = Icons.Outlined.Warning)
        listOf(stringResource(R.string.hooks_url) to res.hook.url, stringResource(R.string.hooks_secret) to res.secret).forEach { (label, value) ->
            Text(label, style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                PillButton(if (copied == label) stringResource(R.string.hooks_copied) else stringResource(R.string.hooks_copy), {
                    scope.launch { clipboard.setClipEntry(ClipData.newPlainText(label, value).toClipEntry()) }
                    copied = label
                }, tone = ButtonTone.Glass, height = 36.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Which explicit actions a connected app offers on a message's text (by the capability that allows each). */
data class MessageAction(val appId: String, val appName: String, val action: String, val label: Int)

object MessageActions {
    /** Known actions, by the capability the server checks for each (docs/INTEGRATIONS.md › Actions). */
    private val BY_CAPABILITY = mapOf(
        "issues.create" to ("create_issue" to R.string.send_to_app_jira),
        "events.create" to ("create_event" to R.string.send_to_app_calendar),
    )

    fun available(apps: List<com.meldiq.android.data.api.AppSummaryDto>): List<MessageAction> =
        apps.filter { it.state == "connected" }.flatMap { a ->
            a.capabilities.filter { it.granted && it.kind == "write" }.mapNotNull { cap -> BY_CAPABILITY[cap.key]?.let { (action, label) -> MessageAction(a.id, a.name, action, label) } }
        }

    /** "2026-10-22 15:30" in this phone's time zone, as an ISO instant; null when it isn't that format. */
    fun parseLocal(v: String): String? = runCatching {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        java.time.LocalDateTime.parse(v.trim(), f).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
    }.getOrNull()
}

/**
 * Sending a message's text to an app as an explicit action (create a Jira issue, add a calendar event). The fields are
 * prefilled from the message and editable; the sheet says plainly that this text leaves end-to-end encryption.
 */
@Composable
fun SendToAppSheet(orgId: String, action: MessageAction, messageText: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val firstLine = messageText.lineSequence().firstOrNull().orEmpty().take(200)
    var fields by remember {
        mutableStateOf(
            if (action.action == "create_issue") mapOf("projectKey" to "", "summary" to firstLine, "description" to messageText.take(5000), "issueType" to "")
            else mapOf("summary" to firstLine, "start" to "", "end" to "", "location" to ""),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val badTime = stringResource(R.string.send_bad_time)
    GlassSheet(onDismiss, title = stringResource(action.label)) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            StatusBanner(stringResource(R.string.send_warning, action.appName), BannerKind.Warning, icon = Icons.Outlined.Warning)
            val labels = mapOf(
                "projectKey" to R.string.send_project, "summary" to (if (action.action == "create_issue") R.string.send_summary else R.string.send_title),
                "description" to R.string.send_description, "issueType" to R.string.send_type,
                "start" to R.string.send_start, "end" to R.string.send_end, "location" to R.string.send_location,
            )
            fields.keys.forEach { k ->
                Spacer(Modifier.height(8.dp))
                GriipTextField(fields[k].orEmpty(), { v -> fields = fields + (k to v) }, stringResource(labels[k] ?: R.string.send_summary), singleLine = k != "description", minLines = if (k == "description") 3 else 1)
            }
            error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.send_do), {
                error = null
                val payload = if (action.action == "create_event") {
                    val s = MessageActions.parseLocal(fields["start"].orEmpty())
                    val e = MessageActions.parseLocal(fields["end"].orEmpty())
                    if (s == null || e == null) {
                        error = badTime
                        return@PillButton
                    }
                    fields + ("start" to s) + ("end" to e)
                } else fields
                busy = true
                scope.launch {
                    runCatching { container.integrations.action(orgId, action.appId, action.action, null, payload.filterValues { it.isNotBlank() }) }
                        .onSuccess { r -> onDone(r.title) }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), loading = busy, height = 50.dp)
            Spacer(Modifier.height(8.dp))
        }
    }
}
