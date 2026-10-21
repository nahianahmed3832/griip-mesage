package com.meldiq.android.ui.screens.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.model.NavPrefsRules
import com.meldiq.android.data.model.NotifyLevel
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/**
 * Which channels the community notification settings list, and which can be added (pure, `CommunityNotifyRulesTest`).
 * Only channels you're in carry a setting of your own.
 */
object CommunityNotifyRules {
    /** Channels with a level of their own, in panel order, with that level. */
    fun overrides(channels: List<PanelChannel>, prefs: NavPrefsDto): List<Pair<PanelChannel, String>> =
        channels.filter { it.joined }.mapNotNull { ch -> prefs.notify[ch.id]?.let { ch to it } }

    /** Channels you're in that still follow the community. */
    fun addable(channels: List<PanelChannel>, prefs: NavPrefsDto): List<PanelChannel> =
        channels.filter { it.joined && it.id !in prefs.notify }
}

/** What the community notification settings change. */
class CommunityNotifyActions(
    val onToggleMute: (() -> Unit)?,
    val onLevel: (String) -> Unit,
    val onSuppressEveryone: (Boolean) -> Unit,
    /** A channel's own level; null: follow the community again. */
    val onChannelLevel: (PanelChannel, String?) -> Unit,
)

@Composable
private fun levelLabel(level: String): String = stringResource(
    when (level) {
        NotifyLevel.MENTIONS -> R.string.r3_notify_mentions
        NotifyLevel.NONE -> R.string.r3_notify_none
        else -> R.string.r3_notify_all
    },
)

private val LEVELS = listOf(NotifyLevel.ALL, NotifyLevel.MENTIONS, NotifyLevel.NONE)

/**
 * A community's notification settings (batch B2, Discord reference COMM-CTRL-002), opened from the community sheet:
 * mute the community, the level its channels follow, ignore @everyone, and the channels with a level of their own.
 * Kept for every device through the preferences document; decided on the phone (wake-ups carry nothing).
 */
@Composable
fun CommunityNotificationsSheet(
    orgName: String,
    allMuted: Boolean,
    prefs: NavPrefsDto,
    channels: List<PanelChannel>,
    actions: CommunityNotifyActions,
    onDismiss: () -> Unit,
) {
    val c = Griip.colors
    var editing by remember { mutableStateOf<PanelChannel?>(null) }
    var picking by remember { mutableStateOf(false) }
    val communityLevel = NavPrefsRules.communityLevel(prefs)
    GlassSheet(onDismiss, title = stringResource(R.string.cn_title)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            actions.onToggleMute?.let { toggle ->
                SheetSwitch(Icons.Outlined.NotificationsOff, stringResource(R.string.cn_mute, orgName), allMuted) { toggle() }
                Text(stringResource(R.string.cn_mute_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(start = 46.dp, end = 8.dp))
                SheetDivider()
            }
            SectionTitle(stringResource(R.string.cn_level_title))
            LEVELS.forEach { level -> LevelRow(levelLabel(level), levelSub(level), level == communityLevel) { actions.onLevel(level) } }
            Text(stringResource(R.string.cn_level_help), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            SheetDivider()
            SheetSwitch(Icons.Outlined.AlternateEmail, stringResource(R.string.cn_suppress), !NavPrefsRules.countsEveryone(prefs), actions.onSuppressEveryone)
            Text(stringResource(R.string.cn_suppress_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(start = 46.dp, end = 8.dp))
            SheetDivider()
            SectionTitle(stringResource(R.string.cn_overrides))
            val overrides = CommunityNotifyRules.overrides(channels, prefs)
            if (overrides.isEmpty()) {
                Text(stringResource(R.string.cn_overrides_empty), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            overrides.forEach { (ch, level) -> ChannelLine(ch.name, levelLabel(level)) { editing = ch } }
            SheetItem(Icons.Rounded.Add, stringResource(R.string.cn_add_override), { picking = true })
            Spacer(Modifier.height(8.dp))
        }
    }
    editing?.let { ch ->
        GlassSheet({ editing = null }, title = "#" + ch.name) {
            val own = prefs.notify[ch.id]
            LevelRow(stringResource(R.string.cn_follow, levelLabel(communityLevel)), null, own == null) { actions.onChannelLevel(ch, null); editing = null }
            LEVELS.forEach { level -> LevelRow(levelLabel(level), levelSub(level), own == level) { actions.onChannelLevel(ch, level); editing = null } }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (picking) {
        GlassSheet({ picking = false }, title = stringResource(R.string.cn_pick_channel)) {
            val addable = CommunityNotifyRules.addable(channels, prefs)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (addable.isEmpty()) Text(stringResource(R.string.cn_no_more_channels), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(8.dp))
                addable.forEach { ch -> ChannelLine(ch.name, null) { picking = false; editing = ch } }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun levelSub(level: String): String? = when (level) {
    NotifyLevel.MENTIONS -> stringResource(R.string.r3_notify_mentions_sub)
    NotifyLevel.NONE -> stringResource(R.string.r3_notify_none_sub)
    else -> null
}

@Composable
private fun SectionTitle(text: String) = Text(
    text, style = Griip.type.label, color = Griip.colors.textSecondary,
    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).semantics { heading() },
)

@Composable
private fun LevelRow(label: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Griip.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.small).pressable(onClick, role = Role.RadioButton).heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Griip.type.bodyMedium, color = c.textPrimary)
            subtitle?.let { Text(it, style = Griip.type.caption, color = c.textSecondary) }
        }
    }
}

@Composable
private fun ChannelLine(name: String, level: String?, onClick: () -> Unit) {
    val c = Griip.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.small).pressable(onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tag, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(name, style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        level?.let { Text(it, style = Griip.type.caption, color = c.textSecondary) }
    }
}
