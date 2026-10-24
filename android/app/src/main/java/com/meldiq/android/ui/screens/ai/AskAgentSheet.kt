package com.meldiq.android.ui.screens.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.ai.AgentRules
import com.meldiq.android.data.ai.AiAgentDetail
import com.meldiq.android.data.ai.AiJobDetail
import com.meldiq.android.data.ai.AiJobReq
import com.meldiq.android.data.ai.AiSharedMessage
import com.meldiq.android.data.ai.AiWorkforceApi
import com.meldiq.android.data.ai.SkillForm
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

/** An agent the person may pick in the Ask sheet. */
data class AskableAgent(val id: String, val displayName: String, val canInvoke: Boolean = true)

/** A file picked for a request, copied into the app's cache so it can be streamed to the server. */
private data class Picked(val file: File, val name: String, val mime: String) {
    val size get() = file.length()
}

/**
 * Asking an agent for work (as Meldiq Web's AskAgentDialog): the agent, the brief, its options, files, and the notice
 * that all of it leaves end-to-end encryption. Nothing is sent before Send. From a channel, the request and the agent's
 * answers show there for its members, and the person may share the channel's recent messages: [recent] are what this
 * phone decrypted (oldest first), shared only when the person picks a number. The job runs on the server; closing the
 * sheet after Send never cancels it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAgentSheet(
    orgId: String,
    agents: List<AskableAgent>,
    initialAgent: String? = null,
    initialBrief: String = "",
    conversationId: String? = null,
    threadRoot: String? = null,
    recent: List<AiSharedMessage> = emptyList(),
    onDismiss: () -> Unit,
    onSent: (AiJobDetail) -> Unit,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Griip.colors
    val usable = agents.filter { it.canInvoke }
    val blocked = agents.firstOrNull { it.id == initialAgent && !it.canInvoke }
    var installId by remember { mutableStateOf(initialAgent?.takeIf { id -> usable.any { it.id == id } } ?: usable.firstOrNull()?.id) }
    var brief by remember { mutableStateOf(initialBrief) }
    var options by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var files by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var shareCount by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<AiAgentDetail?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // One key per opened sheet: a second tap on Send, or a retried request, is the same job on the server.
    val idempotencyKey = remember { AiWorkforceApi.idempotencyKey() }
    LaunchedEffect(installId) {
        options = emptyMap()
        shareCount = 0
        detail = null
        installId?.let { id -> runCatching { container.ai.agent(orgId, id) }.onSuccess { detail = it }.onFailure { error = it.friendly() } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val copied = withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(context, it) } }
            files = files + copied
        }
    }
    val def = detail?.definition
    val limits = def?.skill?.files
    val canShare = conversationId != null && def?.skill?.readsMessages == true && recent.isNotEmpty()
    val shared = if (canShare && shareCount > 0) recent.takeLast(shareCount) else emptyList()
    val problem = AgentRules.fileProblem(files.map { AgentRules.PickedFile(it.name, it.mime, it.size) }, limits)
    val problemText = when (problem) {
        is AgentRules.FileProblem.TooMany -> stringResource(R.string.aw_ask_too_many, problem.max)
        is AgentRules.FileProblem.TooFew -> stringResource(R.string.aw_ask_too_few, problem.min, problem.types.joinToString())
        is AgentRules.FileProblem.WrongKind -> stringResource(R.string.aw_ask_wrong_kind, problem.name)
        is AgentRules.FileProblem.TooLarge -> stringResource(R.string.aw_ask_too_large, problem.name)
        null -> null
    }
    val uploading = stringResource(R.string.aw_ask_uploading)
    val sending = stringResource(R.string.aw_ask_sending)
    fun send() {
        val id = installId ?: return
        error = null
        scope.launch {
            runCatching {
                val ids = files.mapIndexed { i, f ->
                    progress = uploading.format(f.name, i + 1, files.size)
                    container.ai.upload(orgId, f.file, f.name, f.mime) {}.id
                }
                progress = sending
                container.ai.createJob(
                    orgId,
                    AiJobReq(
                        installId = id, input = SkillForm.input(brief, options), conversationId = conversationId, threadRoot = threadRoot,
                        fileIds = ids, idempotencyKey = idempotencyKey, requestText = brief.trim().takeIf { conversationId != null },
                        channelMessages = shared.takeIf { it.isNotEmpty() },
                    ),
                )
            }.onSuccess { job ->
                files.forEach { it.file.delete() }
                container.aiSignals.jobChanged()
                onSent(job)
            }.onFailure { error = it.friendly() }
            progress = null
        }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.aw_ask_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocked?.let { StatusBanner(stringResource(R.string.aw_ask_blocked, it.displayName), BannerKind.Error) }
            if (usable.isEmpty()) {
                Text(stringResource(R.string.aw_ask_none), style = Griip.type.body, color = c.textSecondary)
                return@Column
            }
            if (usable.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                usable.forEach { a -> PillChip(a.displayName, a.id == installId, { installId = a.id }) }
            }
            def?.let { Text(it.description, style = Griip.type.bodySmall, color = c.textSecondary) }
            detail?.setup?.takeIf { !it.ready }?.let { StatusBanner(stringResource(R.string.aw_ask_setup, it.missing.joinToString(" ")), BannerKind.Warning) }
            GriipTextField(
                brief, { brief = it.take(AgentRules.BRIEF_MAX) }, stringResource(R.string.aw_ask_brief),
                placeholder = def?.examples?.firstOrNull() ?: stringResource(R.string.aw_ask_brief_ph), singleLine = false, minLines = 4,
            )
            def?.let { SkillFields(SkillForm.fields(it.skill.inputSchema), options) { options = it } }
            Text(
                stringResource(R.string.aw_ask_files) + (limits?.takeIf { it.max > 0 }?.let { " " + stringResource(R.string.aw_ask_files_limits, it.min, it.max) } ?: ""),
                style = Griip.type.label, color = c.textSecondary,
            )
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name + " · " + sizeText(f.size), style = Griip.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                    CircleIconButton(Icons.Outlined.Close, stringResource(R.string.aw_ask_remove_file, f.name), { files = files - f; f.file.delete() }, size = 36.dp, tint = c.textSecondary)
                }
            }
            if (limits == null || limits.max > 0) {
                PillButton(stringResource(R.string.aw_ask_add_files), { pick.launch(limits?.types?.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("*/*")) }, tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.AttachFile, height = 40.dp)
            }
            problemText?.let { Text(it, style = Griip.type.caption, color = c.danger) }
            if (canShare) {
                Text(stringResource(R.string.aw_ask_share), style = Griip.type.label, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip(stringResource(R.string.aw_ask_share_none), shareCount == 0, { shareCount = 0 })
                    AgentRules.shareChoices(recent.size).forEach { n ->
                        val label = if (n < recent.size) stringResource(R.string.aw_ask_share_last, n) else stringResource(R.string.aw_ask_share_all, recent.size)
                        PillChip(label, shareCount == n, { shareCount = n })
                    }
                }
            }
            AiNotice(
                text = listOf(
                    stringResource(R.string.aw_notice),
                    if (shared.isNotEmpty()) stringResource(R.string.aw_ask_share_notice, shared.size) else stringResource(R.string.aw_ask_only_this),
                ).joinToString(" "),
            )
            error?.let { StatusBanner(it, BannerKind.Error) }
            PillButton(
                progress ?: stringResource(R.string.aw_ask_send), ::send, Modifier.fillMaxWidth(),
                enabled = progress == null && installId != null && brief.isNotBlank() && problem == null, loading = progress != null,
                leadingIcon = Icons.AutoMirrored.Outlined.Send,
            )
        }
    }
}

