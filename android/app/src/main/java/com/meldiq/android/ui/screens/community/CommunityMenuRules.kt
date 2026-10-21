package com.meldiq.android.ui.screens.community

/** What a long-press on a channel offers (docs/product/MELDIQ_ANDROID_INTERACTION_MATRIX.md › Channel menu). */
enum class ChannelMenuItem {
    MARK_READ, FAVOURITE, UNFAVOURITE, MUTE, UNMUTE, COPY_NAME,
    EDIT, PERMISSIONS, MOVE_TO, MOVE_UP, MOVE_DOWN, ARCHIVE, RESTORE, DELETE,
}

/** What a long-press on a category header offers. */
enum class CategoryMenuItem {
    MARK_READ, COLLAPSE, EXPAND, COLLAPSE_ALL, MUTE, UNMUTE,
    NEW_CHANNEL, RENAME, PERMISSIONS, MOVE_UP, MOVE_DOWN, DELETE,
}

/**
 * Which menu items show. Hiding is for clarity only: the server checks every one of them again. Pure and unit-tested
 * (`CommunityMenuRulesTest`).
 */
object CommunityMenuRules {
    /**
     * A channel's menu. [canManage]: you manage this channel (`channels.manage` there); [canArrange]: you may move
     * channels in the community; [canDelete]: the server deletes channels (`channel-delete`). Channels you haven't
     * joined offer nothing personal (no unread, mute or favourite yet).
     */
    fun channel(
        ch: PanelChannel,
        starred: Boolean,
        canArrange: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        canDelete: Boolean,
    ): List<ChannelMenuItem> = buildList {
        if (ch.joined) {
            if (ch.hasNews) add(ChannelMenuItem.MARK_READ)
            add(if (starred) ChannelMenuItem.UNFAVOURITE else ChannelMenuItem.FAVOURITE)
            add(if (ch.muted) ChannelMenuItem.UNMUTE else ChannelMenuItem.MUTE)
        }
        add(ChannelMenuItem.COPY_NAME)
        if (ch.canManage) {
            if (!ch.archived) add(ChannelMenuItem.EDIT)
            add(ChannelMenuItem.PERMISSIONS)
        }
        if (canArrange) {
            add(ChannelMenuItem.MOVE_TO)
            if (canMoveUp) add(ChannelMenuItem.MOVE_UP)
            if (canMoveDown) add(ChannelMenuItem.MOVE_DOWN)
        }
        if (ch.canManage) {
            add(if (ch.archived) ChannelMenuItem.RESTORE else ChannelMenuItem.ARCHIVE)
            if (canDelete) add(ChannelMenuItem.DELETE)
        }
    }

    /**
     * A category's menu. [hasNews]: a channel in it has something unread; [allMuted]: every joined channel in it is
     * muted; [canCreate]: you may create channels in it; [canManage]: you manage it; [isCategory]: false for the
     * channels without a category (nothing to rename, move or delete there).
     */
    fun category(
        isCategory: Boolean,
        collapsed: Boolean,
        hasNews: Boolean,
        hasJoined: Boolean,
        allMuted: Boolean,
        canCreate: Boolean,
        canManage: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
    ): List<CategoryMenuItem> = buildList {
        if (hasNews) add(CategoryMenuItem.MARK_READ)
        add(if (collapsed) CategoryMenuItem.EXPAND else CategoryMenuItem.COLLAPSE)
        add(CategoryMenuItem.COLLAPSE_ALL)
        if (hasJoined) add(if (allMuted) CategoryMenuItem.UNMUTE else CategoryMenuItem.MUTE)
        if (canCreate) add(CategoryMenuItem.NEW_CHANNEL)
        if (isCategory && canManage) {
            add(CategoryMenuItem.RENAME)
            add(CategoryMenuItem.PERMISSIONS)
            if (canMoveUp) add(CategoryMenuItem.MOVE_UP)
            if (canMoveDown) add(CategoryMenuItem.MOVE_DOWN)
            add(CategoryMenuItem.DELETE)
        }
    }

    /** Typing a channel's name to delete it: matches with or without the leading "#", ignoring surrounding spaces. */
    fun deleteConfirmed(channelName: String, typed: String): Boolean = typed.trim().removePrefix("#").trim() == channelName.trim() && channelName.isNotBlank()
}
