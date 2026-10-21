package com.meldiq.android.ui.screens.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.GuideDto
import com.meldiq.android.data.api.GuideItemDto
import com.meldiq.android.data.api.OnboardingDto
import com.meldiq.android.data.api.OnboardingOptionDto
import com.meldiq.android.data.api.OnboardingPromptDto
import com.meldiq.android.data.api.OnboardingPutReq
import com.meldiq.android.data.model.GuideRules
import com.meldiq.android.data.model.OnboardingRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * The community guide on the community's home (batch B8b, Discord reference COMM-CTRL-003/004/005): the welcome line,
 * "Get started" tasks ticked once you open their channel (on this phone), and resources.
 */
@Composable
fun GuideCard(guide: GuideDto, done: List<String>, onOpenChannel: (String) -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.b8b_guide), style = Griip.type.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.b8b_guide_hide), onHide, size = 32.dp, tint = c.textSecondary)
        }
        guide.welcome?.let { Text(it, style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp)) }
        if (guide.tasks.isNotEmpty()) {
            val (n, of) = GuideRules.progress(guide, done)
            Text(stringResource(R.string.b8b_get_started, n, of), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            guide.tasks.forEach { t ->
                val ticked = t.id in done
                Row(Modifier.fillMaxWidth().pressable({ onOpenChannel(t.channelId) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ticked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (ticked) c.success else c.textTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(t.title, style = Griip.type.bodyMedium, color = if (ticked) c.textSecondary else c.textPrimary)
                }
            }
        }
        if (guide.resources.isNotEmpty()) {
            Text(stringResource(R.string.b8b_resources), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            guide.resources.forEach { r ->
                Row(Modifier.fillMaxWidth().pressable({ onOpenChannel(r.channelId) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tag, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(r.title, style = Griip.type.bodyMedium, color = c.textPrimary)
                }
            }
        }
    }
}

/** Answering the community's onboarding questions (batch B8b): one card per question; required ones must be answered. */
@Composable
fun OnboardingSheet(orgId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<OnboardingDto?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(orgId) {
        runCatching { container.guide.onboarding(orgId) }.onSuccess { data = it; chosen = it.answered.orEmpty().toSet() }.onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.b8b_onboarding_title)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.b8b_onboarding_intro), style = Griip.type.bodySmall, color = c.textSecondary)
            data?.prompts?.forEach { p ->
                Spacer(Modifier.height(12.dp))
                Text(p.title + if (p.required) " *" else "", style = Griip.type.bodyMedium, color = c.textPrimary)
                Text(stringResource(if (p.single) R.string.poll_pick_one else R.string.poll_pick_several), style = Griip.type.caption, color = c.textSecondary)
                ChipRow(Modifier.padding(horizontal = 0.dp)) {
                    p.options.forEach { o ->
                        val id = o.id ?: return@forEach
                        PillChip(listOfNotNull(o.emoji, o.title).joinToString(" "), id in chosen, { chosen = OnboardingRules.toggle(chosen, p, id) })
                    }
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(14.dp))
            val missing = data?.prompts?.let { OnboardingRules.missing(it, chosen) }
            PillButton(stringResource(R.string.b8b_onboarding_done), {
                busy = true
                scope.launch {
                    runCatching { container.guide.answer(orgId, chosen.toList()) }.onSuccess { onDone() }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = data != null && missing == null, loading = busy, height = 50.dp)
        }
    }
}

/** Editing the community guide (people who can edit the community). */
@Composable
fun GuideEditorSheet(orgId: String, current: GuideDto?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var welcome by remember { mutableStateOf(current?.welcome.orEmpty()) }
    val tasks = remember { mutableStateListOf(*current?.tasks.orEmpty().toTypedArray()) }
    val resources = remember { mutableStateListOf(*current?.resources.orEmpty().toTypedArray()) }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orgId) { channels = runCatching { container.orgs.channels(orgId).filter { !it.archived } }.getOrDefault(emptyList()) }
    GlassSheet(onDismiss, title = stringResource(R.string.b8b_guide)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GriipTextField(welcome, { welcome = it.take(500) }, stringResource(R.string.b8b_guide_welcome), singleLine = false, minLines = 2)
            GuideItemsEditor(stringResource(R.string.b8b_guide_tasks), tasks, channels)
            GuideItemsEditor(stringResource(R.string.b8b_resources), resources, channels)
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(14.dp))
            PillButton(stringResource(R.string.ja_save), {
                scope.launch {
                    runCatching { container.guide.setGuide(orgId, GuideDto(welcome.trim().ifEmpty { null }, tasks.toList(), resources.toList())) }
                        .onSuccess { onSaved(); onDismiss() }.onFailure { error = it.friendly() }
                }
            }, Modifier.fillMaxWidth(), height = 50.dp)
            if (current != null) PillButton(stringResource(R.string.b8b_guide_remove), {
                scope.launch { runCatching { container.guide.setGuide(orgId, null) }.onSuccess { onSaved(); onDismiss() }.onFailure { error = it.friendly() } }
            }, Modifier.fillMaxWidth().padding(vertical = 8.dp), tone = ButtonTone.Glass, height = 46.dp)
        }
    }
}

