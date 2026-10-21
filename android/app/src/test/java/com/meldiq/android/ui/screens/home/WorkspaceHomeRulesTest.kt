package com.meldiq.android.ui.screens.home

import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.db.CallLogEntity
import com.meldiq.android.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home and Activity tabs and the tab badges (UI-002): built only from data already on the phone. */
class WorkspaceHomeRulesTest {
    private fun conv(
        id: String,
        type: String = "CHANNEL",
        orgId: String? = if (type == "CHANNEL") "o1" else null,
        scope: String? = if (type == "CHANNEL") "ORG" else null,
        scopeId: String? = orgId,
        scopeName: String? = null,
        unread: Int = 0,
        mentions: Int = 0,
        muted: Boolean = false,
        archivedAt: Long? = null,
        removed: Boolean = false,
        joined: Boolean = true,
        draft: String? = null,
        categoryId: String? = null,
        categoryName: String? = null,
        position: Int = 0,
    ) = ConversationEntity(
        id = id, type = type, orgId = orgId, scope = scope, scopeId = scopeId, scopeName = scopeName, kind = "PUBLIC", name = id,
        topic = null, archived = false, memberCount = 3, membersJson = "[]", joined = joined, myRole = null, canPost = true,
        canManage = false, activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, unreadCount = unread,
        mentionCount = mentions, muted = muted, draft = draft, removed = removed, archivedAt = archivedAt,
        categoryId = categoryId, categoryName = categoryName, position = position,
    )

    @Test
    fun aVoiceChannelIsListedOnceInItsCategoryWithTheTextChannels() {
        val convs = listOf(conv("general", categoryId = "talk", categoryName = "Talk"), conv("lounge", categoryId = "talk", categoryName = "Talk", position = 1).copy(voice = true))
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Channels")
        assertEquals(listOf("general", "lounge"), groups.flatMap { g -> g.channels.map { it.id } })
        assertEquals(listOf("Talk"), groups.map { it.title })
    }

    @Test
    fun markAllAsReadClearsThisWorkspacesUnreadChannelsOnly() {
        val convs = listOf(
            conv("general", orgId = "o1", unread = 2),
            conv("random", orgId = "o1", mentions = 1),
            conv("quiet", orgId = "o1"),
            conv("other", orgId = "o2", unread = 4),
            conv("left", orgId = "o1", unread = 1, removed = true),
            conv("ann", type = "DM", unread = 3),
        )
        assertEquals(listOf("general", "random"), WorkspaceHomeRules.markAllReadTargets(convs, "o1").map { it.id })
        assertEquals(emptyList<String>(), WorkspaceHomeRules.markAllReadTargets(convs, null).map { it.id })
    }

    @Test
    fun theWorkspaceRailMarksWorkspacesWithUnreadChannelsYouHaventMuted() {
        val convs = listOf(
            conv("general", orgId = "o1", unread = 3),
            conv("random", orgId = "o1", mentions = 1),
            conv("quiet", orgId = "o2", unread = 9, muted = true),
            conv("left", orgId = "o2", unread = 2, removed = true),
            conv("not-joined", orgId = "o3", unread = 4, joined = false),
            conv("archived", orgId = "o3", unread = 1, archivedAt = 5),
            conv("lounge", orgId = "o4", unread = 1),
            conv("ann", type = "DM", unread = 2),
        )
        assertEquals(mapOf("o1" to 2, "o4" to 1), WorkspaceHomeRules.unreadByWorkspace(convs))
        assertEquals(emptyMap<String, Int>(), WorkspaceHomeRules.unreadByWorkspace(emptyList()))
    }

    @Test
    fun unreadDmsAreUnreadDirectAndGroupChatsInTheListsOrder() {
        val convs = listOf(
            conv("ann", type = "DM", unread = 2),
            conv("general", unread = 5), // a channel: on Home under its group, not here
            conv("crew", type = "GROUP", unread = 1),
            conv("bob", type = "DM"), // read
            conv("old", type = "DM", unread = 3, archivedAt = 9), // archived on this phone
            conv("gone", type = "DM", unread = 1, removed = true),
        )
        assertEquals(listOf("ann", "crew"), WorkspaceHomeRules.unreadDms(convs).map { it.id })
    }

