package com.meldiq.android.ui.screens.calls

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.call.CallMediaPolicy
import com.meldiq.android.call.ShareMode
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.theme.Griip

/**
 * Batch B11 (CALL-05/06): before Android's consent, what the share is for and whether app sound goes with it. The last
 * choice is the default next time. Android 14+ then offers one app or the whole screen; earlier versions the whole screen.
 */
@Composable
fun ShareSheet(initialMode: ShareMode, initialSound: Boolean, onContinue: (ShareMode, Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Griip.colors
    val soundPossible = CallMediaPolicy.canShareAppSound(Build.VERSION.SDK_INT)
    var mode by remember { mutableStateOf(initialMode) }
    var sound by remember { mutableStateOf(initialSound && soundPossible) }
    GlassSheet(onDismiss, title = stringResource(R.string.b11_title)) {
        Column {
            Text(stringResource(R.string.b11_warning), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            ModeRow(ShareMode.TEXT, mode, Icons.Rounded.Article, R.string.b11_text, R.string.b11_text_sub) { mode = it }
            ModeRow(ShareMode.GAME, mode, Icons.Rounded.SportsEsports, R.string.b11_game, R.string.b11_game_sub) { mode = it }
            ListCard(
                stringResource(R.string.b11_sound),
                subtitle = stringResource(if (soundPossible) R.string.b11_sound_short else R.string.b11_sound_old),
                leading = { Icon(Icons.Rounded.VolumeUp, null, tint = c.textSecondary) },
                trailing = { GriipSwitch(sound, { sound = it }, enabled = soundPossible) },
                onClick = if (soundPossible) ({ sound = !sound }) else null,
            )
            // The row's subtitle is one line: how the sound travels gets its own lines.
            if (soundPossible && sound) Text(stringResource(R.string.b11_sound_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(start = 46.dp, end = 8.dp))
            Text(
                stringResource(if (Build.VERSION.SDK_INT >= 34) R.string.b11_android14 else R.string.b11_android_asks),
                style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            PillButton(stringResource(R.string.b11_continue), { onContinue(mode, sound) }, height = 48.dp, showKnob = false)
        }
    }
}

@Composable
private fun ModeRow(value: ShareMode, chosen: ShareMode, icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, subtitle: Int, onPick: (ShareMode) -> Unit) {
    val c = Griip.colors
    ListCard(
        stringResource(title), subtitle = stringResource(subtitle), selected = value == chosen,
        leading = { Icon(icon, null, tint = if (value == chosen) c.primaryBright else c.textSecondary) },
        trailing = { if (value == chosen) Icon(Icons.Rounded.CheckCircle, null, tint = c.primaryBright) },
        onClick = { onPick(value) },
    )
}
