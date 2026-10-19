package com.meldiq.android.ui.screens.home

import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.api.OrgPermissions
import com.meldiq.android.data.api.OrgStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's New menu offers only what the selected workspace lets you do (the server checks each one again). */
class NewActionsTest {
    private fun detail(channels: Boolean, invite: Boolean) = OrgDetailRes(
        org = OrgDto("o1", "Acme"), branches = emptyList(), departments = emptyList(), teams = emptyList(),
        stats = OrgStats(), permissions = OrgPermissions(canCreateChannels = channels, canInvite = invite),
    )

    @Test
    fun `without a workspace only messages, groups and joining are offered`() {
        assertEquals(listOf(NewAction.MESSAGE, NewAction.GROUP, NewAction.WORKSPACE), NewActionRules.available(null))
    }

    @Test
    fun `channels and invitations follow the workspace's permissions`() {
        assertEquals(
            listOf(NewAction.MESSAGE, NewAction.GROUP, NewAction.CHANNEL, NewAction.INVITE, NewAction.WORKSPACE),
            NewActionRules.available(detail(channels = true, invite = true)),
        )
        assertEquals(listOf(NewAction.MESSAGE, NewAction.GROUP, NewAction.INVITE, NewAction.WORKSPACE), NewActionRules.available(detail(channels = false, invite = true)))
        assertEquals(listOf(NewAction.MESSAGE, NewAction.GROUP, NewAction.WORKSPACE), NewActionRules.available(detail(channels = false, invite = false)))
    }
}
