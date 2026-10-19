package com.meldiq.android.ui.screens.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * Swipe rules (unit-tested in `SwipeToReplyTest`): the message follows the finger toward the end edge with growing
 * resistance, and a release past [THRESHOLD_DP] replies. Only horizontal drags count (Compose's touch slop decides
 * between this and the list's vertical scroll), and controls inside the bubble that drag sideways (the voice
 * message's seek bar) take their own drags first.
 */
object SwipeReply {
    const val THRESHOLD_DP = 64f
    const val MAX_DP = 96f

    /** Where the bubble is drawn for a finger [dragPx] along (never backwards past rest; resistance beyond the threshold). */
    fun offsetFor(dragPx: Float, thresholdPx: Float, maxPx: Float): Float = when {
        dragPx <= 0f -> 0f
        dragPx <= thresholdPx -> dragPx
        else -> minOf(maxPx, thresholdPx + (dragPx - thresholdPx) * 0.35f)
    }

    fun triggers(offsetPx: Float, thresholdPx: Float): Boolean = offsetPx >= thresholdPx

    /** 0..1 for the reply icon's fade and scale. */
    fun progress(offsetPx: Float, thresholdPx: Float): Float = (offsetPx / thresholdPx).coerceIn(0f, 1f)
}

/**
 * Wraps one message row: swipe it toward the end edge to reply. [enabled] false (deleted, call entries, no
 * permission to post) leaves the row untouched. Long press, taps and the accessibility "Reply" action keep working.
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val threshold = with(density) { SwipeReply.THRESHOLD_DP.dp.toPx() }
    val max = with(density) { SwipeReply.MAX_DP.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var drag = remember { floatArrayOf(0f) }
    var armed = remember { booleanArrayOf(false) }
    // A drag that starts in Android's Back-gesture edge is Back, not a reply (2026-10-17).
    val edge = remember { booleanArrayOf(false) }
    val gestures = androidx.compose.foundation.layout.WindowInsets.systemGestures
    var width = remember { intArrayOf(0) }
    val state = rememberDraggableState { delta ->
        if (edge[0]) return@rememberDraggableState
        drag[0] = (drag[0] + delta).coerceAtLeast(0f)
        val o = SwipeReply.offsetFor(drag[0], threshold, max)
        val now = SwipeReply.triggers(o, threshold)
        if (now && !armed[0]) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        armed[0] = now
        scope.launch { offset.snapTo(o) }
    }
    Box(
        Modifier
            .onSizeChanged { width[0] = it.width }
            .semantics { customActions = listOf(CustomAccessibilityAction("Reply") { onReply(); true }) }
            .draggable(
                state, Orientation.Horizontal, reverseDirection = rtl,
                onDragStarted = { start ->
                    drag[0] = 0f; armed[0] = false
                    edge[0] = com.meldiq.android.ui.screens.chats.ChatSwipe.inSystemEdge(
                        start.x, width[0], gestures.getLeft(density, LayoutDirection.Ltr), gestures.getRight(density, LayoutDirection.Ltr),
                    )
                },
                onDragStopped = {
                    if (!edge[0] && SwipeReply.triggers(offset.value, threshold)) onReply()
                    drag[0] = 0f; armed[0] = false; edge[0] = false
                    offset.animateTo(0f, spring(stiffness = 600f))
                },
            ),
    ) {
        val p = SwipeReply.progress(offset.value, threshold)
        if (p > 0f) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(32.dp).alpha(p).scale(0.6f + 0.4f * p)
                    .clip(CircleShape).background(Griip.colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = Griip.colors.primary, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.graphicsLayer { translationX = if (rtl) -offset.value else offset.value }) { content() }
    }
}
