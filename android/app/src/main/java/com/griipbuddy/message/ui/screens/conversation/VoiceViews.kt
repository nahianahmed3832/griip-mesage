package com.griipbuddy.message.ui.screens.conversation

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.data.model.AttachmentRef
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.voice.VoiceDraftController
import com.griipbuddy.message.voice.VoiceNote
import com.griipbuddy.message.voice.VoicePlayer
import kotlinx.coroutines.delay

/** What the composer needs to know about voice messages. [playback] is the player state when it holds the draft. */
data class VoiceComposerState(
    val draft: VoiceDraftController.Draft,
    val available: Boolean,
    val playback: VoicePlayer.State?,
)

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: (() -> Unit)?, bg: Color, fg: Color, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).pressable(onClick, onClickLabel = label).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun ComposerFrame(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Griip.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Recording: cancel, a live timer with a pulsing dot, and stop (which keeps the clip for preview). */
@Composable
fun VoiceRecordingBar(startedAt: Long, onCancel: () -> Unit, onStop: () -> Unit) {
    val c = Griip.colors
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - startedAt
            delay(200)
        }
    }
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    ComposerFrame {
        RoundAction(Icons.Outlined.DeleteOutline, "Cancel recording", onCancel, Color.Transparent, c.danger)
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant).padding(horizontal = 16.dp)
                .semantics { contentDescription = "Recording a voice message, ${VoiceNote.spoken(elapsed)}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c.danger.copy(alpha = pulse)))
            Spacer(Modifier.width(10.dp))
            Text(VoiceNote.clock(elapsed), style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Recording…", style = Griip.type.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.width(6.dp))
        RoundAction(Icons.Rounded.Stop, "Stop recording", onStop, c.danger, Color.White)
    }
}

/** A finished clip: delete, listen (play, pause, seek) and send. */
@Composable
fun VoicePreviewBar(
    clip: VoiceDraftController.Draft.Ready,
    playback: VoicePlayer.State?,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSend: () -> Unit,
) {
    val c = Griip.colors
    ComposerFrame {
        RoundAction(Icons.Outlined.DeleteOutline, "Delete voice message", onDelete, Color.Transparent, c.danger)
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant).padding(start = 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceControls(
                durationMs = playback?.durationMs?.takeIf { it > 0 } ?: clip.durationMs,
                playback = playback,
                busy = false,
                accent = c.primary,
                meta = c.textSecondary,
                onToggle = onToggle,
                onSeek = onSeek,
                what = "recording",
            )
        }
        Spacer(Modifier.width(6.dp))
        RoundAction(Icons.AutoMirrored.Rounded.Send, "Send voice message", onSend, c.actionStart, c.onAction)
    }
}

/** A voice message inside a bubble: play or pause, a seek bar and the time. Downloads on first play. */
@Composable
fun VoiceMessageView(
    a: AttachmentRef,
    out: Boolean,
    transfer: Float?,
    playback: VoicePlayer.State?,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val c = Griip.colors
    Row(Modifier.widthIn(min = 220.dp, max = 270.dp), verticalAlignment = Alignment.CenterVertically) {
        VoiceControls(
            durationMs = playback?.durationMs?.takeIf { it > 0 } ?: a.durationMs ?: 0,
            playback = playback,
            busy = transfer != null,
            accent = c.primary,
            meta = if (out) c.bubbleOutgoingMeta else c.textSecondary,
            onToggle = onToggle,
            onSeek = onSeek,
            what = "voice message",
            progress = transfer,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.VoiceControls(
    durationMs: Long,
    playback: VoicePlayer.State?,
    busy: Boolean,
    accent: Color,
    meta: Color,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    what: String,
    progress: Float? = null,
) {
    val c = Griip.colors
    val playing = playback?.playing == true
    val position = playback?.positionMs ?: 0L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: VoiceNote.fraction(position, durationMs)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(accent)
            .pressable(if (busy) null else onToggle, onClickLabel = if (playing) "Pause" else "Play")
            .semantics {
                contentDescription = when {
                    busy -> "Downloading $what"
                    playing -> "Pause $what"
                    else -> "Play $what, ${VoiceNote.spoken(durationMs)}"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            if (progress != null && progress > 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), color = c.onAction, strokeWidth = 3.dp)
            else CircularProgressIndicator(modifier = Modifier.size(24.dp), color = c.onAction, strokeWidth = 3.dp)
        } else {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = c.onAction, modifier = Modifier.size(24.dp))
        }
    }
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = !busy && durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth().height(28.dp).semantics {
                contentDescription = "Seek $what"
                stateDescription = "${VoiceNote.spoken((fraction * durationMs).toLong())} of ${VoiceNote.spoken(durationMs)}"
            },
        )
        val shown = if (playback != null && (playing || position > 0)) "${VoiceNote.clock(position)} / ${VoiceNote.clock(durationMs)}" else VoiceNote.clock(durationMs)
        Text(shown, style = Griip.type.caption, color = meta, modifier = Modifier.padding(start = 6.dp))
    }
}
