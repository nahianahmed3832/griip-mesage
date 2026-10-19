package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.PermissionDto
import com.meldiq.android.ui.screens.workspace.roles.RoleEditorRules
import com.meldiq.android.ui.screens.workspace.roles.RoleEditorRules.Lock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The role editor mirrors the server's role rules (server/test/permissions_rules.test.ts): same cases, same answers. */
class RoleEditorRulesTest {
    private val announce = PermissionDto("messages.announce", "messaging", "Post announcements")
    private val manageChannels = PermissionDto("channels.manage", "channels", "Manage channels", admin = true)
    private val delete = PermissionDto("workspace.delete", "workspace", "Delete", admin = true, ownerOnly = true)
    private val post = PermissionDto("messages.post", "messaging", "Post", restrictable = true, guestAllowed = true)
    private val remove = PermissionDto("members.remove", "members", "Remove", admin = true)
    private val catalog = listOf(announce, manageChannels, delete, post, remove)
    private val adminPerms = setOf("roles.manage", "roles.assign", "messages.announce", "channels.manage", "messages.post", "members.remove")

    @Test
    fun locksPermissionsTheEditorCannotGive() {
        assertEquals(Lock.None, RoleEditorRules.grantLock(announce, adminPerms, canDelegate = false))
        assertEquals(Lock.NeedsDelegate, RoleEditorRules.grantLock(manageChannels, adminPerms, canDelegate = false))
        assertEquals(Lock.None, RoleEditorRules.grantLock(manageChannels, adminPerms + "roles.delegate", canDelegate = true))
        assertEquals(Lock.OwnerOnly, RoleEditorRules.grantLock(delete, adminPerms + "workspace.delete", canDelegate = true))
        assertEquals(Lock.NotYours, RoleEditorRules.grantLock(announce, setOf("roles.manage"), canDelegate = true))
    }

    @Test
    fun restrictsOnlyRestrictablePowersTheEditorHolds() {
        assertEquals(Lock.None, RoleEditorRules.denyLock(post, adminPerms))
        assertEquals(Lock.NotRestrictable, RoleEditorRules.denyLock(remove, adminPerms))
        assertEquals(Lock.NotYours, RoleEditorRules.denyLock(post, setOf("roles.manage")))
    }

    @Test
    fun savesOnlyWhatTheServerWouldAccept() {
        assertTrue(RoleEditorRules.canSave("Announcer", setOf("messages.announce"), emptySet(), catalog, adminPerms))
        assertFalse(RoleEditorRules.canSave(" ", setOf("messages.announce"), emptySet(), catalog, adminPerms)) // needs a name
        assertFalse(RoleEditorRules.canSave("Boss", setOf("channels.manage"), emptySet(), catalog, adminPerms)) // admin power, no delegate
        assertFalse(RoleEditorRules.canSave("Both", setOf("messages.post"), setOf("messages.post"), catalog, adminPerms))
        assertFalse(RoleEditorRules.canSave("x", setOf("messages.announce"), emptySet(), catalog, adminPerms - "roles.manage"))
        assertTrue(RoleEditorRules.canSave("Read-only", emptySet(), setOf("messages.post"), catalog, adminPerms))
        // An existing role already beyond the editor can't be edited, even to make it weaker.
        val strong = CustomRoleDto("rl_1", "Strong", permissions = listOf("channels.manage"))
        assertFalse(RoleEditorRules.canSave("Strong", emptySet(), emptySet(), catalog, adminPerms, before = strong))
    }

    @Test
    fun groupsByCategoryInAFixedOrder() {
        val groups = RoleEditorRules.grouped(catalog).map { it.first }
        assertEquals(listOf("workspace", "members", "channels", "messaging"), groups)
    }

    @Test
    fun parsesOnlyRealColours() {
        assertEquals(0xFFE39B7B.toInt(), RoleEditorRules.parseColor("#E39B7B"))
        assertNull(RoleEditorRules.parseColor("red"))
        assertNull(RoleEditorRules.parseColor("#12345"))
        assertTrue(RoleEditorRules.SWATCHES.all { RoleEditorRules.parseColor(it) != null })
    }
}
