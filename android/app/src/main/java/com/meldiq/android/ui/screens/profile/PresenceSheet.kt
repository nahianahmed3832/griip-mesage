package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.PresenceRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.PresenceDot
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

private val DURATION_LABELS = listOf(R.string.sa_1h, R.string.b6b_8h, R.string.sa_24h, R.string.sa_7d, R.string.b6b_until_changed)

@Composable
private fun modeLabel(mode: String) = stringResource(
    when (mode) {
        PresenceRules.IDLE -> R.string.b6b_idle
        PresenceRules.DND -> R.string.b6b_dnd
        PresenceRules.INVISIBLE -> R.string.b6b_invisible
        else -> R.string.b6b_auto
    },
)

@Composable
private fun modeHelp(mode: String) = stringResource(
    when (mode) {
        PresenceRules.IDLE -> R.string.b6b_idle_sub
        PresenceRules.DND -> R.string.b6b_dnd_sub
        PresenceRules.INVISIBLE -> R.string.b6b_invisible_sub
        else -> R.string.b6b_auto_sub
    },
)

/** The You screen's presence row (batch B6b): what you appear as, and until when. */
@Composable
fun PresenceRow(mode: String?, until: Long?, now: Long, onClick: () -> Unit) {
    val current = PresenceRules.current(mode, until, now)
    MenuRow(
        Icons.Outlined.Circle, modeLabel(current),
        subtitle = until?.takeIf { current != PresenceRules.AUTO }?.let { stringResource(R.string.r3_status_until, TimeFmt.clock(it)) } ?: modeHelp(current),
        onClick = onClick,
    )
}

/**
 * Choosing how you appear (batch B6b): Online (automatic), Idle, Do not disturb (also pauses notifications on every
 * device for as long) or Invisible (offline to everyone; you can still use Meldiq). Anything but automatic can end by
 * itself after 1 hour to 7 days, or stay until you change it.
 */
@Composable
fun PresenceSheet(currentMode: String?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(mode: String, duration: Long?) = scope.launch {
        runCatching { container.profiles.setPresence(mode, duration?.let { System.currentTimeMillis() + it }) }
            .onSuccess { onDismiss() }
            .onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.b6b_title)) {
        Column {
            PresenceRules.MODES.forEach { mode ->
                MenuRow(
                    Icons.Outlined.Circle, modeLabel(mode), subtitle = modeHelp(mode),
                    trailing = {
                        when {
                            mode == (currentMode ?: PresenceRules.AUTO) -> Text("✓", style = Griip.type.titleSmall, color = Griip.colors.primary)
                            mode == PresenceRules.IDLE || mode == PresenceRules.DND || mode == PresenceRules.AUTO ->
                                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { PresenceDot(40.dp, mode) }
                        }
                    },
                    onClick = { if (mode == PresenceRules.AUTO) save(mode, null) else picked = mode },
                )
            }
            picked?.let { mode ->
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.b6b_for_how_long, modeLabel(mode)), style = Griip.type.bodyMedium, color = Griip.colors.textPrimary)
                ChipRow(Modifier.padding(horizontal = 0.dp)) {
                    PresenceRules.DURATIONS.zip(DURATION_LABELS).forEach { (ms, label) -> PillChip(stringResource(label), false, { save(mode, ms) }) }
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
