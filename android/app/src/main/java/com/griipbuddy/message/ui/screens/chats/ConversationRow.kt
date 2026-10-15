package com.griipbuddy.message.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.data.repo.ConversationRepository
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.CountBadge
import com.griipbuddy.message.ui.components.GlassCard
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.TagPill
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes

fun channelIcon(kind: String?): ImageVector = when (kind) {
    "PRIVATE" -> Icons.Outlined.Lock
    "ANNOUNCEMENT" -> Icons.Outlined.Campaign
    else -> Icons.Outlined.Tag
}

@Composable
fun ConversationAvatar(conv: ConversationEntity, myUserId: String?, size: androidx.compose.ui.unit.Dp = 52.dp, online: Boolean? = null) {
    val c = Griip.colors
    when (conv.type) {
        "DM" -> {
            val peer = ConversationRepository.peer(conv, myUserId)
            Avatar(peer?.displayName ?: "?", peer?.avatarColor ?: 0, size = size, online = online)
        }
        "GROUP" -> IconTile(Icons.Rounded.Groups, c.lavender, c.lavenderSoft, size = size)
        else -> {
            val (fg, bg) = when (conv.kind) {
                "ANNOUNCEMENT" -> c.peach to c.peachSoft
                "PRIVATE" -> c.lavender to c.lavenderSoft
                else -> c.primary to c.primarySoft
            }
            IconTile(channelIcon(conv.kind), fg, bg, size = size)
        }
    }
}

/** Frosted conversation card: avatar/channel tile, title, preview, time and unread badge. */
@Composable
fun ConversationRow(conv: ConversationEntity, myUserId: String?, typingNames: List<String>, online: Boolean?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val title = ConversationRepository.title(conv, myUserId)
    val unread = conv.unreadCount > 0
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConversationAvatar(conv, myUserId, online = online)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (conv.type == "CHANNEL") title else title,
                        style = Griip.type.titleSmall.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold),
                        color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conv.muted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.NotificationsOff, "Muted", tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    }
                    if (conv.activeCallId != null) {
                        Spacer(Modifier.width(6.dp))
                        TagPill("Live", c.success, c.successSoft, icon = Icons.Rounded.Call)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        TimeFmt.listStamp(if (conv.lastMessageAt > 0) conv.lastMessageAt else conv.serverUpdatedAt),
                        style = Griip.type.caption, color = if (unread) c.primaryDeep else c.textTertiary,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val preview = when {
                        typingNames.isNotEmpty() -> (if (conv.type == "DM") "typing…" else "${typingNames.first()} is typing…")
                        conv.lastMessagePreview != null -> {
                            val who = conv.lastSenderName
                            if (conv.type != "DM" && who != null) "$who: ${conv.lastMessagePreview}" else if (who == "You") "You: ${conv.lastMessagePreview}" else conv.lastMessagePreview
                        }
                        conv.type == "CHANNEL" -> listOfNotNull(conv.scopeName, conv.topic).joinToString(" · ").ifEmpty { "No messages yet" }
                        else -> "No messages yet"
                    }
                    Text(
                        preview, style = Griip.type.bodySmall, color = if (typingNames.isNotEmpty()) c.primaryDeep else if (unread) c.textPrimary else c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (conv.mentionCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(20.dp).clip(Shapes.pill).background(c.peach), contentAlignment = Alignment.Center) {
                            Text("@", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    if (unread) {
                        Spacer(Modifier.width(6.dp))
                        CountBadge(conv.unreadCount, muted = conv.muted)
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private val pad = Modifier.padding(0.dp)
