package com.griipbuddy.orbit.ui.screens.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.call.CallUiState
import com.griipbuddy.orbit.call.MediaFlow
import com.griipbuddy.orbit.ui.TimeFmt
import com.griipbuddy.orbit.ui.components.Avatar
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.accentBrush
import kotlinx.coroutines.delay

/**
 * The minimised call (UI-003): a persistent panel above the app while the person uses Home, DMs, Activity or a chat.
 * It takes its own space at the top (the app below moves down rather than being covered, so no back button or header
 * action is hidden), and keeps the call's name, status and time with mute, the audio output and end/leave in reach;
 * tapping the name opens the full call screen. The call and its media never stop while it's shown.
 */
@Composable
fun MiniCallPanel(s: CallUiState, onExpand: () -> Unit, onMic: () -> Unit, onAudio: () -> Unit, onEnd: () -> Unit) {
    val c = Griip.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val status = MiniCallText.status(s, now)
    Row(
        Modifier.fillMaxWidth().background(c.accentBrush()).statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(CircleShape).pressable(onExpand, onClickLabel = "Return to the call screen").padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(s.title, s.colorIndex, size = 34.dp, ring = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(s.title, style = Griip.type.label, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = Griip.type.caption, color = c.onAccent.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.OpenInFull, null, tint = c.onAccent.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        PanelButton(if (s.micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, if (s.micOn) "Mute" else "Unmute", onMic, highlighted = !s.micOn)
        Spacer(Modifier.width(6.dp))
        PanelButton(routeIcon(s.audioRoute?.kind), "Audio output: ${s.audioRoute?.label ?: "not ready"}", onAudio, highlighted = false)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFD93A2E)).pressable(onEnd, onClickLabel = CallControlsRules.endLabel(s) + " call"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.CallEnd, CallControlsRules.endLabel(s), tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean) {
    val c = Griip.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (highlighted) Color.White else Color.Black.copy(alpha = 0.18f))
            .pressable(onClick, onClickLabel = label).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (highlighted) Color(0xFF2A1914) else c.onAccent, modifier = Modifier.size(18.dp)) }
}

/** The panel's status line (unit-tested in `MiniCallTextTest`): what is true now, never a guess. */
object MiniCallText {
    fun status(s: CallUiState, now: Long): String {
        val main = when {
            s.phase == CallPhase.Ended -> "Call ended"
            s.phase == CallPhase.Reconnecting -> "Reconnecting…"
            s.peerReconnecting -> "Waiting for them to reconnect…"
            s.phase == CallPhase.Outgoing -> if (s.isGroup) "Waiting for others…" else if (s.alerted) "Ringing…" else "Calling…"
            s.phase == CallPhase.Connecting -> "Connecting…"
            s.participants.none { !it.isLocal } -> if (s.isGroup) "Waiting for others…" else "Waiting for the other person…"
            s.media == MediaFlow.Stalled -> "Reconnecting media…"
            else -> s.connectedAt?.let { TimeFmt.duration(((now - it) / 1000).coerceAtLeast(0)) } ?: "Connecting media…"
        }
        return listOfNotNull(
            main,
            if (s.isGroup && s.participants.size > 1) "${s.participants.size} in call" else null,
            if (s.screenSharing) "sharing your screen" else null,
            if (!s.micOn) "muted" else null,
        ).joinToString(" · ")
    }
}
