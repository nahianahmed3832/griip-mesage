package com.meldiq.android.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ChannelHitDto
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.api.WorkspaceHitDto
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.data.repo.ConversationRepository
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.OrgMark
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.screens.chats.ConversationAvatar
import com.meldiq.android.ui.screens.chats.channelIcon
import com.meldiq.android.ui.screens.conversation.humanSize
import com.meldiq.android.ui.theme.Griip

// The rows of the search screen (round 7 slice C2). Each opens the original item where the person can see it.

private val rowPadding = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)

@Composable
internal fun RecentRow(q: String, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(q, rowPadding, leading = { IconTile(Icons.Outlined.History, c.textSecondary, c.surfaceVariant, size = 40.dp) }, onClick = onClick)
}

@Composable
internal fun ShowMoreRow(onClick: () -> Unit) {
    PillButton(stringResource(R.string.search_more), onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), tone = ButtonTone.Glass, height = 40.dp)
}

@Composable
internal fun ConversationHit(conv: ConversationEntity, me: String?, onClick: () -> Unit) {
    ListCard(
        ConversationRepository.title(conv, me).let { if (conv.type == "CHANNEL") "#$it" else it }, rowPadding,
        subtitle = conv.scopeName ?: conv.topic,
        leading = { ConversationAvatar(conv, me, size = 44.dp) },
        trailing = { ArrowKnob() },
        onClick = onClick,
    )
}

@Composable
internal fun ChannelHit(ch: ChannelHitDto, onOpen: () -> Unit, onJoin: () -> Unit) {
    val c = Griip.colors
    ListCard(
        "#${ch.name.orEmpty()}", rowPadding,
        subtitle = listOfNotNull(ch.orgName, ch.topic ?: ch.scopeName).joinToString(" · ").ifBlank { null },
        leading = { IconTile(channelIcon(ch.kind), c.textSecondary, c.surfaceVariant, size = 44.dp) },
        trailing = {
            if (ch.joined) ArrowKnob() else PillButton(stringResource(R.string.search_join), onJoin, tone = ButtonTone.Glass, height = 36.dp)
        },
        onClick = if (ch.joined) onOpen else onJoin,
    )
}

@Composable
internal fun WorkspaceHit(o: WorkspaceHitDto, onClick: () -> Unit) {
    ListCard(o.name, rowPadding, subtitle = "@${o.slug}", leading = { OrgMark(o.id, o.name, o.id.hashCode(), null, size = 44.dp) }, trailing = { ArrowKnob() }, onClick = onClick)
}

@Composable
internal fun GroupHit(g: ConversationDto, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(
        g.name.orEmpty(), rowPadding, subtitle = stringResource(R.string.search_group_members, g.memberCount),
        leading = { IconTile(Icons.Outlined.Groups, c.primary, c.primarySoft, size = 44.dp) }, trailing = { ArrowKnob() }, onClick = onClick,
    )
}

@Composable
internal fun PersonHit(u: UserDto, onClick: () -> Unit) {
    ListCard(
        u.displayName, rowPadding, subtitle = "@${u.username}",
        leading = { Avatar(u.displayName, u.avatarColor, size = 44.dp, online = u.online) },
        trailing = { ArrowKnob(contentDescription = "Message ${u.displayName}") },
        onClick = onClick,
    )
}

@Composable
internal fun MessageHit(m: MessageEntity, conv: ConversationEntity?, me: String?, pinned: Boolean, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(
        conv?.let { ConversationRepository.title(it, me) } ?: stringResource(R.string.search_conversation),
        rowPadding,
        subtitle = m.text,
        leading = if (pinned) ({ IconTile(Icons.Outlined.PushPin, c.primary, c.primarySoft, size = 44.dp) }) else conv?.let { cv -> { ConversationAvatar(cv, me, size = 44.dp) } },
        trailing = { Text(TimeFmt.listStamp(m.sortTs), style = Griip.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
    )
}

@Composable
internal fun FileHit(ref: AttachmentRef, conv: ConversationEntity?, me: String?, ts: Long, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(
        ref.name, rowPadding,
        subtitle = listOfNotNull(conv?.let { ConversationRepository.title(it, me) }, humanSize(ref.size), TimeFmt.listStamp(ts)).joinToString(" · "),
        leading = { IconTile(Icons.Outlined.Description, c.textSecondary, c.surfaceVariant, size = 44.dp) },
        trailing = { ArrowKnob() },
        onClick = onClick,
    )
}
