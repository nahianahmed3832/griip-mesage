package com.meldiq.android.ui.screens.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chat-row swipe rules (unit-tested in `ChatSwipeRulesTest`). Offsets are logical: positive toward the end edge (mark
 * read or unread), negative toward the start edge (archive or restore), so right-to-left layouts mirror them.
 *
 * Until 2026-10-17 the row was a Material SwipeToDismissBox: a swipe to mark read flew the whole row off the screen
 * and slid it back in from the other side, and a swipe begun at the screen edge also started Android's Back gesture.
 */
object ChatSwipe {
    /** The action fires past a third of the row, but never less than this far (narrow screens). */
    const val MIN_THRESHOLD_DP = 88f

    /** Mark read/unread doesn't remove the row, so it only reveals its action this far (with resistance), then springs back. */
    const val READ_MAX_FRACTION = 0.45f

    /** A quick flick counts from this much travel (px/s for velocity, fraction of the threshold for distance). */
    const val FLING_VELOCITY = 1800f
    const val FLING_MIN_FRACTION = 0.5f

    enum class Action { None, ToggleRead, ToggleArchive }

    fun threshold(widthPx: Int, minPx: Float): Float = maxOf(widthPx / 3f, minPx)

    /**
     * Where the row is drawn for a finger [dragPx] along (logical): toward the end edge it follows the finger up to the
     * threshold, then with resistance up to [READ_MAX_FRACTION] of the row; toward the start edge up to the full width.
     */
    fun offsetFor(dragPx: Float, widthPx: Int, thresholdPx: Float): Float = when {
        dragPx >= 0f -> {
            val max = maxOf(widthPx * READ_MAX_FRACTION, thresholdPx)
            if (dragPx <= thresholdPx) dragPx else minOf(max, thresholdPx + (dragPx - thresholdPx) * 0.3f)
        }
        else -> dragPx.coerceAtLeast(-widthPx.toFloat())
    }

    /** What a release at [offset] with [velocity] (logical px/s) does. */
    fun resolve(offset: Float, velocity: Float, thresholdPx: Float): Action = when {
        offset >= thresholdPx -> Action.ToggleRead
        offset <= -thresholdPx -> Action.ToggleArchive
        offset > thresholdPx * FLING_MIN_FRACTION && velocity > FLING_VELOCITY -> Action.ToggleRead
        offset < -thresholdPx * FLING_MIN_FRACTION && velocity < -FLING_VELOCITY -> Action.ToggleArchive
        else -> Action.None
    }

    /**
     * Whether a drag starting at [x] (px from the row's left) begins in the system Back-gesture area of either screen
     * edge ([leftPx], [rightPx]: Android's system-gesture insets, 0 with button navigation). Those drags belong to Android.
     */
    fun inSystemEdge(x: Float, widthPx: Int, leftPx: Int, rightPx: Int): Boolean = x < leftPx || x > widthPx - rightPx
}

/**
 * A chat-list row with swipe actions (2026-10-17): swipe toward the end edge to mark read or unread, toward the start
 * edge to archive (or, in the archived list, to restore). Marking read reveals the action and springs back, so the row
 * never leaves its place; archiving slides it out because it leaves the list. Drags that begin in Android's Back-gesture
 * edge are left to Android. The same actions, and mute, are in the long-press menu and offered to accessibility
 * services as custom actions.
 */
@Composable
fun SwipeableChat(
    archived: Boolean,
    unread: Boolean,
    muted: Boolean,
    onToggleRead: () -> Unit,
    onToggleArchive: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestures = WindowInsets.systemGestures
    var width by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) } // logical
    val minThreshold = with(density) { ChatSwipe.MIN_THRESHOLD_DP.dp.toPx() }
    val threshold = ChatSwipe.threshold(width, minThreshold)
    // Per gesture: whether it began at the screen edge (ignored), whether the threshold has been crossed (haptics), and
    // how far the finger has moved (logical px).
    val gesture = remember { booleanArrayOf(false, false) }
    val finger = remember { floatArrayOf(0f) }
    val readLabel = androidx.compose.ui.res.stringResource(if (unread) com.meldiq.android.R.string.swipe_mark_read else com.meldiq.android.R.string.swipe_mark_unread)
    val archiveLabel = androidx.compose.ui.res.stringResource(if (archived) com.meldiq.android.R.string.swipe_unarchive else com.meldiq.android.R.string.swipe_archive)
    val draggable = rememberDraggableState { delta ->
        if (gesture[0] || width == 0) return@rememberDraggableState
        finger[0] += if (rtl) -delta else delta
        val next = ChatSwipe.offsetFor(finger[0], width, threshold)
        val armed = ChatSwipe.resolve(next, 0f, threshold) != ChatSwipe.Action.None
        if (armed && !gesture[1]) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        gesture[1] = armed
        scope.launch { offset.snapTo(next) }
    }
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(readLabel) { onToggleRead(); true },
                    CustomAccessibilityAction(archiveLabel) { onToggleArchive(); true },
                    CustomAccessibilityAction(if (muted) "Unmute" else "Mute") { onToggleMute(); true },
                )
            }
            .draggable(
                draggable, Orientation.Horizontal,
                onDragStarted = { start ->
                    gesture[0] = ChatSwipe.inSystemEdge(start.x, width, gestures.getLeft(density, LayoutDirection.Ltr), gestures.getRight(density, LayoutDirection.Ltr))
                    gesture[1] = false
                    finger[0] = offset.value
                },
                onDragStopped = { v ->
                    val velocity = if (rtl) -v else v
                    when (if (gesture[0]) ChatSwipe.Action.None else ChatSwipe.resolve(offset.value, velocity, threshold)) {
                        ChatSwipe.Action.ToggleRead -> {
                            onToggleRead()
                            offset.animateTo(0f, spring(stiffness = 500f))
                        }
                        ChatSwipe.Action.ToggleArchive -> {
                            offset.animateTo(-width.toFloat(), tween(160))
                            onToggleArchive()
                            // The row normally leaves the list; if it's still shown (a name filter), bring it back.
                            delay(600)
                            offset.snapTo(0f)
                        }
                        ChatSwipe.Action.None -> offset.animateTo(0f, spring(stiffness = 600f))
                    }
                    gesture[0] = false
                    gesture[1] = false
                },
            ),
    ) {
        val o = offset.value
        if (o != 0f) {
            val towardEnd = o > 0f
            val active = ChatSwipe.resolve(o, 0f, threshold) != ChatSwipe.Action.None
            val bg = when {
                towardEnd -> if (active) c.primary else c.primarySoft
                else -> if (active) c.lavender else c.lavenderSoft
            }
            val fg = when {
                active -> c.onPrimary
                towardEnd -> c.primaryDeep
                else -> c.lavender
            }
            Row(
                Modifier.matchParentSize().background(bg).padding(horizontal = 24.dp),
                horizontalArrangement = if (towardEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    towardEnd && unread -> Icons.Outlined.MarkChatRead
                    towardEnd -> Icons.Outlined.MarkChatUnread
                    archived -> Icons.Outlined.Unarchive
                    else -> Icons.Outlined.Archive
                }
                Icon(icon, null, tint = fg)
                Spacer(Modifier.width(8.dp))
                Text(if (towardEnd) readLabel else archiveLabel, style = Griip.type.button, color = fg)
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = if (rtl) -offset.value else offset.value }.background(c.background)) { content() }
    }
}
