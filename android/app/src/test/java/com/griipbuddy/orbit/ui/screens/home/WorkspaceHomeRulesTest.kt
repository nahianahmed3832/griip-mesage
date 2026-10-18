package com.griipbuddy.orbit.ui.screens.home

import com.griipbuddy.orbit.data.api.OrgDto
import com.griipbuddy.orbit.data.db.CallLogEntity
import com.griipbuddy.orbit.data.db.ConversationEntity
import org.junit.Assert.assertEquals
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
    ) = ConversationEntity(
        id = id, type = type, orgId = orgId, scope = scope, scopeId = scopeId, scopeName = scopeName, kind = "PUBLIC", name = id,
        topic = null, archived = false, memberCount = 3, membersJson = "[]", joined = joined, myRole = null, canPost = true,
        canManage = false, activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, unreadCount = unread,
        mentionCount = mentions, muted = muted, draft = draft, removed = removed, archivedAt = archivedAt,
    )

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
    fun channelsAreGroupedByOrganisationThenBranchThenTeamAndSortedByName() {
        val convs = listOf(
            conv("zeta", scope = "TEAM", scopeId = "t1", scopeName = "Mobile"),
            conv("alpha", scope = "TEAM", scopeId = "t1", scopeName = "Mobile", unread = 2),
            conv("announcements"), // organisation-wide, no scope name: the workspace's name
            conv("dhaka-desk", scope = "BRANCH", scopeId = "b1", scopeName = "Dhaka"),
            conv("api", scope = "TEAM", scopeId = "t2", scopeName = "Backend"),
            conv("other-org", orgId = "o2"),
            conv("not-joined", joined = false),
            conv("archived", archivedAt = 1),
            conv("ann", type = "DM", unread = 1),
        )
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Acme")
        assertEquals(listOf("Acme", "Dhaka", "Backend", "Mobile"), groups.map { it.title })
        assertEquals(listOf("ORG:o1", "BRANCH:b1", "TEAM:t2", "TEAM:t1"), groups.map { it.key })
        assertEquals(listOf("alpha", "zeta"), groups.last().channels.map { it.id })
        assertEquals(1, groups.last().unreadChannels)
        assertTrue(WorkspaceHomeRules.channelGroups(convs, null, "Acme").isEmpty()) // no workspace: no channels
    }

    @Test
    fun unreadOnlyKeepsUnreadChannelsAndDropsEmptyGroups() {
        val convs = listOf(
            conv("zeta", scope = "TEAM", scopeId = "t1", scopeName = "Mobile"),
            conv("alpha", scope = "TEAM", scopeId = "t1", scopeName = "Mobile", unread = 2),
            conv("announcements"),
        )
        val groups = WorkspaceHomeRules.channelGroups(convs, "o1", "Acme", unreadOnly = true)
        assertEquals(listOf("Mobile"), groups.map { it.title })
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
}
