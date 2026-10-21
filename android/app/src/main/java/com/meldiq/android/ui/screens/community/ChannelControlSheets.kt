package com.meldiq.android.ui.screens.community

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.meldiq.android.data.model.MuteRules
import com.meldiq.android.data.model.NavPrefsRules
import com.meldiq.android.data.model.NotifyLevel
import com.meldiq.android.data.model.SlowmodeRules
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

private val DURATION_LABELS = listOf(R.string.mf_15m, R.string.mf_1h, R.string.mf_3h, R.string.mf_8h, R.string.mf_24h)

/**
 * Mute for (batch B3, Discord reference CHAN-CTRL-002): 15 minutes to 24 hours, or until turned back on. [timed] false
 * (a server without `channel-preferences`) offers only the mute without an end. [onPick] gets the duration in ms, or
 * null for the mute without an end.
 */
@Composable
fun MuteForSheet(title: String, timed: Boolean, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss, title = stringResource(R.string.mf_title) + " · " + title) {
        if (timed) MuteRules.DURATIONS.zip(DURATION_LABELS).forEach { (ms, label) ->
            SheetItem(Icons.Outlined.Timer, stringResource(label), { onDismiss(); onPick(ms) })
        }
        SheetItem(Icons.Outlined.NotificationsOff, stringResource(R.string.mf_forever), { onDismiss(); onPick(null) })
        Spacer(Modifier.height(8.dp))
    }
}

/** A category's own level (batch B3, CHAN-CTRL-003's "category default"): its channels follow it unless they have one. */
@Composable
fun CategoryLevelSheet(name: String, prefs: NavPrefsDto, categoryId: String, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = Griip.colors
    val own = prefs.categoryNotify?.get(categoryId)
    val community = NavPrefsRules.communityLevel(prefs)
    GlassSheet(onDismiss, title = stringResource(R.string.cl_title, name)) {
        LevelChoice(stringResource(R.string.cn_follow, levelName(community)), null, own == null) { onPick(null); onDismiss() }
        listOf(NotifyLevel.ALL, NotifyLevel.MENTIONS, NotifyLevel.NONE).forEach { level ->
            LevelChoice(levelName(level), levelHint(level), own == level) { onPick(level); onDismiss() }
        }
        Text(stringResource(R.string.cl_help), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(8.dp))
    }
}

/**
 * Browse channels (batch B3, Discord reference COMM-CTRL-007): every channel of the community you can see, by section,
 * with a switch that keeps it in your list or hides it. Hidden channels stay joined and come back when you're mentioned.
 */
@Composable
fun BrowseChannelsSheet(
    sections: List<Pair<String, List<PanelChannel>>>,
    hidden: Set<String>,
    onShow: (PanelChannel, Boolean) -> Unit,
    onShowAll: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = stringResource(R.string.hc_browse)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.hc_browse_body), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            onShowAll?.let { PillButton(stringResource(R.string.hc_show_all), it, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 44.dp, showKnob = false) }
            sections.forEach { (title, channels) ->
                Text(
                    title.uppercase(), style = Griip.type.label, color = c.textSecondary,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                )
                channels.forEach { ch ->
                    val shown = ch.id !in hidden
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.small).pressable({ onShow(ch, !shown) }, role = Role.Switch).heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.Visibility, null, tint = c.textSecondary)
                        Spacer(Modifier.width(14.dp))
                        Text((if (ch.voice) "" else "#") + ch.name, style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        GriipSwitch(shown, { onShow(ch, it) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "30 s", "5 min", "1 h", or "Off" for 0. */
@Composable
fun slowmodeLabel(seconds: Int): String {
    if (seconds <= 0) return stringResource(R.string.sm_off)
    val (n, unit) = SlowmodeRules.short(seconds)
    return when (unit) {
        SlowmodeRules.Unit.HOURS -> stringResource(R.string.sm_hours, n)
        SlowmodeRules.Unit.MINUTES -> stringResource(R.string.sm_minutes, n)
        SlowmodeRules.Unit.SECONDS -> stringResource(R.string.sm_seconds, n)
    }
}

@Composable
private fun levelName(level: String): String = stringResource(
    when (level) {
        NotifyLevel.MENTIONS -> R.string.r3_notify_mentions
        NotifyLevel.NONE -> R.string.r3_notify_none
        else -> R.string.r3_notify_all
    },
)

@Composable
private fun levelHint(level: String): String? = when (level) {
    NotifyLevel.MENTIONS -> stringResource(R.string.r3_notify_mentions_sub)
    NotifyLevel.NONE -> stringResource(R.string.r3_notify_none_sub)
    else -> null
}

@Composable
private fun LevelChoice(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Griip.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.small).pressable(onClick, role = Role.RadioButton).heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Griip.type.bodyMedium, color = c.textPrimary)
            hint?.let { Text(it, style = Griip.type.caption, color = c.textSecondary) }
        }
    }
}

/**
 * Above the composer while slowmode applies to you (batch B3, Discord reference CHAN-CTRL-013): the interval, and after
 * you posted a countdown to your next message. Messages sent meanwhile wait in the outbox and go out when allowed.
 */
@Composable
fun SlowmodeLine(slowmode: com.meldiq.android.data.model.Slowmode?) {
    if (slowmode == null || slowmode.exempt || slowmode.seconds <= 0) return
    val c = Griip.colors
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val left = SlowmodeRules.secondsLeft(slowmode, now)
    androidx.compose.runtime.LaunchedEffect(slowmode.nextPostAt) {
        while (SlowmodeRules.secondsLeft(slowmode, System.currentTimeMillis()) > 0) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(Icons.Outlined.Timer, null, tint = c.textTertiary, modifier = Modifier.padding(end = 8.dp))
        Text(
            if (left > 0) stringResource(R.string.sm_wait, slowmodeLabel(left)) else stringResource(R.string.sm_on, slowmodeLabel(slowmode.seconds)),
            style = Griip.type.caption, color = c.textSecondary,
        )
    }
}
