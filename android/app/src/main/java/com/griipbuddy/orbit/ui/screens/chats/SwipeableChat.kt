package com.griipbuddy.orbit.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.ui.theme.Griip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A chat-list row with swipe actions (2026-10-17): swipe right to mark read or unread, swipe left to archive (or, in the
 * archived list, to restore). Both are reversible and local to this phone. The same actions, and mute, are in the
 * long-press menu and offered to accessibility services as custom actions.
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
    // A third of the row: a short accidental drag springs back.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { it * 0.33f } }
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    val readLabel = if (unread) "Mark read" else "Mark unread"
    val archiveLabel = if (archived) "Unarchive" else "Archive"
    SwipeToDismissBox(
        state,
        backgroundContent = background@{
            val dir = state.dismissDirection
            if (dir == SwipeToDismissBoxValue.Settled) return@background
            val right = dir == SwipeToDismissBoxValue.StartToEnd
            val active = state.targetValue == dir
            val bg = when {
                right -> if (active) c.primary else c.primarySoft
                else -> if (active) c.lavender else c.lavenderSoft
            }
            val fg = when {
                active -> c.onPrimary
                right -> c.primaryDeep
                else -> c.lavender
            }
            Row(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    right && unread -> Icons.Outlined.MarkChatRead
                    right -> Icons.Outlined.MarkChatUnread
                    archived -> Icons.Outlined.Unarchive
                    else -> Icons.Outlined.Archive
                }
                Icon(icon, null, tint = fg)
                Spacer(Modifier.width(8.dp))
                Text(if (right) readLabel else archiveLabel, style = Griip.type.button, color = fg)
            }
        },
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(readLabel) { onToggleRead(); true },
                CustomAccessibilityAction(archiveLabel) { onToggleArchive(); true },
                CustomAccessibilityAction(if (muted) "Unmute" else "Mute") { onToggleMute(); true },
            )
        },
        onDismiss = { dir ->
            when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onToggleRead()
                    scope.launch { state.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onToggleArchive()
                    // The row normally leaves the list; if it's still here (the list didn't change), bring it back.
                    scope.launch { delay(700); state.snapTo(SwipeToDismissBoxValue.Settled) }
                }
                else -> Unit
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().background(c.background)) { content() }
    }
}
