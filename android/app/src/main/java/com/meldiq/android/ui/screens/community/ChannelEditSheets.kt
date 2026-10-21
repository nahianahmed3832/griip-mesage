package com.meldiq.android.ui.screens.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.meldiq.android.data.api.ForumTagReq
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.model.ForumRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * Edit channel (managers): name, topic, text or forum, private, and a forum's tags. Only what changed is sent; the
 * server checks every field again (access needs `channels.visibility`).
 */
@Composable
fun EditChannelSheet(conv: ConversationEntity, canChangeAccess: Boolean, tagsAvailable: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = Griip.colors
    val initialTags = remember(conv.id) { container.conversations.forumTags(conv) }
    var name by remember { mutableStateOf(conv.name.orEmpty()) }
    var topic by remember { mutableStateOf(conv.topic.orEmpty()) }
    var forum by remember { mutableStateOf(conv.format == "forum") }
    var private by remember { mutableStateOf(conv.kind == "PRIVATE") }
    val tags = remember(conv.id) { mutableStateListOf(*initialTags.map { it.id to it.name }.toTypedArray()) }
    var newTag by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canBeForum = !conv.voice && conv.kind != "ANNOUNCEMENT"
    // Batch B3: slowmode for text channels, on servers that enforce it.
    val initialSlowmode = remember(conv.id) { container.conversations.slowmode.value[conv.id]?.seconds ?: 0 }
    var slowmode by remember { mutableStateOf(initialSlowmode) }
    var slowmodeAvailable by remember { mutableStateOf(false) }
    // Batch B8: a forum's post guidelines.
    val initialGuidelines = remember(conv.id) { container.conversations.guidelines.value[conv.id].orEmpty() }
    var guidelines by remember { mutableStateOf(initialGuidelines) }
    var guidelinesAvailable by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        slowmodeAvailable = !conv.voice && container.features.has(com.meldiq.android.data.repo.ServerFeatures.SLOWMODE) == true
        guidelinesAvailable = container.features.has(com.meldiq.android.data.repo.ServerFeatures.FORUM_GUIDELINES) == true
    }
    GlassSheet(onDismiss, title = stringResource(R.string.nv_edit_channel)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GriipTextField(name, { name = if (conv.voice) it.take(60) else it.lowercase().replace(' ', '-').take(60) }, stringResource(R.string.ch_name), prefix = if (conv.voice) null else "#")
            Spacer(Modifier.height(12.dp))
            GriipTextField(topic, { topic = it.take(300) }, stringResource(R.string.ch_topic), singleLine = false, minLines = 2)
            if (canBeForum) {
                Spacer(Modifier.height(12.dp))
                SwitchLine(stringResource(R.string.ch_forum), stringResource(R.string.ch_forum_sub), forum) { forum = it }
            }
            if (canChangeAccess && conv.kind != "ANNOUNCEMENT") {
                Spacer(Modifier.height(8.dp))
                SwitchLine(stringResource(R.string.ch_private), stringResource(R.string.ch_private_sub), private) { private = it }
            }
            if (slowmodeAvailable) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.sm_title), style = Griip.type.bodyMedium, color = c.textPrimary)
                Text(stringResource(R.string.sm_body), style = Griip.type.caption, color = c.textSecondary)
                com.meldiq.android.ui.components.ChipRow(Modifier.padding(horizontal = 0.dp)) {
                    com.meldiq.android.data.model.SlowmodeRules.STEPS.forEach { step -> PillChip(slowmodeLabel(step), slowmode == step, { slowmode = step }) }
                }
            }
            if (forum && guidelinesAvailable) {
                Spacer(Modifier.height(14.dp))
                GriipTextField(guidelines, { guidelines = it.take(GUIDELINES_MAX) }, stringResource(R.string.b8_guidelines), singleLine = false, minLines = 3,
                    helper = stringResource(R.string.b8_guidelines_help))
            }
            if (forum && tagsAvailable) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.nv_forum_tags), style = Griip.type.bodyMedium, color = c.textPrimary)
                Text(stringResource(R.string.nv_forum_tags_sub), style = Griip.type.caption, color = c.textSecondary)
                Spacer(Modifier.height(8.dp))
                tags.forEachIndexed { i, (_, tagName) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillChip(tagName, true, {}, modifier = Modifier.weight(1f, fill = false))
                        CircleIconButton(Icons.Outlined.Close, stringResource(R.string.nv_remove_tag, tagName), { tags.removeAt(i) }, tint = c.textSecondary, size = 36.dp)
                    }
                }
                if (tags.size < ForumRules.MAX_FORUM_TAGS) Row(verticalAlignment = Alignment.CenterVertically) {
                    GriipTextField(newTag, { newTag = it.take(ForumRules.MAX_TAG_NAME) }, stringResource(R.string.nv_new_tag), modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.Add, stringResource(R.string.nv_add_tag), {
                        val clean = ForumRules.cleanTagNames(listOf(newTag)).firstOrNull()
                        if (clean != null && tags.none { it.second.equals(clean, ignoreCase = true) }) tags.add("" to clean)
                        newTag = ""
                    }, tint = c.primary)
                }
            }
            error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(16.dp))
            PillButton(stringResource(R.string.cm_save), {
                busy = true
                error = null
                scope.launch {
                    val wantTags = tags.map { (id, n) -> ForumTagReq(id.ifEmpty { null }, n) }
                    val tagsChanged = forum && tagsAvailable && wantTags.map { it.name } != initialTags.map { it.name }
                    runCatching {
                        container.conversations.update(
                            conv.id,
                            name = name.trim().takeIf { it != conv.name },
                            topic = topic.trim().takeIf { it != conv.topic.orEmpty() },
                            kind = (if (private) "PRIVATE" else "PUBLIC").takeIf { canChangeAccess && conv.kind != "ANNOUNCEMENT" && it != conv.kind },
                            format = (if (forum) "forum" else "text").takeIf { canBeForum && it != conv.format },
                            forumTags = wantTags.takeIf { tagsChanged },
                            slowmodeSec = slowmode.takeIf { slowmodeAvailable && it != initialSlowmode },
                            guidelines = guidelines.trim().takeIf { forum && guidelinesAvailable && it != initialGuidelines },
                        )
                    }.onSuccess { onDismiss(); onSaved() }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = name.isNotBlank(), loading = busy, height = 50.dp)
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Griip.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Griip.type.bodyMedium, color = c.textPrimary)
            Text(subtitle, style = Griip.type.caption, color = c.textTertiary)
        }
        GriipSwitch(checked, onChange)
    }
}