@Composable
private fun GuideItemsEditor(label: String, items: MutableList<GuideItemDto>, channels: List<ConversationDto>) {
    val c = Griip.colors
    var title by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf<String?>(null) }
    Text(label, style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.padding(top = 14.dp))
    items.forEachIndexed { i, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.title} · #${channels.firstOrNull { it.id == item.channelId }?.name.orEmpty()}", style = Griip.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            CircleIconButton(Icons.Outlined.Close, stringResource(R.string.b8b_remove_item), { items.removeAt(i) }, size = 34.dp, tint = c.textSecondary)
        }
    }
    if (items.size < GUIDE_ITEMS_MAX) {
        GriipTextField(title, { title = it.take(80) }, stringResource(R.string.b8b_item_title))
        ChipRow(Modifier.padding(horizontal = 0.dp)) { channels.forEach { ch -> PillChip("#" + ch.name.orEmpty(), channelId == ch.id, { channelId = ch.id }) } }
        PillButton(stringResource(R.string.b8b_add_item), {
            val ch = channelId ?: return@PillButton
            if (title.isBlank()) return@PillButton
            items.add(GuideItemDto(null, title.trim(), ch))
            title = ""
            channelId = null
        }, Modifier.padding(top = 4.dp), enabled = title.isNotBlank() && channelId != null, tone = ButtonTone.Glass, height = 40.dp)
    }
}

/**
 * Editing onboarding questions (people who can edit the community). An answer can give roles without administrative
 * permissions, and open public channels; the server checks both again.
 */
@Composable
fun OnboardingEditorSheet(orgId: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    val prompts = remember { mutableStateListOf<OnboardingPromptDto>() }
    var roles by remember { mutableStateOf<List<CustomRoleDto>>(emptyList()) }
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(orgId) {
        runCatching { container.guide.onboarding(orgId) }.onSuccess { enabled = it.enabled; prompts.clear(); prompts.addAll(it.prompts) }.onFailure { error = it.friendly() }
        runCatching { container.roles.roles(orgId) }.onSuccess { r ->
            val admin = r.catalog.filter { it.admin }.map { it.key }.toSet()
            roles = r.roles.filter { role -> role.permissions.none { it in admin } }
        }
        channels = runCatching { container.orgs.channels(orgId).filter { !it.archived && it.kind == "PUBLIC" } }.getOrDefault(emptyList())
    }
    GlassSheet(onDismiss, title = stringResource(R.string.b8b_onboarding_title)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.b8b_onboarding_on), style = Griip.type.bodyMedium, color = c.textPrimary)
                    Text(stringResource(R.string.b8b_onboarding_on_sub), style = Griip.type.caption, color = c.textSecondary)
                }
                GriipSwitch(enabled, { enabled = it })
            }
            prompts.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${p.title} (${p.options.size})", style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f).pressable({ editing = i }))
                    CircleIconButton(Icons.Outlined.Close, stringResource(R.string.b8b_remove_item), { prompts.removeAt(i) }, size = 34.dp, tint = c.textSecondary)
                }
            }
            if (prompts.size < PROMPTS_MAX) PillButton(stringResource(R.string.b8b_add_question), { editing = -1 }, Modifier.padding(top = 8.dp), tone = ButtonTone.Glass, height = 40.dp)
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(14.dp))
            PillButton(stringResource(R.string.ja_save), {
                scope.launch {
                    runCatching { container.guide.setOnboarding(orgId, OnboardingPutReq(enabled, prompts.toList())) }
                        .onSuccess { onSaved(); onDismiss() }.onFailure { error = it.friendly() }
                }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = !enabled || prompts.isNotEmpty(), height = 50.dp)
        }
    }
    editing?.let { index ->
        PromptEditorSheet(
            prompts.getOrNull(index), roles, channels,
            onDismiss = { editing = null },
            onSave = { p -> if (index in prompts.indices) prompts[index] = p else prompts.add(p); editing = null },
        )
    }
}

