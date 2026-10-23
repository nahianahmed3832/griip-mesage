package com.meldiq.android.ui.screens.community

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meldiq.android.R
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.model.MuteRules
import com.meldiq.android.data.model.NavPrefsRules
import com.meldiq.android.data.model.NotifyLevel
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

private val BELL_DURATIONS = listOf(R.string.mf_15m, R.string.mf_1h, R.string.mf_3h, R.string.mf_8h, R.string.mf_24h)

/**
 * The channel header's bell (2026-10-23, the reference's bell: "Mute this channel" with notification settings under it):
 * mute for a while or unmute, and the channel's own level, where "default" says what it currently follows.
 */
@Composable
fun ChannelBellButton(conv: ConversationEntity) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val all by container.navPrefs.prefs.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var timed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { timed = container.features.has(ServerFeatures.CHANNEL_PREFERENCES) == true }
    val prefs = all[com.meldiq.android.data.repo.NavPreferences.workspaceOf(conv)] ?: NavPrefsDto()
    val until = NavPrefsRules.mutedUntil(prefs, conv.id)
    val muted = conv.muted || until != null
    CircleIconButton(
        if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
        stringResource(R.string.ux_channel_notifications), { open = true }, tint = Griip.colors.textSecondary,
    )
    if (open) ChannelNotifySheet(
        "#" + (conv.name ?: ""), timed, muted, until, own = prefs.notify[conv.id], parent = NavPrefsRules.parentLevel(prefs, conv.categoryId),
        onMute = { duration -> container.navPrefs.muteFor(listOf(conv), duration) },
        onUnmute = { scope.launch { container.navPrefs.setMuted(conv, false) } },
        onLevel = { level -> scope.launch { if (level == null) container.navPrefs.resetLevel(conv) else container.navPrefs.setLevel(conv, level) } },
        onDismiss = { open = false },
    )
}

@Composable
fun ChannelNotifySheet(
    title: String, timed: Boolean, muted: Boolean, mutedUntil: Long?, own: String?, parent: String,
    onMute: (Long?) -> Unit, onUnmute: () -> Unit, onLevel: (String?) -> Unit, onDismiss: () -> Unit,
) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = title) {
        if (muted) {
            SheetItem(
                Icons.Outlined.Notifications, stringResource(R.string.nv_unmute_channel), { onDismiss(); onUnmute() },
                subtitle = mutedUntil?.let { stringResource(R.string.ux_muted_until, TimeFmt.clock(it)) },
            )
        } else {
            Text(stringResource(R.string.mf_title), style = Griip.type.section, color = c.textSecondary, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp))
            if (timed) MuteRules.DURATIONS.zip(BELL_DURATIONS).forEach { (ms, label) ->
                SheetItem(Icons.Outlined.Timer, stringResource(label), { onDismiss(); onMute(ms) })
            }
            SheetItem(Icons.Outlined.NotificationsOff, stringResource(R.string.mf_forever), { onDismiss(); onMute(null) })
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ux_notification_settings), style = Griip.type.section, color = c.textSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        LevelChoice(stringResource(R.string.cn_follow, levelName(parent)), null, own == null) { onLevel(null); onDismiss() }
        listOf(NotifyLevel.ALL, NotifyLevel.MENTIONS, NotifyLevel.NONE).forEach { level ->
            LevelChoice(levelName(level), levelHint(level), own == level) { onLevel(level); onDismiss() }
        }
        Spacer(Modifier.height(8.dp))
    }
}