/**
 * Deleting a channel for everyone: says what goes, and asks for the channel's name (servers with `channel-delete`).
 * Archiving is offered alongside as the reversible choice.
 */
@Composable
fun DeleteChannelSheet(conv: ConversationEntity, onDismiss: () -> Unit, onArchive: (() -> Unit)?, onDeleted: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val name = conv.name.orEmpty()
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = stringResource(R.string.nv_delete_channel_title, name)) {
        Text(stringResource(R.string.nv_delete_channel_body), style = Griip.type.bodySmall, color = Griip.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        GriipTextField(typed, { typed = it.take(80) }, stringResource(R.string.nv_type_name_to_delete, name), placeholder = name)
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.nv_delete_channel), {
            busy = true
            scope.launch {
                runCatching { container.conversations.deleteChannel(conv.id, typed) }
                    .onSuccess { onDismiss(); onDeleted() }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, enabled = CommunityMenuRules.deleteConfirmed(name, typed), loading = busy, height = 50.dp)
        onArchive?.let {
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.nv_archive_instead), { onDismiss(); it() }, Modifier.fillMaxWidth().padding(bottom = 8.dp), tone = ButtonTone.Glass, height = 50.dp)
        }
    }
}

/** Batch B8: the server's limit for a forum's post guidelines. */
private const val GUIDELINES_MAX = 2000
