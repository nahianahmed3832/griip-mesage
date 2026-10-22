package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.call.CallPhase
import com.meldiq.android.call.CallUiState
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.UserAvatar
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.screens.home.roomPeople
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/**
 * Batch B14 (owner's request, Discord reference): a voice channel is a room to talk in, not a chat. It shows who is in
 * the room, Join (or the call you're already in), and inviting people; there is no message list, composer or message
 * menu, and the server refuses messages sent to it (`voice_channel`).
 */
@Composable
fun VoiceChannelScreen(
    nav: NavHostController,
    conv: ConversationEntity,
    users: Map<String, UserEntity>,
    call: CallUiState,
    onJoin: () -> Unit,
    onOpenCall: () -> Unit,
) {
    val c = Griip.colors
    val people = roomPeople(conv)
    val inThisRoom = call.phase != CallPhase.Idle && call.conversationId == conv.id
    val noAccess = conv.canConnect == false
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.b5_back), { nav.popBackStack() })
            Row(
                Modifier.weight(1f).clip(Shapes.pill).pressable({ nav.navigate(Routes.info(conv.id)) }, onClickLabel = stringResource(R.string.b14_voice_details))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(conv.name.orEmpty(), style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            if (noAccess) StatusBanner(stringResource(R.string.nv_no_voice), BannerKind.Info, Modifier.padding(vertical = 4.dp), icon = Icons.Outlined.Headset)
            else if (conv.canSpeak == false) StatusBanner(stringResource(R.string.nv_listen_only), BannerKind.Info, Modifier.padding(vertical = 4.dp), icon = Icons.Outlined.Headset)
            if (people.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).clip(Shapes.hero).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Headset, null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.b14_voice_empty), style = Griip.type.title, color = c.textPrimary, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.b14_voice_empty_sub), style = Griip.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                Text(
                    stringResource(R.string.b14_voice_here) + " — ${people.size}", style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                // One person gets the room's whole width, as on a call; more share it two by two.
                if (people.size == 1) PersonTile(users[people[0]], people[0], Modifier.fillMaxWidth())
                else people.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { id -> PersonTile(users[id], id, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp)) {
            conv.orgId?.let { org ->
                ListCard(
                    stringResource(R.string.b14_voice_invite), Modifier.padding(bottom = 10.dp).clip(Shapes.card).background(c.surfaceVariant),
                    subtitle = stringResource(R.string.b14_voice_invite_sub),
                    leading = { Icon(Icons.Outlined.PersonAdd, null, tint = c.textPrimary) },
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = c.textTertiary) },
                    onClick = { nav.navigate(Routes.invites(org)) },
                )
            }
            if (inThisRoom) PillButton(stringResource(R.string.b14_voice_open_call), onOpenCall, Modifier.fillMaxWidth(), height = 52.dp)
            else PillButton(
                stringResource(R.string.b14_voice_join), onJoin, Modifier.fillMaxWidth(), height = 52.dp,
                enabled = !noAccess && call.phase == CallPhase.Idle,
            )
        }
    }
}

@Composable
private fun PersonTile(u: UserEntity?, id: String, modifier: Modifier) {
    val c = Griip.colors
    Column(
        modifier.aspectRatio(1f).clip(Shapes.card).background(c.surfaceVariant).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        UserAvatar(id, u?.displayName ?: "?", u?.avatarColor ?: 0, u?.photoRev, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(u?.displayName ?: "", style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
