package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.CreateChannelReq
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.model.ChannelType
import com.meldiq.android.data.model.ChannelTypes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

@Composable
internal fun Label(text: String) {
    Text(text, style = Griip.type.label, color = Griip.colors.textSecondary)
    Spacer(Modifier.height(6.dp))
}

/**
 * A new channel: its type (text, announcements, forum, voice), whether it's private, and its category. Only the
 * categories this person may create channels in are offered ("No category" needs the community-wide power); the server
 * checks again.
 */
@Composable
fun CreateChannelSheet(
    d: OrgDetailRes,
    preselectCategory: String?,
    onDismiss: () -> Unit,
    /** A private channel starts with only you: [onPrivateCreated] opens its member list to add people (like the reference's second step). */
    onPrivateCreated: ((String) -> Unit)? = null,
    onDone: (String) -> Unit,
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val communityWide = "channels.create" in d.permissions.effective || d.permissions.canManageOrg
    val categories = d.categories.filter { it.canCreateChannels }
    var categoryId by remember { mutableStateOf(preselectCategory?.takeIf { id -> categories.any { it.id == id } } ?: if (communityWide) null else categories.firstOrNull()?.id) }
    var type by remember { mutableStateOf(ChannelType.TEXT) }
    var private by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = stringResource(R.string.cm_new_channel)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Label(stringResource(R.string.ch_type))
            ChipRow {
                PillChip(stringResource(R.string.ch_text), type == ChannelType.TEXT, { type = ChannelType.TEXT }, icon = Icons.Outlined.Tag)
                PillChip(stringResource(R.string.ch_announcement), type == ChannelType.ANNOUNCEMENT, { type = ChannelType.ANNOUNCEMENT; private = false }, icon = Icons.Outlined.Campaign)
                PillChip(stringResource(R.string.ch_forum), type == ChannelType.FORUM, { type = ChannelType.FORUM }, icon = Icons.Outlined.Forum)
                PillChip(stringResource(R.string.ch_voice), type == ChannelType.VOICE, { type = ChannelType.VOICE }, icon = Icons.Outlined.Headset)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (type) {
                        ChannelType.TEXT -> R.string.ch_text_sub
                        ChannelType.ANNOUNCEMENT -> R.string.ch_announcement_sub
                        ChannelType.FORUM -> R.string.ch_forum_sub
                        ChannelType.VOICE -> R.string.ch_voice_sub
                    },
                ),
                style = Griip.type.caption, color = Griip.colors.textTertiary,
            )
            if (type != ChannelType.ANNOUNCEMENT) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ch_private), style = Griip.type.bodyMedium, color = Griip.colors.textPrimary)
                        Text(stringResource(R.string.ch_private_sub), style = Griip.type.caption, color = Griip.colors.textTertiary)
                    }
                    Switch(private, { private = it })
                }
            }
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.ch_category))
                ChipRow {
                    if (communityWide) PillChip(stringResource(R.string.cm_no_category), categoryId == null, { categoryId = null })
                    categories.forEach { cat -> PillChip(cat.name, categoryId == cat.id, { categoryId = cat.id }) }
                }
            }
            Spacer(Modifier.height(12.dp))
            GriipTextField(name, { name = it.lowercase().replace(' ', '-').take(60) }, stringResource(R.string.ch_name), placeholder = "general", prefix = if (type == ChannelType.VOICE) null else "#")
            Spacer(Modifier.height(12.dp))
            GriipTextField(topic, { topic = it.take(300) }, stringResource(R.string.ch_topic))
            error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(16.dp))
            PillButton(stringResource(R.string.ch_create), {
                busy = true
                val (kind, voice, format) = ChannelTypes.request(type, private)
                scope.launch {
                    runCatching {
                        container.orgs.createChannel(d.org.id, CreateChannelReq(name.trim(), topic.trim().ifEmpty { null }, kind, voice = voice, categoryId = categoryId, format = format))
                    }.onSuccess { onDismiss(); if (kind == "PRIVATE" && onPrivateCreated != null) onPrivateCreated(it.id) else onDone(it.id) }.onFailure { error = it.friendly() }
                    busy = false
                }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = name.isNotBlank() && (communityWide || categoryId != null), loading = busy, height = 50.dp)
        }
    }
}