    @Test
    fun mentionsFollowTheSelectedWorkspaceAndKeepArchivedChats() {
        val convs = listOf(
            conv("design", mentions = 1),
            conv("sales", orgId = "o2", mentions = 2),
            conv("ann", type = "DM", mentions = 1),
            conv("hr", mentions = 3, archivedAt = 4),
            conv("quiet", unread = 4),
            conv("left", mentions = 1, removed = true),
        )
        assertEquals(listOf("design", "ann", "hr"), WorkspaceHomeRules.mentions(convs, "o1").map { it.id })
        assertEquals(listOf("design", "sales", "ann", "hr"), WorkspaceHomeRules.allMentions(convs).map { it.id })
    }

    @Test
    fun channelsAreGroupedByCategoryInTheCommunitysOrderUncategorisedFirst() {
        val cats = listOf(
            com.meldiq.android.data.api.CategoryDto("talk", "Talk", position = 1),
            com.meldiq.android.data.api.CategoryDto("start", "Start here", position = 0),
        )
        val convs = listOf(
            conv("zeta", categoryId = "talk", categoryName = "Talk", position = 1),
            conv("alpha", categoryId = "talk", categoryName = "Talk", position = 0, unread = 2),
            conv("lobby"), // no category: the first group, titled with the given name
            conv("welcome", categoryId = "start", categoryName = "Start here"),
            conv("other-org", orgId = "o2", categoryId = "talk"),
            conv("not-joined", joined = false),
            conv("archived", archivedAt = 1),
            conv("ann", type = "DM", unread = 1),
        )
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Channels", categories = cats)
        assertEquals(listOf("Channels", "Start here", "Talk"), groups.map { it.title })
        assertEquals(listOf("category:none", "category:start", "category:talk"), groups.map { it.key })
        assertEquals(listOf("alpha", "zeta"), groups.last().channels.map { it.id })
        assertEquals(1, groups.last().unreadChannels)
        // Before the community's categories load, the names the channels carry are used.
        assertEquals(listOf("Channels", "Start here", "Talk"), WorkspaceHomeRules.channelGroups(convs, "o1", "Channels").map { it.title })
        assertTrue(WorkspaceHomeRules.channelGroups(convs, null, "Channels").isEmpty()) // no community: no channels
    }

    @Test
    fun unreadOnlyKeepsUnreadChannelsAndDropsEmptyGroups() {
        val convs = listOf(
            conv("zeta", categoryId = "talk", categoryName = "Talk"),
            conv("alpha", categoryId = "talk", categoryName = "Talk", unread = 2),
            conv("announcements"),
        )
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Channels", unreadOnly = true)
        assertEquals(listOf("Talk"), groups.map { it.title })
        assertEquals(listOf("alpha"), groups.single().channels.map { it.id })
    }

    @Test
    fun cardCountsAndTabBadgesCountOnlyWhatIsReal() {
        val convs = listOf(
            conv("ann", type = "DM", unread = 2),
            conv("crew", type = "GROUP", unread = 3, muted = true),
            conv("general", unread = 4, mentions = 1),
            conv("sales", orgId = "o2", unread = 1, mentions = 2),
            conv("old", type = "DM", unread = 7, archivedAt = 1, draft = "see you"),
            conv("notes", type = "DM", draft = "  "),
            conv("plan", type = "DM", draft = "Friday?"),
        )
        assertEquals(3, WorkspaceHomeRules.unreadChats(convs, "o1")) // ann, crew, general; not o2's channel or the archived chat
        assertEquals(listOf("old", "plan"), WorkspaceHomeRules.drafts(convs).map { it.id }) // blank drafts aren't drafts
        assertEquals(2, WorkspaceHomeRules.dmBadge(convs)) // muted and archived chats don't badge
        assertEquals(2, WorkspaceHomeRules.homeBadge(convs)) // unread channels in any workspace
        assertEquals(3, WorkspaceHomeRules.activityBadge(convs))
    }

