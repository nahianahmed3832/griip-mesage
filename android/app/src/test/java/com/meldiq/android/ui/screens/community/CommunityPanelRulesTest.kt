package com.meldiq.android.ui.screens.community

import com.meldiq.android.data.api.CategoryDto
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's community rail and channel panel (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §2). */
class CommunityPanelRulesTest {
    private fun conv(
        id: String,
        orgId: String = "o1",
        categoryId: String? = "talk",
        unread: Int = 0,
        mentions: Int = 0,
        muted: Boolean = false,
        joined: Boolean = true,
        removed: Boolean = false,
        archivedAt: Long? = null,
        position: Int = 0,
        type: String = "CHANNEL",
    ) = ConversationEntity(
        id = id, type = type, orgId = if (type == "CHANNEL") orgId else null, scope = null, scopeId = null, scopeName = null, kind = "PUBLIC", name = id,
        topic = null, archived = false, memberCount = 3, membersJson = "[]", joined = joined, myRole = null, canPost = true,
        canManage = false, activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, unreadCount = unread,
        mentionCount = mentions, muted = muted, removed = removed, archivedAt = archivedAt, categoryId = categoryId, position = position,
    )

    private fun dto(id: String, joined: Boolean, archived: Boolean = false, categoryId: String? = "talk") =
        ConversationDto(id = id, type = "CHANNEL", orgId = "o1", name = id, joined = joined, archived = archived, categoryId = categoryId)

    private val categories = listOf(CategoryDto(id = "start", name = "Start here", position = 0), CategoryDto(id = "talk", name = "Talk", position = 1))

    @Test
    fun `joined channels come from the phone, the ones you may join from the server, each once`() {
        val list = CommunityPanelRules.channels(
            listOf(conv("general"), conv("left", removed = true), conv("archived-here", archivedAt = 5), conv("other", orgId = "o2"), conv("dm", type = "DM")),
            listOf(dto("general", joined = true), dto("lobby", joined = false), dto("old", joined = false, archived = true)),
            "o1",
        )
        assertEquals(listOf("general" to true, "lobby" to false), list.map { it.id to it.joined })
        assertTrue(CommunityPanelRules.channels(listOf(conv("general")), emptyList(), null).isEmpty())
    }

    @Test
    fun `sections follow the community order, uncategorised channels first`() {
        val channels = CommunityPanelRules.channels(
            listOf(conv("general", position = 1), conv("intro", position = 0), conv("rules", categoryId = "start"), conv("loose", categoryId = null)), emptyList(), "o1",
        )
        val s = CommunityPanelRules.sections(channels, categories, collapsed = emptySet())
        assertEquals(listOf("category:none", "category:start", "category:talk"), s.map { it.key })
        assertEquals(listOf("intro", "general"), s.last().shown.map { it.id })
    }

    @Test
    fun `a collapsed category keeps channels with something new and the open one`() {
        val channels = CommunityPanelRules.channels(
            listOf(conv("quiet"), conv("busy", unread = 2), conv("pinged", mentions = 1), conv("open")), emptyList(), "o1",
        )
        val s = CommunityPanelRules.sections(channels, categories, collapsed = setOf("category:talk"), selectedId = "open").single()
        assertTrue(s.collapsed)
        assertEquals(setOf("busy", "pinged", "open"), s.shown.map { it.id }.toSet())
        assertEquals(4, s.all.size)
        // Collapsed with nothing new: the header stays so it can be opened.
        val calm = CommunityPanelRules.sections(CommunityPanelRules.channels(listOf(conv("quiet")), emptyList(), "o1"), categories, setOf("category:talk"))
        assertEquals(1, calm.size)
        assertTrue(calm.single().shown.isEmpty())
    }

    @Test
    fun `hiding muted channels keeps the ones that mention you`() {
        val channels = CommunityPanelRules.channels(listOf(conv("a"), conv("muted", muted = true), conv("muted-ping", muted = true, mentions = 1)), emptyList(), "o1")
        val s = CommunityPanelRules.sections(channels, categories, emptySet(), hideMuted = true).single()
        assertEquals(listOf("a", "muted-ping"), s.shown.map { it.id })
    }

    @Test
    fun `unread only drops quiet sections, and favourites are not listed twice`() {
        val channels = CommunityPanelRules.channels(listOf(conv("a", unread = 1), conv("b"), conv("c", categoryId = "start")), emptyList(), "o1")
        val s = CommunityPanelRules.sections(channels, categories, emptySet(), unreadOnly = true)
        assertEquals(listOf("category:talk"), s.map { it.key })
        assertEquals(listOf("a"), s.single().shown.map { it.id })
        val placed = CommunityPanelRules.sections(channels, categories, emptySet(), placed = setOf("a"))
        assertFalse(placed.flatMap { it.shown }.any { it.id == "a" })
    }

    @Test
    fun `empty categories stay only for the people who fill them`() {
        val channels = CommunityPanelRules.channels(listOf(conv("a")), emptyList(), "o1")
        assertEquals(1, CommunityPanelRules.sections(channels, categories, emptySet()).size)
        assertEquals(2, CommunityPanelRules.sections(channels, categories, emptySet(), keepEmpty = true).size)
    }

    @Test
    fun `the rail shows unread outside muted channels and every mention`() {
        val badges = CommunityPanelRules.rail(
            listOf(conv("a", unread = 3), conv("m", orgId = "o2", muted = true, unread = 9), conv("p", orgId = "o3", muted = true, mentions = 2), conv("q", orgId = "o4")),
        )
        assertEquals(RailBadge(unread = true, mentions = 0), badges["o1"])
        assertNull(badges["o2"])
        assertEquals(RailBadge(unread = false, mentions = 2), badges["o3"])
        assertNull(badges["o4"])
    }

    @Test
    fun `mark as read, mute and the remembered channel act on joined channels only`() {
        val channels = CommunityPanelRules.channels(listOf(conv("a", unread = 1), conv("b", muted = true), conv("c", mentions = 1)), listOf(dto("x", joined = false)), "o1")
        assertEquals(listOf("a", "c"), CommunityPanelRules.markReadTargets(channels).map { it.id })
        assertFalse(CommunityPanelRules.allMuted(channels))
        assertEquals(listOf("a", "c"), CommunityPanelRules.muteTargets(channels, mute = true).map { it.id })
        assertEquals(listOf("b"), CommunityPanelRules.muteTargets(channels, mute = false).map { it.id })
        assertEquals("a", CommunityPanelRules.selected("a", channels))
        assertNull(CommunityPanelRules.selected("gone", channels))
    }

    @Test
    fun `the selected community survives while you belong to it`() {
        val orgs = listOf(OrgDto(id = "o1", name = "One", slug = "one", myRole = "MEMBER"), OrgDto(id = "o2", name = "Two", slug = "two", myRole = "MEMBER"))
        assertEquals("o2", CommunityPanelRules.selectedCommunity(orgs, "o2"))
        assertEquals("o1", CommunityPanelRules.selectedCommunity(orgs, "gone"))
        assertNull(CommunityPanelRules.selectedCommunity(emptyList(), "o1"))
    }
}
