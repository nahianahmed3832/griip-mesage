package com.griipbuddy.message.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.CountBadge
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.TagPill
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes

fun channelIcon(kind: String?): ImageVector = when (kind) {
    "PRIVATE" -> Icons.Outlined.Lock
    "ANNOUNCEMENT" -> Icons.Outlined.Campaign
    else -> Icons.Outlined.Tag
}

/**
 * People are circles with initials, groups are circles with a group icon, and org channels are rounded
 * squares with their channel mark, so a personal group never looks like an organisation channel.
 */
@Composable
fun ConversationAvatar(conv: ConversationEntity, myUserId: String?, size: androidx.compose.ui.unit.Dp = 52.dp, online: Boolean? = null) {
    val c = Griip.colors
    when (conv.type) {
        "DM" -> {
            val peer = ConversationRepository.peer(conv, myUserId)
            Avatar(peer?.displayName ?: "?", peer?.avatarColor ?: 0, size = size, online = online)
        }
        "GROUP" -> Box(Modifier.size(size).clip(CircleShape).background(c.lavenderSoft).semantics { contentDescription = "Group" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Groups, null, tint = c.lavender, modifier = Modifier.size(size * 0.5f))
        }
        else -> {
            val (fg, bg) = when (conv.kind) {
                "ANNOUNCEMENT" -> c.peach to c.peachSoft
                "PRIVATE" -> c.lavender to c.lavenderSoft
                else -> c.primaryDeep to c.primarySoft
            }
            IconTile(channelIcon(conv.kind), fg, bg, size = size, modifier = Modifier.semantics { contentDescription = "Channel" })
        }
    }
}

/** What the second line of a chat row shows, in priority order. Pure, so it is unit-tested. */
data class ChatPreview(val prefix: String?, val text: String, val kind: Kind) {
    enum class Kind { Typing, Draft, Message, Empty }

    companion object {
        fun of(conv: ConversationEntity, typingNames: List<String>): ChatPreview {
            if (typingNames.isNotEmpty()) {
                return ChatPreview(null, if (conv.type == "DM") "typing…" else "${typingNames.first()} is typing…", Kind.Typing)
            }
            val draft = conv.draft?.trim().orEmpty()
            if (draft.isNotEmpty()) return ChatPreview("Draft", draft.replace('\n', ' '), Kind.Draft)
            val last = conv.lastMessagePreview
            if (last != null) {
                val who = conv.lastSenderName
                val prefix = when {
                    who == "You" -> "You"
                    conv.type != "DM" && who != null -> who
                    else -> null
                }
                return ChatPreview(prefix, last.replace('\n', ' '), Kind.Message)
            }
            if (conv.type == "CHANNEL") {
                val about = listOfNotNull(conv.scopeName, conv.topic).joinToString(" · ")
                if (about.isNotEmpty()) return ChatPreview(null, about, Kind.Empty)
            }
            return ChatPreview(null, "No messages yet", Kind.Empty)
        }
    }
}

/** A dense, flat chat-list row: avatar, name, time, preview, and the indicators that apply. */
@Composable
fun ConversationRow(
    conv: ConversationEntity,
    myUserId: String?,
    typingNames: List<String>,
    online: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val c = Griip.colors
    val title = ConversationRepository.title(conv, myUserId).let { if (conv.type == "CHANNEL") "#$it" else it }
    val unread = conv.unreadCount > 0
    val preview = ChatPreview.of(conv, typingNames)
    val stamp = TimeFmt.listStamp(if (conv.lastMessageAt > 0) conv.lastMessageAt else conv.serverUpdatedAt)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = if (onLongClick != null) "More actions" else null,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationAvatar(conv, myUserId, online = online)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = Griip.type.titleSmall.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold),
                            color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (conv.muted) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.NotificationsOff, "Muted", tint = c.textTertiary, modifier = Modifier.size(15.dp))
                        }
                        if (conv.activeCallId != null) {
                            Spacer(Modifier.width(6.dp))
                            TagPill("Live", c.success, c.successSoft, icon = Icons.Rounded.Call)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stamp, style = Griip.type.caption.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium), color = if (unread && !conv.muted) c.primary else c.textSecondary)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val line = buildAnnotatedString {
                        preview.prefix?.let { p ->
                            withStyle(SpanStyle(color = if (preview.kind == ChatPreview.Kind.Draft) c.danger else c.textSecondary, fontWeight = FontWeight.SemiBold)) { append("$p: ") }
                        }
                        append(preview.text)
                    }
                    Text(
                        line, style = Griip.type.bodySmall,
                        color = when {
                            preview.kind == ChatPreview.Kind.Typing -> c.primary
                            unread -> c.textPrimary
                            else -> c.textSecondary
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (conv.mentionCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(20.dp).clip(Shapes.pill).background(c.primary).semantics { contentDescription = "Mentioned" }, contentAlignment = Alignment.Center) {
                            Text("@", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = c.onPrimary)
                        }
                    }
                    if (unread) {
                        Spacer(Modifier.width(6.dp))
                        CountBadge(conv.unreadCount, muted = conv.muted)
                    }
                }
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = 16.dp + 52.dp + 14.dp), thickness = 1.dp, color = c.border)
    }
}

