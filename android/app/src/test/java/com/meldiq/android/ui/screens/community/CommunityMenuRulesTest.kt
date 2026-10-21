package com.meldiq.android.ui.screens.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long-press menus on Home (docs/product/MELDIQ_ANDROID_INTERACTION_MATRIX.md). */
class CommunityMenuRulesTest {
    private fun ch(joined: Boolean = true, unread: Int = 0, muted: Boolean = false, canManage: Boolean = false, archived: Boolean = false) =
        PanelChannel("c", "general", "PUBLIC", false, "text", "talk", "Talk", 0, null, joined, unread = unread, muted = muted, canManage = canManage, archived = archived)

    @Test
    fun `members get personal items only`() {
        val items = CommunityMenuRules.channel(ch(unread = 2), starred = false, canArrange = false, canMoveUp = true, canMoveDown = true, canDelete = true)
        assertEquals(listOf(ChannelMenuItem.MARK_READ, ChannelMenuItem.FAVOURITE, ChannelMenuItem.MUTE, ChannelMenuItem.COPY_NAME), items)
    }

    @Test
    fun `managers can edit, set permissions, arrange, archive and delete`() {
        val items = CommunityMenuRules.channel(ch(canManage = true, muted = true), starred = true, canArrange = true, canMoveUp = false, canMoveDown = true, canDelete = true)
        assertEquals(
            listOf(
                ChannelMenuItem.UNFAVOURITE, ChannelMenuItem.UNMUTE, ChannelMenuItem.COPY_NAME, ChannelMenuItem.EDIT, ChannelMenuItem.PERMISSIONS,
                ChannelMenuItem.MOVE_TO, ChannelMenuItem.MOVE_DOWN, ChannelMenuItem.ARCHIVE, ChannelMenuItem.DELETE,
            ),
            items,
        )
        // An older server can't delete channels; an archived channel is restored, not edited.
        val older = CommunityMenuRules.channel(ch(canManage = true, archived = true), starred = false, canArrange = false, canMoveUp = false, canMoveDown = false, canDelete = false)
        assertFalse(ChannelMenuItem.DELETE in older)
        assertFalse(ChannelMenuItem.EDIT in older)
        assertTrue(ChannelMenuItem.RESTORE in older)
    }

    @Test
    fun `a channel you haven't joined offers nothing personal`() {
        val items = CommunityMenuRules.channel(ch(joined = false), starred = false, canArrange = false, canMoveUp = false, canMoveDown = false, canDelete = false)
        assertEquals(listOf(ChannelMenuItem.COPY_NAME), items)
    }

    @Test
    fun `category menus follow permissions, and the uncategorised group has nothing to manage`() {
        val member = CommunityMenuRules.category(true, collapsed = false, hasNews = true, hasJoined = true, allMuted = false, canCreate = false, canManage = false, canMoveUp = true, canMoveDown = true)
        assertEquals(listOf(CategoryMenuItem.MARK_READ, CategoryMenuItem.COLLAPSE, CategoryMenuItem.COLLAPSE_ALL, CategoryMenuItem.MUTE), member)
        val manager = CommunityMenuRules.category(true, collapsed = true, hasNews = false, hasJoined = true, allMuted = true, canCreate = true, canManage = true, canMoveUp = true, canMoveDown = false)
        assertEquals(
            listOf(
                CategoryMenuItem.EXPAND, CategoryMenuItem.COLLAPSE_ALL, CategoryMenuItem.UNMUTE, CategoryMenuItem.NEW_CHANNEL,
                CategoryMenuItem.RENAME, CategoryMenuItem.PERMISSIONS, CategoryMenuItem.MOVE_UP, CategoryMenuItem.DELETE,
            ),
            manager,
        )
        val loose = CommunityMenuRules.category(false, collapsed = false, hasNews = false, hasJoined = false, allMuted = false, canCreate = true, canManage = true, canMoveUp = true, canMoveDown = true)
        assertEquals(listOf(CategoryMenuItem.COLLAPSE, CategoryMenuItem.COLLAPSE_ALL, CategoryMenuItem.NEW_CHANNEL), loose)
    }

    @Test
    fun `deleting needs the channel's exact name, with or without the hash`() {
        assertTrue(CommunityMenuRules.deleteConfirmed("old-news", " #old-news "))
        assertTrue(CommunityMenuRules.deleteConfirmed("old-news", "old-news"))
        assertFalse(CommunityMenuRules.deleteConfirmed("old-news", "old"))
        assertFalse(CommunityMenuRules.deleteConfirmed("", ""))
    }
}
