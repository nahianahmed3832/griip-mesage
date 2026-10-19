package com.meldiq.android.ui.screens.chats

import com.meldiq.android.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-list swipe actions and the archive (2026-10-17). */
class ChatListRulesTest {
    private fun conv(id: String, type: String = "DM", unread: Int = 0, archivedAt: Long? = null, name: String = id) = ConversationEntity(
        id = id, type = type, orgId = null, scope = null, scopeId = null, scopeName = null, kind = null, name = name, topic = null,
        archived = false, memberCount = 2, membersJson = "[]", joined = true, myRole = null, canPost = true, canManage = false,
        activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, unreadCount = unread, archivedAt = archivedAt,
    )

    private val ann = conv("ann", unread = 2)
    private val team = conv("team", type = "GROUP")
    private val old = conv("old project", type = "GROUP", unread = 1, archivedAt = 5)
    private val news = conv("news", type = "CHANNEL", archivedAt = 6)
    private val all = listOf(ann, team, old, news)
    private fun visible(filter: Int = ChatListRules.ALL, query: String = "", archived: Boolean = false) =
        ChatListRules.visible(all, filter, query, archived) { it.name!! }.map { it.id }

    @Test
    fun archivedChatsLeaveTheMainListAndAppearInTheArchive() {
        assertEquals(listOf("ann", "team"), visible())
        assertEquals(listOf("old project", "news"), visible(archived = true))
        assertEquals(listOf("team"), visible(ChatListRules.GROUPS)) // filters ignore archived chats
        assertEquals(listOf("ann"), visible(ChatListRules.UNREAD))
    }

    @Test
    fun theDmsTabListsPeopleChatsWhileItsArchiveStillRestoresChannels() {
        val general = conv("general", type = "CHANNEL", unread = 3)
        val list = all + general
        fun dms(query: String = "", archived: Boolean = false, filter: Int = ChatListRules.ALL) =
            ChatListRules.visible(list, filter, query, archived, peopleOnly = true) { it.name!! }.map { it.id }
        assertEquals(listOf("ann", "team"), dms()) // channels are on Home
        assertEquals(listOf("old project", "news"), dms(archived = true)) // an archived channel can still be restored here
        assertEquals(emptyList<String>(), dms(query = "gen"))
        assertEquals(listOf("ann"), dms(filter = ChatListRules.UNREAD))
        assertEquals(1, ChatListRules.unreadCount(list, peopleOnly = true))
        assertEquals(2, ChatListRules.unreadCount(list))
    }

    @Test
    fun theNameFilterStillFindsArchivedChats() {
        assertEquals(listOf("old project"), visible(query = "OLD"))
        assertEquals(listOf("news"), visible(query = "ne", archived = true))
        assertEquals(emptyList<String>(), visible(query = "ann", archived = true))
    }

    @Test
    fun countsLeaveArchivedChatsOut() {
        assertEquals(1, ChatListRules.unreadCount(all)) // "old project" is unread but archived
        assertEquals(ChatListRules.Archived(chats = 2, unreadChats = 1), ChatListRules.archived(all))
        assertEquals(ChatListRules.Archived(0, 0), ChatListRules.archived(listOf(ann, team)))
    }

    @Test
    fun swipingRightTogglesReadState() {
        assertTrue(ChatListRules.swipeMarksRead(ann))
        assertFalse(ChatListRules.swipeMarksRead(team))
        assertTrue(ChatListRules.isArchived(old))
        assertFalse(ChatListRules.isArchived(ann))
    }
}