@Composable
private fun PromptEditorSheet(current: OnboardingPromptDto?, roles: List<CustomRoleDto>, channels: List<ConversationDto>, onDismiss: () -> Unit, onSave: (OnboardingPromptDto) -> Unit) {
    val c = Griip.colors
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    var single by remember { mutableStateOf(current?.single ?: true) }
    var required by remember { mutableStateOf(current?.required ?: false) }
    val options = remember { mutableStateListOf(*current?.options.orEmpty().toTypedArray()) }
    var optTitle by remember { mutableStateOf("") }
    var optRoles by remember { mutableStateOf(emptySet<String>()) }
    var optChannels by remember { mutableStateOf(emptySet<String>()) }
    GlassSheet(onDismiss, title = stringResource(R.string.b8b_question)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GriipTextField(title, { title = it.take(100) }, stringResource(R.string.b8b_question))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.b8b_single), style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                GriipSwitch(single, { single = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.b8b_required), style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                GriipSwitch(required, { required = it })
            }
            Text(stringResource(R.string.b8b_answers), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
            options.forEachIndexed { i, o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val gives = (o.roleIds.mapNotNull { r -> roles.firstOrNull { it.id == r }?.name } + o.channelIds.mapNotNull { id -> channels.firstOrNull { it.id == id }?.name?.let { "#$it" } }).joinToString(", ")
                    Text(o.title + if (gives.isNotEmpty()) " → $gives" else "", style = Griip.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Outlined.Close, stringResource(R.string.b8b_remove_item), { options.removeAt(i) }, size = 34.dp, tint = c.textSecondary)
                }
            }
            if (options.size < OPTIONS_MAX) {
                GriipTextField(optTitle, { optTitle = it.take(100) }, stringResource(R.string.b8b_answer))
                if (roles.isNotEmpty()) {
                    Text(stringResource(R.string.b8b_gives_roles), style = Griip.type.caption, color = c.textSecondary)
                    ChipRow(Modifier.padding(horizontal = 0.dp)) { roles.forEach { r -> PillChip(r.name, r.id in optRoles, { optRoles = if (r.id in optRoles) optRoles - r.id else optRoles + r.id }) } }
                }
                Text(stringResource(R.string.b8b_opens_channels), style = Griip.type.caption, color = c.textSecondary)
                ChipRow(Modifier.padding(horizontal = 0.dp)) { channels.forEach { ch -> PillChip("#" + ch.name.orEmpty(), ch.id in optChannels, { optChannels = if (ch.id in optChannels) optChannels - ch.id else optChannels + ch.id }) } }
                PillButton(stringResource(R.string.b8b_add_answer), {
                    options.add(OnboardingOptionDto(null, optTitle.trim(), null, optRoles.toList(), optChannels.toList()))
                    optTitle = ""; optRoles = emptySet(); optChannels = emptySet()
                }, Modifier.padding(top = 4.dp), enabled = optTitle.isNotBlank(), tone = ButtonTone.Glass, height = 40.dp)
            }
            Spacer(Modifier.height(14.dp))
            PillButton(stringResource(R.string.ja_save), { onSave(OnboardingPromptDto(current?.id, title.trim(), single, required, options.toList())) },
                Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = title.isNotBlank() && options.isNotEmpty(), height = 48.dp)
        }
    }
}

private const val GUIDE_ITEMS_MAX = 5
private const val PROMPTS_MAX = 5
private const val OPTIONS_MAX = 10
