package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.InviteDto
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.api.OrgPermissions
import com.meldiq.android.data.api.OrgStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workspace administration rules (docs/WORKSPACE_ADMINISTRATION.md). The role table is the same as the server's
 * `test/roles.test.ts`, so the app never offers a role change the server refuses, nor hides one it allows.
 */
class WorkspaceRolesTest {
    private val roles = WorkspaceRoles.ALL
    private val others = mapOf(
        "OWNER" to mapOf("OWNER" to listOf("ADMIN", "MEMBER", "GUEST"), "ADMIN" to listOf("OWNER", "MEMBER", "GUEST"), "MEMBER" to listOf("OWNER", "ADMIN", "GUEST"), "GUEST" to listOf("OWNER", "ADMIN", "MEMBER")),
        "ADMIN" to mapOf("OWNER" to emptyList(), "ADMIN" to emptyList(), "MEMBER" to listOf("GUEST"), "GUEST" to listOf("MEMBER")),
        "MEMBER" to roles.associateWith { emptyList<String>() },
        "GUEST" to roles.associateWith { emptyList<String>() },
    )
    private val self = mapOf("OWNER" to listOf("ADMIN", "MEMBER", "GUEST"), "ADMIN" to listOf("MEMBER", "GUEST"), "MEMBER" to emptyList(), "GUEST" to emptyList())

    @Test
    fun `role changes for someone else match the server's table`() {
        for (actor in roles) for (target in roles) {
            assertEquals("$actor → $target", others.getValue(actor).getValue(target), WorkspaceRoles.assignable(actor, target, self = false, ownerCount = 2))
        }
    }

    @Test
    fun `role changes for yourself match the server's table`() {
        for (actor in roles) assertEquals(actor, self.getValue(actor), WorkspaceRoles.assignable(actor, actor, self = true, ownerCount = 2))
    }

    @Test
    fun `the last owner is offered nothing, and an unknown owner count counts as one`() {
        assertEquals(emptyList<String>(), WorkspaceRoles.assignable("OWNER", "OWNER", self = true, ownerCount = 1))
        assertEquals(emptyList<String>(), WorkspaceRoles.assignable("OWNER", "OWNER", self = true, ownerCount = null))
        assertEquals(listOf("OWNER", "ADMIN", "GUEST"), WorkspaceRoles.assignable("OWNER", "MEMBER", self = false, ownerCount = null))
        assertEquals(emptyList<String>(), WorkspaceRoles.assignable(null, "MEMBER", self = false, ownerCount = 2))
        assertEquals(1, WorkspaceRoles.ownerCount(listOf("OWNER", "ADMIN", null)))
    }

    @Test
    fun `titles and removal follow the server`() {
        assertEquals(listOf("MEMBER", "GUEST"), roles.filter { WorkspaceRoles.canChangeTitle("ADMIN", it, self = false) })
        assertEquals(roles, roles.filter { WorkspaceRoles.canChangeTitle("OWNER", it, self = false) })
        assertTrue(WorkspaceRoles.canChangeTitle("GUEST", "GUEST", self = true))
        assertEquals(listOf("MEMBER", "GUEST"), roles.filter { WorkspaceRoles.canRemove("ADMIN", it, self = false) })
        assertEquals(roles, roles.filter { WorkspaceRoles.canRemove("OWNER", it, self = false) })
        assertFalse(WorkspaceRoles.canRemove("OWNER", "MEMBER", self = true)) // that's leaving
        assertFalse(WorkspaceRoles.canRemove("MEMBER", "GUEST", self = false))
    }

    @Test
    fun `leave uses the server's answer, otherwise hides it from owners`() {
        assertTrue(WorkspaceRoles.canLeave(detail("OWNER", canLeave = true)))
        assertFalse(WorkspaceRoles.canLeave(detail("MEMBER", canLeave = false)))
        assertFalse(WorkspaceRoles.canLeave(detail("OWNER", canLeave = null)))
        assertTrue(WorkspaceRoles.canLeave(detail("GUEST", canLeave = null)))
        assertFalse(WorkspaceRoles.canLeave(null))
    }

    @Test
    fun `invites have one state from the server, or worked out the same way`() {
        val now = 1_000_000L
        val base = InviteDto("i", role = "MEMBER", maxUses = 2, uses = 0, expiresAt = now + 10)
        assertEquals(InviteState.ACTIVE, InviteStates.of(base, now))
        assertEquals(InviteState.USED_UP, InviteStates.of(base.copy(uses = 2), now))
        assertEquals(InviteState.EXPIRED, InviteStates.of(base.copy(uses = 2, expiresAt = now), now))
        assertEquals(InviteState.REVOKED, InviteStates.of(base.copy(expiresAt = now - 1, revokedAt = 5), now))
        // The server's status wins over the clock on this phone.
        assertEquals(InviteState.ACTIVE, InviteStates.of(base.copy(expiresAt = now - 1, status = "active"), now))
        assertEquals(InviteState.USED_UP, InviteStates.of(base.copy(status = "used_up"), now))
        val list = listOf(base, base.copy(id = "j", revokedAt = 1))
        assertEquals(listOf("i"), InviteStates.filter(list, activeOnly = true, now = now).map { it.id })
        assertEquals(listOf("j"), InviteStates.filter(list, activeOnly = false, now = now).map { it.id })
        assertEquals(2, InviteStates.filter(list, activeOnly = null, now = now).size)
    }

    @Test
    fun `the access switch shows only where the server allows it`() {
        val ch = ConversationDto("c", "CHANNEL", kind = "PUBLIC", canManageAccess = true)
        assertTrue(ChannelAccess.canChange(ch, featureOn = true))
        assertFalse(ChannelAccess.canChange(ch, featureOn = false))
        assertFalse(ChannelAccess.canChange(ch, featureOn = null))
        assertFalse(ChannelAccess.canChange(ch.copy(canManageAccess = false), featureOn = true))
        assertFalse(ChannelAccess.canChange(ch.copy(archived = true), featureOn = true))
        assertFalse(ChannelAccess.canChange(ch.copy(type = "GROUP"), featureOn = true))
        assertFalse(ChannelAccess.canChange(null, featureOn = true))
    }

    private fun detail(
        myRole: String,
        canLeave: Boolean? = null,
        canManageOrg: Boolean = false,
    ) = OrgDetailRes(
        OrgDto("o1", "Acme", myRole = myRole), OrgStats(),
        OrgPermissions(canManageOrg = canManageOrg, canLeave = canLeave),
    )
}
