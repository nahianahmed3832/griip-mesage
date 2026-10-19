package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.channelIcon
import com.meldiq.android.ui.screens.workspace.ChannelAccess
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/** How many recent files the channel details list (from this phone's history). */
const val CHANNEL_FILES_SHOWN = 20

/** A channel's access mode in the interface language. */
@Composable
fun channelModeLabel(kind: String?): String = when (kind) {
    ChannelAccess.PRIVATE -> stringResource(R.string.ch_mode_private)
    ChannelAccess.ANNOUNCEMENT -> stringResource(R.string.ch_mode_announcement)
    else -> stringResource(R.string.ch_mode_public)
}

/** Who can see, join and post in a channel of this mode, for [scopeName] (the workspace, branch or team). */
@Composable
fun channelModeDescription(kind: String?, scopeName: String): String = when (kind) {
    ChannelAccess.PRIVATE -> stringResource(R.string.ch_mode_private_desc)
    ChannelAccess.ANNOUNCEMENT -> stringResource(R.string.ch_mode_announcement_desc, scopeName)
    else -> stringResource(R.string.ch_mode_public_desc, scopeName)
}

/** Where a channel lives: the whole workspace, a branch or a team. */
@Composable
private fun scopeLine(cv: ConversationEntity): String? {
    val name = cv.scopeName?.takeIf { it.isNotBlank() } ?: return null
    return when (cv.scope) {
        "BRANCH" -> stringResource(R.string.ch_scope_branch, name)
        "TEAM" -> stringResource(R.string.ch_scope_team, name)
        else -> stringResource(R.string.ch_scope_org, name)
    }
}

/** About: the topic, where the channel lives and whether you can post. */
@Composable
fun ChannelAboutCard(cv: ConversationEntity, modifier: Modifier = Modifier) {
    val c = Griip.colors
    GlassCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ch_about), style = Griip.type.label, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        Text(cv.topic?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ch_topic_none), style = Griip.type.body, color = if (cv.topic.isNullOrBlank()) c.textTertiary else c.textPrimary)
        scopeLine(cv)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Griip.type.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        val posting = when {
            cv.archived -> stringResource(R.string.ch_archived_note)
            cv.canPost -> stringResource(R.string.ch_posting_you_can)
            else -> stringResource(R.string.ch_posting_you_cannot)
        }
        Text(posting, style = Griip.type.caption, color = if (cv.archived) c.warning else c.textTertiary)
    }
}

/**
 * Privacy and access: the channel's mode explained, and for the people who manage its scope (the server's
 * `canManageAccess`, servers with `workspace-admin`) a switch between public, private and announcement. Nobody is added
 * or removed by a switch.
 */
@Composable
fun ChannelAccessCard(cv: ConversationEntity, dto: ConversationDto?, featureOn: Boolean?, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scopeName = cv.scopeName ?: ""
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canChange = ChannelAccess.canChange(dto, featureOn)
    GlassCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ch_access), style = Griip.type.label, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(channelIcon(cv.kind), null, tint = c.textPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(channelModeLabel(cv.kind), style = Griip.type.titleSmall, color = c.textPrimary)
                Text(channelModeDescription(cv.kind, scopeName), style = Griip.type.bodySmall, color = c.textSecondary)
            }
        }
        error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(10.dp))
        if (canChange) {
            PillButton(stringResource(R.string.ch_mode_change), { picking = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, loading = busy, height = 44.dp)
        } else {
            Text(stringResource(R.string.ch_mode_who), style = Griip.type.caption, color = c.textTertiary)
        }
    }
    if (picking) GlassSheet({ picking = false }, title = stringResource(R.string.ch_mode_change)) {
        ChannelAccess.MODES.forEach { mode ->
            val selected = mode == cv.kind
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).pressable({ if (!selected) { picking = false; confirm = mode } })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(channelIcon(mode), if (selected) c.primary else c.textSecondary, if (selected) c.primarySoft else c.surfaceVariant, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(channelModeLabel(mode), style = Griip.type.titleSmall, color = c.textPrimary)
                    Text(channelModeDescription(mode, scopeName), style = Griip.type.bodySmall, color = c.textSecondary)
                }
                if (selected) Icon(Icons.Rounded.Check, null, tint = c.primary)
            }
        }
        Text(stringResource(R.string.ch_mode_change_note), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
    }
    confirm?.let { mode ->
        val label = channelModeLabel(mode)
        val done = stringResource(R.string.ch_mode_changed, cv.name.orEmpty(), label)
        ConfirmDialog(
            "${stringResource(R.string.ch_mode_change)}: $label", channelModeDescription(mode, scopeName) + "\n\n" + stringResource(R.string.ch_mode_change_note),
            stringResource(R.string.member_confirm),
            onConfirm = {
                confirm = null
                busy = true
                scope.launch {
                    runCatching { container.conversations.update(cv.id, kind = mode) }
                        .onSuccess { error = null; android.widget.Toast.makeText(context, done, android.widget.Toast.LENGTH_SHORT).show(); onChanged() }
                        .onFailure { error = it.friendly() }
                    busy = false
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

/** Files on this phone: the newest messages with attachments in this conversation (no server index exists). */
fun LazyListScope.channelFilesSection(files: List<MessageEntity>, names: (MessageEntity) -> List<Pair<String, Long>>, onOpen: () -> Unit) {
    item(key = "files-header") { SectionHeader(stringResource(R.string.ch_files), Modifier.padding(top = 12.dp, bottom = 2.dp)) }
    item(key = "files-note") {
        Text(
            stringResource(if (files.isEmpty()) R.string.ch_files_none else R.string.ch_files_note), style = Griip.type.caption, color = Griip.colors.textTertiary,
            modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp),
        )
    }
    items(files, key = { "file" + it.id }) { m ->
        val c = Griip.colors
        names(m).forEachIndexed { i, (name, size) ->
            ListCard(
                name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = listOf(humanSize(size), TimeFmt.listStamp(m.sortTs)).joinToString(" · "),
                leading = { if (i == 0) IconTile(Icons.Outlined.AttachFile, c.textSecondary, c.surfaceVariant, size = 38.dp) else Spacer(Modifier.size(38.dp)) },
                onClick = onOpen,
            )
        }
    }
}
