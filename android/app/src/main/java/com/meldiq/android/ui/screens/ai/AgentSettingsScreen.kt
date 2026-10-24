package com.meldiq.android.ui.screens.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.ai.AiAgentDetail
import com.meldiq.android.data.ai.AiInstall
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

private val STYLES = listOf("balanced", "concise", "detailed", "friendly", "formal")
private val COLORS = listOf("#5B5BD6", "#0EA5E9", "#10B981", "#F59E0B", "#EF4444", "#EC4899", "#8B5CF6", "#64748B")
private val ACTIVATIONS = listOf("mention", "command", "explicit")
private const val AVATAR_PX = 512
private const val AVATAR_MAX_BYTES = 512 * 1024

/**
 * One installed agent's settings (as Meldiq Web's AgentSettings): its identity in this community, how it works, the
 * tools it may use within the platform's policy, its channels and its limits. Only this community's copy changes; the
 * platform's definition and other communities' copies don't. Everyone may look; only "Manage AI agents" can change it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSettingsScreen(nav: NavHostController, orgId: String, installId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AiAgentDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val conversations by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(orgId, installId, reload) {
        runCatching { container.ai.agent(orgId, installId) }.onSuccess { detail = it; error = null }.onFailure { error = it.friendly() }
    }
    val savedText = stringResource(R.string.aw_saved)
    val unreadablePhoto = stringResource(R.string.aw_photo_unreadable)
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        saved = null
        scope.launch {
            runCatching { block() }.onSuccess { saved = savedText; reload++ }.onFailure { error = it.friendly() }
            busy = false
        }
    }
    fun patch(a: AiInstall, fields: Map<String, JsonElement>) = run { container.ai.update(orgId, a.id, JsonObject(fields + ("rev" to JsonPrimitive(a.rev)))) }
    val avatarPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val a = detail?.agent ?: return@rememberLauncherForActivityResult
        if (uri != null) run {
            val bytes = withContext(Dispatchers.IO) { avatarBytes(context, uri) } ?: throw com.meldiq.android.data.api.ApiException(400, "unreadable", unreadablePhoto)
            container.ai.setAvatar(orgId, a.id, bytes)
        }
    }
    val c = Griip.colors
    val d = detail
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        GriipTopBar(d?.agent?.displayName ?: stringResource(R.string.aw_title), subtitle = d?.let { listOfNotNull(it.definition?.name, stringResource(R.string.aw_skill_version, it.agent.skill?.id ?: it.agent.agentId, it.agent.version)).joinToString(" · ") }, onBack = { nav.popBackStack() })
        error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        saved?.let { StatusBanner(it, BannerKind.Success, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
        if (d == null) {
            if (error == null) ShimmerList(4)
            return@Column
        }
        val a = d.agent
        val manage = d.permissions.canManage
        var name by remember(a.rev) { mutableStateOf(a.displayName) }
        var role by remember(a.rev) { mutableStateOf(a.roleTitle) }
        var description by remember(a.rev) { mutableStateOf(a.description) }
        var language by remember(a.rev) { mutableStateOf(a.language.orEmpty()) }
        var style by remember(a.rev) { mutableStateOf(a.style) }
        var instructions by remember(a.rev) { mutableStateOf(a.instructions) }
        var mode by remember(a.rev) { mutableStateOf(a.executionMode) }
        var perDay by remember(a.rev) { mutableStateOf(a.maxJobsPerDay?.toString().orEmpty()) }
        var atOnce by remember(a.rev) { mutableStateOf(a.maxConcurrent?.toString().orEmpty()) }
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAvatar(orgId, a.id, a.displayName, a.avatarColor, a.avatar != null, size = 56.dp)
                if (manage) PillButton(stringResource(R.string.aw_change_avatar), { avatarPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.Image, height = 40.dp, enabled = !busy)
            }
            if (manage) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COLORS.forEach { hex ->
                    val chosen = a.avatarColor.equals(hex, ignoreCase = true)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(com.meldiq.android.data.ai.AgentRules.hexColor(hex) ?: 0))
                            .border(if (chosen) 3.dp else 0.dp, c.textPrimary, CircleShape)
                            .pressable({ if (!chosen) patch(a, mapOf("avatarColor" to JsonPrimitive(hex))) }),
                    )
                }
            }
            d.setup?.takeIf { !it.ready }?.let { StatusBanner(stringResource(R.string.aw_setup_missing, it.missing.joinToString(" ")), BannerKind.Warning) }
            d.upgrade?.let { up ->
                StatusBanner(stringResource(R.string.aw_upgrade, up.version), BannerKind.Info, action = if (manage) stringResource(R.string.aw_upgrade_action) else null, onAction = if (manage) ({ patch(a, mapOf("upgradeTo" to JsonPrimitive(up.version))) }) else null)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.aw_on), style = Griip.type.body, color = c.textPrimary)
                    Text(stringResource(R.string.aw_on_sub), style = Griip.type.caption, color = c.textSecondary)
                }
                GriipSwitch(a.active, { on -> patch(a, mapOf("state" to JsonPrimitive(if (on) "active" else "disabled"))) }, enabled = manage && !busy)
            }
            GriipTextField(name, { name = it.take(40) }, stringResource(R.string.aw_name), helper = stringResource(R.string.aw_name_helper), enabled = manage)
            GriipTextField(role, { role = it.take(60) }, stringResource(R.string.aw_role), enabled = manage)
            GriipTextField(description, { description = it.take(500) }, stringResource(R.string.aw_description), singleLine = false, minLines = 2, enabled = manage)
            GriipTextField(language, { language = it.take(10) }, stringResource(R.string.aw_language), placeholder = stringResource(R.string.aw_language_ph), enabled = manage)
            Text(stringResource(R.string.aw_style), style = Griip.type.label, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                STYLES.forEach { s -> PillChip(s, s == style, { if (manage) style = s }) }
            }
            GriipTextField(instructions, { instructions = it.take(2000) }, stringResource(R.string.aw_instructions), placeholder = stringResource(R.string.aw_instructions_ph), helper = stringResource(R.string.aw_instructions_helper), singleLine = false, minLines = 3, enabled = manage)
            Text(stringResource(R.string.aw_review), style = Griip.type.label, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.aw_mode_review), mode == "review", { if (manage) mode = "review" })
                PillChip(stringResource(R.string.aw_mode_auto), mode == "auto", { if (manage) mode = "auto" })
            }
            if (a.effectiveMode != a.executionMode) Text(stringResource(R.string.aw_mode_forced), style = Griip.type.caption, color = c.warning)
            Text(stringResource(R.string.aw_mode_outside), style = Griip.type.caption, color = c.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GriipTextField(perDay, { perDay = it.filter(Char::isDigit).take(5) }, stringResource(R.string.aw_per_day), Modifier.weight(1f), placeholder = stringResource(R.string.aw_no_limit), enabled = manage,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                GriipTextField(atOnce, { atOnce = it.filter(Char::isDigit).take(2) }, stringResource(R.string.aw_at_once), Modifier.weight(1f), placeholder = stringResource(R.string.aw_platform_default), enabled = manage,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
            if (manage) PillButton(stringResource(R.string.aw_save), {
                patch(
                    a,
                    mapOf(
                        "displayName" to JsonPrimitive(name.trim()), "roleTitle" to JsonPrimitive(role.trim()), "description" to JsonPrimitive(description.trim()),
                        "language" to (language.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull), "style" to JsonPrimitive(style),
                        "instructions" to JsonPrimitive(instructions.trim()), "executionMode" to JsonPrimitive(mode),
                        "maxJobsPerDay" to (perDay.toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull),
                        "maxConcurrent" to (atOnce.toIntOrNull()?.coerceIn(1, 16)?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                )
            }, Modifier.fillMaxWidth(), enabled = !busy && name.trim().length >= 2, loading = busy)
        }
        SectionHeader(stringResource(R.string.aw_tools), Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text(stringResource(R.string.aw_tools_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter))
        d.tools.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.label + if (t.required) " · " + stringResource(R.string.aw_tool_needed) else "", style = Griip.type.body, color = c.textPrimary)
                    val notes = listOfNotNull(t.description.ifBlank { null }, stringResource(R.string.aw_tool_not_connected).takeIf { !t.connected }, stringResource(R.string.aw_tool_approval).takeIf { t.platformMode == "approval" })
                    Text(notes.joinToString(" · "), style = Griip.type.caption, color = c.textSecondary)
                }
                GriipSwitch(
                    t.granted,
                    { on -> patch(a, mapOf("tools" to JsonArray((if (on) a.tools + t.key else a.tools - t.key).distinct().map(::JsonPrimitive)))) },
                    enabled = manage && !busy && !(t.required && t.granted),
                )
            }
        }
        SectionHeader(stringResource(R.string.aw_channels), Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text(stringResource(R.string.aw_channels_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter))
        val assigned = d.channels.associateBy { it.conversationId }
        val channels = conversations.filter { it.orgId == orgId && it.type == "CHANNEL" && !it.archived && !it.voice && !it.removed }.sortedBy { it.name.orEmpty().lowercase() }
        if (channels.isEmpty()) Text(stringResource(R.string.aw_channels_none), style = Griip.type.body, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
        channels.forEach { ch ->
            val on = assigned[ch.id]
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("# " + ch.name.orEmpty(), style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    GriipSwitch(on != null, { yes ->
                        run { if (yes) container.ai.assign(orgId, a.id, ch.id, "mention") else container.ai.unassign(orgId, a.id, ch.id) }
                    }, enabled = manage && !busy)
                }
                if (on != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ACTIVATIONS.forEach { act ->
                        val label = stringResource(
                            when (act) {
                                "mention" -> R.string.aw_activation_mention
                                "command" -> R.string.aw_activation_command
                                else -> R.string.aw_activation_explicit
                            },
                        )
                        PillChip(label, on.activation == act, { if (manage && on.activation != act) run { container.ai.assign(orgId, a.id, ch.id, act) } })
                    }
                }
            }
        }
        if (manage) PillButton(
            stringResource(R.string.aw_uninstall), { removing = true }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 20.dp),
            tone = ButtonTone.Danger, leadingIcon = Icons.Outlined.DeleteForever, enabled = !busy,
        )
        if (removing) ConfirmDialog(stringResource(R.string.aw_uninstall), stringResource(R.string.aw_uninstall_body, a.displayName), stringResource(R.string.aw_uninstall), onConfirm = {
            removing = false
            scope.launch { runCatching { container.ai.uninstall(orgId, a.id) }.onSuccess { nav.popBackStack() }.onFailure { error = it.friendly() } }
        }, onDismiss = { removing = false }, destructive = true)
    }
}

/** A picked photo as an avatar: at most 512 px and 512 KB, JPEG (the server takes PNG, JPEG or WebP). */
private fun avatarBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? {
    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val scale = minOf(1f, AVATAR_PX.toFloat() / maxOf(src.width, src.height))
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
    var quality = 90
    while (true) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= AVATAR_MAX_BYTES || quality <= 40) return out.toByteArray()
        quality -= 15
    }
}
