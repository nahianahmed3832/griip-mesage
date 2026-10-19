package com.griipbuddy.orbit.ui.screens.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.di.AppContainer
import com.griipbuddy.orbit.ui.LocalContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.MenuRow

/** The local, per-phone list actions behind swipes and the long-press sheet (nothing is sent to anyone). */
class ChatListActions(private val container: AppContainer, private val scope: CoroutineScope) {
    /** Unread chats become read (and their notification goes), read chats become unread. */
    fun toggleRead(conv: ConversationEntity) {
        scope.launch {
            if (ChatListRules.swipeMarksRead(conv)) {
                container.conversations.markRead(conv.id)
                container.notifier.clear(conv.id)
            } else container.conversations.markUnread(conv.id)
        }
    }

    fun setArchived(conv: ConversationEntity, archived: Boolean) {
        scope.launch { container.conversations.setArchived(conv.id, archived) }
    }

    /** Mutes or unmutes on every device of this person (servers with `nav-preferences`; otherwise this phone only). */
    fun toggleMute(conv: ConversationEntity) {
        scope.launch { container.navPrefs.setMuted(conv, !conv.muted) }
    }

    /** Stars or unstars a conversation for this person (kept on the server, so every device shows it). */
    fun toggleStar(conv: ConversationEntity) = container.navPrefs.toggleStar(conv)
}

@Composable
fun rememberChatListActions(): ChatListActions {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    return remember(container, scope) { ChatListActions(container, scope) }
}

/** A conversation's title as lists show it: channels with "#". */
fun listTitle(conv: ConversationEntity, myUserId: String?): String =
    ConversationRepository.title(conv, myUserId).let { if (conv.type == "CHANNEL") "#$it" else it }

/**
 * A conversation's long-press actions (DMs tab and Home): mute, read state and archive, all local to this phone, and its
 * info screen. Each action closes the sheet.
 */
@Composable
fun ChatActionsSheet(
    conv: ConversationEntity,
    myUserId: String?,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleArchive: () -> Unit,
    onInfo: () -> Unit,
    starred: Boolean = false,
    onToggleStar: (() -> Unit)? = null,
    /** Opens the section picker: a custom section, Starred, or back to its usual group (Home). */
    onMoveToSection: (() -> Unit)? = null,
) {
    GlassSheet(onDismiss, title = listTitle(conv, myUserId)) {
        if (onToggleStar != null) MenuRow(
            if (starred) Icons.Rounded.Star else Icons.Outlined.StarOutline,
            androidx.compose.ui.res.stringResource(if (starred) com.griipbuddy.orbit.R.string.star_remove else com.griipbuddy.orbit.R.string.star_add),
            subtitle = androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.star_note),
            onClick = { onToggleStar(); onDismiss() }, trailing = null,
        )
        if (onMoveToSection != null) MenuRow(
            Icons.Outlined.Folder, androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.sec_move_to),
            subtitle = androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.sec_move_to_sub),
            onClick = { onDismiss(); onMoveToSection() }, trailing = null,
        )
        MenuRow(
            if (conv.muted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
            if (conv.muted) "Unmute notifications" else "Mute notifications",
            subtitle = if (conv.muted) null else "You'll still get notified when someone @mentions you",
            onClick = { onToggleMute(); onDismiss() }, trailing = null,
        )
        if (conv.unreadCount > 0) MenuRow(
            Icons.Outlined.MarkChatRead, "Mark as read", subtitle = "Only on this device; no read receipt is sent",
            onClick = { onToggleRead(); onDismiss() }, trailing = null,
        ) else MenuRow(
            Icons.Outlined.MarkChatUnread, "Mark as unread", subtitle = "Only on this device; nothing is sent",
            onClick = { onToggleRead(); onDismiss() }, trailing = null,
        )
        val archived = ChatListRules.isArchived(conv)
        MenuRow(
            if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (archived) "Unarchive" else "Archive",
            subtitle = if (archived) "Back to your chat list" else "Only on this phone; you stay in the chat and can restore it",
            onClick = { onToggleArchive(); onDismiss() }, trailing = null,
        )
        MenuRow(
            Icons.Outlined.Info, if (conv.type == "DM") "Contact info" else if (conv.type == "GROUP") "Group info" else "Channel info",
            onClick = { onDismiss(); onInfo() }, trailing = null,
        )
    }
}