/** A skill's options ([SkillForm]): choices, switches, numbers, short text and lists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillFields(fields: List<SkillForm.Field>, values: Map<String, JsonElement>, onChange: (Map<String, JsonElement>) -> Unit) {
    val c = Griip.colors
    fun set(k: String, v: JsonElement?) = onChange(if (v == null) values - k else values + (k to v))
    fields.forEach { f ->
        val label = SkillForm.label(f.key)
        when (f) {
            is SkillForm.Field.Choice -> Column {
                Text(label, style = Griip.type.label, color = c.textSecondary)
                val cur = (values[f.key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: f.default
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    f.options.forEach { o -> PillChip(o.replace('_', ' '), o == cur, { set(f.key, kotlinx.serialization.json.JsonPrimitive(o)) }) }
                }
            }
            is SkillForm.Field.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                val on = (values[f.key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: f.default
                com.meldiq.android.ui.components.GriipSwitch(on, { set(f.key, kotlinx.serialization.json.JsonPrimitive(it)) })
            }
            is SkillForm.Field.Number -> {
                var text by remember(f.key) { mutableStateOf((values[f.key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "") }
                val hint = listOfNotNull(f.min?.let { "≥ ${it.toLong()}" }, f.max?.let { "≤ ${it.toLong()}" }).joinToString(" ")
                GriipTextField(
                    text, { t -> text = t.filter { it.isDigit() || it == '.' || it == '-' }; set(f.key, SkillForm.number(text, f.integer)) }, label,
                    placeholder = f.default?.let { if (f.integer) it.toLong().toString() else it.toString() } ?: "", helper = hint.ifEmpty { null },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
            is SkillForm.Field.MultiChoice -> Column {
                Text(label, style = Griip.type.label, color = c.textSecondary)
                val cur = SkillForm.chosen(values[f.key], f.default)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    f.options.forEach { o -> PillChip(o, o in cur, { set(f.key, SkillForm.choices(if (o in cur) cur - o else cur + o)) }) }
                }
            }
            is SkillForm.Field.Lines -> {
                var text by remember(f.key) { mutableStateOf(SkillForm.linesText(values[f.key])) }
                GriipTextField(text, { text = it; set(f.key, SkillForm.lines(it)) }, label, singleLine = false, minLines = 2)
            }
            is SkillForm.Field.Text -> {
                var text by remember(f.key) { mutableStateOf((values[f.key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "") }
                GriipTextField(text, { t -> text = f.maxLength?.let { t.take(it) } ?: t; set(f.key, kotlinx.serialization.json.JsonPrimitive(text)) }, label, placeholder = f.default.orEmpty())
            }
        }
    }
}

/** Copies a picked document into the app's cache (streamed), with its display name and type; null when unreadable. */
private fun copyIn(context: Context, uri: Uri): Picked? = runCatching {
    val cr = context.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    val dir = File(context.cacheDir, "ai-uploads").apply { mkdirs() }
    val out = File(dir, "${System.nanoTime()}-" + name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120))
    cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    Picked(out, name.take(200), AgentRules.mimeOf(name, cr.getType(uri)))
}.getOrNull()
