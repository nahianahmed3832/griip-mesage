package com.griipbuddy.orbit.ui.screens.chats

import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.UserEntity
import com.griipbuddy.orbit.ui.theme.SystemBarRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's quick-contacts strip and its system-bar request (UI-002). */
class HomeRulesTest {
    private val me = "me"

    private fun member(id: String, name: String, color: Int = 0) =
        """{"id":"$id","username":"$id","displayName":"$name","avatarColor":$color}"""

    private fun dm(id: String, peer: String, peerName: String, unread: Int = 0, archivedAt: Long? = null, removed: Boolean = false) = ConversationEntity(
        id = id, type = "DM", orgId = null, scope = null, scopeId = null, scopeName = null, kind = null, name = null, topic = null,
        archived = false, memberCount = 2, membersJson = "[${member(me, "Me")},${member(peer, peerName, color = 3)}]", joined = true,
        myRole = null, canPost = true, canManage = false, activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1,
        unreadCount = unread, archivedAt = archivedAt, removed = removed,
    )

    private fun group(id: String) = dm(id, "x", "X").copy(type = "GROUP", name = id)

    private fun user(id: String, name: String, online: Boolean = false, photoRev: Long? = null) =
        UserEntity(id = id, username = id, displayName = name, avatarColor = 5, statusText = null, pinnedIdentityKey = null, online = online, photoRev = photoRev)

    @Test
    fun onlyDirectChatsInTheListsOwnOrder() {
        val convs = listOf(dm("d1", "ann", "Ann Rahman", unread = 2), group("team"), dm("d2", "bob", "Bob"), dm("d3", "cy", "Cy"))
        val people = HomeRules.quickContacts(convs, emptyMap(), me)
        assertEquals(listOf("ann", "bob", "cy"), people.map { it.userId }) // most recent first, as the list orders them
        assertEquals("d1", people.first().conversationId)
        assertEquals(2, people.first().unread)
    }

    @Test
    fun archivedAndLeftChatsAreLeftOut() {
        val convs = listOf(dm("d1", "ann", "Ann", archivedAt = 5), dm("d2", "bob", "Bob", removed = true), dm("d3", "cy", "Cy"))
        assertEquals(listOf("cy"), HomeRules.quickContacts(convs, emptyMap(), me).map { it.userId })
    }

    @Test
    fun knownProfilesWinOverTheChatsCopy() {
        val convs = listOf(dm("d1", "ann", "Ann (old)"))
        val known = HomeRules.quickContacts(convs, mapOf("ann" to user("ann", "Ann Rahman", online = true, photoRev = 7)), me).single()
        assertEquals("Ann Rahman", known.name)
        assertEquals(true, known.online)
        assertEquals(7L, known.photoRev)
        assertEquals(5, known.colorIndex)
        val unknown = HomeRules.quickContacts(convs, emptyMap(), me).single()
        assertEquals("Ann (old)", unknown.name)
        assertNull(unknown.online) // presence not known: no dot is drawn
        assertEquals(3, unknown.colorIndex)
    }

    @Test
    fun nobodyTwiceNobodyUnnamedAndAtMostTheLimit() {
        val convs = listOf(dm("d1", "ann", "Ann"), dm("d2", "ann", "Ann"), dm("d3", "anon", " ")) + (1..20).map { dm("x$it", "p$it", "P$it") }
        val people = HomeRules.quickContacts(convs, emptyMap(), me)
        assertEquals(HomeRules.MAX_QUICK_CONTACTS, people.size)
        assertEquals(1, people.count { it.userId == "ann" })
        assertTrue(people.none { it.userId == "anon" })
        assertTrue(HomeRules.quickContacts(emptyList(), emptyMap(), me).isEmpty())
    }

    @Test
    fun shortNamesAreTheFirstWord() {
        assertEquals("Nusrat", HomeRules.shortName("  Nusrat   Jahan "))
        assertEquals("Ann", HomeRules.shortName("Ann"))
    }

    @Test
    fun darkSurfaceRequestsCountAndNeverGoNegative() {
        val bars = SystemBarRequests()
        bars.acquire()
        bars.acquire() // the chat list leaving while it arrives again
        bars.release()
        assertEquals(1, bars.darkSurfaces)
        bars.release()
        bars.release()
        assertEquals(0, bars.darkSurfaces)
    }
}
