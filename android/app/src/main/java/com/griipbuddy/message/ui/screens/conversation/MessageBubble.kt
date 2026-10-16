package com.griipbuddy.message.ui.screens.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.data.db.MessageEntity
import com.griipbuddy.message.data.db.UserEntity
import com.griipbuddy.message.data.model.AttachmentRef
import com.griipbuddy.message.data.model.MessageStatus
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.media.MediaExport
import com.griipbuddy.message.ui.media.MediaGallery
import com.griipbuddy.message.ui.media.MediaKind
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.voice.VoiceNote
import com.griipbuddy.message.voice.VoicePlayer
import java.io.File

data class BubbleModel(
    val m: MessageEntity,
    val sender: UserEntity?,
    val showSender: Boolean,
    val showAvatar: Boolean,
    val firstInGroup: Boolean,
    val reply: MessageEntity?,
    val replySender: String?,
    val reactions: Map<String, List<String>>,
    val attachments: List<AttachmentRef>,
    val threadCount: Int,
    val isGroupConv: Boolean,
    val replyPreview: String? = null,
)

/**
 * Message bubble: outgoing = soft emerald, incoming = plain surface; no shadows, borders or gradients.
 * A smaller corner on the sender side marks the first bubble in a run.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    b: BubbleModel,
    me: String?,
    transfer: Float?,
    localFile: (AttachmentRef) -> File?,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onOpenAttachment: (AttachmentRef) -> Unit,
    onOpenThread: (() -> Unit)?,
    onRetry: () -> Unit,
    onReplyClick: (String) -> Unit,
    voice: VoicePlayer.State = VoicePlayer.State(),
    onVoiceToggle: (AttachmentRef) -> Unit = {},
    onVoiceSeek: (AttachmentRef, Long) -> Unit = { _, _ -> },
) {
    val c = Griip.colors
    val m = b.m
    val out = m.outgoing
    if (m.kind == "call") {
        CallChip(m, out)
        return
    }
    val r = Shapes.bubble
    val tail = Shapes.bubbleTail
    val shape = if (out) RoundedCornerShape(r, if (b.firstInGroup) tail else r, r, r) else RoundedCornerShape(if (b.firstInGroup) tail else r, r, r, r)
    val fg = if (out) c.onBubbleOutgoing else c.textPrimary
    val sub = if (out) c.bubbleOutgoingMeta else c.textSecondary

    Row(
        Modifier.fillMaxWidth().padding(start = if (out) 56.dp else 10.dp, end = if (out) 10.dp else 48.dp, top = if (b.firstInGroup) 8.dp else 2.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!out && b.isGroupConv) {
            if (b.showAvatar) Avatar(b.sender?.displayName ?: "?", b.sender?.avatarColor ?: 0, size = 30.dp, ring = false)
            else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
            val bg = when {
                m.kind == "undecryptable" -> c.dangerSoft
                m.deleted -> c.surfaceVariant
                out -> c.bubbleOutgoing
                else -> c.bubbleIncoming
            }
            Column(
                Modifier
                    .widthIn(max = 310.dp)
                    .clip(shape)
                    .background(bg)
                    .combinedClickable(onClick = { if (m.status == MessageStatus.FAILED) onRetry() }, onLongClick = onLongPress, onLongClickLabel = "Message actions")
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                if (b.showSender && !out && b.isGroupConv) {
                    Text(b.sender?.displayName ?: "Unknown", style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = senderColor(b.sender?.avatarColor ?: 0), maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                }
                if (b.reply != null) {
                    Row(
                        Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min).clip(Shapes.small)
                            .background(if (out) c.primary.copy(alpha = 0.10f) else c.surfaceVariant)
                            .pressable({ onReplyClick(b.reply.id) }, onClickLabel = "Go to quoted message"),
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(c.primary))
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(b.replySender ?: "", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = c.primaryDeep, maxLines = 1)
                            Text(if (b.reply.deleted) "Deleted message" else b.reply.text.ifEmpty { b.replyPreview ?: "📎 Attachment" }, style = Griip.type.caption, color = if (out) c.bubbleOutgoingMeta else c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                when {
                    m.kind == "undecryptable" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GppMaybe, null, tint = c.danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(m.text, style = Griip.type.bodySmall, color = c.danger)
                    }
                    m.deleted -> Text("This message was deleted", style = Griip.type.bodySmall.copy(fontStyle = FontStyle.Italic), color = c.textSecondary)
                    else -> {
                        b.attachments.forEach { a ->
                            if (VoiceNote.isVoice(a)) {
                                VoiceMessageView(a, out, transfer, voice.takeIf { it.key == m.id }, { onVoiceToggle(a) }, { onVoiceSeek(a, it) })
                            } else AttachmentView(a, out, transfer, localFile(a), onOpenAttachment)
                            if (m.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        }
                        if (m.text.isNotEmpty()) Text(richText(m.text, out), style = Griip.type.body, color = fg)
                    }
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.pinned) {
                        Icon(Icons.Outlined.PushPin, "Pinned", tint = sub, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    if (m.editedAt != null && !m.deleted) Text("edited · ", style = Griip.type.caption, color = sub)
                    Text(TimeFmt.clock(m.sortTs), style = Griip.type.caption, color = sub)
                    if (out) {
                        Spacer(Modifier.width(4.dp))
                        StatusIcon(m.status, sub)
                    }
                }
            }
            if (m.status == MessageStatus.HELD) {
                Text(
                    m.errorText ?: "Paused: a security key changed", style = Griip.type.caption, color = c.warning,
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp),
                )
            }
            if (m.status == MessageStatus.FAILED) {
                Text(
                    (m.errorText ?: "Not sent") + " · Tap to retry", style = Griip.type.caption, color = c.danger,
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp).clip(Shapes.pill).pressable(onRetry).padding(2.dp),
                )
            }
            if (b.reactions.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.reactions.forEach { (emoji, users) ->
                        val mine = me in users
                        Row(
                            Modifier.clip(Shapes.pill).background(if (mine) c.primarySoft else c.surface).border(1.dp, if (mine) c.primary.copy(alpha = 0.5f) else c.border, Shapes.pill)
                                .pressable({ onReact(emoji) }).padding(horizontal = 8.dp, vertical = 3.dp)
                                .semantics { contentDescription = "$emoji reaction, ${users.size}" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emoji, style = Griip.type.label)
                            if (users.size > 1 || !mine) {
                                Spacer(Modifier.width(4.dp))
                                Text("${users.size}", style = Griip.type.caption, color = if (mine) c.primaryDeep else c.textSecondary)
                            }
                        }
                    }
                }
            }
            if (b.threadCount > 0 && onOpenThread != null) {
                Text(
                    "${b.threadCount} ${if (b.threadCount == 1) "reply" else "replies"} →", style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary,
                    modifier = Modifier.padding(top = 4.dp).clip(Shapes.pill).pressable(onOpenThread).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(status: String, tint: Color) {
    val c = Griip.colors
    when (status) {
        MessageStatus.SENDING -> Icon(Icons.Outlined.Schedule, "Sending", tint = tint, modifier = Modifier.size(13.dp))
        MessageStatus.SENT -> Icon(Icons.Rounded.Done, "Sent", tint = tint, modifier = Modifier.size(14.dp))
        MessageStatus.DELIVERED -> Icon(Icons.Rounded.DoneAll, "Delivered", tint = tint, modifier = Modifier.size(15.dp))
        MessageStatus.READ -> Icon(Icons.Rounded.DoneAll, "Read", tint = c.readReceipt, modifier = Modifier.size(15.dp))
        MessageStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Failed", tint = c.danger, modifier = Modifier.size(14.dp))
        MessageStatus.HELD -> Icon(Icons.Outlined.Schedule, "Paused", tint = c.warning, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun richText(text: String, out: Boolean): AnnotatedString {
    val c = Griip.colors
    val mention = Regex("@[a-z0-9._]{3,24}", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var last = 0
        for (mt in mention.findAll(text)) {
            append(text.substring(last, mt.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.primaryDeep)) { append(mt.value) }
            last = mt.range.last + 1
        }
        append(text.substring(last))
    }
}

private fun senderColor(i: Int): Color = com.griipbuddy.message.ui.theme.AvatarGradients[((i % 8) + 8) % 8].second

@Composable
private fun AttachmentView(a: AttachmentRef, out: Boolean, transfer: Float?, local: File?, onOpen: (AttachmentRef) -> Unit) {
    val c = Griip.colors
    val kind = MediaGallery.kindOf(a)
    // Thumbnails: photos decoded at bubble size with their EXIF rotation, videos as their first frame.
    val bitmap by produceState<ImageBitmap?>(null, local?.path) {
        value = when {
            local == null -> null
            kind == MediaKind.Image -> MediaExport.decode(local, 1080)?.asImageBitmap()
            kind == MediaKind.Video -> MediaExport.videoFrame(local, 720)?.asImageBitmap()
            else -> null
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Box(Modifier.widthIn(max = 260.dp).heightIn(max = 300.dp).clip(Shapes.tile).pressable({ onOpen(a) }, onClickLabel = if (kind == MediaKind.Video) "Play video" else "View photo")) {
            Image(bmp, a.name, contentScale = ContentScale.Crop)
            if (kind == MediaKind.Video) {
                Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        return
    }
    val label = when (kind) {
        MediaKind.Image -> "Photo"
        MediaKind.Video -> "Video"
        null -> null
    }
    Row(
        Modifier.widthIn(min = 200.dp, max = 270.dp).clip(Shapes.small).background(if (out) c.primary.copy(alpha = 0.10f) else c.surfaceVariant)
            .pressable(if (a.id != "pending") ({ onOpen(a) }) else null, onClickLabel = if (kind != null) "Open ${label!!.lowercase()}" else "Open file").padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(Shapes.small).background(c.surface), contentAlignment = Alignment.Center) {
            if (transfer != null) CircularProgressIndicator(progress = { transfer }, modifier = Modifier.size(26.dp), color = c.primary, strokeWidth = 3.dp)
            else Icon(
                when {
                    local == null && a.id != "pending" -> Icons.Rounded.Download
                    kind == MediaKind.Video -> Icons.Rounded.Videocam
                    kind == MediaKind.Image -> Icons.Rounded.Image
                    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                if (local == null && a.id != "pending") "Download" else null, tint = c.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label ?: a.name, style = Griip.type.bodyMedium, color = if (out) c.onBubbleOutgoing else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                humanSize(a.size) + if (transfer != null) " · ${(transfer * 100).toInt()}%" else if (kind != null && local == null) " · tap to view" else "",
                style = Griip.type.caption, color = if (out) c.bubbleOutgoingMeta else c.textSecondary,
            )
        }
    }
}

fun humanSize(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

@Composable
private fun CallChip(m: MessageEntity, out: Boolean) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(Shapes.pill).background(c.surface).border(1.dp, c.border, Shapes.pill).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (m.text.startsWith("Video")) Icons.Rounded.Videocam else Icons.Rounded.Call, null, tint = c.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("${if (out) "Outgoing" else "Incoming"} ${m.text.lowercase()} · ${TimeFmt.clock(m.sortTs)}", style = Griip.type.label, color = c.textSecondary)
        }
    }
}
