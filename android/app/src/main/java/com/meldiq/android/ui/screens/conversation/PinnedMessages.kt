package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/**
 * The pin button in the conversation header (round 7). It takes the place the always-visible encryption label once had;
 * the padlock beside it keeps the security details one tap away. The count shows when anything is pinned.
 */
@Composable
fun PinnedButton(count: Int, onOpen: () -> Unit) {
    val c = Griip.colors
    val label = if (count > 0) stringResource(R.string.pin_button_count, count) else stringResource(R.string.pin_button)
    CircleIconButton(Icons.Outlined.PushPin, label, onOpen, tint = if (count > 0) c.primary else c.textSecondary, size = 40.dp, badge = count > 0)
}

/**
 * The pinned messages of this conversation, newest pin first. Tapping one closes the sheet and jumps to the message;
 * people who may post here can unpin (pins are encrypted control messages, so every member's devices follow).
 */
@Composable
fun PinnedMessagesSheet(
    pinned: List<MessageEntity>,
    users: Map<String, UserEntity>,
    preview: (MessageEntity) -> String,
    canUnpin: Boolean,
    onOpen: (MessageEntity) -> Unit,
    onUnpin: (MessageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss, title = stringResource(R.string.pin_sheet_title)) {
        if (pinned.isEmpty()) {
            Text(stringResource(R.string.pin_sheet_empty), style = Griip.type.body, color = Griip.colors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
            return@GlassSheet
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(pinned, key = { it.id }) { m ->
                PinnedRow(m, sender = if (m.outgoing) stringResource(R.string.pin_you) else users[m.senderUserId]?.displayName.orEmpty(), preview(m), canUnpin, { onOpen(m) }, { onUnpin(m) })
            }
        }
    }
}

@Composable
private fun PinnedRow(m: MessageEntity, sender: String, preview: String, canUnpin: Boolean, onOpen: () -> Unit, onUnpin: () -> Unit) {
    val c = Griip.colors
    val text = if (m.deleted) stringResource(R.string.pin_deleted) else preview
    Row(
        Modifier.fillMaxWidth().clip(Shapes.small).pressable(onOpen, onClickLabel = stringResource(R.string.pin_go_to)).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(Shapes.small).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PushPin, null, tint = c.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sender, style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(TimeFmt.listStamp(m.sortTs), style = Griip.type.caption, color = c.textTertiary, maxLines = 1)
            }
            Text(text, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (canUnpin && !m.deleted) CircleIconButton(Icons.Rounded.Close, stringResource(R.string.pin_unpin), onUnpin, size = 36.dp, tint = c.textSecondary)
    }
}
