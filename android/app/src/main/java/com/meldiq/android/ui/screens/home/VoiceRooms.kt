package com.meldiq.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headset
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
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Who is in a voice room now (user ids), from the server; empty when nobody is. */
fun roomPeople(conv: ConversationEntity): List<String> =
    conv.roomPeopleJson?.let { runCatching { GriipJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
        .takeIf { conv.activeCallId != null }.orEmpty()

/**
 * Home › Voice rooms (round 6, docs/VOICE_ROOMS.md): the workspace's voice rooms, who is in each now, and Join. Joining
 * starts or joins the room's call; nobody rings.
 */
fun LazyListScope.voiceRoomsSection(
    rooms: List<ConversationEntity>,
    users: Map<String, UserEntity>,
    onOpen: (ConversationEntity) -> Unit,
    onJoin: (ConversationEntity) -> Unit,
) {
    if (rooms.isEmpty()) return
    item(key = "voice-header") { HomeSectionHeader(stringResource(R.string.vr_section)) }
    items(rooms, key = { "voice-" + it.id }) { room -> VoiceRoomRow(room, roomPeople(room), users, { onOpen(room) }, { onJoin(room) }) }
}

@Composable
private fun VoiceRoomRow(room: ConversationEntity, people: List<String>, users: Map<String, UserEntity>, onOpen: () -> Unit, onJoin: () -> Unit) {
    val c = Griip.colors
    val live = people.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().pressable(onOpen).padding(start = Space.gutter + 4.dp, end = Space.gutter, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(Shapes.small).background(if (live) c.successSoft else c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Headset, null, tint = if (live) c.success else c.textSecondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name ?: "", style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.Medium), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (live) {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    people.take(MAX_FACES).forEach { id ->
                        val u = users[id]
                        Avatar(u?.displayName ?: "?", u?.avatarColor ?: id.hashCode(), size = 22.dp, ring = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.vr_here, people.size), style = Griip.type.caption, color = c.success)
                }
            } else {
                Text(stringResource(R.string.vr_empty), style = Griip.type.caption, color = c.textSecondary)
            }
        }
        Text(
            stringResource(R.string.vr_join), style = Griip.type.button, color = c.onAction,
            modifier = Modifier.clip(Shapes.pill).background(c.actionStart).pressable(onJoin, onClickLabel = stringResource(R.string.vr_join_room, room.name ?: ""))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** A dot in the call's colours (used by the room banner). */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(Griip.colors.success))
}

private const val MAX_FACES = 5