    @Test
    fun missedCallsAreUnansweredIncomingCallsInTheWindowNewestFirst() {
        val now = 10_000_000L
        val day = WorkspaceHomeRules.MISSED_CALLS_CARD_WINDOW_MS
        val log = listOf(
            CallLogEntity("a", "c1", video = false, outgoing = false, startedAt = now - 1_000),
            CallLogEntity("b", "c1", video = true, outgoing = false, startedAt = now - 500),
            CallLogEntity("answered", "c1", video = false, outgoing = false, startedAt = now - 100, answeredAt = now - 90),
            CallLogEntity("mine", "c1", video = false, outgoing = true, startedAt = now - 100),
            CallLogEntity("old", "c1", video = false, outgoing = false, startedAt = now - day - 1),
        )
        assertEquals(listOf("b", "a"), WorkspaceHomeRules.missedCalls(log, now, day).map { it.callId })
    }

    @Test
    fun theSelectedWorkspaceStaysWhileYouBelongToIt() {
        val orgs = listOf(OrgDto("o1", "Acme"), OrgDto("o2", "Beta"))
        assertEquals("o2", WorkspaceHomeRules.resolveOrg(orgs, "o2"))
        assertEquals("o1", WorkspaceHomeRules.resolveOrg(orgs, "gone")) // removed from it: the first one
        assertEquals("o1", WorkspaceHomeRules.resolveOrg(orgs, null))
        assertNull(WorkspaceHomeRules.resolveOrg(emptyList(), "o1"))
    }

    @Test
    fun aWorkspaceSelectedBeforeTheListKnowsItAsksForAReload() {
        // Made or joined on another screen, which selects it: Home's list was loaded before it existed.
        val orgs = listOf(OrgDto("o1", "Acme"))
        assertTrue(WorkspaceHomeRules.selectionUnknown(orgs, "o2"))
        assertTrue(WorkspaceHomeRules.selectionUnknown(emptyList(), "o2"))
        assertFalse(WorkspaceHomeRules.selectionUnknown(orgs, "o1"))
        assertFalse(WorkspaceHomeRules.selectionUnknown(orgs, null))
        // Still loading: the first load decides.
        assertFalse(WorkspaceHomeRules.selectionUnknown(null, "o2"))
    }

    @Test
    fun starredFollowsThePersonsOrderAndTheWorkspace() {
        val convs = listOf(conv("general"), conv("sales", orgId = "o2"), conv("ann", type = "DM", unread = 1), conv("design"))
        val order = listOf("design", "ann", "sales", "missing", "general")
        assertEquals(listOf("design", "ann", "general"), WorkspaceHomeRules.starred(convs, "o1", order).map { it.id })
        assertEquals(listOf("ann"), WorkspaceHomeRules.starred(convs, "o1", order, unreadOnly = true).map { it.id })
    }

    @Test
    fun customSectionsKeepTheirOrderAndLeaveTheirChannelsOutOfTheGroups() {
        val convs = listOf(conv("general"), conv("design", unread = 2), conv("ann", type = "DM"), conv("sales", orgId = "o2"))
        val sections = listOf(
            "o1" to com.meldiq.android.data.api.NavSectionDto("s_a", "Projects", listOf("design", "general", "gone")),
            "personal" to com.meldiq.android.data.api.NavSectionDto("s_p", "People", listOf("ann"), collapsed = true),
            "o2" to com.meldiq.android.data.api.NavSectionDto("s_o", "Other org", listOf("sales")),
        )
        val shown = WorkspaceHomeRules.customSections(convs, "o1", sections)
        assertEquals(listOf("Projects", "People", "Other org"), shown.map { it.name })
        assertEquals(listOf("design", "general"), shown[0].items.map { it.id })
        assertTrue(shown[1].collapsed)
        assertTrue(shown[2].items.isEmpty()) // another workspace's channel isn't shown here
        // Unread only: sections without an unread conversation are hidden.
        assertEquals(listOf("Projects"), WorkspaceHomeRules.customSections(convs, "o1", sections, unreadOnly = true).map { it.name })
        assertEquals(listOf("design"), WorkspaceHomeRules.customSections(convs, "o1", sections, unreadOnly = true)[0].items.map { it.id })
        // A placed channel isn't listed again under its group.
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Acme", placed = setOf("design", "general"))
        assertTrue(groups.isEmpty())
    }
}
